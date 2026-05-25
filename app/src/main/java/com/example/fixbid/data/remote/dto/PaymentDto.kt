package com.example.fixbid.data.remote.dto

import com.example.fixbid.core.utils.toEpochMillis
import com.example.fixbid.domain.model.EscrowStatus
import com.example.fixbid.domain.model.Payment
import com.example.fixbid.domain.model.PaymentMethod
import com.example.fixbid.domain.model.PaymentStatus
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PaymentDto(
    val id: String = "",
    @SerialName("booking_id")       val bookingId: String = "",
    @SerialName("customer_id")      val customerId: String = "",
    @SerialName("worker_id")        val workerId: String = "",
    val amount: Double = 0.0,
    @SerialName("platform_fee")     val platformFee: Double = 0.0,
    @SerialName("worker_receives")  val workerReceives: Double = 0.0,
    val method: String = "cash",
    val status: String = "pending",
    @SerialName("transaction_id")   val transactionId: String? = null,
    @SerialName("vnpay_url")        val vnpayUrl: String? = null,
    @SerialName("escrow_status")    val escrowStatus: String? = "none",
    @SerialName("escrow_released_at") val escrowReleasedAt: String? = null,
    @SerialName("paid_at")          val paidAt: String? = null,
    @SerialName("created_at")       val createdAt: String = ""
) {
    fun toDomain() = Payment(
        id             = id,
        bookingId      = bookingId,
        customerId     = customerId,
        workerId       = workerId,
        amount         = amount,
        platformFee    = platformFee,
        workerReceives = workerReceives,
        method         = runCatching { PaymentMethod.valueOf(method.uppercase()) }
            .getOrDefault(PaymentMethod.CASH),
        status         = runCatching { PaymentStatus.valueOf(status.uppercase()) }
            .getOrDefault(PaymentStatus.PENDING),
        transactionId  = transactionId,
        vnpayUrl       = vnpayUrl,
        escrowStatus   = runCatching { EscrowStatus.valueOf((escrowStatus ?: "none").uppercase()) }
            .getOrDefault(EscrowStatus.NONE),
        escrowReleasedAt = escrowReleasedAt?.toEpochMillis(),
        paidAt         = paidAt?.toEpochMillis(),
        createdAt      = createdAt.toEpochMillis()
    )
}

fun Payment.toDto() = PaymentDto(
    id             = id,
    bookingId      = bookingId,
    customerId     = customerId,
    workerId       = workerId,
    amount         = amount,
    platformFee    = platformFee,
    workerReceives = workerReceives,
    method         = method.name.lowercase(),
    status         = status.name.lowercase(),
    transactionId  = transactionId,
    vnpayUrl       = vnpayUrl,
    escrowStatus   = escrowStatus.name.lowercase(),
    escrowReleasedAt = null
)

@Serializable
data class WorkerWalletDto(
    val id: String = "",
    @SerialName("worker_id")       val workerId: String = "",
    val balance: Double = 0.0,
    @SerialName("total_earned")    val totalEarned: Double = 0.0,
    @SerialName("total_withdrawn") val totalWithdrawn: Double = 0.0,
    @SerialName("updated_at")      val updatedAt: String = ""
) {
    fun toDomain() = com.example.fixbid.domain.model.WorkerWallet(
        id             = id,
        workerId       = workerId,
        balance        = balance,
        totalEarned    = totalEarned,
        totalWithdrawn = totalWithdrawn,
        updatedAt      = updatedAt.toEpochMillis()
    )
}

@Serializable
data class WalletTransactionDto(
    val id: String = "",
    @SerialName("wallet_id")      val walletId: String = "",
    @SerialName("booking_id")     val bookingId: String? = null,
    val type: String = "earning",
    val amount: Double = 0.0,
    @SerialName("balance_after")  val balanceAfter: Double = 0.0,
    val description: String = "",
    @SerialName("created_at")     val createdAt: String = ""
) {
    fun toDomain() = com.example.fixbid.domain.model.WalletTransaction(
        id           = id,
        walletId     = walletId,
        bookingId    = bookingId,
        type         = runCatching { com.example.fixbid.domain.model.WalletTransactionType.valueOf(type.uppercase()) }
            .getOrDefault(com.example.fixbid.domain.model.WalletTransactionType.EARNING),
        amount       = amount,
        balanceAfter = balanceAfter,
        description  = description,
        createdAt    = createdAt.toEpochMillis()
    )
}