package com.example.fixbid.data.remote.vnpay

import com.example.fixbid.BuildConfig
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * VNPay payment URL generator.
 *
 * Sử dụng sandbox:
 * - URL: https://sandbox.vnpayment.vn/paymentv2/vpcpay.html
 * - TmnCode & HashSecret lấy từ VNPay sandbox dashboard
 *
 * Trong production chuyển sang: https://pay.vnpay.vn/vpcpay.html
 */
@Singleton
class VNPayService @Inject constructor() {

    companion object {
        // VNPay Sandbox config - đọc từ BuildConfig (local.properties)
        const val VNP_PAY_URL = "https://sandbox.vnpayment.vn/paymentv2/vpcpay.html"
        val VNP_TMN_CODE: String get() = BuildConfig.VNPAY_TMN_CODE
        val VNP_HASH_SECRET: String get() = BuildConfig.VNPAY_HASH_SECRET
        const val VNP_RETURN_URL = "fixbid://vnpay-return"      // Deep link callback
        const val VNP_VERSION = "2.1.0"
        const val VNP_COMMAND = "pay"
        const val VNP_CURRENCY_CODE = "VND"
        const val VNP_LOCALE = "vn"
        const val VNP_ORDER_TYPE = "other"
    }

    /**
     * Tạo URL thanh toán VNPay.
     *
     * @param orderId - Mã đơn hàng (paymentId)
     * @param amount - Số tiền (VND, không có phần thập phân)
     * @param orderInfo - Mô tả đơn hàng
     * @param ipAddress - IP khách hàng (có thể dùng "127.0.0.1" cho mobile)
     * @return Payment URL hoàn chỉnh để redirect user
     */
    fun createPaymentUrl(
        orderId: String,
        amount: Long,
        orderInfo: String,
        ipAddress: String = "127.0.0.1"
    ): String {
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("Etc/GMT+7"))
        val formatter = SimpleDateFormat("yyyyMMddHHmmss", Locale.US)
        formatter.timeZone = TimeZone.getTimeZone("Etc/GMT+7")
        val createDate = formatter.format(calendar.time)

        calendar.add(Calendar.MINUTE, 15)
        val expireDate = formatter.format(calendar.time)

        val params = sortedMapOf(
            "vnp_Version" to VNP_VERSION,
            "vnp_Command" to VNP_COMMAND,
            "vnp_TmnCode" to VNP_TMN_CODE,
            "vnp_Amount" to (amount * 100).toString(), // VNPay yêu cầu amount * 100
            "vnp_CurrCode" to VNP_CURRENCY_CODE,
            "vnp_TxnRef" to orderId,
            "vnp_OrderInfo" to orderInfo,
            "vnp_OrderType" to VNP_ORDER_TYPE,
            "vnp_Locale" to VNP_LOCALE,
            "vnp_ReturnUrl" to VNP_RETURN_URL,
            "vnp_IpAddr" to ipAddress,
            "vnp_CreateDate" to createDate,
            "vnp_ExpireDate" to expireDate
        )

        // Build query string (sorted by key)
        val queryString = params.entries.joinToString("&") { (key, value) ->
            "$key=${URLEncoder.encode(value, "UTF-8")}"
        }

        // Calculate HMAC-SHA512
        val secureHash = hmacSHA512(VNP_HASH_SECRET, queryString)

        return "$VNP_PAY_URL?$queryString&vnp_SecureHash=$secureHash"
    }

    /**
     * Xác thực response từ VNPay callback.
     *
     * @param params - Map các tham số trả về từ VNPay
     * @return true nếu chữ ký hợp lệ
     */
    fun verifyReturnUrl(params: Map<String, String>): Boolean {
        val secureHash = params["vnp_SecureHash"] ?: return false

        // Build hash data từ các params (bỏ vnp_SecureHash và vnp_SecureHashType)
        val hashParams = params.toSortedMap().filter {
            it.key != "vnp_SecureHash" && it.key != "vnp_SecureHashType"
        }

        val hashData = hashParams.entries.joinToString("&") { (key, value) ->
            "$key=${URLEncoder.encode(value, "UTF-8")}"
        }

        val calculatedHash = hmacSHA512(VNP_HASH_SECRET, hashData)
        return secureHash.equals(calculatedHash, ignoreCase = true)
    }

    /**
     * Kiểm tra mã phản hồi từ VNPay.
     * "00" = Thanh toán thành công
     */
    fun isPaymentSuccess(responseCode: String?): Boolean {
        return responseCode == "00"
    }

    private fun hmacSHA512(key: String, data: String): String {
        val hmacSHA512 = Mac.getInstance("HmacSHA512")
        val secretKey = SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA512")
        hmacSHA512.init(secretKey)
        val hash = hmacSHA512.doFinal(data.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }
}
