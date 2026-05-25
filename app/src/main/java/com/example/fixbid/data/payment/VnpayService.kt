package com.example.fixbid.data.payment

import com.example.fixbid.domain.model.VnpayReturnResult
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.*
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * VNPay Sandbox integration service.
 * Tạo payment URL để redirect user sang VNPay và xác minh kết quả trả về.
 *
 * Sandbox credentials (mặc định cho môi trường test):
 * - TMN Code: FIXBID01
 * - Hash Secret: VNPAY_HASH_SECRET_SANDBOX
 * - URL: https://sandbox.vnpayment.vn/paymentv2/vpcpay.html
 */
@Singleton
class VnpayService @Inject constructor(
    private val vnpayConfig: VnpayConfig
) {

    companion object {
        private const val VNP_VERSION = "2.1.0"
        private const val VNP_COMMAND = "pay"
        private const val VNP_CURR_CODE = "VND"
        private const val VNP_LOCALE = "vn"
        private const val VNP_ORDER_TYPE = "other"
    }

    /**
     * Tạo URL thanh toán VNPay.
     * @param orderId ID đơn hàng (payment ID)
     * @param amount Số tiền (VND) - VNPay yêu cầu nhân 100
     * @param orderInfo Mô tả đơn hàng
     * @param ipAddress IP khách hàng (có thể dùng 127.0.0.1 cho sandbox)
     * @return URL hoàn chỉnh để redirect user sang VNPay
     */
    fun createPaymentUrl(
        orderId: String,
        amount: Long,
        orderInfo: String,
        ipAddress: String = "127.0.0.1"
    ): String {
        val vnpParams = TreeMap<String, String>()

        val createDate = SimpleDateFormat("yyyyMMddHHmmss", Locale.getDefault()).apply {
            timeZone = TimeZone.getTimeZone("Asia/Ho_Chi_Minh")
        }.format(Date())

        val expireDate = SimpleDateFormat("yyyyMMddHHmmss", Locale.getDefault()).apply {
            timeZone = TimeZone.getTimeZone("Asia/Ho_Chi_Minh")
        }.format(Date(System.currentTimeMillis() + 15 * 60 * 1000)) // 15 phút

        vnpParams["vnp_Version"] = VNP_VERSION
        vnpParams["vnp_Command"] = VNP_COMMAND
        vnpParams["vnp_TmnCode"] = vnpayConfig.tmnCode
        vnpParams["vnp_Amount"] = (amount * 100).toString() // VNPay yêu cầu * 100
        vnpParams["vnp_CurrCode"] = VNP_CURR_CODE
        vnpParams["vnp_TxnRef"] = orderId
        vnpParams["vnp_OrderInfo"] = orderInfo
        vnpParams["vnp_OrderType"] = VNP_ORDER_TYPE
        vnpParams["vnp_Locale"] = VNP_LOCALE
        vnpParams["vnp_ReturnUrl"] = vnpayConfig.returnUrl
        vnpParams["vnp_IpAddr"] = ipAddress
        vnpParams["vnp_CreateDate"] = createDate
        vnpParams["vnp_ExpireDate"] = expireDate

        // Build query string (sorted by key)
        val queryBuilder = StringBuilder()
        val hashDataBuilder = StringBuilder()

        vnpParams.entries.forEachIndexed { index, entry ->
            if (index > 0) {
                queryBuilder.append("&")
                hashDataBuilder.append("&")
            }
            val encodedKey = URLEncoder.encode(entry.key, "UTF-8")
            val encodedValue = URLEncoder.encode(entry.value, "UTF-8")
            queryBuilder.append("$encodedKey=$encodedValue")
            hashDataBuilder.append("${entry.key}=${entry.value}")
        }

        val hashData = hashDataBuilder.toString()
        val secureHash = hmacSHA512(vnpayConfig.hashSecret, hashData)

        queryBuilder.append("&vnp_SecureHash=$secureHash")

        return "${vnpayConfig.paymentUrl}?$queryBuilder"
    }

    /**
     * Xác minh kết quả trả về từ VNPay (return URL params).
     * @param params Map các tham số từ return URL
     * @return VnpayReturnResult cho biết thanh toán thành công hay thất bại
     */
    fun verifyReturnUrl(params: Map<String, String>): VnpayReturnResult {
        val secureHash = params["vnp_SecureHash"] ?: return VnpayReturnResult(
            isSuccess = false,
            transactionId = null,
            amount = null,
            orderInfo = null,
            responseCode = "99",
            message = "Thiếu chữ ký bảo mật"
        )

        // Loại bỏ vnp_SecureHash và vnp_SecureHashType khỏi params để tính lại hash
        val filteredParams = params.filterKeys {
            it != "vnp_SecureHash" && it != "vnp_SecureHashType"
        }.toSortedMap()

        val hashData = filteredParams.entries.joinToString("&") { "${it.key}=${it.value}" }
        val calculatedHash = hmacSHA512(vnpayConfig.hashSecret, hashData)

        if (!calculatedHash.equals(secureHash, ignoreCase = true)) {
            return VnpayReturnResult(
                isSuccess = false,
                transactionId = params["vnp_TransactionNo"],
                amount = params["vnp_Amount"]?.toLongOrNull()?.div(100),
                orderInfo = params["vnp_OrderInfo"],
                responseCode = "97",
                message = "Chữ ký không hợp lệ"
            )
        }

        val responseCode = params["vnp_ResponseCode"] ?: "99"
        val transactionStatus = params["vnp_TransactionStatus"] ?: "99"

        val isSuccess = responseCode == "00" && transactionStatus == "00"

        return VnpayReturnResult(
            isSuccess = isSuccess,
            transactionId = params["vnp_TransactionNo"],
            amount = params["vnp_Amount"]?.toLongOrNull()?.div(100),
            orderInfo = params["vnp_OrderInfo"],
            responseCode = responseCode,
            message = getResponseMessage(responseCode)
        )
    }

    /**
     * Parse return URL thành Map params
     */
    fun parseReturnUrl(url: String): Map<String, String> {
        val queryString = url.substringAfter("?", "")
        if (queryString.isBlank()) return emptyMap()

        return queryString.split("&").associate { param ->
            val parts = param.split("=", limit = 2)
            val key = java.net.URLDecoder.decode(parts[0], "UTF-8")
            val value = if (parts.size > 1) java.net.URLDecoder.decode(parts[1], "UTF-8") else ""
            key to value
        }
    }

    private fun hmacSHA512(key: String, data: String): String {
        val hmacSha512 = Mac.getInstance("HmacSHA512")
        val secretKey = SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA512")
        hmacSha512.init(secretKey)
        val hash = hmacSha512.doFinal(data.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }

    private fun getResponseMessage(code: String): String = when (code) {
        "00" -> "Thanh toán thành công"
        "07" -> "Trừ tiền thành công. Giao dịch bị nghi ngờ (liên quan tới lừa đảo, giao dịch bất thường)"
        "09" -> "Thẻ/Tài khoản chưa đăng ký dịch vụ InternetBanking"
        "10" -> "Xác thực thông tin thẻ/tài khoản không đúng quá 3 lần"
        "11" -> "Đã hết hạn chờ thanh toán. Xin quý khách vui lòng thực hiện lại giao dịch"
        "12" -> "Thẻ/Tài khoản bị khóa"
        "13" -> "Quý khách nhập sai mật khẩu xác thực giao dịch (OTP)"
        "24" -> "Khách hàng hủy giao dịch"
        "51" -> "Tài khoản không đủ số dư để thực hiện giao dịch"
        "65" -> "Tài khoản đã vượt quá hạn mức giao dịch trong ngày"
        "75" -> "Ngân hàng thanh toán đang bảo trì"
        "79" -> "Nhập sai mật khẩu thanh toán quá số lần quy định"
        "99" -> "Lỗi không xác định"
        else -> "Lỗi thanh toán (Mã: $code)"
    }
}
