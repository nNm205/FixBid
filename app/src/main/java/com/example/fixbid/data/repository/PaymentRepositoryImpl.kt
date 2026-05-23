package com.example.fixbid.data.repository

import com.example.fixbid.data.remote.dto.PaymentDto
import com.example.fixbid.data.remote.sepay.SePayConfig
import com.example.fixbid.data.remote.supabase.Tables
import com.example.fixbid.domain.model.Payment
import com.example.fixbid.domain.model.PaymentMethod
import com.example.fixbid.domain.model.PaymentStatus
import com.example.fixbid.domain.model.Resource
import com.example.fixbid.domain.model.SePayQrInfo
import com.example.fixbid.domain.repository.PaymentRepository
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.jan.supabase.realtime.decodeRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import javax.inject.Inject

class PaymentRepositoryImpl @Inject constructor(
    private val client: SupabaseClient
) : PaymentRepository {

    override suspend fun createPayment(
        bookingId: String,
        amount: Double,
        method: PaymentMethod
    ): Resource<Payment> = runCatching {
        val userId = client.auth.currentUserOrNull()?.id
            ?: return Resource.Error("Chưa đăng nhập")

        // Lấy workerId từ booking
        val booking = client.from(Tables.BOOKINGS)
            .select { filter { eq("id", bookingId) } }
            .decodeSingle<Map<String, String?>>()

        val workerId = booking["worker_id"]
            ?: return Resource.Error("Booking chưa có thợ")

        val result = client.from(Tables.PAYMENTS)
            .insert(buildJsonObject {
                put("booking_id", bookingId)
                put("customer_id", userId)
                put("worker_id", workerId)
                put("amount", amount)
                put("method", method.name.lowercase())
                // platform_fee và worker_receives tự tính bởi trigger DB
            }) { select() }
            .decodeSingle<PaymentDto>()
        Resource.Success(result.toDomain())
    }.getOrElse { Resource.Error(it.message ?: "Tạo thanh toán thất bại") }

    override suspend fun createSePayPayment(
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

        // Tạo payment record trước để có ID
        val result = client.from(Tables.PAYMENTS)
            .insert(buildJsonObject {
                put("booking_id", bookingId)
                put("customer_id", userId)
                put("worker_id", workerId)
                put("amount", amount)
                put("method", "sepay")
                put("status", "pending")
            }) { select() }
            .decodeSingle<PaymentDto>()

        // Tạo transfer content duy nhất từ payment ID
        val transferContent = SePayConfig.generateTransferContent(result.id)

        // Cập nhật transfer_content vào payment record
        val updated = client.from(Tables.PAYMENTS)
            .update(buildJsonObject {
                put("transfer_content", transferContent)
            }) {
                filter { eq("id", result.id) }
                select()
            }
            .decodeSingle<PaymentDto>()

        // Cập nhật booking status sang "awaiting_payment"
        client.from(Tables.BOOKINGS)
            .update(buildJsonObject {
                put("status", "confirmed")
                put("updated_at", Instant.now().toString())
            }) {
                filter { eq("id", bookingId) }
            }

        Resource.Success(updated.toDomain())
    }.getOrElse { Resource.Error(it.message ?: "Tạo thanh toán SePay thất bại") }

    override suspend fun getSePayQrInfo(paymentId: String): Resource<SePayQrInfo> =
        runCatching {
            val payment = client.from(Tables.PAYMENTS)
                .select { filter { eq("id", paymentId) } }
                .decodeSingle<PaymentDto>()

            val transferContent = payment.transferContent
                ?: SePayConfig.generateTransferContent(payment.id)

            val qrImageUrl = SePayConfig.generateQrImageUrl(
                amount = payment.amount.toLong(),
                transferContent = transferContent
            )

            Resource.Success(
                SePayQrInfo(
                    bankCode = SePayConfig.BANK_CODE,
                    accountNumber = SePayConfig.ACCOUNT_NUMBER,
                    accountName = SePayConfig.ACCOUNT_NAME,
                    amount = payment.amount,
                    transferContent = transferContent,
                    qrImageUrl = qrImageUrl
                )
            )
        }.getOrElse { Resource.Error(it.message ?: "Lỗi lấy thông tin QR") }

    override suspend fun checkPaymentStatus(paymentId: String): Resource<PaymentStatus> =
        runCatching {
            val payment = client.from(Tables.PAYMENTS)
                .select { filter { eq("id", paymentId) } }
                .decodeSingle<PaymentDto>()

            val status = runCatching { PaymentStatus.valueOf(payment.status.uppercase()) }
                .getOrDefault(PaymentStatus.PENDING)

            Resource.Success(status)
        }.getOrElse { Resource.Error(it.message ?: "Lỗi kiểm tra trạng thái") }

    override fun observePaymentStatus(paymentId: String): Flow<PaymentStatus> {
        val channel = client.realtime.channel("payment_status_$paymentId")

        return channel.postgresChangeFlow<PostgresAction.Update>(schema = "public") {
            table = Tables.PAYMENTS
            filter("id", FilterOperator.EQ, paymentId)
        }.map { action ->
            runCatching {
                val dto = action.decodeRecord<PaymentDto>()
                PaymentStatus.valueOf(dto.status.uppercase())
            }.getOrDefault(PaymentStatus.PENDING)
        }
    }

    override suspend fun releasePaymentToWorker(bookingId: String): Resource<Payment> =
        runCatching {
            val result = client.from(Tables.PAYMENTS)
                .update(buildJsonObject {
                    put("status", "completed")
                    put("paid_at", Instant.now().toString())
                }) {
                    filter { eq("booking_id", bookingId) }
                    select()
                }
                .decodeSingle<PaymentDto>()
            Resource.Success(result.toDomain())
        }.getOrElse { Resource.Error(it.message ?: "Giải phóng tiền thất bại") }

    override suspend fun confirmCashPayment(bookingId: String): Resource<Payment> =
        runCatching {
            val result = client.from(Tables.PAYMENTS)
                .update(buildJsonObject {
                    put("status", "completed")
                    put("paid_at", Instant.now().toString())
                }) {
                    filter { eq("booking_id", bookingId) }
                    select()
                }
                .decodeSingle<PaymentDto>()
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
}
