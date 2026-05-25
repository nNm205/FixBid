package com.example.fixbid.data.payment

/**
 * VNPay configuration - Sandbox environment.
 * Trong production, các giá trị này nên được lấy từ BuildConfig hoặc remote config.
 */
data class VnpayConfig(
    val tmnCode: String = "FIXBID01",
    val hashSecret: String = "VNPAYSANDBOXSECRETKEY2024FIXBID01",
    val paymentUrl: String = "https://sandbox.vnpayment.vn/paymentv2/vpcpay.html",
    val returnUrl: String = "fixbid://vnpay-return",
    val apiUrl: String = "https://sandbox.vnpayment.vn/merchant_webapi/api/transaction"
)
