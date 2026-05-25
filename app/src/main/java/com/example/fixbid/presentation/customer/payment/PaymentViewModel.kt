package com.example.fixbid.presentation.customer.payment

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.fixbid.data.payment.VnpayService
import com.example.fixbid.domain.model.Payment
import com.example.fixbid.domain.model.PaymentStatus
import com.example.fixbid.domain.model.Resource
import com.example.fixbid.domain.repository.BookingRepository
import com.example.fixbid.domain.usecase.customer.ConfirmVnpayPaymentUseCase
import com.example.fixbid.domain.usecase.customer.InitiateVnpayPaymentUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PaymentUiState(
    val isLoading: Boolean = true,
    val bookingId: String = "",
    val amount: Double = 0.0,
    val workerName: String = "",
    val payment: Payment? = null,
    val vnpayUrl: String? = null,
    val paymentResult: PaymentResultState? = null,
    val errorMessage: String? = null
)

data class PaymentResultState(
    val isSuccess: Boolean,
    val message: String,
    val transactionId: String? = null,
    val amount: Double = 0.0
)

sealed class PaymentEvent {
    data class Toast(val message: String) : PaymentEvent()
    object PaymentSuccess : PaymentEvent()
    object PaymentFailed : PaymentEvent()
    object NavigateBack : PaymentEvent()
}

@HiltViewModel
class PaymentViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val initiateVnpayPaymentUseCase: InitiateVnpayPaymentUseCase,
    private val confirmVnpayPaymentUseCase: ConfirmVnpayPaymentUseCase,
    private val bookingRepository: BookingRepository,
    private val vnpayService: VnpayService
) : ViewModel() {

    private val bookingId: String = savedStateHandle.get<String>("bookingId") ?: ""

    private val _uiState = MutableStateFlow(PaymentUiState(bookingId = bookingId))
    val uiState: StateFlow<PaymentUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<PaymentEvent>()
    val events: SharedFlow<PaymentEvent> = _events.asSharedFlow()

    init {
        loadBookingDetails()
    }

    private fun loadBookingDetails() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            when (val result = bookingRepository.getBookingById(bookingId)) {
                is Resource.Success -> {
                    val booking = result.data
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        amount = booking.agreedPrice ?: 0.0,
                        workerName = booking.worker?.fullName ?: "Thợ"
                    )
                }
                is Resource.Error -> {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        errorMessage = result.message
                    )
                }
                is Resource.Loading -> {}
            }
        }
    }

    /**
     * Khởi tạo thanh toán VNPay - tạo payment URL
     */
    fun initiatePayment() {
        val amount = _uiState.value.amount
        if (amount <= 0) {
            viewModelScope.launch {
                _events.emit(PaymentEvent.Toast("Số tiền không hợp lệ"))
            }
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            when (val result = initiateVnpayPaymentUseCase(bookingId, amount)) {
                is Resource.Success -> {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        payment = result.data,
                        vnpayUrl = result.data.vnpayUrl
                    )
                }
                is Resource.Error -> {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        errorMessage = result.message
                    )
                    _events.emit(PaymentEvent.Toast(result.message))
                }
                is Resource.Loading -> {}
            }
        }
    }

    /**
     * Xử lý URL trả về từ VNPay WebView
     */
    fun handleVnpayReturn(returnUrl: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)

            val params = vnpayService.parseReturnUrl(returnUrl)
            val paymentId = _uiState.value.payment?.id ?: ""

            when (val result = confirmVnpayPaymentUseCase(paymentId, params)) {
                is Resource.Success -> {
                    val payment = result.data
                    val isSuccess = payment.status == PaymentStatus.ESCROW_HELD
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        payment = payment,
                        vnpayUrl = null,
                        paymentResult = PaymentResultState(
                            isSuccess = isSuccess,
                            message = if (isSuccess) "Thanh toán thành công!" else "Thanh toán thất bại",
                            transactionId = payment.transactionId,
                            amount = payment.amount
                        )
                    )
                    if (isSuccess) {
                        _events.emit(PaymentEvent.PaymentSuccess)
                    } else {
                        _events.emit(PaymentEvent.PaymentFailed)
                    }
                }
                is Resource.Error -> {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        vnpayUrl = null,
                        paymentResult = PaymentResultState(
                            isSuccess = false,
                            message = result.message,
                            amount = _uiState.value.amount
                        )
                    )
                    _events.emit(PaymentEvent.PaymentFailed)
                }
                is Resource.Loading -> {}
            }
        }
    }

    fun retryPayment() {
        _uiState.value = _uiState.value.copy(
            paymentResult = null,
            vnpayUrl = null,
            payment = null,
            errorMessage = null
        )
        initiatePayment()
    }
}
