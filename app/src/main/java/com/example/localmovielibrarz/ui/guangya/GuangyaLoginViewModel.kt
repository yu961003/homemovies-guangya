package com.example.localmovielibrarz.ui.guangya

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.localmovielibrarz.cloudguangya.GuangyaLoginClient
import com.example.localmovielibrarz.cloudguangya.GuangyaQrPollResult
import com.example.localmovielibrarz.cloudguangya.GuangyaTokenStore
import com.example.localmovielibrarz.data.repository.AppSettingsRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class GuangyaLoginStep { Idle, CodeSent, LoggedIn }

/** 扫码登录流程状态。 */
enum class GuangyaQrStep { Idle, Pending, Expired, LoggedIn }

data class GuangyaLoginUiState(
    val step: GuangyaLoginStep = GuangyaLoginStep.Idle,
    val phoneNumber: String = "",
    val verificationCode: String = "",
    val status: String = "",
    val isLoading: Boolean = false,
    val countdown: Int = 0,
    val error: String? = null,
    // 扫码登录
    val qrStep: GuangyaQrStep = GuangyaQrStep.Idle,
    val qrPayload: String = "",
    val qrStatus: String = "",
    val qrLoading: Boolean = false
)

/**
 * 光鸭短信登录 ViewModel。
 * 流程：输入手机号 → 获取验证码（smsInit + smsSend）→ 输入验证码 → 提交（smsVerify + smsSignin）→ 存令牌。
 * 全程 App 内完成，不跳转浏览器。
 */
