package com.example.fixbid.domain.usecase.customer

import com.example.fixbid.data.remote.vnpay.VNPayService
import com.example.fixbid.domain.model.Payment
import com.example.fixbid.domain.model.PaymentMethod
import com.example.fixbid.domain.model.Resource
import com.example.fixbid.domain.repository.PaymentRepository
import javax.inject.Inject

/**
 * Use case: Tạo payment record + generate VNPay URL.
 *
 * Flow:
 * 1. Tạo payment record trong DB (status = PENDING)
 * 2. Generate VNPay payment URL với paymentId làm orderId
 * 3. Return URL để customer redirect sang VNPay
 */
class CreateVNPayPaymentUseCase @Inject constructor(
    private val paymentRepository: PaymentRepository,
    private val vnPayService: VNPayService
) {
    data class VNPayResult(
        val payment: Payment,
        val paymentUrl: String
    )

    suspend operator fun invoke(
        bookingId: String,
        amount: Double
    ): Resource<VNPayResult> {
        if (amount <= 0) return Resource.Error("Số tiền không hợp lệ")

        // 1. Tạo payment record
        val paymentResult = paymentRepository.createPayment(
            bookingId = bookingId,
            amount = amount,
            method = PaymentMethod.VNPAY
        )

        return when (paymentResult) {
            is Resource.Success -> {
                val payment = paymentResult.data
                // 2. Generate VNPay URL
                val paymentUrl = vnPayService.createPaymentUrl(
                    orderId = payment.id,
                    amount = amount.toLong(),
                    orderInfo = "Thanh toan FixBid - Don hang ${payment.id.take(8)}"
                )
                Resource.Success(VNPayResult(payment = payment, paymentUrl = paymentUrl))
            }
            is Resource.Error -> Resource.Error(paymentResult.message)
            is Resource.Loading -> Resource.Loading()
        }
    }
}
