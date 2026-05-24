package com.example.fixbid.data.remote.sepay

/**
 * Cấu hình SePay Sandbox
 *
 * SePay hoạt động theo mô hình:
 * 1. App tạo mã QR chuyển khoản với nội dung chuyển khoản duy nhất
 * 2. Khách quét QR và chuyển khoản
 * 3. SePay phát hiện giao dịch và gọi webhook xác nhận
 * 4. Backend (Supabase Edge Function) cập nhật trạng thái payment
 *
 * Thông tin QR sử dụng chuẩn VietQR (img.vietqr.io)
 */
object SePayConfig {
    // ─── Sandbox Bank Account Info ──────────────────────────────────────────
    // TODO: Thay bằng thông tin tài khoản SePay sandbox thật của nhóm
    const val BANK_CODE = "MBBank"          // Mã ngân hàng (BIN code)
    const val ACCOUNT_NUMBER = "0977788899" // Số tài khoản nhận tiền sandbox
    const val ACCOUNT_NAME = "FIXBID APP"   // Tên tài khoản

    // ─── VietQR Image API ───────────────────────────────────────────────────
    private const val VIETQR_BASE_URL = "https://img.vietqr.io/image"

    /**
     * Tạo URL ảnh QR code theo chuẩn VietQR
     * Format: https://img.vietqr.io/image/{bankCode}-{accountNumber}-compact2.jpg
     *         ?amount={amount}&addInfo={transferContent}&accountName={accountName}
     */
    fun generateQrImageUrl(
        amount: Long,
        transferContent: String
    ): String {
        val encodedContent = java.net.URLEncoder.encode(transferContent, "UTF-8")
        val encodedName = java.net.URLEncoder.encode(ACCOUNT_NAME, "UTF-8")
        return "$VIETQR_BASE_URL/$BANK_CODE-$ACCOUNT_NUMBER-compact2.jpg" +
                "?amount=$amount" +
                "&addInfo=$encodedContent" +
                "&accountName=$encodedName"
    }

    /**
     * Tạo nội dung chuyển khoản duy nhất cho mỗi giao dịch
     * Format: FIXBID {paymentId_short}
     * SePay sẽ dùng nội dung này để match giao dịch
     */
    fun generateTransferContent(paymentId: String): String {
        // Lấy 8 ký tự đầu của payment ID để tạo mã ngắn gọn
        val shortId = paymentId.replace("-", "").take(8).uppercase()
        return "FIXBID $shortId"
    }

    // ─── SePay API (dùng cho check status - optional) ───────────────────────
    const val SEPAY_API_BASE_URL = "https://my.sepay.vn/userapi"
    // API Key sẽ được lưu trong local.properties / BuildConfig

    // ─── Webhook Configuration ──────────────────────────────────────────────
    // Webhook URL cần cấu hình trên SePay Dashboard:
    // https://<SUPABASE_PROJECT_REF>.supabase.co/functions/v1/sepay-webhook
    //
    // Ví dụ với project hiện tại:
    // https://tvekjridlosdykhdazvd.supabase.co/functions/v1/sepay-webhook
    //
    // Cách cấu hình:
    // 1. Vào https://my.sepay.vn → Cài đặt → Webhook
    // 2. Dán URL trên vào ô "Webhook URL"
    // 3. Bật webhook
    // 4. (Optional) Đặt Secret Key nếu muốn xác thực
    //
    // Khi khách chuyển khoản thành công:
    //   SePay gọi webhook → Edge Function cập nhật payment (pending → escrow)
    //   → Booking chuyển sang in_progress → Thợ bắt đầu làm việc
}
