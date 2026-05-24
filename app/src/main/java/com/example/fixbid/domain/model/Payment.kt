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
    val paidAt: Long?,
    val createdAt: Long,
    val transferContent: String? = null // nội dung chuyển khoản (dùng cho SePay)
)

enum class PaymentMethod {
    CASH,           // tiền mặt (xác nhận thủ công)
    MOMO,
    VNPAY,
    BANK_TRANSFER,  // chuyển khoản ngân hàng thông thường
    SEPAY           // thanh toán qua SePay QR (tự động xác nhận)
}

enum class PaymentStatus {
    PENDING,        // chờ thanh toán
    PROCESSING,     // đang xử lý (tiền đã chuyển, chờ xác nhận)
    ESCROW,         // hệ thống đang giữ tiền (chờ hoàn thành công việc)
    COMPLETED,      // hoàn tất (tiền đã chuyển cho thợ)
    FAILED,         // thất bại
    REFUNDED        // đã hoàn tiền
}

/**
 * Thông tin QR thanh toán SePay
 */
data class SePayQrInfo(
    val bankCode: String,          // Mã ngân hàng (VD: "MBBank")
    val accountNumber: String,     // Số tài khoản nhận
    val accountName: String,       // Tên tài khoản nhận
    val amount: Double,            // Số tiền cần chuyển
    val transferContent: String,   // Nội dung chuyển khoản (mã giao dịch)
    val qrImageUrl: String         // URL ảnh QR từ VietQR
)