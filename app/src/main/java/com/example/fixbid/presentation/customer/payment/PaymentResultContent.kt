package com.example.fixbid.presentation.customer.payment

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.NumberFormat
import java.util.Locale

@Composable
fun PaymentResultContent(
    result: PaymentResultState,
    onDone: () -> Unit,
    onRetry: () -> Unit
) {
    val formattedAmount = NumberFormat.getNumberInstance(Locale("vi", "VN"))
        .format(result.amount.toLong()) + " đ"

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Icon
        Icon(
            imageVector = if (result.isSuccess) Icons.Default.CheckCircle else Icons.Default.Error,
            contentDescription = null,
            modifier = Modifier.size(96.dp),
            tint = if (result.isSuccess) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Title
        Text(
            text = if (result.isSuccess) "Thanh toán thành công!" else "Thanh toán thất bại",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            color = if (result.isSuccess) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = result.message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(32.dp))

        // Details card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                DetailRow("Số tiền", formattedAmount)

                if (result.transactionId != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    DetailRow("Mã giao dịch", result.transactionId)
                }

                Spacer(modifier = Modifier.height(12.dp))
                DetailRow(
                    "Trạng thái",
                    if (result.isSuccess) "Tiền đang được giữ an toàn" else "Không thành công"
                )

                if (result.isSuccess) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "💡 Tiền sẽ được chuyển cho thợ sau khi bạn xác nhận công việc hoàn thành.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(40.dp))

        if (result.isSuccess) {
            Button(
                onClick = onDone,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF4CAF50)
                )
            ) {
                Text(
                    "Hoàn tất",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        } else {
            Button(
                onClick = onRetry,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text(
                    "Thử lại",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedButton(
                onClick = onDone,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text(
                    "Quay lại",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}
