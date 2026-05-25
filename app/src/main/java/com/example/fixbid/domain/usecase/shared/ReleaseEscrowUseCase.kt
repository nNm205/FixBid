package com.example.fixbid.domain.usecase.shared

import com.example.fixbid.domain.model.Payment
import com.example.fixbid.domain.model.Resource
import com.example.fixbid.domain.repository.PaymentRepository
import javax.inject.Inject

/**
 * Giải phóng tiền escrow cho thợ khi khách xác nhận hoàn thành.
 * Tiền sẽ được cộng vào ví của thợ.
 */
class ReleaseEscrowUseCase @Inject constructor(
    private val paymentRepository: PaymentRepository
) {
    suspend operator fun invoke(bookingId: String): Resource<Payment> {
        if (bookingId.isBlank()) return Resource.Error("Mã đơn hàng không hợp lệ")
        return paymentRepository.releaseEscrow(bookingId)
    }
}
