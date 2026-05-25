package com.example.fixbid.domain.repository

import com.example.fixbid.domain.model.*

interface PaymentRepository {
    /**
     * Tạo payment record và trả về URL VNPay để thanh toán
     */
    suspend fun createVnpayPayment(
        bookingId: String,
        amount: Double
    ): Resource<Payment>

    /**
     * Xác nhận kết quả thanh toán VNPay (sau khi redirect về app)
     * - Verify checksum
     * - Cập nhật trạng thái payment
     * - Cập nhật booking status sang IN_PROGRESS
     * - Giữ tiền trong escrow
     */
    suspend fun confirmVnpayPayment(
        paymentId: String,
        vnpayParams: Map<String, String>
    ): Resource<Payment>

    /**
     * Giải phóng tiền escrow cho thợ (khi khách xác nhận hoàn thành)
     */
    suspend fun releaseEscrow(bookingId: String): Resource<Payment>

    /**
     * Hoàn tiền cho khách (khi có tranh chấp)
     */
    suspend fun refundPayment(bookingId: String, reason: String): Resource<Payment>

    /**
     * Tạo payment bằng tiền mặt
     */
    suspend fun createPayment(
        bookingId: String,
        amount: Double,
        method: PaymentMethod
    ): Resource<Payment>

    suspend fun confirmCashPayment(bookingId: String): Resource<Payment>
    suspend fun getPaymentByBooking(bookingId: String): Resource<Payment>
    suspend fun getPaymentHistory(userId: String): Resource<List<Payment>>

    // Wallet
    suspend fun getWorkerWallet(workerId: String): Resource<WorkerWallet>
    suspend fun getWalletTransactions(walletId: String): Resource<List<WalletTransaction>>
}