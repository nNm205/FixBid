package com.example.fixbid.domain.repository

import com.example.fixbid.domain.model.Payment
import com.example.fixbid.domain.model.PaymentMethod
import com.example.fixbid.domain.model.PaymentStatus
import com.example.fixbid.domain.model.Resource
import com.example.fixbid.domain.model.SePayQrInfo
import kotlinx.coroutines.flow.Flow

interface PaymentRepository {
    suspend fun createPayment(
        bookingId: String,
        amount: Double,
        method: PaymentMethod
    ): Resource<Payment>

    suspend fun confirmCashPayment(bookingId: String): Resource<Payment>
    suspend fun getPaymentByBooking(bookingId: String): Resource<Payment>
    suspend fun getPaymentHistory(userId: String): Resource<List<Payment>>

    // ─── SePay specific ─────────────────────────────────────────────────────
    /**
     * Tạo payment với phương thức SePay và trả về thông tin QR code
     */
    suspend fun createSePayPayment(
        bookingId: String,
        amount: Double
    ): Resource<Payment>

    /**
     * Lấy thông tin QR code để hiển thị cho khách thanh toán
     */
    suspend fun getSePayQrInfo(paymentId: String): Resource<SePayQrInfo>

    /**
     * Kiểm tra trạng thái thanh toán (polling từ client)
     */
    suspend fun checkPaymentStatus(paymentId: String): Resource<PaymentStatus>

    /**
     * Observe realtime payment status changes
     */
    fun observePaymentStatus(paymentId: String): Flow<PaymentStatus>

    /**
     * Giải phóng tiền cho thợ sau khi khách xác nhận hoàn thành
     */
    suspend fun releasePaymentToWorker(bookingId: String): Resource<Payment>
}