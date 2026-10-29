package com.example.localmovielibrarz.ui.guangya
import com.example.localmovielibrarz.ui.AppRadius

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.QrCode
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.localmovielibrarz.ui.shared.ConfirmDangerDialog
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * 光鸭云盘登录面板（短信验证码 + 扫码登录双方式共存）。
 * 嵌入「网盘设置」页，位于 115 二维码登录面板下方，视觉风格与之一致。
 *
 * 扫码登录走 OAuth Device Code（/v1/auth/device/code + /v1/auth/token），
 * 二维码内容来自 verification_uri_complete，App 内轮询令牌，登录成功与短信登录共用令牌存储。
 */
@Composable
fun GuangyaLoginPanel(viewModel: GuangyaLoginViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var loginTab by remember { mutableStateOf(0) }
    // H+1 轮：退出登录此前无任何确认，误触即登出
    var confirmLogout by remember { mutableStateOf(false) }

    // 切到扫码 Tab 且二维码尚未初始化时自动生成
    LaunchedEffect(loginTab, uiState.step) {
        if (loginTab == 1 &&
            uiState.step != GuangyaLoginStep.LoggedIn &&
            uiState.qrStep == GuangyaQrStep.Idle
        ) {
            viewModel.initQr()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.075f),
                AppRadius.LG
            )
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Rounded.Public,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.82f)
            )
            Column(modifier = Modifier.padding(start = 10.dp)) {
                Text(
                    text = "光鸭云盘",
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "支持短信验证码或扫码登录，登录后即可生成光鸭网盘的 STRM 文件并播放。",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        when (uiState.step) {
            GuangyaLoginStep.LoggedIn -> {
                Text(
                    text = "已登录光鸭云盘",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f),
                    style = MaterialTheme.typography.bodyMedium
                )
                OutlinedButton(
                    onClick = { confirmLogout = true },
                    modifier = Modifier.fillMaxWidth(),
                    shape = AppRadius.LG,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("退出登录")
                }
                if (confirmLogout) {
                    ConfirmDangerDialog(
                        title = "退出光鸭云盘登录？",
                        message = "退出后需要重新登录才能生成光鸭网盘的 STRM 文件并播放，已生成的 STRM 不受影响。",
                        confirmText = "退出登录",
                        onConfirm = {
                            confirmLogout = false
                            viewModel.logout()
                        },
                        onDismiss = { confirmLogout = false }
                    )
                }
            }

            else -> {
                // 登录方式切换 Tab
                TabRow(
                    selectedTabIndex = loginTab,
                    containerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                    contentColor = MaterialTheme.colorScheme.onSurface
                ) {
                    Tab(
                        selected = loginTab == 0,
                        onClick = { loginTab = 0 }
                    ) {
                        Text("短信登录", style = MaterialTheme.typography.labelLarge)
                    }
                    Tab(
                        selected = loginTab == 1,
                        onClick = { loginTab = 1 }
                    ) {
                        Text("扫码登录", style = MaterialTheme.typography.labelLarge)
                    }
                }

                if (loginTab == 0) {
                    SmsLoginContent(uiState = uiState, viewModel = viewModel)
                } else {
                    QrLoginContent(uiState = uiState, viewModel = viewModel)
                }
            }
        }

        uiState.status.takeIf { it.isNotBlank() && it != "登录成功" }?.let { status ->
            Text(
                text = status,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
                style = MaterialTheme.typography.bodySmall
            )
        }
        uiState.error?.let { err ->
            Text(
                text = "出错：$err",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun SmsLoginContent(
    uiState: GuangyaLoginUiState,
    viewModel: GuangyaLoginViewModel
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when (uiState.step) {
            GuangyaLoginStep.CodeSent -> {
                OutlinedTextField(
                    value = uiState.verificationCode,
                    onValueChange = { value ->
                        viewModel.onCodeChanged(value.filter { it.isDigit() }.take(6))
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("短信验证码") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    enabled = !uiState.isLoading,
                    shape = AppRadius.MD
                )
                Button(
                    onClick = viewModel::submitCode,
                    enabled = uiState.verificationCode.length >= 4 && !uiState.isLoading,
                    modifier = Modifier.fillMaxWidth(),
                    shape = AppRadius.LG
                ) {
                    if (uiState.isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(end = 8.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                    Text(if (uiState.isLoading) "正在登录..." else "登录")
                }
                Text(
                    text = if (uiState.countdown > 0) {
                        "验证码已发送至 ${maskPhone(uiState.phoneNumber)}，${uiState.countdown}s 后可重新获取"
                    } else {
                        "未收到验证码？"
                    },
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
                    style = MaterialTheme.typography.bodySmall
                )
                if (uiState.countdown <= 0) {
                    TextButton(
                        onClick = viewModel::resendCode,
                        enabled = !uiState.isLoading,
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text("重新获取验证码")
                    }
                }
            }

            else -> {
                OutlinedTextField(
                    value = uiState.phoneNumber,
                    onValueChange = viewModel::onPhoneChanged,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("手机号（+86 13800138000）") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    enabled = !uiState.isLoading,
                    shape = AppRadius.MD
                )
                Button(
                    onClick = viewModel::sendCode,
                    enabled = !uiState.isLoading,
                    modifier = Modifier.fillMaxWidth(),
                    shape = AppRadius.LG
                ) {
                    if (uiState.isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(end = 8.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                    Text(if (uiState.isLoading) "正在发送..." else "获取验证码")
                }
            }
        }
    }
}

@Composable
private fun QrLoginContent(
    uiState: GuangyaLoginUiState,
    viewModel: GuangyaLoginViewModel
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        when (uiState.qrStep) {
            GuangyaQrStep.LoggedIn -> {
                Text(
                    text = "扫码登录成功",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }

            GuangyaQrStep.Expired -> {
                Text(
                    text = uiState.qrStatus.ifBlank { "二维码已过期" },
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedButton(
                    onClick = viewModel::refreshQr,
                    modifier = Modifier.fillMaxWidth(),
                    shape = AppRadius.LG
                ) {
                    Text("刷新二维码")
                }
            }

            GuangyaQrStep.Idle -> {
                Button(
                    onClick = viewModel::initQr,
                    modifier = Modifier.fillMaxWidth(),
                    shape = AppRadius.LG
                ) {
                    Icon(Icons.Rounded.QrCode, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                    Text("生成登录二维码")
                }
            }

            GuangyaQrStep.Pending -> {
                if (uiState.qrPayload.isNotBlank()) {
                    val qrBitmap = remember(uiState.qrPayload) {
                        generateQrBitmap(uiState.qrPayload, size = 220)
                    }
                    if (qrBitmap != null) {
                        Box(
                            modifier = Modifier
                                .background(
                                    ComposeColor.White,
                                    AppRadius.SM
                                )
                                .padding(8.dp)
                        ) {
                            Image(
                                bitmap = qrBitmap.asImageBitmap(),
                                contentDescription = "光鸭扫码登录二维码",
                                modifier = Modifier.size(204.dp)
                            )
                        }
                    }
                    Text(
                        text = uiState.qrStatus.ifBlank { "请使用光鸭 App / 网页扫码确认" },
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (uiState.qrLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    TextButton(onClick = viewModel::refreshQr) {
                        Text("刷新二维码")
                    }
                } else {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(vertical = 20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = uiState.qrStatus.ifBlank { "正在生成二维码…" },
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

/** 用 zxing 生成二维码位图（尺寸 size×size，失败返回 null）。 */
private fun generateQrBitmap(content: String, size: Int = 220): Bitmap? {
    if (content.isBlank()) return null
    return try {
        val hints = mapOf(
            com.google.zxing.EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            com.google.zxing.EncodeHintType.MARGIN to 2
        )
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
        val pixels = IntArray(size * size)
        for (y in 0 until size) {
            val offset = y * size
            for (x in 0 until size) {
                pixels[offset + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
            }
        }
        Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    } catch (_: Exception) {
        null
    }
}

private fun maskPhone(phone: String): String {
    val digits = phone.filter { it.isDigit() }
    return if (digits.length >= 7) {
        "${digits.take(3)}****${digits.takeLast(4)}"
    } else {
        phone
    }
}