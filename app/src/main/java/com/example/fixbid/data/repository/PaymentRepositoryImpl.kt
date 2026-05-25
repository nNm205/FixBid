package com.example.fixbid.data.repository

import com.example.fixbid.data.payment.VnpayService
import com.example.fixbid.data.remote.dto.PaymentDto
import com.example.fixbid.data.remote.dto.WalletTransactionDto
import com.example.fixbid.data.remote.dto.WorkerWalletDto
import com.example.fixbid.data.remote.supabase.Tables
import com.example.fixbid.domain.model.*
import com.example.fixbid.domain.repository.PaymentRepository
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import javax.inject.Inject

class PaymentRepositoryImpl @Inject constructor(
    private val client: SupabaseClient,
    private val vnpayService: VnpayService
) : PaymentRepository {

    override suspend fun createVnpayPayment(
        bookingId: String,
        amount: Double
    ): Resource<Payment> = runCatching {
        val userId = client.auth.currentUserOrNull()?.id
            ?: return Resource.Error("Chưa đăng nhập")

        // Lấy workerId từ booking
        val booking = client.from(Tables.BOOKINGS)
            .select { filter { eq("id", bookingId) } }
            .decodeSingle<Map<String, String?>>()

        val workerId = booking["worker_id"]
            ?: return Resource.Error("Booking chưa có thợ")

        // Tính phí nền tảng 10%
        val platformFee = amount * 0.10
        val workerReceives = amount - platformFee

        // Tạo payment record trong DB (status = processing)
        val paymentResult = client.from(Tables.PAYMENTS)
            .insert(buildJsonObject {
                put("booking_id", bookingId)
                put("customer_id", userId)
                put("worker_id", workerId)
                put("amount", amount)
                put("platform_fee", platformFee)
                put("worker_receives", workerReceives)
                put("method", "vnpay")
                put("status", "processing")
                put("escrow_status", "none")
            }) { select() }
            .decodeSingle<PaymentDto>()

        // Tạo VNPay URL
        val vnpayUrl = vnpayService.createPaymentUrl(
            orderId = paymentResult.id,
            amount = amount.toLong(),
            orderInfo = "Thanh toan don hang FixBid #${bookingId.take(8)}"
        )

        // Cập nhật vnpay_url vào payment record
        client.from(Tables.PAYMENTS)
            .update(buildJsonObject {
                put("vnpay_url", vnpayUrl)
            }) { filter { eq("id", paymentResult.id) } }

        // Cập nhật booking status sang awaiting_payment
        client.from(Tables.BOOKINGS)
            .update(buildJsonObject {
                put("status", "awaiting_payment")
                put("updated_at", Instant.now().toString())
            }) { filter { eq("id", bookingId) } }

        Resource.Success(paymentResult.toDomain().copy(vnpayUrl = vnpayUrl))
    }.getOrElse { Resource.Error(it.message ?: "Tạo thanh toán VNPay thất bại") }

    override suspend fun confirmVnpayPayment(
        paymentId: String,
        vnpayParams: Map<String, String>
    ): Resource<Payment> = runCatching {
        // Verify VNPay response
        val verifyResult = vnpayService.verifyReturnUrl(vnpayParams)

        if (!verifyResult.isSuccess) {
            // Cập nhật payment status = failed
            client.from(Tables.PAYMENTS)
                .update(buildJsonObject {
                    put("status", "failed")
                }) { filter { eq("id", paymentId) } }

            return Resource.Error(verifyResult.message)
        }

        // Thanh toán thành công → cập nhật payment
        val now = Instant.now().toString()
        val result = client.from(Tables.PAYMENTS)
            .update(buildJsonObject {
                put("status", "escrow_held")
                put("escrow_status", "holding")
                put("transaction_id", verifyResult.transactionId)
                put("paid_at", now)
            }) {
                filter { eq("id", paymentId) }
                select()
            }
            .decodeSingle<PaymentDto>()

        // Cập nhật booking status sang in_progress
        client.from(Tables.BOOKINGS)
            .update(buildJsonObject {
                put("status", "in_progress")
                put("updated_at", now)
            }) { filter { eq("id", result.bookingId) } }

        Resource.Success(result.toDomain())
    }.getOrElse { Resource.Error(it.message ?: "Xác nhận thanh toán thất bại") }

    override suspend fun releaseEscrow(bookingId: String): Resource<Payment> = runCatching {
        val now = Instant.now().toString()

        // Lấy payment
        val payment = client.from(Tables.PAYMENTS)
            .select { filter { eq("booking_id", bookingId) } }
            .decodeSingle<PaymentDto>()

        if (payment.escrowStatus != "holding") {
            return Resource.Error("Không có tiền escrow để giải phóng")
        }

        // Cập nhật payment: escrow released, status completed
        val updatedPayment = client.from(Tables.PAYMENTS)
            .update(buildJsonObject {
                put("status", "completed")
                put("escrow_status", "released")
                put("escrow_released_at", now)
            }) {
                filter { eq("id", payment.id) }
                select()
            }
            .decodeSingle<PaymentDto>()

        // Cộng tiền vào ví thợ (upsert wallet + insert transaction)
        // Kiểm tra ví đã tồn tại chưa
        val existingWallets = client.from("worker_wallets")
            .select { filter { eq("worker_id", payment.workerId) } }
            .decodeList<WorkerWalletDto>()

        if (existingWallets.isEmpty()) {
            // Tạo ví mới
            client.from("worker_wallets")
                .insert(buildJsonObject {
                    put("worker_id", payment.workerId)
                    put("balance", payment.workerReceives)
                    put("total_earned", payment.workerReceives)
                    put("total_withdrawn", 0.0)
                    put("updated_at", now)
                })
        } else {
            // Cập nhật ví hiện có
            val wallet = existingWallets.first()
            client.from("worker_wallets")
                .update(buildJsonObject {
                    put("balance", wallet.balance + payment.workerReceives)
                    put("total_earned", wallet.totalEarned + payment.workerReceives)
                    put("updated_at", now)
                }) { filter { eq("id", wallet.id) } }
        }

        // Tạo wallet transaction record
        val walletId = if (existingWallets.isEmpty()) {
            client.from("worker_wallets")
                .select { filter { eq("worker_id", payment.workerId) } }
                .decodeSingle<WorkerWalletDto>().id
        } else {
            existingWallets.first().id
        }

        val newBalance = if (existingWallets.isEmpty()) {
            payment.workerReceives
        } else {
            existingWallets.first().balance + payment.workerReceives
        }

        client.from("wallet_transactions")
            .insert(buildJsonObject {
                put("wallet_id", walletId)
                put("booking_id", bookingId)
                put("type", "earning")
                put("amount", payment.workerReceives)
                put("balance_after", newBalance)
                put("description", "Thu nhập từ công việc #${bookingId.take(8)}")
            })

        Resource.Success(updatedPayment.toDomain())
    }.getOrElse { Resource.Error(it.message ?: "Giải phóng escrow thất bại") }

    override suspend fun refundPayment(bookingId: String, reason: String): Resource<Payment> =
        runCatching {
            val now = Instant.now().toString()
            val result = client.from(Tables.PAYMENTS)
                .update(buildJsonObject {
                    put("status", "refunded")
                    put("escrow_status", "refunded")
                }) {
                    filter { eq("booking_id", bookingId) }
                    select()
                }
                .decodeSingle<PaymentDto>()

            // Cập nhật booking
            client.from(Tables.BOOKINGS)
                .update(buildJsonObject {
                    put("status", "disputed")
                    put("customer_note", reason)
                    put("updated_at", now)
                }) { filter { eq("id", bookingId) } }

            Resource.Success(result.toDomain())
        }.getOrElse { Resource.Error(it.message ?: "Hoàn tiền thất bại") }

    override suspend fun createPayment(
        bookingId: String,
        amount: Double,
        method: PaymentMethod
    ): Resource<Payment> = runCatching {
        val userId = client.auth.currentUserOrNull()?.id
            ?: return Resource.Error("Chưa đăng nhập")

        val booking = client.from(Tables.BOOKINGS)
            .select { filter { eq("id", bookingId) } }
            .decodeSingle<Map<String, String?>>()

        val workerId = booking["worker_id"]
            ?: return Resource.Error("Booking chưa có thợ")

        val platformFee = amount * 0.10
        val workerReceives = amount - platformFee

        val result = client.from(Tables.PAYMENTS)
            .insert(buildJsonObject {
                put("booking_id", bookingId)
                put("customer_id", userId)
                put("worker_id", workerId)
                put("amount", amount)
                put("platform_fee", platformFee)
                put("worker_receives", workerReceives)
                put("method", method.name.lowercase())
                put("status", "pending")
                put("escrow_status", "none")
            }) { select() }
            .decodeSingle<PaymentDto>()
        Resource.Success(result.toDomain())
    }.getOrElse { Resource.Error(it.message ?: "Tạo thanh toán thất bại") }

    override suspend fun confirmCashPayment(bookingId: String): Resource<Payment> =
        runCatching {
            val now = Instant.now().toString()
            val result = client.from(Tables.PAYMENTS)
                .update(buildJsonObject {
                    put("status", "completed")
                    put("paid_at", now)
                }) {
                    filter { eq("booking_id", bookingId) }
                    select()
                }
                .decodeSingle<PaymentDto>()

            // Cập nhật booking sang in_progress
            client.from(Tables.BOOKINGS)
                .update(buildJsonObject {
                    put("status", "in_progress")
                    put("updated_at", now)
                }) { filter { eq("id", bookingId) } }

            Resource.Success(result.toDomain())
        }.getOrElse { Resource.Error(it.message ?: "Xác nhận thanh toán thất bại") }

    override suspend fun getPaymentByBooking(bookingId: String): Resource<Payment> =
        runCatching {
            val result = client.from(Tables.PAYMENTS)
                .select { filter { eq("booking_id", bookingId) } }
                .decodeSingle<PaymentDto>()
            Resource.Success(result.toDomain())
        }.getOrElse { Resource.Error(it.message ?: "Không tìm thấy thanh toán") }

    override suspend fun getPaymentHistory(userId: String): Resource<List<Payment>> =
        runCatching {
            val result = client.from(Tables.PAYMENTS)
                .select {
                    filter {
                        or {
                            eq("customer_id", userId)
                            eq("worker_id", userId)
                        }
                    }
                    order("created_at", Order.DESCENDING)
                }
                .decodeList<PaymentDto>()
            Resource.Success(result.map { it.toDomain() })
        }.getOrElse { Resource.Error(it.message ?: "Lỗi tải lịch sử thanh toán") }

    override suspend fun getWorkerWallet(workerId: String): Resource<WorkerWallet> =
        runCatching {
            val result = client.from("worker_wallets")
                .select { filter { eq("worker_id", workerId) } }
                .decodeSingle<WorkerWalletDto>()
            Resource.Success(result.toDomain())
        }.getOrElse { Resource.Error(it.message ?: "Không tìm thấy ví") }

    override suspend fun getWalletTransactions(walletId: String): Resource<List<WalletTransaction>> =
        runCatching {
            val result = client.from("wallet_transactions")
                .select {
                    filter { eq("wallet_id", walletId) }
                    order("created_at", Order.DESCENDING)
                }
                .decodeList<WalletTransactionDto>()
            Resource.Success(result.map { it.toDomain() })
        }.getOrElse { Resource.Error(it.message ?: "Lỗi tải lịch sử giao dịch") }
}
