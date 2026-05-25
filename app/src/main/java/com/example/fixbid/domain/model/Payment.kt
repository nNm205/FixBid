package com.example.fixbid.domain.model

data class Payment(
    val id: String,
    val bookingId: String,
    val customerId: String,
    val workerId: String,
    val amount: Double,
    val platformFee: Double,           // phí app (ví dụ 10%)
    val workerReceives: Double,        // amount - platformFee
    val method: PaymentMethod,
    val status: PaymentStatus,
    val transactionId: String?,        // mã từ cổng thanh toán
    val vnpayUrl: String? = null,      // URL thanh toán VNPay
    val escrowStatus: EscrowStatus = EscrowStatus.NONE,
    val escrowReleasedAt: Long? = null,
    val paidAt: Long?,
    val createdAt: Long
)

enum class PaymentMethod {
    CASH,           // tiền mặt (xác nhận thủ công)
    MOMO,
    VNPAY,
    BANK_TRANSFER
}

enum class PaymentStatus {
    PENDING,        // chờ thanh toán
    PROCESSING,     // đang xử lý (redirect sang VNPay)
    ESCROW_HELD,    // đã thanh toán, tiền giữ trong escrow
    COMPLETED,      // hoàn tất, tiền đã chuyển cho thợ
    FAILED,         // thanh toán thất bại
    REFUNDED        // hoàn tiền
}

enum class EscrowStatus {
    NONE,           // chưa có escrow
    HOLDING,        // đang giữ tiền
    RELEASED,       // đã giải phóng cho thợ
    REFUNDED        // đã hoàn lại cho khách
}

/**
 * Ví của thợ - theo dõi số dư
 */
data class WorkerWallet(
    val id: String,
    val workerId: String,
    val balance: Double,
    val totalEarned: Double,
    val totalWithdrawn: Double,
    val updatedAt: Long
)

/**
 * Giao dịch ví (lịch sử nạp/rút/nhận tiền)
 */
data class WalletTransaction(
    val id: String,
    val walletId: String,
    val bookingId: String?,
    val type: WalletTransactionType,
    val amount: Double,
    val balanceAfter: Double,
    val description: String,
    val createdAt: Long
)

enum class WalletTransactionType {
    EARNING,        // nhận tiền từ job hoàn thành
    WITHDRAWAL,     // rút tiền về tài khoản ngân hàng
    REFUND,         // hoàn tiền (dispute)
    PLATFORM_FEE    // phí nền tảng (trừ)
}

/**
 * Kết quả trả về từ VNPay sau khi thanh toán
 */
data class VnpayReturnResult(
    val isSuccess: Boolean,
    val transactionId: String?,
    val amount: Long?,
    val orderInfo: String?,
    val responseCode: String,
    val message: String
)