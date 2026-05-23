package com.example.fixbid.domain.usecase.customer

import com.example.fixbid.domain.model.Booking
import com.example.fixbid.domain.model.Resource
import com.example.fixbid.domain.repository.BookingRepository
import com.example.fixbid.domain.repository.PaymentRepository
import javax.inject.Inject

class ConfirmCompletionUseCase @Inject constructor(
    private val bookingRepository: BookingRepository,
    private val paymentRepository: PaymentRepository
) {
    /**
     * Xác nhận hoàn thành công việc:
     * 1. Cập nhật booking status → COMPLETED
     * 2. Giải phóng tiền escrow → chuyển cho thợ (status → COMPLETED)
     */
    suspend fun confirm(bookingId: String): Resource<Booking> {
        // 1. Confirm booking completion
        val bookingResult = bookingRepository.confirmCompletion(bookingId)
        if (bookingResult is Resource.Error) return bookingResult

        // 2. Release payment to worker (escrow → completed)
        paymentRepository.releasePaymentToWorker(bookingId)

        return bookingResult
    }

    suspend fun reject(bookingId: String, reason: String): Resource<Booking> {
        return bookingRepository.rejectCompletion(bookingId, reason)
    }
}