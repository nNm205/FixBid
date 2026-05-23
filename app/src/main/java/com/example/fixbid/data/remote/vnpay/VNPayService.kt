package com.example.fixbid.data.remote.vnpay

import android.util.Log
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
        private const val TAG = "VNPayService"
        const val VNP_PAY_URL = "https://sandbox.vnpayment.vn/paymentv2/vpcpay.html"
        const val VNP_RETURN_URL = "fixbid://vnpay-return"
        const val VNP_VERSION = "2.1.0"
        const val VNP_COMMAND = "pay"
        const val VNP_CURRENCY_CODE = "VND"
        const val VNP_LOCALE = "vn"
        const val VNP_ORDER_TYPE = "other"
    }

    // Đọc từ BuildConfig (local.properties)
    private val vnpTmnCode: String get() = BuildConfig.VNPAY_TMN_CODE
    private val vnpHashSecret: String get() = BuildConfig.VNPAY_HASH_SECRET

    /**
     * Tạo URL thanh toán VNPay.
     */
    fun createPaymentUrl(
        orderId: String,
        amount: Long,
        orderInfo: String,
        ipAddress: String = "127.0.0.1"
    ): String {
        val vnTimeZone = TimeZone.getTimeZone("Asia/Ho_Chi_Minh")
        val calendar = Calendar.getInstance(vnTimeZone)
        val formatter = SimpleDateFormat("yyyyMMddHHmmss", Locale.US)
        formatter.timeZone = vnTimeZone
        val createDate = formatter.format(calendar.time)

        calendar.add(Calendar.MINUTE, 15)
        val expireDate = formatter.format(calendar.time)

        val params = sortedMapOf(
            "vnp_Version" to VNP_VERSION,
            "vnp_Command" to VNP_COMMAND,
            "vnp_TmnCode" to vnpTmnCode,
            "vnp_Amount" to (amount * 100).toString(),
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

        // ====== DEBUG LOG ======
        Log.d(TAG, "══════════════════════════════════════════")
        Log.d(TAG, "VNPay Payment URL Generation Debug:")
        Log.d(TAG, "──────────────────────────────────────────")
        Log.d(TAG, "TmnCode: '$vnpTmnCode'")
        Log.d(TAG, "HashSecret: '${vnpHashSecret.take(8)}...${vnpHashSecret.takeLast(4)}' (length=${vnpHashSecret.length})")
        Log.d(TAG, "──────────────────────────────────────────")
        Log.d(TAG, "Params (sorted):")
        params.forEach { (k, v) -> Log.d(TAG, "  $k = $v") }
        Log.d(TAG, "──────────────────────────────────────────")

        // Tính HMAC trên raw data (KHÔNG URL-encode value)
        val hashData = params.entries.joinToString("&") { (key, value) ->
            "$key=$value"
        }
        Log.d(TAG, "Hash Data (raw):")
        Log.d(TAG, hashData)
        Log.d(TAG, "──────────────────────────────────────────")

        // Calculate HMAC-SHA512
        val secureHash = hmacSHA512(vnpHashSecret, hashData)
        Log.d(TAG, "SecureHash: $secureHash")
        Log.d(TAG, "──────────────────────────────────────────")

        // Build URL cuối cùng với value đã URL-encode
        val queryString = params.entries.joinToString("&") { (key, value) ->
            "$key=${URLEncoder.encode(value, "UTF-8")}"
        }

        val fullUrl = "$VNP_PAY_URL?$queryString&vnp_SecureHash=$secureHash"
        Log.d(TAG, "Full URL:")
        Log.d(TAG, fullUrl)
        Log.d(TAG, "══════════════════════════════════════════")

        return fullUrl
    }

    /**
     * Xác thực response từ VNPay callback.
     */
    fun verifyReturnUrl(params: Map<String, String>): Boolean {
        val secureHash = params["vnp_SecureHash"] ?: return false

        val hashParams = params.toSortedMap().filter {
            it.key != "vnp_SecureHash" && it.key != "vnp_SecureHashType"
        }

        val hashData = hashParams.entries.joinToString("&") { (key, value) ->
            "$key=$value"
        }

        val calculatedHash = hmacSHA512(vnpHashSecret, hashData)

        Log.d(TAG, "Verify Return URL:")
        Log.d(TAG, "  Received hash: $secureHash")
        Log.d(TAG, "  Calculated:    $calculatedHash")
        Log.d(TAG, "  Match: ${secureHash.equals(calculatedHash, ignoreCase = true)}")

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
        val sb = StringBuilder(hash.size * 2)
        for (b in hash) {
            sb.append(String.format("%02x", b.toInt() and 0xff))
        }
        return sb.toString()
    }
}
