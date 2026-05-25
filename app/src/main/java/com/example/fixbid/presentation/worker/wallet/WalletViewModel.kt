package com.example.fixbid.presentation.worker.wallet

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.fixbid.domain.model.Resource
import com.example.fixbid.domain.model.WalletTransaction
import com.example.fixbid.domain.model.WorkerWallet
import com.example.fixbid.domain.repository.AuthRepository
import com.example.fixbid.domain.usecase.worker.GetWalletUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class WalletUiState(
    val isLoading: Boolean = true,
    val wallet: WorkerWallet? = null,
    val transactions: List<WalletTransaction> = emptyList(),
    val errorMessage: String? = null
)

@HiltViewModel
class WalletViewModel @Inject constructor(
    private val getWalletUseCase: GetWalletUseCase,
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(WalletUiState())
    val uiState: StateFlow<WalletUiState> = _uiState.asStateFlow()

    init {
        loadWallet()
    }

    fun loadWallet() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)

            val user = authRepository.getCurrentUser()
            if (user == null) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = "Chưa đăng nhập"
                )
                return@launch
            }

            when (val walletResult = getWalletUseCase.getWallet(user.id)) {
                is Resource.Success -> {
                    val wallet = walletResult.data
                    _uiState.value = _uiState.value.copy(wallet = wallet)

                    // Load transactions
                    when (val txResult = getWalletUseCase.getTransactions(wallet.id)) {
                        is Resource.Success -> {
                            _uiState.value = _uiState.value.copy(
                                isLoading = false,
                                transactions = txResult.data
                            )
                        }
                        is Resource.Error -> {
                            _uiState.value = _uiState.value.copy(
                                isLoading = false,
                                transactions = emptyList()
                            )
                        }
                        is Resource.Loading -> {}
                    }
                }
                is Resource.Error -> {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        errorMessage = walletResult.message
                    )
                }
                is Resource.Loading -> {}
            }
        }
    }
}