class GuangyaLoginViewModel(
    private val loginClient: GuangyaLoginClient,
    private val tokenStore: GuangyaTokenStore,
    private val settingsRepository: AppSettingsRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(GuangyaLoginUiState())
    val uiState: StateFlow<GuangyaLoginUiState> = _uiState.asStateFlow()

    // 跨步骤暂存（验证码发送后、提交前需要）
    private var captchaToken: String? = null
    private var verificationId: String? = null
    private var currentPhone: String? = null
    private var countdownJob: Job? = null

    // 扫码登录
    private var qrPollJob: Job? = null
    private var qrDeviceCode: String? = null

    init {
        // 进入页面时根据持久标记还原登录态
        if (settingsRepository.isGuangyaLoggedIn()) {
            _uiState.value = _uiState.value.copy(step = GuangyaLoginStep.LoggedIn)
        }
    }

    override fun onCleared() {
        stopQrPolling()
        super.onCleared()
    }

    // ---------------- 扫码登录 ----------------

    /** 发起扫码登录：请求 Device Code，生成二维码内容（二维码渲染由 UI 完成）。 */
    fun initQr() {
        if (settingsRepository.isGuangyaLoggedIn()) return
        qrPollJob?.cancel()
        _uiState.update {
            it.copy(
                qrStep = GuangyaQrStep.Pending,
                qrLoading = true,
                qrStatus = "正在生成二维码…",
                error = null
            )
        }
        viewModelScope.launch {
            val result = runCatching { loginClient.qrInit() }
            result.onSuccess { session ->
                qrDeviceCode = session.deviceCode
                _uiState.update {
                    it.copy(
                        qrLoading = false,
                        qrStep = GuangyaQrStep.Pending,
                        qrPayload = session.qrPayload,
                        qrStatus = "请使用光鸭 App / 网页扫码确认"
                    )
                }
                startQrPolling()
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        qrLoading = false,
                        qrStep = GuangyaQrStep.Expired,
                        qrPayload = "",
                        qrStatus = "二维码生成失败：${error.message ?: "未知错误"}",
                        error = error.message
                    )
                }
            }
        }
    }

    /** 轮询令牌，直到登录成功 / 过期。 */
    fun startQrPolling() {
        val deviceCode = qrDeviceCode ?: return
        if (qrPollJob?.isActive == true) return
        qrPollJob = viewModelScope.launch {
            var interval = POLL_INTERVAL_MS
            while (true) {
                delay(interval)
                val result = runCatching { loginClient.qrPoll(deviceCode) }.getOrElse {
                    GuangyaQrPollResult.Pending
                }
                when (result) {
                    is GuangyaQrPollResult.Success -> {
                        tokenStore.save(result.tokenPair)
                        settingsRepository.setGuangyaLoggedIn(true)
                        _uiState.update {
                            it.copy(
                                qrStep = GuangyaQrStep.LoggedIn,
                                qrStatus = "扫码登录成功",
                                step = GuangyaLoginStep.LoggedIn
                            )
                        }
                        break
                    }

                    GuangyaQrPollResult.SlowDown -> {
                        interval = (interval * 2).coerceAtMost(POLL_INTERVAL_MAX_MS)
                        _uiState.update { it.copy(qrStatus = "轮询过快，已放慢频率…") }
                    }

                    GuangyaQrPollResult.Expired -> {
                        qrDeviceCode = null
                        _uiState.update {
                            it.copy(
                                qrStep = GuangyaQrStep.Expired,
                                qrStatus = "二维码已过期，请点击刷新"
                            )
                        }
                        break
                    }

                    GuangyaQrPollResult.Pending -> {
                        interval = POLL_INTERVAL_MS
                        val status = _uiState.value.qrStatus
                        if (status.isBlank() || status.startsWith("轮询")) {
                            _uiState.update { it.copy(qrStatus = "等待扫码确认…") }
                        }
                    }
                }
                if (_uiState.value.step == GuangyaLoginStep.LoggedIn) break
            }
        }
    }

    fun stopQrPolling() {
        qrPollJob?.cancel()
        qrPollJob = null
    }

    /** 重新生成二维码。 */
    fun refreshQr() {
        qrPollJob?.cancel()
        qrDeviceCode = null
        _uiState.update {
            it.copy(qrStep = GuangyaQrStep.Idle, qrPayload = "", qrStatus = "", error = null)
        }
        initQr()
    }

    fun onPhoneChanged(value: String) {
        _uiState.value = _uiState.value.copy(phoneNumber = value, error = null)
    }

    fun onCodeChanged(value: String) {
        _uiState.value = _uiState.value.copy(verificationCode = value, error = null)
    }

    fun sendCode() = sendCode(_uiState.value.phoneNumber.trim())

    fun resendCode() {
        val phone = currentPhone ?: return
        sendCode(phone)
    }

    private fun sendCode(phone: String) {
        if (!isValidPhone(phone)) {
            _uiState.value = _uiState.value.copy(error = "请输入有效的手机号，如 +86 13800138000")
            return
        }
        countdownJob?.cancel()
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null, status = "正在发送验证码...")
            val captcha = runCatching { loginClient.smsInit(phone) }.getOrElse {
                _uiState.value = _uiState.value.copy(isLoading = false, error = it.message ?: "初始化失败")
                return@launch
            }
            val sendResult = runCatching { loginClient.smsSend(phone, captcha) }.getOrElse {
                _uiState.value = _uiState.value.copy(isLoading = false, error = it.message ?: "发送失败")
                return@launch
            }
            // 使用发码成功后实际生效的 captcha_token（captcha 过期自动重试后可能已刷新）
            captchaToken = sendResult.captchaToken
            verificationId = sendResult.verificationId
            currentPhone = phone
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                step = GuangyaLoginStep.CodeSent,
                status = "验证码已发送，请查收短信",
                countdown = RESEND_SECONDS,
                verificationCode = "" // 审查 Y3：重发验证码后清空旧输入，避免新 verificationId + 旧 code 提交失败
            )
            startCountdown()
        }
    }

    fun submitCode() {
        val code = _uiState.value.verificationCode.trim()
        val vid = verificationId
        val captcha = captchaToken
        val phone = currentPhone
        if (code.length < 4) {
            _uiState.value = _uiState.value.copy(error = "请输入收到的验证码")
            return
        }
        if (vid == null || captcha == null || phone == null) {
            _uiState.value = _uiState.value.copy(error = "登录状态已失效，请重新获取验证码")
            return
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null, status = "正在验证...")
            val vtoken = runCatching { loginClient.smsVerify(vid, code) }.getOrElse {
                _uiState.value = _uiState.value.copy(isLoading = false, error = it.message ?: "验证失败")
                return@launch
            }
            val pair = runCatching { loginClient.smsSignin(code, vtoken, phone, captcha) }.getOrElse {
                _uiState.value = _uiState.value.copy(isLoading = false, error = it.message ?: "登录失败")
                return@launch
            }
            tokenStore.save(pair)
            settingsRepository.setGuangyaLoggedIn(true)
            countdownJob?.cancel()
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                step = GuangyaLoginStep.LoggedIn,
                status = "登录成功",
                verificationCode = ""
            )
        }
    }

    fun logout() {
        countdownJob?.cancel()
        stopQrPolling()
        captchaToken = null
        verificationId = null
        currentPhone = null
        qrDeviceCode = null
        tokenStore.clear()
        settingsRepository.setGuangyaLoggedIn(false)
        _uiState.value = GuangyaLoginUiState()
    }

    private fun startCountdown() {
        countdownJob?.cancel()
        countdownJob = viewModelScope.launch {
            while (_uiState.value.countdown > 0) {
                delay(1000)
                _uiState.value = _uiState.value.copy(countdown = _uiState.value.countdown - 1)
            }
        }
    }

    private fun isValidPhone(phone: String): Boolean {
        val digits = phone.replace(Regex("[^0-9]"), "")
        return phone.any { it.isDigit() } && digits.length in 6..15
    }

    companion object {
        const val RESEND_SECONDS = 60
        const val POLL_INTERVAL_MS = 5_000L
        const val POLL_INTERVAL_MAX_MS = 30_000L

        fun factory(
            loginClient: GuangyaLoginClient,
            tokenStore: GuangyaTokenStore,
            settingsRepository: AppSettingsRepository
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                return GuangyaLoginViewModel(loginClient, tokenStore, settingsRepository) as T
            }
        }
    }
}
