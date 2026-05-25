package com.example.fixbid.domain.usecase.customer

import com.example.fixbid.domain.model.Payment
import com.example.fixbid.domain.model.Resource
import com.example.fixbid.domain.repository.PaymentRepository
import javax.inject.Inject

/**
 * Xác nhận kết quả thanh toán VNPay sau khi user quay về app.
 * Verify checksum, cập nhật trạng thái payment & booking.
 */
class ConfirmVnpayPaymentUseCase @Inject constructor(
    private val paymentRepository: PaymentRepository
) {
    suspend operator fun invoke(
        paymentId: String,
        vnpayParams: Map<String, String>
    ): Resource<Payment> {
        if (paymentId.isBlank()) return Resource.Error("Mã thanh toán không hợp lệ")
        return paymentRepository.confirmVnpayPayment(paymentId, vnpayParams)
    }
}
