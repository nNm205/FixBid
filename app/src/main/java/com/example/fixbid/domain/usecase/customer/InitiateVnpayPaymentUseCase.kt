package com.example.fixbid.domain.usecase.customer

import com.example.fixbid.domain.model.Payment
import com.example.fixbid.domain.model.Resource
import com.example.fixbid.domain.repository.PaymentRepository
import javax.inject.Inject

/**
 * Khách hàng khởi tạo thanh toán VNPay sau khi chọn thợ.
 * Trả về Payment object có vnpayUrl để mở WebView.
 */
class InitiateVnpayPaymentUseCase @Inject constructor(
    private val paymentRepository: PaymentRepository
) {
    suspend operator fun invoke(bookingId: String, amount: Double): Resource<Payment> {
        if (amount <= 0) return Resource.Error("Số tiền không hợp lệ")
        if (bookingId.isBlank()) return Resource.Error("Mã đơn hàng không hợp lệ")
        return paymentRepository.createVnpayPayment(bookingId, amount)
    }
}
