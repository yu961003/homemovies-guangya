package com.example.localmovielibrarz.ui.settings
import com.example.localmovielibrarz.util.userMessage

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.localmovielibrarz.cloud115.Cloud115LoginApp
import com.example.localmovielibrarz.cloud115.Cloud115LoginApps
import com.example.localmovielibrarz.cloud115.Cloud115QrLoginClient
import com.example.localmovielibrarz.cloud115.Cloud115QrLoginStatus
import com.example.localmovielibrarz.cloud115.Cloud115QrToken
import com.example.localmovielibrarz.cloud115.SavedCloud115Account
import com.example.localmovielibrarz.data.local.CloudFolderBatchTaskEntity
import com.example.localmovielibrarz.data.local.CloudFolderBatchTaskStatus
import com.example.localmovielibrarz.data.local.CloudVideoTaskEntity
import com.example.localmovielibrarz.data.local.MovieEntity
import com.example.localmovielibrarz.data.repository.AppSettingsRepository
import com.example.localmovielibrarz.data.repository.AppUpdateInfo
import com.example.localmovielibrarz.data.repository.AppUpdateRepository
import com.example.localmovielibrarz.data.repository.CloudFolderBatchTaskRepository
import com.example.localmovielibrarz.data.repository.CloudFolderBatchTaskRunner
import com.example.localmovielibrarz.data.repository.CloudStrmRecordRepository
import com.example.localmovielibrarz.data.repository.CloudVideoTaskRepository
import com.example.localmovielibrarz.data.repository.CloudVideoTaskRunner
import com.example.localmovielibrarz.data.repository.MovieRepository
import com.example.localmovielibrarz.data.repository.MovieMetadataRefreshRunner
import com.example.localmovielibrarz.data.repository.MovieScrapeTaskRunner
import com.example.localmovielibrarz.data.repository.ScrapeTaskSummary
import com.example.localmovielibrarz.data.repository.StrmScrapeRepository
import com.example.localmovielibrarz.playback.lut.LutParamKey
import com.example.localmovielibrarz.playback.lut.LutParams
import com.example.localmovielibrarz.playback.lut.LutPreset
import com.example.localmovielibrarz.playback.lut.LutSettings
import com.example.localmovielibrarz.scraper.CustomJsonScrapeConfig
import com.example.localmovielibrarz.scraper.CustomJsonPathCandidate
import com.example.localmovielibrarz.scraper.NumberPrefixRewriteRule
import com.example.localmovielibrarz.scraper.ScrapeChainMode
import com.example.localmovielibrarz.scraper.ScrapeProxyMode
import com.example.localmovielibrarz.scraper.ScrapeSource
import com.example.localmovielibrarz.scraper.ScrapedMovieInfo
import com.example.localmovielibrarz.util.NumberRecognitionRules
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsViewModel(
    private val repository: AppSettingsRepository,
    private val movieRepository: MovieRepository,
    private val cloudStrmRecordRepository: CloudStrmRecordRepository,
    private val scrapeRepository: StrmScrapeRepository,
    private val appUpdateRepository: AppUpdateRepository,
    private val cloud115QrLoginClient: Cloud115QrLoginClient,
    private val cloudFolderBatchTaskRepository: CloudFolderBatchTaskRepository,
    private val cloudFolderBatchTaskRunner: CloudFolderBatchTaskRunner,
    private val cloudVideoTaskRepository: CloudVideoTaskRepository,
    private val cloudVideoTaskRunner: CloudVideoTaskRunner,
    private val movieScrapeTaskRunner: MovieScrapeTaskRunner,
    private val movieMetadataRefreshRunner: MovieMetadataRefreshRunner
) : ViewModel() {
    private val _uiState = MutableStateFlow(loadState())
    val uiState: StateFlow<SettingsUiState> = _uiState
    private var qrLoginJob: Job? = null
    private var appUpdateJob: Job? = null
    private var customJsonTestJob: Job? = null
    private var manualScrapeCoordinatorJob: Job? = null

    init {
        refreshScrapeTaskSummary()
        observeCloudFolderBatchTasks()
        observeCloudFolderBatchRunner()
        observeCloudVideoTasks()
        observeCloudVideoTaskRunner()
        observeMovieScrapeTaskRunner()
        observeMetadataRefreshRunner()
    }

    fun refreshSavedCloud115Accounts() {
        viewModelScope.launch {
            runCatching { cloud115QrLoginClient.listSavedAccounts() }
                .onSuccess { accounts ->
                    val currentCookies = repository.getCookies()
                    val selected = accounts.firstOrNull { it.cookies == currentCookies }
                    _uiState.update {
                        it.copy(
                            savedCloud115Accounts = accounts,
                            selectedCloud115AccountFileName = selected?.fileName ?: it.selectedCloud115AccountFileName
                        )
                    }
                }
        }
    }

    fun applySavedCloud115Account(account: SavedCloud115Account) {
        viewModelScope.launch {
            runCatching { cloud115QrLoginClient.applySavedAccount(account.fileName) }
                .onSuccess { selected ->
                    _uiState.value = loadState().copy(
                        savedCloud115Accounts = cloud115QrLoginClient.listSavedAccounts(),
                        selectedCloud115AccountFileName = selected.fileName,
                        savedMessage = "已切换 115 账号：${selected.displayName}"
                    )
                }
                .onFailure { error ->
                    _uiState.update { it.copy(savedMessage = error.message ?: "切换 115 账号失败") }
                }
        }
    }

    fun deleteSavedCloud115Account(account: SavedCloud115Account) {
        viewModelScope.launch {
            runCatching { cloud115QrLoginClient.deleteSavedAccount(account.fileName) }
                .onSuccess { deleted ->
                    val accounts = cloud115QrLoginClient.listSavedAccounts()
                    val currentCookies = repository.getCookies()
                    val selected = accounts.firstOrNull { it.cookies == currentCookies }
                    _uiState.update {
                        it.copy(
                            savedCloud115Accounts = accounts,
                            selectedCloud115AccountFileName = selected?.fileName,
                            savedMessage = if (deleted) {
                                "已删除 115 账号：${account.displayName}"
                            } else {
                                "账号文件不存在，已刷新列表"
                            }
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update { it.copy(savedMessage = error.message ?: "删除 115 账号失败") }
                }
        }
    }

    fun selectCloud115LoginApp(app: Cloud115LoginApp) {
        repository.saveCloud115LoginApp(app.app)
        _uiState.update {
            it.copy(
                selectedCloud115LoginApp = app,
                cloud115QrStatusText = "已选择：${app.description}",
                savedMessage = null
            )
        }
    }

    fun startCloud115QrLogin() {
        qrLoginJob?.cancel()
        val loginApp = _uiState.value.selectedCloud115LoginApp
        _uiState.update {
            it.copy(
                isCloud115QrLoginActive = true,
                cloud115QrToken = null,
                cloud115QrStatusText = "正在获取 115 登录二维码...",
                cloud115QrSavedFile = null,
                savedMessage = null
            )
        }
        qrLoginJob = viewModelScope.launch {
            runCatching {
                val token = cloud115QrLoginClient.requestToken()
                _uiState.update {
                    it.copy(
                        cloud115QrToken = token,
                        cloud115QrStatusText = "请使用 115 App 扫码登录：${loginApp.description}"
                    )
                }
                while (true) {
                    delay(QR_LOGIN_POLL_INTERVAL_MS)
                    when (cloud115QrLoginClient.checkStatus(token)) {
                        Cloud115QrLoginStatus.Waiting -> {
                            _uiState.update { it.copy(cloud115QrStatusText = "等待扫码...") }
                        }

                        Cloud115QrLoginStatus.Scanned -> {
                            _uiState.update { it.copy(cloud115QrStatusText = "已扫码，请在手机上确认登录") }
                        }

                        Cloud115QrLoginStatus.Confirmed -> {
                            val result = cloud115QrLoginClient.login(token, loginApp)
                            _uiState.value = loadState().copy(
                                savedCloud115Accounts = cloud115QrLoginClient.listSavedAccounts(),
                                selectedCloud115AccountFileName = result.fileName,
                                isCloud115QrLoginActive = false,
                                cloud115QrToken = null,
                                cloud115QrStatusText = "115 登录成功，Cookie 文件已保存：${result.fileName}",
                                cloud115QrSavedFile = result.filePath,
                                savedMessage = "115 登录成功，已保存 ${result.fileName}"
                            )
                            return@launch
                        }

                        Cloud115QrLoginStatus.Expired -> {
                            _uiState.update {
                                it.copy(
                                    isCloud115QrLoginActive = false,
                                    cloud115QrStatusText = "二维码已过期，请重新获取"
                                )
                            }
                            return@launch
                        }

                        Cloud115QrLoginStatus.Canceled -> {
                            _uiState.update {
                                it.copy(
                                    isCloud115QrLoginActive = false,
                                    cloud115QrStatusText = "扫码登录已取消"
                                )
                            }
                            return@launch
                        }
                    }
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isCloud115QrLoginActive = false,
                        cloud115QrStatusText = error.message ?: "115 二维码登录失败",
                        savedMessage = error.message ?: "115 二维码登录失败"
                    )
                }
            }
        }
    }

    fun cancelCloud115QrLogin() {
        qrLoginJob?.cancel()
        qrLoginJob = null
        _uiState.update {
            it.copy(
                isCloud115QrLoginActive = false,
                cloud115QrToken = null,
                cloud115QrStatusText = "已取消 115 二维码登录"
            )
        }
    }

    fun updateBaseUrl(value: String) {
        _uiState.update { it.copy(strmBaseUrl = value, savedMessage = null) }
    }

    fun updateManifestUrl(value: String) {
        _uiState.update {
            it.copy(
                updateManifestUrl = value.take(500),
                updateMessage = "",
                savedMessage = null
            )
        }
    }

    fun updateProxyBaseUrl(value: String) {
        _uiState.update {
            it.copy(
                updateProxyBaseUrl = value.take(300),
                updateMessage = "",
                savedMessage = null
            )
        }
    }

    fun updateUseUpdateProxyEnabled(enabled: Boolean) {
        repository.saveUpdateProxyEnabled(enabled)
        _uiState.update {
            it.copy(
                useUpdateProxyEnabled = enabled,
                updateMessage = "",
                savedMessage = if (enabled) "已开启代理更新" else "已关闭代理更新"
            )
        }
    }

    fun updateAutoCheckUpdateOnStartupEnabled(enabled: Boolean) {
        repository.saveUpdateAutoCheckOnStartupEnabled(enabled)
        _uiState.update {
            it.copy(
                autoCheckUpdateOnStartupEnabled = enabled,
                savedMessage = if (enabled) "已开启启动后自动检查更新" else "已关闭启动后自动检查更新"
            )
        }
    }

    fun updateAutoDeleteInstalledUpdateApkEnabled(enabled: Boolean) {
        repository.saveUpdateAutoDeleteInstalledApkEnabled(enabled)
        _uiState.update {
            it.copy(
                autoDeleteInstalledUpdateApkEnabled = enabled,
                savedMessage = if (enabled) "已开启安装后自动删除更新 APK" else "已关闭安装后自动删除更新 APK"
            )
        }
    }

    fun updateScrapeProxyAddress(value: String) {
        _uiState.update { it.copy(scrapeProxyAddress = value.take(300), savedMessage = null) }
    }

    fun updateScrapeProxyMode(mode: ScrapeProxyMode) {
        // The scrape client reads this preference for every new connection. Persist the
        // mode immediately so a scrape started from another page sees the new value.
        repository.saveScrapeProxyMode(mode)
        _uiState.update { it.copy(scrapeProxyMode = mode, savedMessage = null) }
    }

    fun checkForAppUpdate() {
        if (appUpdateJob?.isActive == true) return
        val url = _uiState.value.updateManifestUrl.trim()
        repository.saveUpdateManifestUrl(url)
        repository.saveUpdateProxyBaseUrl(_uiState.value.updateProxyBaseUrl)
        repository.saveUpdateProxyEnabled(_uiState.value.useUpdateProxyEnabled)
        if (url.isBlank()) {
            _uiState.update { it.copy(updateMessage = "请先填写版本信息地址") }
            return
        }
        appUpdateJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isCheckingUpdate = true,
                    latestAppUpdate = null,
                    hasAppUpdate = false,
                    updateMessage = "正在检查更新...",
                    savedMessage = null
                )
            }
            runCatching { appUpdateRepository.checkForUpdate(url) }
                .onSuccess { result ->
                    _uiState.update {
                        it.copy(
                            isCheckingUpdate = false,
                            latestAppUpdate = result.latest,
                            hasAppUpdate = result.hasUpdate,
                            downloadedUpdateReady = appUpdateRepository.hasDownloadedApk(),
                            downloadedUpdateApkPath = appUpdateRepository.latestDownloadedApkPath(),
                            updateMessage = if (result.hasUpdate) {
                                "发现新版本 ${result.latest.versionName}"
                            } else {
                                "当前已是最新版本"
                            }
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isCheckingUpdate = false,
                            updateMessage = error.message ?: "检查更新失败"
                        )
                    }
                }
        }
    }

    fun downloadAndInstallUpdate() {
        if (appUpdateJob?.isActive == true) return
        val update = _uiState.value.latestAppUpdate
        if (update == null || !_uiState.value.hasAppUpdate) {
            _uiState.update { it.copy(updateMessage = "请先检查更新") }
            return
        }
        appUpdateJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isDownloadingUpdate = true,
                    updateDownloadProgress = 0,
                    updateMessage = "正在下载更新...",
                    savedMessage = null
                )
            }
            runCatching {
                appUpdateRepository.downloadAndInstall(update) { progress ->
                    _uiState.update {
                        it.copy(
                            updateDownloadProgress = progress,
                            updateMessage = "正在下载更新 $progress%"
                        )
                    }
                }
            }.onSuccess {
                _uiState.update {
                    it.copy(
                        isDownloadingUpdate = false,
                        updateDownloadProgress = 100,
                        downloadedUpdateReady = true,
                        downloadedUpdateApkPath = appUpdateRepository.latestDownloadedApkPath(),
                        updateMessage = "APK 已下载，已打开安装器"
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isDownloadingUpdate = false,
                        downloadedUpdateReady = appUpdateRepository.hasDownloadedApk(),
                        downloadedUpdateApkPath = appUpdateRepository.latestDownloadedApkPath(),
                        updateMessage = error.message ?: "下载更新失败"
                    )
                }
            }
        }
    }

    fun installDownloadedUpdate() {
        if (appUpdateJob?.isActive == true) return
        appUpdateJob = viewModelScope.launch {
            runCatching { appUpdateRepository.installDownloadedApk() }
                .onSuccess { installed ->
                    _uiState.update {
                        it.copy(
                            downloadedUpdateReady = installed || appUpdateRepository.hasDownloadedApk(),
                            downloadedUpdateApkPath = appUpdateRepository.latestDownloadedApkPath(),
                            updateMessage = if (installed) "已打开安装器" else "没有已下载的 APK"
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            downloadedUpdateReady = appUpdateRepository.hasDownloadedApk(),
                            downloadedUpdateApkPath = appUpdateRepository.latestDownloadedApkPath(),
                            updateMessage = error.message ?: "安装 APK 失败"
                        )
                    }
                }
        }
    }

    fun addPriorityScrapeSource(source: ScrapeSource) {
        val current = _uiState.value.priorityScrapeSources
        if (source !in _uiState.value.priorityScrapeSourceOptions || source in current) return
        savePriorityScrapeSources(current + source)
    }

    fun removePriorityScrapeSource(source: ScrapeSource) {
        val current = _uiState.value.priorityScrapeSources
        if (current.size <= 1) {
            _uiState.update { it.copy(savedMessage = "至少保留一个优先级刮削来源") }
            return
        }
        savePriorityScrapeSources(current - source)
    }

    fun movePriorityScrapeSourceUp(source: ScrapeSource) {
        val current = _uiState.value.priorityScrapeSources.toMutableList()
        val index = current.indexOf(source)
        if (index <= 0) return
        current[index] = current[index - 1]
        current[index - 1] = source
        savePriorityScrapeSources(current)
    }

    fun movePriorityScrapeSourceDown(source: ScrapeSource) {
        val current = _uiState.value.priorityScrapeSources.toMutableList()
        val index = current.indexOf(source)
        if (index < 0 || index >= current.lastIndex) return
        current[index] = current[index + 1]
        current[index + 1] = source
        savePriorityScrapeSources(current)
    }

    private fun savePriorityScrapeSources(sources: List<ScrapeSource>) {
        repository.savePriorityScrapeSources(sources)
        _uiState.update {
            it.copy(
                priorityScrapeSources = repository.getPriorityScrapeSources(),
                savedMessage = null
            )
        }
    }

    fun updateImageDownloadRetryCount(value: String) {
        val cleaned = value.filter { it.isDigit() }.take(2)
        _uiState.update { it.copy(imageDownloadRetryCountText = cleaned, savedMessage = null) }
    }

    fun updateScrapeRetryCount(value: String) {
        val cleaned = value.filter { it.isDigit() }.take(2)
        _uiState.update { it.copy(scrapeRetryCountText = cleaned, savedMessage = null) }
    }

    fun updateScrapeConcurrencyLimit(value: String) {
        val cleaned = value.filter { it.isDigit() }.take(1)
        _uiState.update { it.copy(scrapeConcurrencyLimitText = cleaned, savedMessage = null) }
    }

    fun updateNewDmm2SkippedPrefix(value: String) {
        _uiState.update {
            it.copy(
                newDmm2SkippedPrefix = value.uppercase().filter { char -> char.isLetterOrDigit() }.take(12),
                savedMessage = null
            )
        }
    }

    fun addDmm2SkippedPrefix() {
        val prefix = _uiState.value.newDmm2SkippedPrefix.trim()
        if (prefix.isBlank()) {
            _uiState.update { it.copy(savedMessage = "请输入要跳过的番号开头") }
            return
        }
        repository.addDmm2SkippedNumberPrefix(prefix)
        _uiState.value = loadState().copy(savedMessage = "已添加 DMM2 跳过前缀：${prefix.uppercase()}")
    }

    fun removeDmm2SkippedPrefix(prefix: String) {
        repository.removeDmm2SkippedNumberPrefix(prefix)
        _uiState.value = loadState().copy(savedMessage = "已移除 DMM2 跳过前缀")
    }

    fun updateNewNumberPrefixRulePrefix(value: String) {
        _uiState.update {
            it.copy(newNumberPrefixRulePrefix = value.uppercase().filter(Char::isLetter).take(12), savedMessage = null)
        }
    }

    fun updateNewNumberPrefixRuleNumericPrefix(value: String) {
        _uiState.update {
            it.copy(newNumberPrefixRuleNumericPrefix = value.filter(Char::isDigit).take(4), savedMessage = null)
        }
    }

    fun updateNewNumberPrefixRuleSources(sources: Set<ScrapeSource>) {
        _uiState.update {
            it.copy(newNumberPrefixRuleSources = sources - ScrapeSource.Priority, savedMessage = null)
        }
    }

    fun addNumberPrefixRewriteRule() {
        val state = _uiState.value
        val prefix = state.newNumberPrefixRulePrefix
        val numericPrefix = state.newNumberPrefixRuleNumericPrefix
        if (prefix.isBlank() || numericPrefix.isBlank()) {
            _uiState.update { it.copy(savedMessage = "请填写标准前缀和附加数字") }
            return
        }
        if (state.newNumberPrefixRuleSources.isEmpty()) {
            _uiState.update { it.copy(savedMessage = "请至少选择一个刮削源") }
            return
        }
        repository.saveNumberPrefixRewriteRules(
            repository.getNumberPrefixRewriteRules().filterNot { it.prefix == prefix } +
                NumberPrefixRewriteRule(prefix, numericPrefix, state.newNumberPrefixRuleSources)
        )
        _uiState.value = loadState().copy(savedMessage = "已添加数字前缀规则：$prefix → $numericPrefix$prefix")
    }

    fun removeNumberPrefixRewriteRule(prefix: String) {
        repository.saveNumberPrefixRewriteRules(
            repository.getNumberPrefixRewriteRules().filterNot { it.prefix == prefix }
        )
        _uiState.value = loadState().copy(savedMessage = "已移除数字前缀规则")
    }

    fun updateMgstageAmateurPriorityEnabled(enabled: Boolean) {
        repository.saveMgstageAmateurPriorityEnabled(enabled)
        _uiState.update { it.copy(mgstageAmateurPriorityEnabled = enabled, savedMessage = null) }
    }

    /**
     * 切换刮削链模式（桃链优先 ↔ 只用原有链）。
     *
     * 用户能按下这个开关，是本轮 peach 移植的「回退口」：桃链的站点侧行为只有真机能验，
     * 一旦某家解析出错，用户要能立刻退回原链把东西刮出来，而不是等下一个版本。
     * 所以这里保存后立刻回读 `getScrapeChainMode()` 而不是直接把入参写进 state——
     * 存进去的值经过一层 `fromStoredValue` 归一（未知值回落默认），回读才能反映真实生效值。
     */
    fun updateScrapeChainMode(mode: ScrapeChainMode) {
        repository.saveScrapeChainMode(mode)
        val effective = repository.getScrapeChainMode()
        _uiState.update {
            it.copy(
                scrapeChainMode = effective,
                savedMessage = if (effective == mode) {
                    "刮削链已切换为「${effective.label}」"
                } else {
                    "刮削链切换失败，当前仍为「${effective.label}」"
                }
            )
        }
    }

    fun updateNewMgstagePrefix(value: String) {
        _uiState.update {
            it.copy(
                newMgstagePrefix = value.uppercase().filter { char -> char.isLetterOrDigit() }.take(16),
                savedMessage = null
            )
        }
    }

    fun updateNewMgstageNumericPrefix(value: String) {
        _uiState.update {
            it.copy(
                newMgstageNumericPrefix = value.filter(Char::isDigit).take(4),
                savedMessage = null
            )
        }
    }

    fun addCustomMgstagePrefix() {
        val prefix = _uiState.value.newMgstagePrefix.trim()
        val numericPrefix = _uiState.value.newMgstageNumericPrefix.trim()
        if (prefix.isBlank()) {
            _uiState.update { it.copy(savedMessage = "请输入 MGStage 番号开头") }
            return
        }
        if (prefix.none { it.isLetter() }) {
            _uiState.update { it.copy(savedMessage = "MGStage 番号前缀需要包含字母") }
            return
        }
        if (prefix.firstOrNull()?.isDigit() == true) {
            _uiState.update { it.copy(savedMessage = "请填写不带附加数字的标准前缀，例如 MIUM 或 SCUTE") }
            return
        }
        repository.addCustomMgstageNumberPrefix(prefix, numericPrefix)
        _uiState.value = loadState().copy(
            savedMessage = "已添加 MGStage 规则：${prefix.uppercase()} → ${numericPrefix.ifBlank { "无需附加" }}"
        )
    }

    fun removeCustomMgstagePrefix(prefix: String) {
        repository.removeCustomMgstageNumberPrefix(prefix)
        _uiState.value = loadState().copy(savedMessage = "已移除 MGStage 自定义前缀")
    }

    fun saveNumberRecognitionIgnoredSuffixes(text: String) {
        val parsed = parseCsvList(text)
        repository.saveCachedNumberRecognitionIgnoredSuffixes(parsed)
        _uiState.update {
            it.copy(
                numberRecognitionIgnoredSuffixes = parsed.toList().sorted(),
                savedMessage = "忽略后缀已保存"
            )
        }
    }

    fun saveNumberRecognitionPartMarkers(text: String) {
        val parsed = parseCsvList(text)
        repository.saveCachedNumberRecognitionPartMarkers(parsed)
        _uiState.update {
            it.copy(
                numberRecognitionPartMarkers = parsed.toList().sorted(),
                savedMessage = "分段标记已保存"
            )
        }
    }

    fun saveNumberRecognitionAttachedLetterPrefixes(text: String) {
        val parsed = parseCsvList(text)
        repository.saveCachedNumberRecognitionAttachedLetterPrefixes(parsed)
        _uiState.update {
            it.copy(
                numberRecognitionAttachedLetterPrefixes = parsed.toList().sorted(),
                savedMessage = "连写字母分段前缀已保存"
            )
        }
    }

    /** 保存「番号前缀 → 搜索前缀」映射（归一化，data 层已实现持久化）。 */
    fun saveNumberRecognitionNumericPrefixAliases(aliases: Map<String, String>) {
        val normalized = aliases.mapNotNull { (prefix, searchPrefix) ->
            val p = prefix.trim().uppercase()
            if (p.isBlank()) return@mapNotNull null
            p to searchPrefix.trim()
        }.toMap()
        repository.saveCustomMgstageSearchPrefixAliases(normalized)
        _uiState.update {
            it.copy(
                numberRecognitionNumericPrefixAliases = repository.getMergedMgstageSearchPrefixAliases().toSortedMap(),
                savedMessage = "番号映射已保存"
            )
        }
    }

    /** 逗号/空格分隔文本 → 归一化集合（去重、去空）。 */
    private fun parseCsvList(text: String): Set<String> =
        text.split(',', '\n', '，')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toSet()

    fun updateLibraryNoMediaEnabled(enabled: Boolean) {
        repository.saveLibraryNoMediaEnabled(enabled)
        _uiState.update {
            it.copy(
                libraryNoMediaEnabled = enabled,
                savedMessage = if (enabled) {
                    "已开启 .nomedia 屏蔽图片"
                } else {
                    "已关闭 .nomedia 屏蔽图片"
                }
            )
        }
    }

    /** 刷新光鸭云盘登录状态（登录/登出后由设置页实时调用）。 */
    fun refreshGuangyaLoggedIn() {
        _uiState.update { it.copy(guangyaLoggedIn = repository.isGuangyaLoggedIn()) }
    }

    fun updateCloudAddButtonMessageEnabled(enabled: Boolean) {
        repository.saveCloudAddButtonMessageEnabled(enabled)
        _uiState.update { it.copy(cloudAddButtonMessageEnabled = enabled, savedMessage = null) }
    }

    fun updateCloudDeleteEnabled(enabled: Boolean) {
        repository.saveCloudDeleteEnabled(enabled)
        _uiState.update { it.copy(cloudDeleteEnabled = enabled, savedMessage = null) }
    }

    fun updateNewExcludedVideoName(value: String) {
        _uiState.update { it.copy(newExcludedVideoName = value, savedMessage = null) }
    }

    fun addExcludedVideoName() {
        val name = _uiState.value.newExcludedVideoName.trim()
        if (name.isBlank()) {
            _uiState.update { it.copy(savedMessage = "请输入要排除的视频文件名") }
            return
        }
        repository.addCloudExcludedVideoName(name)
        _uiState.value = loadState().copy(savedMessage = "已添加排除视频：$name")
    }

    fun removeExcludedVideoName(name: String) {
        repository.removeCloudExcludedVideoName(name)
        _uiState.value = loadState().copy(savedMessage = "已移除排除视频")
    }

    fun updateCloudScrapeSkipBelowSizeMb(value: String) {
        val cleaned = value.filter { it.isDigit() }.take(6)
        _uiState.update { it.copy(cloudScrapeSkipBelowSizeMbText = cleaned, savedMessage = null) }
    }

    fun updateExternalSubtitleFontSizeSp(value: Int) {
        repository.saveExternalSubtitleFontSizeSp(value)
        _uiState.update {
            it.copy(
                externalSubtitleFontSizeSp = repository.getExternalSubtitleFontSizeSp(),
                savedMessage = "外挂字幕字号已调整"
            )
        }
    }

    fun updatePlayerSeekBackSeconds(value: Int) {
        repository.savePlayerSeekBackSeconds(value)
        _uiState.update { it.copy(playerSeekBackSeconds = repository.getPlayerSeekBackSeconds(), savedMessage = "后退时间已调整") }
    }

    fun updatePlayerSeekForwardSeconds(value: Int) {
        repository.savePlayerSeekForwardSeconds(value)
        _uiState.update { it.copy(playerSeekForwardSeconds = repository.getPlayerSeekForwardSeconds(), savedMessage = "快进时间已调整") }
    }

    /**
     * 默认播放内核。
     *
     * 这里只存偏好，不做能力判断：mpv 的 so 是否可用由播放页在打开媒体时探测，
     * 不可用会显式降级回 exo 并在播放页提示，而不是在设置页把开关禁掉——
     * 设置页禁用会让「为什么没有 mpv」变得不可解释。
     */
    fun updateDefaultPlaybackEngine(isMpv: Boolean) {
        repository.saveDefaultPlaybackEngine(
            if (isMpv) AppSettingsRepository.PLAYER_ENGINE_MPV else AppSettingsRepository.PLAYER_ENGINE_EXO
        )
        _uiState.update {
            it.copy(
                defaultPlaybackEngineIsMpv = repository.getDefaultPlaybackEngine() == AppSettingsRepository.PLAYER_ENGINE_MPV,
                savedMessage = "默认播放内核已切换"
            )
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 实时调色（LUT）
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 保存 LUT 设置的唯一出口。
     *
     * 所有 setter 都走这里，是为了保证「写盘」与「回读」永远成对出现：UI 上显示的
     * 永远是 `repository.getLutSettings()` 的结果（含夹取与缺键回落），而不是
     * 调用方手里的那份期望值。曾经有设置页直接 `copy(...)` 更新 UI 状态、
     * 结果滑杆停在了磁盘里不存在的值上。
     */
    private fun commitLutSettings(transform: (LutSettings) -> LutSettings) {
        val next = transform(repository.getLutSettings())
        repository.saveLutSettings(next)
        _uiState.update { it.copy(lutSettings = repository.getLutSettings()) }
    }

    /**
     * 开关实时调色。
     *
     * 打开时若 7 个参数全是 0，自动套用「标准」预设：全 0 是恒等变换，开了等于没开，
     * 用户会以为功能坏了。这是他按下开关时唯一合理的意图推断，且参数仍可随时改。
     */
    fun updateLutEnabled(enabled: Boolean) {
        commitLutSettings { current ->
            val params =
                if (enabled && current.params.isNeutral()) LutPreset.Standard.params
                else current.params
            current.copy(enabled = enabled, params = params)
        }
        _uiState.update { it.copy(savedMessage = if (enabled) "实时调色已开启" else "实时调色已关闭") }
    }

    /** 改单个色彩参数（滑杆回调）。 */
    fun updateLutParam(key: LutParamKey, value: Int) {
        commitLutSettings { it.copy(params = it.params.set(key, value)) }
    }

    /** 改强度（0..100）。 */
    fun updateLutStrength(value: Int) {
        commitLutSettings { it.withStrength(value) }
    }

    /** 改 LUT 尺寸档（17 / 33 / 65）。 */
    fun updateLutSize(size: Int) {
        commitLutSettings { it.withLutSize(size) }
    }

    /**
     * 套用预设。
     *
     * 套用「关闭调色」预设时同时把开关置 false——否则会出现「预设写着关闭调色、
     * 开关却还亮着」的矛盾状态（参数全 0 时下发的是恒等 LUT，画面确实没变化，
     * 但开关的语义已经错了）。
     */
    fun applyLutPreset(preset: LutPreset) {
        commitLutSettings { current ->
            if (preset == LutPreset.Neutral) {
                current.copy(enabled = false, params = LutParams.NEUTRAL)
            } else {
                current.copy(enabled = true, params = preset.params)
            }
        }
        _uiState.update {
            it.copy(
                savedMessage =
                    if (preset == LutPreset.Neutral) "已关闭实时调色"
                    else "已套用「${preset.label}」预设"
            )
        }
    }

    fun updateExternalSubtitleBottomPaddingPercent(value: Int) {
        repository.saveExternalSubtitleBottomPaddingPercent(value)
        _uiState.update {
            it.copy(
                externalSubtitleBottomPaddingPercent = repository.getExternalSubtitleBottomPaddingPercent(),
                savedMessage = "外挂字幕位置已调整"
            )
        }
    }

    fun updateExternalSubtitleBackgroundAlphaPercent(value: Int) {
        repository.saveExternalSubtitleBackgroundAlphaPercent(value)
        _uiState.update {
            it.copy(
                externalSubtitleBackgroundAlphaPercent = repository.getExternalSubtitleBackgroundAlphaPercent(),
                savedMessage = "外挂字幕背景已调整"
            )
        }
    }

    fun updatePlayerProgressBarWidthDp(value: Int) {
        repository.savePlayerProgressBarWidthDp(value)
        _uiState.update { it.copy(playerProgressBarWidthDp = repository.getPlayerProgressBarWidthDp(), savedMessage = "进度条宽度已调整") }
    }

    fun updatePlayerProgressBarColor(value: Int) {
        repository.savePlayerProgressBarColor(value)
        _uiState.update { it.copy(playerProgressBarColor = repository.getPlayerProgressBarColor(), savedMessage = "进度条颜色已调整") }
    }

    fun updatePlayerProgressBarAlphaPercent(value: Int) {
        repository.savePlayerProgressBarAlphaPercent(value)
        _uiState.update { it.copy(playerProgressBarAlphaPercent = repository.getPlayerProgressBarAlphaPercent(), savedMessage = "进度条透明度已调整") }
    }

    fun updateLightThemeEnabled(enabled: Boolean) {
        repository.saveLightThemeEnabled(enabled)
        _uiState.update { it.copy(lightThemeEnabled = enabled, savedMessage = null) }
    }

    fun updateStartupAnimationEnabled(enabled: Boolean) {
        repository.saveStartupAnimationEnabled(enabled)
        _uiState.update { it.copy(startupAnimationEnabled = enabled, savedMessage = null) }
    }

    fun updateStartupAnimationImageUri(uri: String) {
        repository.saveStartupAnimationImageUri(uri)
        _uiState.update { it.copy(startupAnimationImageUri = uri, savedMessage = "已选择开场动画图片") }
    }

    fun clearStartupAnimationImage() {
        repository.clearStartupAnimationImageUri()
        _uiState.update { it.copy(startupAnimationImageUri = "", savedMessage = "已恢复默认开场画面") }
    }

    fun save() {
        val state = _uiState.value
        repository.saveCookies(state.cookies)
        repository.saveUpdateManifestUrl(state.updateManifestUrl)
        repository.saveUpdateProxyBaseUrl(state.updateProxyBaseUrl)
        repository.saveUpdateProxyEnabled(state.useUpdateProxyEnabled)
        repository.saveUpdateAutoCheckOnStartupEnabled(state.autoCheckUpdateOnStartupEnabled)
        repository.saveUpdateAutoDeleteInstalledApkEnabled(state.autoDeleteInstalledUpdateApkEnabled)
        repository.saveStrmBaseUrl(state.strmBaseUrl)
        repository.saveDefaultScrapeSource(state.defaultScrapeSource)
        repository.saveImageDownloadRetryCount(state.imageDownloadRetryCountText.toIntOrNull() ?: AppSettingsRepository.DEFAULT_IMAGE_DOWNLOAD_RETRY_COUNT)
        repository.saveScrapeRetryCount(state.scrapeRetryCountText.toIntOrNull() ?: AppSettingsRepository.DEFAULT_SCRAPE_RETRY_COUNT)
        repository.saveScrapeConcurrencyLimit(state.scrapeConcurrencyLimitText.toIntOrNull() ?: AppSettingsRepository.DEFAULT_SCRAPE_CONCURRENCY_LIMIT)
        repository.saveScrapeProxyAddress(state.scrapeProxyAddress)
        repository.saveScrapeProxyMode(state.scrapeProxyMode)
        repository.saveDmm2SkippedNumberPrefixes(state.dmm2SkippedPrefixes.toSet())
        repository.saveCustomMgstagePrefixNumberMappings(state.mgstageCustomPrefixes)
        repository.saveLibraryNoMediaEnabled(state.libraryNoMediaEnabled)
        repository.saveCloudAddButtonMessageEnabled(state.cloudAddButtonMessageEnabled)
        repository.saveCloudDeleteEnabled(state.cloudDeleteEnabled)
        repository.saveCloudExcludedVideoNames(state.cloudExcludedVideoNames.toSet())
        repository.saveCloudScrapeSkipBelowSizeMb(state.cloudScrapeSkipBelowSizeMbText.toIntOrNull() ?: AppSettingsRepository.DEFAULT_CLOUD_SCRAPE_SKIP_BELOW_SIZE_MB)
        repository.saveCustomJsonScrapeConfigs(state.customJsonScrapeConfigs, state.selectedCustomJsonScrapeConfigIndex)
        _uiState.value = loadState().copy(savedMessage = "设置已保存")
    }

    fun updateCustomJsonScrapeConfig(config: CustomJsonScrapeConfig) {
        _uiState.update { state ->
            val configs = state.customJsonScrapeConfigs.toMutableList()
            val selected = state.selectedCustomJsonScrapeConfigIndex.coerceIn(0, (configs.size - 1).coerceAtLeast(0))
            if (configs.isEmpty()) configs += config else configs[selected] = config
            state.copy(
                customJsonScrapeConfig = config,
                customJsonScrapeConfigs = configs,
                savedMessage = null
            )
        }
    }

    fun selectCustomJsonScrapeConfig(index: Int) {
        _uiState.update { state ->
            val selected = index.coerceIn(0, (state.customJsonScrapeConfigs.size - 1).coerceAtLeast(0))
            val config = state.customJsonScrapeConfigs.getOrElse(selected) { state.customJsonScrapeConfig }
            state.copy(
                selectedCustomJsonScrapeConfigIndex = selected,
                customJsonScrapeConfig = config,
                customJsonTestResult = null,
                customJsonPathCandidates = emptyList(),
                savedMessage = null
            )
        }
    }

    fun addCustomJsonScrapeConfig() {
        _uiState.update { state ->
            val configs = state.customJsonScrapeConfigs + CustomJsonScrapeConfig(name = "自定义 JSON ${state.customJsonScrapeConfigs.size + 1}")
            state.copy(
                customJsonScrapeConfigs = configs,
                selectedCustomJsonScrapeConfigIndex = configs.lastIndex,
                customJsonScrapeConfig = configs.last(),
                customJsonTestResult = null,
                customJsonPathCandidates = emptyList(),
                savedMessage = null,
            )
        }
    }

    fun duplicateCustomJsonScrapeConfig() {
        _uiState.update { state ->
            val copy = state.customJsonScrapeConfig.copy(name = state.customJsonScrapeConfig.name.ifBlank { "自定义 JSON" } + " 副本")
            val configs = state.customJsonScrapeConfigs + copy
            state.copy(
                customJsonScrapeConfigs = configs,
                selectedCustomJsonScrapeConfigIndex = configs.lastIndex,
                customJsonScrapeConfig = copy,
                customJsonTestResult = null,
                customJsonPathCandidates = emptyList(),
                savedMessage = null,
            )
        }
    }

    fun deleteCustomJsonScrapeConfig() {
        _uiState.update { state ->
            if (state.customJsonScrapeConfigs.size <= 1) {
                val reset = CustomJsonScrapeConfig(name = "自定义 JSON")
                state.copy(
                    customJsonScrapeConfigs = listOf(reset),
                    selectedCustomJsonScrapeConfigIndex = 0,
                    customJsonScrapeConfig = reset,
                    customJsonTestResult = null,
                    customJsonPathCandidates = emptyList(),
                    savedMessage = null,
                )
            } else {
                val configs = state.customJsonScrapeConfigs.toMutableList().also { it.removeAt(state.selectedCustomJsonScrapeConfigIndex.coerceIn(0, it.lastIndex)) }
                val selected = state.selectedCustomJsonScrapeConfigIndex.coerceAtMost(configs.lastIndex)
                state.copy(
                    customJsonScrapeConfigs = configs,
                    selectedCustomJsonScrapeConfigIndex = selected,
                    customJsonScrapeConfig = configs[selected],
                    customJsonTestResult = null,
                    customJsonPathCandidates = emptyList(),
                    savedMessage = null,
                )
            }
        }
    }

    fun dismissCustomJsonTestResult() {
        _uiState.update { it.copy(customJsonTestResult = null) }
    }

    fun testCustomJsonScrapeConfig() {
        val config = _uiState.value.customJsonScrapeConfig
        customJsonTestJob?.cancel()
        customJsonTestJob = viewModelScope.launch {
            _uiState.update { it.copy(isTestingCustomJsonScrape = true, savedMessage = null) }
            repository.saveCustomJsonScrapeConfigs(_uiState.value.customJsonScrapeConfigs, _uiState.value.selectedCustomJsonScrapeConfigIndex)
            runCatching {
                val sampleNumber = config.sampleNumber.ifBlank { "SSIS-115" }
                val info = scrapeRepository.testCustomJsonScrape(sampleNumber)
                val candidates = scrapeRepository.previewCustomJsonPathCandidates(sampleNumber)
                info to candidates
            }
                .onSuccess { (info, candidates) ->
                    _uiState.update {
                        it.copy(
                            isTestingCustomJsonScrape = false,
                            customJsonTestResult = info,
                            customJsonPathCandidates = candidates,
                            savedMessage = "自定义 JSON 测试成功：" + info.title.ifBlank { info.number }
                        )
                    }
                }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    _uiState.update {
                        it.copy(
                            isTestingCustomJsonScrape = false,
                            customJsonTestResult = null,
                            customJsonPathCandidates = emptyList(),
                            savedMessage = error.message ?: "自定义 JSON 测试失败"
                        )
                    }
                }
        }
    }

    fun scanLibrary(uri: Uri) {
        repository.saveLibraryRootUri(uri)
        _uiState.update {
            it.copy(isScanning = true, scanningCount = 0, scanningCurrentPath = "", savedMessage = null)
        }
        viewModelScope.launch {
            runCatching {
                // F4 优化：扫描进度回调（每 200ms 节流更新 UI，避免过密重组拖慢扫描）
                var lastUiUpdateMs = 0L
                movieRepository.scanLibrary(uri) { scanned, path ->
                    val now = System.currentTimeMillis()
                    if (now - lastUiUpdateMs >= 200L) {
                        lastUiUpdateMs = now
                        _uiState.update {
                            it.copy(scanningCount = scanned, scanningCurrentPath = path)
                        }
                    }
                }
            }
                .onSuccess { count ->
                    _uiState.value = loadState().copy(savedMessage = "影片库扫描完成：$count 部影片")
                }
                .onFailure { error ->
                    _uiState.value = loadState().copy(savedMessage = error.message ?: "影片库扫描失败")
                }
        }
    }

    fun refreshScrapeTaskSummary() {
        viewModelScope.launch {
            val summary = movieRepository.scrapeTaskSummary()
            val movies = movieRepository.getScrapeIssueMovies()
            _uiState.update { it.copy(scrapeTaskSummary = summary, scrapeIssueMovies = movies) }
        }
    }

    private fun observeCloudFolderBatchTasks() {
        viewModelScope.launch {
            cloudFolderBatchTaskRepository.observeTasks().collect { tasks ->
                _uiState.update { it.copy(cloudFolderBatchTasks = tasks) }
            }
        }
    }

    private fun observeCloudFolderBatchRunner() {
        viewModelScope.launch {
            cloudFolderBatchTaskRunner.isRunning.collect { running ->
                _uiState.update {
                    it.copy(
                        isCloudFolderBatchRunning = running,
                        isScraping = running || it.isManualScrapeRunning || it.isCloudVideoTaskRunning,
                        cloudFolderBatchTaskMessage = if (running) {
                            "正在执行网盘文件夹任务"
                        } else {
                            it.cloudFolderBatchTaskMessage
                        }
                    )
                }
            }
        }
    }

    private fun observeCloudVideoTasks() {
        viewModelScope.launch {
            cloudVideoTaskRepository.observeTasks().collect { tasks ->
                _uiState.update { it.copy(cloudVideoTasks = tasks) }
            }
        }
    }

    private fun observeCloudVideoTaskRunner() {
        viewModelScope.launch {
            cloudVideoTaskRunner.isRunning.collect { running ->
                _uiState.update {
                    it.copy(
                        isCloudVideoTaskRunning = running,
                        isScraping = running || it.isManualScrapeRunning || it.isCloudFolderBatchRunning,
                        scrapeTaskMessage = if (running) {
                            "正在执行持久化单视频任务"
                        } else {
                            it.scrapeTaskMessage
                        }
                    )
                }
            }
        }
    }

    private fun observeMovieScrapeTaskRunner() {
        viewModelScope.launch {
            movieScrapeTaskRunner.state.collect { runnerState ->
                val summary = if (runnerState.isRunning) {
                    _uiState.value.scrapeTaskSummary
                } else {
                    movieRepository.scrapeTaskSummary()
                }
                val issues = if (runnerState.isRunning) {
                    _uiState.value.scrapeIssueMovies
                } else {
                    movieRepository.getScrapeIssueMovies()
                }
                _uiState.update {
                    it.copy(
                        isManualScrapeRunning = runnerState.isRunning,
                        isScraping = runnerState.isRunning || it.isCloudVideoTaskRunning || it.isCloudFolderBatchRunning,
                        scrapeTaskSummary = summary,
                        scrapeIssueMovies = issues,
                        scrapeTaskMessage = runnerState.message.ifBlank { it.scrapeTaskMessage }
                    )
                }
            }
        }
    }

    /**
     * 观察元数据补全任务。
     *
     * 与批量刮削分开观察而不是复用 `scrapeTaskMessage`：补全改的是详情页字段，
     * 不影响「哪些影片待刮削」的统计，混进刮削横幅会让用户以为刮削队列变了。
     * 因此只更新自己的 `metadataRefreshMessage`，不动 `scrapeTaskSummary`。
     */
    private fun observeMetadataRefreshRunner() {
        viewModelScope.launch {
            movieMetadataRefreshRunner.state.collect { runnerState ->
                _uiState.update {
                    it.copy(
                        isMetadataRefreshing = runnerState.isRunning,
                        metadataRefreshMessage = runnerState.message,
                        metadataRefreshProgress = if (runnerState.total > 0) {
                            runnerState.current * 100 / runnerState.total
                        } else {
                            0
                        }
                    )
                }
            }
        }
    }

    /**
     * 手动发起一次元数据补全。
     *
     * 为什么必须手动触发而不是冷启动自动跑：补全会对一批影片逐个请求刮削源，
     * 冷启动时网络/登录态都还没就绪，自动跑既容易整批失败，也会让用户莫名其妙
     * 看到流量消耗。按钮是明确的"我现在要补"的意图表达。
     */
    fun startMetadataRefresh() {
        if (_uiState.value.isMetadataRefreshing) return
        _uiState.update {
            it.copy(metadataRefreshMessage = "正在扫描元数据缺口…", metadataRefreshProgress = 0)
        }
        movieMetadataRefreshRunner.start(viewModelScope)
    }

    /** 停止补全：已经写入的字段保留，剩余影片不处理。 */
    fun cancelMetadataRefresh() {
        if (!_uiState.value.isMetadataRefreshing) return
        movieMetadataRefreshRunner.cancel()
    }

    fun startCloudFolderBatchTasks() {
        val unfinished = _uiState.value.cloudFolderBatchTasks.count { it.status != "Completed" }
        if (unfinished <= 0) {
            _uiState.update {
                it.copy(
                    cloudFolderBatchTaskMessage = "没有待执行的网盘文件夹任务",
                    savedMessage = "没有待执行的网盘文件夹任务"
                )
            }
            return
        }
        cloudFolderBatchTaskRunner.start()
        _uiState.update {
            it.copy(
                cloudFolderBatchTaskMessage = "正在启动网盘文件夹任务...",
                savedMessage = null
            )
        }
    }

    fun stopCloudFolderBatchTasks() {
        cloudFolderBatchTaskRunner.stop()
        _uiState.update {
            it.copy(
                cloudFolderBatchTaskMessage = "正在暂停网盘文件夹任务...",
                savedMessage = "已请求暂停网盘文件夹任务"
            )
        }
    }

    fun cancelCloudFolderBatchTasks() {
        cloudFolderBatchTaskRunner.cancel()
        viewModelScope.launch {
            val count = cloudFolderBatchTaskRepository.clearUnfinishedTasks()
            _uiState.update {
                it.copy(
                    isCloudFolderBatchRunning = false,
                    isScraping = it.isManualScrapeRunning,
                    cloudFolderBatchTaskMessage = if (count > 0) {
                        "已取消 $count 个网盘文件夹任务"
                    } else {
                        "没有可取消的网盘文件夹任务"
                    },
                    savedMessage = if (count > 0) {
                        "已取消 $count 个网盘文件夹任务"
                    } else {
                        "没有可取消的网盘文件夹任务"
                    }
                )
            }
        }
    }

    /**
     * 单条文件夹任务的暂停。
     *
     * 与 [stopCloudFolderBatchTasks] 的区别：那个是「整个批量队列停掉」，这个是「只停这一条」。
     * runner 是单协程顺序处理，所以当前正在跑的那条暂停后，runner 会重新起一轮把排在后面的
     * Pending 继续跑——用户点「暂停」只想停这一个文件夹，不希望顺手把整个队列也停了。
     */
    fun pauseCloudFolderBatchTask(taskId: Long) {
        viewModelScope.launch {
            runCatching { cloudFolderBatchTaskRunner.pauseTask(taskId) }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            cloudFolderBatchTaskMessage = "暂停失败：${error.message ?: "未知错误"}",
                            savedMessage = null
                        )
                    }
                }
        }
    }

    /**
     * 单条文件夹任务的继续。
     *
     * 走 runner 的 `resumeTask` 而不是 `start()`：后者会把库里所有 Paused / Failed / Cancelled
     * 一起放回 Pending，等于用户点一个「继续」却把所有手动暂停的任务都唤醒了。
     */
    fun resumeCloudFolderBatchTask(taskId: Long) {
        viewModelScope.launch {
            runCatching { cloudFolderBatchTaskRunner.resumeTask(taskId) }
                .onSuccess {
                    _uiState.update {
                        it.copy(
                            cloudFolderBatchTaskMessage = "已继续该文件夹任务，会从断点处续刮",
                            savedMessage = null
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            cloudFolderBatchTaskMessage = "继续失败：${error.message ?: "未知错误"}",
                            savedMessage = null
                        )
                    }
                }
        }
    }

    /** 取消单条文件夹任务：只把这一条标成 Cancelled，其它排队的照常。 */
    fun cancelCloudFolderBatchTask(taskId: Long) {
        viewModelScope.launch {
            runCatching { cloudFolderBatchTaskRunner.cancelTask(taskId) }
                .onSuccess {
                    _uiState.update {
                        it.copy(
                            cloudFolderBatchTaskMessage = "已取消该文件夹任务",
                            savedMessage = null
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            cloudFolderBatchTaskMessage = "取消失败：${error.message ?: "未知错误"}",
                            savedMessage = null
                        )
                    }
                }
        }
    }

    /**
     * 手动刷新网盘文件夹任务列表（F-3）。
     *
     * 早期实现只改一句文案，等于没有任何作用——用户点完刷新，看到的还是旧的「运行中」。
     * 现在做两件有实际效果的事：
     * 1. runner 确认没在跑时，把库里残留的 Running（进程被杀留下的僵尸行）降级成 Paused，
     *    并写清「上次被中断、继续会从断点续跑」，让状态与实际一致；
     * 2. 重新从数据库读一次，message 里报出真实计数，用户能确认列表确实刷新了。
     */
    fun refreshCloudFolderBatchTasks() {
        viewModelScope.launch {
            val running = cloudFolderBatchTaskRunner.isRunning.value
            val revived = if (running) {
                0
            } else {
                runCatching {
                    cloudFolderBatchTaskRepository.markZombieRunningAsPaused(
                        "上次运行被中断，点击「开始」会从中断处继续"
                    )
                }.getOrDefault(0)
            }
            val tasks = runCatching { cloudFolderBatchTaskRepository.getTasks() }.getOrElse {
                _uiState.update { state ->
                    state.copy(
                        cloudFolderBatchTaskMessage = "刷新失败：${it.message ?: "读取任务列表出错"}",
                        savedMessage = null
                    )
                }
                return@launch
            }
            val unfinished = tasks.count { it.status != CloudFolderBatchTaskStatus.Completed.name }
            val message = buildString {
                append("已刷新：共 ${tasks.size} 个任务，未完成 $unfinished 个")
                if (revived > 0) append("，$revived 个中断任务已标记为暂停")
                if (running) append("，当前正在运行")
            }
            _uiState.update {
                it.copy(
                    cloudFolderBatchTasks = tasks,
                    cloudFolderBatchTaskMessage = message,
                    savedMessage = message
                )
            }
        }
    }

    fun clearCompletedCloudFolderBatchTasks() {
        viewModelScope.launch {
            val count = cloudFolderBatchTaskRepository.clearCompletedTasks()
            _uiState.update {
                it.copy(
                    cloudFolderBatchTaskMessage = "已清理 $count 个已完成文件夹任务",
                    savedMessage = "已清理 $count 个已完成文件夹任务"
                )
            }
        }
    }

    fun clearCompletedCloudVideoTasks() {
        viewModelScope.launch {
            val count = cloudVideoTaskRepository.clearCompletedTasks()
            _uiState.update {
                it.copy(savedMessage = "已清理 $count 个已完成网盘视频任务")
            }
        }
    }

    fun startManualScrapeTasks() {
        if (manualScrapeCoordinatorJob?.isActive == true) return
        manualScrapeCoordinatorJob = viewModelScope.launch {
            resumeScrapeTaskPipelines(
                hasCloudVideoTasks = cloudVideoTaskRepository.hasUnfinishedTasks(),
                startCloudVideoTasks = cloudVideoTaskRunner::start,
                startMovieTasks = movieScrapeTaskRunner::startIssues
            )
        }
        _uiState.update { it.copy(savedMessage = null) }
    }

    fun cancelManualScrapeTasks() {
        manualScrapeCoordinatorJob?.cancel()
        cloudVideoTaskRunner.pause()
        movieScrapeTaskRunner.pause()
        viewModelScope.launch {
            val movieCount = movieRepository.clearUnfinishedScrapeTasks()
            val videoCount = cloudVideoTaskRepository.clearUnfinishedTasks()
            val count = movieCount + videoCount
            _uiState.update {
                it.copy(
                    isManualScrapeRunning = false,
                    isScraping = it.isCloudFolderBatchRunning,
                    scrapeTaskSummary = movieRepository.scrapeTaskSummary(),
                    scrapeIssueMovies = movieRepository.getScrapeIssueMovies(),
                    scrapeTaskMessage = if (count > 0) {
                        "已取消 $count 个刮削任务"
                    } else {
                        "没有可取消的刮削任务"
                    },
                    savedMessage = if (count > 0) {
                        "已取消 $count 个刮削任务"
                    } else {
                        "没有可取消的刮削任务"
                    }
                )
            }
        }
    }

    fun stopManualScrapeTasks() {
        manualScrapeCoordinatorJob?.cancel()
        cloudVideoTaskRunner.pause()
        movieScrapeTaskRunner.pause()
        viewModelScope.launch {
            movieRepository.resetRunningScrapeTasks()
            _uiState.update {
                it.copy(
                    isManualScrapeRunning = false,
                    isScraping = it.isCloudFolderBatchRunning,
                    scrapeTaskSummary = movieRepository.scrapeTaskSummary(),
                    scrapeIssueMovies = movieRepository.getScrapeIssueMovies(),
                    scrapeTaskMessage = "已暂停刮削任务，点击开始可继续处理剩余任务",
                    savedMessage = "已暂停刮削任务"
                )
            }
        }
    }

    fun resetFailedScrapeTasks() {
        viewModelScope.launch {
            val count = movieRepository.resetFailedScrapeTasks()
            _uiState.update {
                it.copy(
                    scrapeTaskSummary = movieRepository.scrapeTaskSummary(),
                    scrapeIssueMovies = movieRepository.getScrapeIssueMovies(),
                    savedMessage = "已将 $count 个失败任务改为待刮削"
                )
            }
        }
    }

    fun clearFinishedScrapeTasks() {
        viewModelScope.launch {
            val count = movieRepository.clearFinishedScrapeTasks()
            _uiState.update {
                it.copy(
                    scrapeTaskSummary = movieRepository.scrapeTaskSummary(),
                    savedMessage = "已清理 $count 个已完成任务记录"
                )
            }
        }
    }

    fun rebuildCloudStrmIndex() {
        _uiState.update { it.copy(isRebuildingStrmIndex = true, savedMessage = null) }
        viewModelScope.launch {
            runCatching { cloudStrmRecordRepository.rebuildIndexAndNormalizeSegments() }
                .onSuccess { result ->
                    _uiState.value = loadState().copy(
                        savedMessage = "STRM 索引已重建：${result.indexed} 个，规范化分段 ${result.renamed} 个"
                    )
                }
                .onFailure { error ->
                    _uiState.value = loadState().copy(savedMessage = error.message ?: "STRM 索引重建失败")
                }
        }
    }

    // ★ 第九轮：手动触发播放来源回填（与 AppContainer 的自动回填共用同一仓储方法；
    // 自动回填的完成标志独立管理，手动重跑不受影响）
    fun backfillRecordSources() {
        if (_uiState.value.isBackfillingSources) return
        _uiState.update { it.copy(isBackfillingSources = true, savedMessage = null, sourceBackfillResult = null) }
        viewModelScope.launch {
            runCatching { cloudStrmRecordRepository.backfillRecordSources() }
                .onSuccess { result ->
                    _uiState.update {
                        it.copy(
                            isBackfillingSources = false,
                            sourceBackfillResult =
                                "来源回填完成：共 ${result.total} 条，更新 ${result.updated} 条" +
                                    "（不可读 ${result.unreadable}，本地/未知路由 ${result.local}）"
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isBackfillingSources = false,
                            sourceBackfillResult = "来源回填失败：${error.userMessage()}"
                        )
                    }
                }
        }
    }

    fun clearMessage() {
        _uiState.update { it.copy(savedMessage = null) }
    }

    private fun loadState(
        scrapeTaskSummary: ScrapeTaskSummary = ScrapeTaskSummary(),
        scrapeTaskMessage: String = ""
    ): SettingsUiState {
        val lastUpdateResult = appUpdateRepository.lastCheckResult
        val customJsonConfigs = repository.getCustomJsonScrapeConfigs()
        val selectedCustomJsonConfigIndex = repository.getSelectedCustomJsonScrapeConfigIndex()
        return SettingsUiState(
            cookies = repository.getCookies(),
            guangyaLoggedIn = repository.isGuangyaLoggedIn(),
            lightThemeEnabled = repository.isLightThemeEnabled(),
            updateManifestUrl = repository.getUpdateManifestUrl(),
            updateProxyBaseUrl = repository.getUpdateProxyBaseUrl(),
            useUpdateProxyEnabled = repository.isUpdateProxyEnabled(),
            appVersionName = appUpdateRepository.currentVersionName,
            appVersionCode = appUpdateRepository.currentVersionCode,
            latestAppUpdate = lastUpdateResult?.latest,
            hasAppUpdate = lastUpdateResult?.hasUpdate ?: false,
            downloadedUpdateReady = appUpdateRepository.hasDownloadedApk(),
            downloadedUpdateApkPath = appUpdateRepository.latestDownloadedApkPath(),
            updateApkDirectoryPath = appUpdateRepository.updateDirectoryPath,
            autoCheckUpdateOnStartupEnabled = repository.isUpdateAutoCheckOnStartupEnabled(),
            autoDeleteInstalledUpdateApkEnabled = repository.isUpdateAutoDeleteInstalledApkEnabled(),
            libraryRootUri = repository.getLibraryRootUri(),
            libraryRootDisplayName = repository.getLibraryRootDisplayName(),
            strmBaseUrl = repository.getStrmBaseUrl(),
            defaultScrapeSource = repository.getDefaultScrapeSource(),
            priorityScrapeSources = repository.getPriorityScrapeSources(),
            priorityScrapeSourceOptions = AppSettingsRepository.PRIORITY_SCRAPE_SOURCE_OPTIONS,
            scrapeChainMode = repository.getScrapeChainMode(),
            customJsonScrapeConfigs = customJsonConfigs,
            selectedCustomJsonScrapeConfigIndex = selectedCustomJsonConfigIndex,
            customJsonScrapeConfig = customJsonConfigs.getOrElse(selectedCustomJsonConfigIndex) { customJsonConfigs.firstOrNull() ?: CustomJsonScrapeConfig() },
            imageDownloadRetryCountText = repository.getImageDownloadRetryCount().toString(),
            scrapeRetryCountText = repository.getScrapeRetryCount().toString(),
            scrapeConcurrencyLimitText = repository.getScrapeConcurrencyLimit().toString(),
            scrapeProxyAddress = repository.getScrapeProxyAddress(),
            savedScrapeProxyAddress = repository.getScrapeProxyAddress(),
            scrapeProxyMode = repository.getScrapeProxyMode(),
            dmm2SkippedPrefixes = repository.getDmm2SkippedNumberPrefixes().toList().sorted(),
            numberPrefixRewriteRules = repository.getNumberPrefixRewriteRules(),
            mgstageAmateurPriorityEnabled = repository.isMgstageAmateurPriorityEnabled(),
            mgstageCustomPrefixes = repository.getCustomMgstagePrefixNumberMappings().toSortedMap(),
            mgstageMergedPrefixes = repository.getMergedMgstagePrefixNumberMappings().toSortedMap(),
            numberRecognitionIgnoredSuffixes = repository.getCachedNumberRecognitionIgnoredSuffixes().toList().sorted(),
            numberRecognitionPartMarkers = repository.getCachedNumberRecognitionPartMarkers().toList().sorted(),
            numberRecognitionAttachedLetterPrefixes = repository.getCachedNumberRecognitionAttachedLetterPrefixes().toList().sorted(),
            numberRecognitionNumericPrefixAliases = repository.getMergedMgstageSearchPrefixAliases().toSortedMap(),
            libraryNoMediaEnabled = repository.isLibraryNoMediaEnabled(),
            cloudAddButtonMessageEnabled = repository.isCloudAddButtonMessageEnabled(),
            cloudDeleteEnabled = repository.isCloudDeleteEnabled(),
            cloudExcludedVideoNames = repository.getCloudExcludedVideoNames().toList().sorted(),
            cloudScrapeSkipBelowSizeMbText = repository.getCloudScrapeSkipBelowSizeMb().toString(),
            playerSeekBackSeconds = repository.getPlayerSeekBackSeconds(),
            playerSeekForwardSeconds = repository.getPlayerSeekForwardSeconds(),
            defaultPlaybackEngineIsMpv = repository.getDefaultPlaybackEngine() == AppSettingsRepository.PLAYER_ENGINE_MPV,
            lutSettings = repository.getLutSettings(),
            externalSubtitleFontSizeSp = repository.getExternalSubtitleFontSizeSp(),
            externalSubtitleBottomPaddingPercent = repository.getExternalSubtitleBottomPaddingPercent(),
            externalSubtitleBackgroundAlphaPercent = repository.getExternalSubtitleBackgroundAlphaPercent(),
            playerProgressBarWidthDp = repository.getPlayerProgressBarWidthDp(),
            playerProgressBarColor = repository.getPlayerProgressBarColor(),
            playerProgressBarAlphaPercent = repository.getPlayerProgressBarAlphaPercent(),
            startupAnimationEnabled = repository.isStartupAnimationEnabled(),
            startupAnimationImageUri = repository.getStartupAnimationImageUri(),
            selectedCloud115LoginApp = Cloud115LoginApps.find(repository.getCloud115LoginApp()),
            savedCloud115Accounts = emptyList(),
            scrapeTaskSummary = scrapeTaskSummary,
            scrapeTaskMessage = scrapeTaskMessage
        )
    }

    companion object {
        private const val QR_LOGIN_POLL_INTERVAL_MS = 1_500L

        fun factory(
            repository: AppSettingsRepository,
            movieRepository: MovieRepository,
            cloudStrmRecordRepository: CloudStrmRecordRepository,
            scrapeRepository: StrmScrapeRepository,
            appUpdateRepository: AppUpdateRepository,
            cloud115QrLoginClient: Cloud115QrLoginClient,
            cloudFolderBatchTaskRepository: CloudFolderBatchTaskRepository,
            cloudFolderBatchTaskRunner: CloudFolderBatchTaskRunner,
            cloudVideoTaskRepository: CloudVideoTaskRepository,
            cloudVideoTaskRunner: CloudVideoTaskRunner,
            movieScrapeTaskRunner: MovieScrapeTaskRunner,
            movieMetadataRefreshRunner: MovieMetadataRefreshRunner
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    SettingsViewModel(
                        repository,
                        movieRepository,
                        cloudStrmRecordRepository,
                        scrapeRepository,
                        appUpdateRepository,
                        cloud115QrLoginClient,
                        cloudFolderBatchTaskRepository,
                        cloudFolderBatchTaskRunner,
                        cloudVideoTaskRepository,
                        cloudVideoTaskRunner,
                        movieScrapeTaskRunner,
                        movieMetadataRefreshRunner
                    ) as T
            }
    }
}

