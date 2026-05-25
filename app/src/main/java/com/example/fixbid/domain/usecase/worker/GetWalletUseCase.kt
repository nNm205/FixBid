package com.example.fixbid.domain.usecase.worker

import com.example.fixbid.domain.model.Resource
import com.example.fixbid.domain.model.WalletTransaction
import com.example.fixbid.domain.model.WorkerWallet
import com.example.fixbid.domain.repository.PaymentRepository
import javax.inject.Inject

/**
 * Lấy thông tin ví và lịch sử giao dịch của thợ.
 */
class GetWalletUseCase @Inject constructor(
    private val paymentRepository: PaymentRepository
) {
    suspend fun getWallet(workerId: String): Resource<WorkerWallet> {
        if (workerId.isBlank()) return Resource.Error("Không tìm thấy thợ")
        return paymentRepository.getWorkerWallet(workerId)
    }

    suspend fun getTransactions(walletId: String): Resource<List<WalletTransaction>> {
        if (walletId.isBlank()) return Resource.Error("Không tìm thấy ví")
        return paymentRepository.getWalletTransactions(walletId)
    }
}
