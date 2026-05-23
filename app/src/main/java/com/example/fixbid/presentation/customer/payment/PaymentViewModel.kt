package com.example.fixbid.presentation.customer.payment

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.fixbid.domain.model.Booking
import com.example.fixbid.domain.model.Payment
import com.example.fixbid.domain.model.PaymentStatus
import com.example.fixbid.domain.model.Resource
import com.example.fixbid.domain.model.SePayQrInfo
import com.example.fixbid.domain.repository.BookingRepository
import com.example.fixbid.domain.repository.PaymentRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PaymentUiState(
    val isLoading: Boolean = true,
    val booking: Booking? = null,
    val payment: Payment? = null,
    val qrInfo: SePayQrInfo? = null,
    val paymentStatus: PaymentStatus = PaymentStatus.PENDING,
    val errorMessage: String? = null,
    val isCreatingPayment: Boolean = false,
    val showSuccessDialog: Boolean = false,
    val pollingActive: Boolean = false
)

sealed class PaymentEvent {
    data class Toast(val message: String) : PaymentEvent()
    object PaymentSuccess : PaymentEvent()
    object PaymentFailed : PaymentEvent()
}

@HiltViewModel
class PaymentViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val bookingRepository: BookingRepository,
    private val paymentRepository: PaymentRepository
) : ViewModel() {

    private val bookingId: String = savedStateHandle.get<String>("bookingId") ?: ""

    private val _uiState = MutableStateFlow(PaymentUiState())
    val uiState: StateFlow<PaymentUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<PaymentEvent>()
    val events: SharedFlow<PaymentEvent> = _events.asSharedFlow()

    private var pollingJob: Job? = null

    init {
        loadBookingAndPayment()
    }

    fun loadBookingAndPayment() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)

            // Load booking info
            when (val bookingResult = bookingRepository.getBookingById(bookingId)) {
                is Resource.Success -> {
                    _uiState.value = _uiState.value.copy(booking = bookingResult.data)
                }
                is Resource.Error -> {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        errorMessage = bookingResult.message
                    )
                    return@launch
                }
                is Resource.Loading -> {}
            }

            // Check if payment already exists
            when (val paymentResult = paymentRepository.getPaymentByBooking(bookingId)) {
                is Resource.Success -> {
                    val payment = paymentResult.data
                    _uiState.value = _uiState.value.copy(
                        payment = payment,
                        paymentStatus = payment.status
                    )

                    // If payment exists and is pending, load QR info
                    if (payment.status == PaymentStatus.PENDING ||
                        payment.status == PaymentStatus.PROCESSING) {
                        loadQrInfo(payment.id)
                        startPolling(payment.id)
                    } else if (payment.status == PaymentStatus.ESCROW ||
                        payment.status == PaymentStatus.COMPLETED) {
                        _uiState.value = _uiState.value.copy(showSuccessDialog = true)
                    }
                }
                is Resource.Error -> {
                    // Payment doesn't exist yet - that's fine, we'll create one
                }
                is Resource.Loading -> {}
            }

            _uiState.value = _uiState.value.copy(isLoading = false)
        }
    }

    fun createPayment() {
        val booking = _uiState.value.booking ?: return
        val amount = booking.agreedPrice ?: return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isCreatingPayment = true)

            when (val result = paymentRepository.createSePayPayment(bookingId, amount)) {
                is Resource.Success -> {
                    val payment = result.data
                    _uiState.value = _uiState.value.copy(
                        payment = payment,
                        paymentStatus = payment.status,
                        isCreatingPayment = false
                    )
                    loadQrInfo(payment.id)
                    startPolling(payment.id)
                }
                is Resource.Error -> {
                    _uiState.value = _uiState.value.copy(isCreatingPayment = false)
                    _events.emit(PaymentEvent.Toast(result.message))
                }
                is Resource.Loading -> {}
            }
        }
    }

    private fun loadQrInfo(paymentId: String) {
        viewModelScope.launch {
            when (val result = paymentRepository.getSePayQrInfo(paymentId)) {
                is Resource.Success -> {
                    _uiState.value = _uiState.value.copy(qrInfo = result.data)
                }
                is Resource.Error -> {
                    _events.emit(PaymentEvent.Toast("Lỗi tải QR: ${result.message}"))
                }
                is Resource.Loading -> {}
            }
        }
    }

    private fun startPolling(paymentId: String) {
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(pollingActive = true)

            while (isActive) {
                delay(5000) // Poll every 5 seconds

                when (val result = paymentRepository.checkPaymentStatus(paymentId)) {
                    is Resource.Success -> {
                        val status = result.data
                        _uiState.value = _uiState.value.copy(paymentStatus = status)

                        when (status) {
                            PaymentStatus.ESCROW -> {
                                // Payment confirmed, money held in escrow
                                _uiState.value = _uiState.value.copy(
                                    showSuccessDialog = true,
                                    pollingActive = false
                                )
                                _events.emit(PaymentEvent.PaymentSuccess)
                                pollingJob?.cancel()
                                return@launch
                            }
                            PaymentStatus.COMPLETED -> {
                                _uiState.value = _uiState.value.copy(
                                    showSuccessDialog = true,
                                    pollingActive = false
                                )
                                _events.emit(PaymentEvent.PaymentSuccess)
                                pollingJob?.cancel()
                                return@launch
                            }
                            PaymentStatus.FAILED -> {
                                _uiState.value = _uiState.value.copy(pollingActive = false)
                                _events.emit(PaymentEvent.PaymentFailed)
                                pollingJob?.cancel()
                                return@launch
                            }
                            else -> { /* continue polling */ }
                        }
                    }
                    is Resource.Error -> { /* ignore, retry next poll */ }
                    is Resource.Loading -> {}
                }
            }
        }
    }

    fun stopPolling() {
        pollingJob?.cancel()
        _uiState.value = _uiState.value.copy(pollingActive = false)
    }

    override fun onCleared() {
        super.onCleared()
        pollingJob?.cancel()
    }
}