internal suspend fun resumeScrapeTaskPipelines(
    hasCloudVideoTasks: Boolean,
    startCloudVideoTasks: () -> Unit,
    startMovieTasks: () -> Unit
) {
    if (hasCloudVideoTasks) startCloudVideoTasks()
    startMovieTasks()
}

data class SettingsUiState(
    val cookies: String = "",
    val guangyaLoggedIn: Boolean = false,
    val lightThemeEnabled: Boolean = false,
    val updateManifestUrl: String = "",
    val updateProxyBaseUrl: String = AppSettingsRepository.DEFAULT_UPDATE_PROXY_BASE_URL,
    val useUpdateProxyEnabled: Boolean = true,
    val appVersionName: String = "",
    val appVersionCode: Int = 0,
    val latestAppUpdate: AppUpdateInfo? = null,
    val hasAppUpdate: Boolean = false,
    val isCheckingUpdate: Boolean = false,
    val isDownloadingUpdate: Boolean = false,
    val updateDownloadProgress: Int = 0,
    val downloadedUpdateReady: Boolean = false,
    val downloadedUpdateApkPath: String = "",
    val updateApkDirectoryPath: String = "",
    val autoCheckUpdateOnStartupEnabled: Boolean = true,
    val autoDeleteInstalledUpdateApkEnabled: Boolean = true,
    val updateMessage: String = "",
    val libraryRootUri: String? = null,
    val libraryRootDisplayName: String = "尚未选择目录",
    val strmBaseUrl: String = AppSettingsRepository.DEFAULT_STRM_BASE_URL,
    val defaultScrapeSource: ScrapeSource = ScrapeSource.Priority,
    val priorityScrapeSources: List<ScrapeSource> = AppSettingsRepository.DEFAULT_PRIORITY_SCRAPE_SOURCES,
    val priorityScrapeSourceOptions: List<ScrapeSource> = AppSettingsRepository.PRIORITY_SCRAPE_SOURCE_OPTIONS,
    /**
     * 当前生效的刮削链模式。
     *
     * 默认值取 [ScrapeChainMode.DEFAULT]（桃链优先）而不是写死 PeachPrimary：默认值的唯一
     * 定义点在枚举的 companion 里，UI 的初始态跟着它走，才不会出现「设置页显示桃链优先、
     * 实际跑的是 legacy」这种两处口径分叉。
     */
    val scrapeChainMode: ScrapeChainMode = ScrapeChainMode.DEFAULT,
    val customJsonScrapeConfigs: List<CustomJsonScrapeConfig> = listOf(CustomJsonScrapeConfig()),
    val selectedCustomJsonScrapeConfigIndex: Int = 0,
    val customJsonScrapeConfig: CustomJsonScrapeConfig = CustomJsonScrapeConfig(),
    val isTestingCustomJsonScrape: Boolean = false,
    val customJsonTestResult: ScrapedMovieInfo? = null,
    val customJsonPathCandidates: List<CustomJsonPathCandidate> = emptyList(),
    val imageDownloadRetryCountText: String = AppSettingsRepository.DEFAULT_IMAGE_DOWNLOAD_RETRY_COUNT.toString(),
    val scrapeRetryCountText: String = AppSettingsRepository.DEFAULT_SCRAPE_RETRY_COUNT.toString(),
    val scrapeConcurrencyLimitText: String = AppSettingsRepository.DEFAULT_SCRAPE_CONCURRENCY_LIMIT.toString(),
    val scrapeProxyAddress: String = "",
    val savedScrapeProxyAddress: String = "",
    val scrapeProxyMode: ScrapeProxyMode = ScrapeProxyMode.DEFAULT,
    val dmm2SkippedPrefixes: List<String> = emptyList(),
    val newDmm2SkippedPrefix: String = "",
    val numberPrefixRewriteRules: List<NumberPrefixRewriteRule> = emptyList(),
    val newNumberPrefixRulePrefix: String = "",
    val newNumberPrefixRuleNumericPrefix: String = "",
    val newNumberPrefixRuleSources: Set<ScrapeSource> = setOf(ScrapeSource.Javbus, ScrapeSource.TheJavDB),
    val mgstageAmateurPriorityEnabled: Boolean = false,
    val mgstageCustomPrefixes: Map<String, String> = emptyMap(),
    val mgstageMergedPrefixes: Map<String, String> = emptyMap(),
    val newMgstagePrefix: String = "",
    val newMgstageNumericPrefix: String = "",
    val numberRecognitionIgnoredSuffixes: List<String> = AppSettingsRepository.DEFAULT_NUMBER_RECOGNITION_IGNORED_SUFFIXES.toList().sorted(),
    val numberRecognitionPartMarkers: List<String> = AppSettingsRepository.DEFAULT_NUMBER_RECOGNITION_PART_MARKERS.toList().sorted(),
    val numberRecognitionAttachedLetterPrefixes: List<String> = NumberRecognitionRules.DEFAULT_ATTACHED_LETTER_SEGMENT_PREFIXES.toList().sorted(),
    val numberRecognitionNumericPrefixAliases: Map<String, String> = AppSettingsRepository.DEFAULT_MGSTAGE_SEARCH_PREFIX_ALIASES.toSortedMap(),
    val libraryNoMediaEnabled: Boolean = true,
    val cloudAddButtonMessageEnabled: Boolean = true,
    val cloudDeleteEnabled: Boolean = false,
    val cloudExcludedVideoNames: List<String> = emptyList(),
    val newExcludedVideoName: String = "",
    val cloudScrapeSkipBelowSizeMbText: String = AppSettingsRepository.DEFAULT_CLOUD_SCRAPE_SKIP_BELOW_SIZE_MB.toString(),
    val playerSeekBackSeconds: Int = AppSettingsRepository.DEFAULT_PLAYER_SEEK_SECONDS,
    val playerSeekForwardSeconds: Int = AppSettingsRepository.DEFAULT_PLAYER_SEEK_SECONDS,
    /** 默认播放内核是否为 mpv；false 即 exo（默认值，也是 mpv 不可用时的回退目标）。 */
    val defaultPlaybackEngineIsMpv: Boolean = false,
    /**
     * 实时调色（LUT）设置。
     *
     * 默认值取 [LutSettings] 自己的 companion 常量，与播放侧读到的是同一份口径——
     * 设置页显示的初值与 mpv 实际下发的值不会分叉。
     */
    val lutSettings: LutSettings = LutSettings(),
    val externalSubtitleFontSizeSp: Int = AppSettingsRepository.DEFAULT_EXTERNAL_SUBTITLE_FONT_SIZE_SP,
    val externalSubtitleBottomPaddingPercent: Int = AppSettingsRepository.DEFAULT_EXTERNAL_SUBTITLE_BOTTOM_PADDING_PERCENT,
    val externalSubtitleBackgroundAlphaPercent: Int = AppSettingsRepository.DEFAULT_EXTERNAL_SUBTITLE_BACKGROUND_ALPHA_PERCENT,
    val playerProgressBarWidthDp: Int = AppSettingsRepository.DEFAULT_PLAYER_PROGRESS_BAR_WIDTH_DP,
    val playerProgressBarColor: Int = AppSettingsRepository.DEFAULT_PLAYER_PROGRESS_BAR_COLOR,
    val playerProgressBarAlphaPercent: Int = AppSettingsRepository.DEFAULT_PLAYER_PROGRESS_BAR_ALPHA_PERCENT,
    val startupAnimationEnabled: Boolean = false,
    val startupAnimationImageUri: String = "",
    val selectedCloud115LoginApp: Cloud115LoginApp = Cloud115LoginApps.default,
    val savedCloud115Accounts: List<SavedCloud115Account> = emptyList(),
    val selectedCloud115AccountFileName: String? = null,
    val cloud115QrToken: Cloud115QrToken? = null,
    val isCloud115QrLoginActive: Boolean = false,
    val cloud115QrStatusText: String = "",
    val cloud115QrSavedFile: String? = null,
    val isScanning: Boolean = false,
    val scanningCount: Int = 0,
    val scanningCurrentPath: String = "",
    val isRebuildingStrmIndex: Boolean = false,
    // ★ 第九轮：播放来源回填状态（设置页手动入口；v18 迁移后的自动回填由 AppContainer 调度）
    val isBackfillingSources: Boolean = false,
    val sourceBackfillResult: String? = null,
    val isScraping: Boolean = false,
    val isManualScrapeRunning: Boolean = false,
    val scrapeTaskSummary: ScrapeTaskSummary = ScrapeTaskSummary(),
    val scrapeIssueMovies: List<MovieEntity> = emptyList(),
    val cloudVideoTasks: List<CloudVideoTaskEntity> = emptyList(),
    val isCloudVideoTaskRunning: Boolean = false,
    val scrapeTaskMessage: String = "",
    val isMetadataRefreshing: Boolean = false,
    val metadataRefreshMessage: String = "",
    val metadataRefreshProgress: Int = 0,
    val cloudFolderBatchTasks: List<CloudFolderBatchTaskEntity> = emptyList(),
    val isCloudFolderBatchRunning: Boolean = false,
    val cloudFolderBatchTaskMessage: String = "",
    val savedMessage: String? = null
) {
}

