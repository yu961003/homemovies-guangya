package com.example.localmovielibrarz.ui.settings
import com.example.localmovielibrarz.ui.AppRadius
import com.example.localmovielibrarz.ui.warning
import com.example.localmovielibrarz.ui.info

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Article
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.localmovielibrarz.cloud115.Cloud115LoginApp
import com.example.localmovielibrarz.cloud115.Cloud115LoginApps
import com.example.localmovielibrarz.cloud115.SavedCloud115Account
import com.example.localmovielibrarz.data.local.CloudFolderBatchTaskEntity
import com.example.localmovielibrarz.data.local.CloudFolderBatchTaskStatus
import com.example.localmovielibrarz.data.local.MovieEntity
import com.example.localmovielibrarz.data.local.ScrapeTaskStatus
import com.example.localmovielibrarz.data.local.CloudVideoTaskEntity
import com.example.localmovielibrarz.data.local.CloudVideoTaskStatus
import com.example.localmovielibrarz.data.repository.AppSettingsRepository
import com.example.localmovielibrarz.data.repository.DailyUsageStats
import com.example.localmovielibrarz.data.repository.UsageStatsRepository
import com.example.localmovielibrarz.scraper.CustomJsonScrapeConfig
import com.example.localmovielibrarz.scraper.CustomJsonPathCandidate
import com.example.localmovielibrarz.scraper.NumberPrefixRewriteRule
import com.example.localmovielibrarz.scraper.ScrapeChainMode
import com.example.localmovielibrarz.scraper.ScrapeProxyMode
import com.example.localmovielibrarz.scraper.ScrapeSource
import com.example.localmovielibrarz.scraper.ScrapedMovieInfo
import com.example.localmovielibrarz.LocalMovieLibraryApp
import com.example.localmovielibrarz.ui.guangya.GuangyaLoginPanel
import com.example.localmovielibrarz.ui.guangya.GuangyaLoginViewModel
import com.example.localmovielibrarz.ui.shared.MovieImageCacheStore
import com.example.localmovielibrarz.ui.shared.FolderBatchProgress
import com.example.localmovielibrarz.ui.shared.folderBatchAggregateProgress
import com.example.localmovielibrarz.ui.shared.folderBatchProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import androidx.compose.foundation.layout.sizeIn
import com.example.localmovielibrarz.ui.MinTouchTarget
import com.example.localmovielibrarz.ui.ContentAlphaMin
import com.example.localmovielibrarz.ui.ContentAlphaSecondary
import com.example.localmovielibrarz.playback.engine.MpvHwdecMode
import com.example.localmovielibrarz.playback.lut.CubeLut
import com.example.localmovielibrarz.playback.lut.LutParamKey
import com.example.localmovielibrarz.playback.lut.LutParams
import com.example.localmovielibrarz.playback.lut.LutPreset
import com.example.localmovielibrarz.playback.lut.LutSettings

private val SettingsBackground: Color
    @Composable get() = MaterialTheme.colorScheme.background

private enum class SettingsPage {
    Directory,
    Cloud,
    Scrape,
    CustomJsonScrape,
    NumberRecognition,
    ScrapeTasks,
    TopRanking,
    Player,
    PlayerSubtitle,
    PlayerProgressBar,
    PlayerColorGrade,
    PlayerSubtitleLibrary,
    StartupAnimation,
    Update,
    UsageStats
}

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onOpenScrapeLogs: () -> Unit,
    onOpenPlayerLogs: () -> Unit = {},
    openScrapeTasksPage: Boolean = false,
    openUpdatePage: Boolean = false,
    onBack: (() -> Unit)? = null,
    onOpenGuangyaBrowser: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val app = context.applicationContext as LocalMovieLibraryApp
    val guangyaLoginViewModel: GuangyaLoginViewModel = viewModel(
        factory = GuangyaLoginViewModel.factory(
            app.container.guangyaLoginClient,
            app.container.guangyaTokenStore,
            app.container.settingsRepository
        )
    )
    // 光鸭登录/登出后实时刷新概览页的登录状态
    val guangyaStep by guangyaLoginViewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(guangyaStep.step) {
        viewModel.refreshGuangyaLoggedIn()
    }
    val scope = rememberCoroutineScope()
    val libraryDirectoryPicker = rememberTreePicker { uri -> viewModel.scanLibrary(uri) }
    // 字幕库目录单独一个选择器：它是 SAF tree 且只要读权限，选完立刻扫一遍索引。
    val localSubtitleLibraryPicker = rememberTreePicker { uri -> viewModel.selectLocalSubtitleLibrary(uri) }
    val startupImagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val copiedImageUri = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        val imageFile = File(
                            context.filesDir,
                            "startup-animation-${System.currentTimeMillis()}.image"
                        )
                        imageFile.outputStream().use { output -> input.copyTo(output) }
                        imageFile.toURI().toString()
                    }
                }.getOrNull()
            }
            copiedImageUri?.let(viewModel::updateStartupAnimationImageUri)
        }
    }
    val overviewScrollState = rememberScrollState()
    val pageScrollState = rememberScrollState()
    val updateScrollState = rememberScrollState()
    var currentPage by rememberSaveable {
        mutableStateOf<SettingsPage?>(
            when {
                openScrapeTasksPage -> SettingsPage.ScrapeTasks
                openUpdatePage -> SettingsPage.Update
                else -> null
            }
        )
    }
    var showImageCacheDialog by remember { mutableStateOf(false) }
    var imageCacheSizeText by remember { mutableStateOf("计算中...") }
    var shouldScrollUpdateToBottom by remember { mutableStateOf(openUpdatePage) }
    var usageStats by remember { mutableStateOf(emptyList<DailyUsageStats>()) }
    val contentScrollState = when (currentPage) {
        null -> overviewScrollState
        SettingsPage.Update -> updateScrollState
        else -> pageScrollState
    }

    BackHandler(enabled = currentPage != null) {
        if ((openScrapeTasksPage || openUpdatePage) && onBack != null) {
            onBack()
        } else {
            currentPage = if (currentPage == SettingsPage.PlayerSubtitle ||
                currentPage == SettingsPage.PlayerProgressBar ||
                currentPage == SettingsPage.PlayerColorGrade ||
                currentPage == SettingsPage.PlayerSubtitleLibrary
            ) SettingsPage.Player else null
        }
    }

    fun refreshImageCacheSize() {
        scope.launch {
            val sizeBytes = withContext(Dispatchers.IO) {
                MovieImageCacheStore.diskCacheSizeBytes(context)
            }
            imageCacheSizeText = formatCacheSize(sizeBytes)
        }
    }

    LaunchedEffect(uiState.savedMessage) {
        uiState.savedMessage?.let {
            val snackbarJob = launch { snackbarHostState.showSnackbar(it) }
            delay(1_600)
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarJob.cancel()
            viewModel.clearMessage()
        }
    }

    LaunchedEffect(Unit) {
        refreshImageCacheSize()
    }

    LaunchedEffect(openScrapeTasksPage) {
        if (openScrapeTasksPage) {
            currentPage = SettingsPage.ScrapeTasks
        }
    }

    LaunchedEffect(openUpdatePage) {
        if (openUpdatePage) {
            currentPage = SettingsPage.Update
            shouldScrollUpdateToBottom = true
        }
    }

    LaunchedEffect(currentPage) {
        when (currentPage) {
            SettingsPage.Cloud -> viewModel.refreshSavedCloud115Accounts()
            SettingsPage.ScrapeTasks -> viewModel.refreshScrapeTaskSummary()
            SettingsPage.UsageStats -> usageStats = UsageStatsRepository(context).recent()
            else -> Unit
        }
    }

    LaunchedEffect(currentPage) {
        when (currentPage) {
            SettingsPage.Update -> shouldScrollUpdateToBottom = true
            null -> Unit
            else -> pageScrollState.scrollTo(0)
        }
    }

    LaunchedEffect(currentPage, shouldScrollUpdateToBottom) {
        if (currentPage != SettingsPage.Update || !shouldScrollUpdateToBottom) return@LaunchedEffect
        snapshotFlow { updateScrollState.maxValue }.collect { maxValue ->
            if (maxValue > 0) {
                updateScrollState.scrollTo(maxValue)
                shouldScrollUpdateToBottom = false
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            SettingsTopBar(
                title = currentPage?.titleText() ?: "设置",
                onBack = currentPage?.let {
                    if ((openScrapeTasksPage || openUpdatePage) && onBack != null) {
                        onBack
                    } else {
                        { currentPage = if (currentPage == SettingsPage.PlayerSubtitle ||
                        currentPage == SettingsPage.PlayerProgressBar ||
                        currentPage == SettingsPage.PlayerColorGrade ||
                        currentPage == SettingsPage.PlayerSubtitleLibrary
                    ) SettingsPage.Player else null }
                    }
                }
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(contentScrollState)
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                when (currentPage) {
                    null -> SettingsOverviewPage(
                        uiState = uiState,
                        imageCacheSizeText = imageCacheSizeText,
                        onOpenPage = { currentPage = it },
                        onOpenLogs = onOpenScrapeLogs,
                        onOpenPlayerLogs = onOpenPlayerLogs,
                        onSave = viewModel::save,
                        onLightThemeChange = viewModel::updateLightThemeEnabled
                    )

                    SettingsPage.Directory -> DirectorySettingsPage(
                        uiState = uiState,
                        onPickLibrary = { libraryDirectoryPicker.launch(null) },
                        onNoMediaEnabledChange = viewModel::updateLibraryNoMediaEnabled,
                        onRebuildIndex = viewModel::rebuildCloudStrmIndex,
                        onBackfillSources = viewModel::backfillRecordSources
                    )

                    SettingsPage.Cloud -> CloudSettingsPage(
                        uiState = uiState,
                        guangyaLoginViewModel = guangyaLoginViewModel,
                        onOpenGuangyaBrowser = onOpenGuangyaBrowser,
                        onCloud115AppSelected = viewModel::selectCloud115LoginApp,
                        onSavedCloud115AccountSelected = viewModel::applySavedCloud115Account,
                        onSavedCloud115AccountDelete = viewModel::deleteSavedCloud115Account,
                        onRefreshSavedCloud115Accounts = viewModel::refreshSavedCloud115Accounts,
                        onStartCloud115QrLogin = viewModel::startCloud115QrLogin,
                        onCancelCloud115QrLogin = viewModel::cancelCloud115QrLogin,
                        onCloudAddButtonMessageEnabledChange = viewModel::updateCloudAddButtonMessageEnabled,
                        onCloudDeleteEnabledChange = viewModel::updateCloudDeleteEnabled,
                        onExcludedVideoNameDraftChange = viewModel::updateNewExcludedVideoName,
                        onAddExcludedVideoName = viewModel::addExcludedVideoName,
                        onRemoveExcludedVideoName = viewModel::removeExcludedVideoName,
                        onCloudScrapeSkipBelowSizeMbChange = viewModel::updateCloudScrapeSkipBelowSizeMb
                    )

                    SettingsPage.TopRanking -> TopRankingSettingsPage()

                    SettingsPage.Scrape -> ScrapeSettingsPage(
                        uiState = uiState,
                        imageCacheSizeText = imageCacheSizeText,
                        onOpenNumberRules = { currentPage = SettingsPage.NumberRecognition },
                        onOpenCustomJsonScrape = { currentPage = SettingsPage.CustomJsonScrape },
                        onAddPrioritySource = viewModel::addPriorityScrapeSource,
                        onRemovePrioritySource = viewModel::removePriorityScrapeSource,
                        onMovePrioritySourceUp = viewModel::movePriorityScrapeSourceUp,
                        onMovePrioritySourceDown = viewModel::movePriorityScrapeSourceDown,
                        onImageRetryCountChange = viewModel::updateImageDownloadRetryCount,
                        onScrapeRetryCountChange = viewModel::updateScrapeRetryCount,
                        onConcurrencyLimitChange = viewModel::updateScrapeConcurrencyLimit,
                        onScrapeProxyAddressChange = viewModel::updateScrapeProxyAddress,
                        onScrapeProxyModeChange = viewModel::updateScrapeProxyMode,
                        onDmm2SkippedPrefixDraftChange = viewModel::updateNewDmm2SkippedPrefix,
                        onAddDmm2SkippedPrefix = viewModel::addDmm2SkippedPrefix,
                        onRemoveDmm2SkippedPrefix = viewModel::removeDmm2SkippedPrefix,
                        onNumberPrefixRulePrefixDraftChange = viewModel::updateNewNumberPrefixRulePrefix,
                        onNumberPrefixRuleNumericDraftChange = viewModel::updateNewNumberPrefixRuleNumericPrefix,
                        onNumberPrefixRuleSourcesChange = viewModel::updateNewNumberPrefixRuleSources,
                        onAddNumberPrefixRule = viewModel::addNumberPrefixRewriteRule,
                        onRemoveNumberPrefixRule = viewModel::removeNumberPrefixRewriteRule,
                        onMgstageAmateurPriorityChange = viewModel::updateMgstageAmateurPriorityEnabled,
                        onScrapeChainModeChange = viewModel::updateScrapeChainMode,
                        onMgstagePrefixDraftChange = viewModel::updateNewMgstagePrefix,
                        onMgstageNumericPrefixDraftChange = viewModel::updateNewMgstageNumericPrefix,
                        onAddMgstagePrefix = viewModel::addCustomMgstagePrefix,
                        onRemoveMgstagePrefix = viewModel::removeCustomMgstagePrefix,
                        onRefreshCacheSize = ::refreshImageCacheSize,
                        onClearImageCache = { showImageCacheDialog = true }
                    )

                    SettingsPage.NumberRecognition -> NumberRecognitionRulesPage(
                        uiState = uiState,
                        onSaveIgnoredSuffixes = viewModel::saveNumberRecognitionIgnoredSuffixes,
                        onSavePartMarkers = viewModel::saveNumberRecognitionPartMarkers,
                        onSaveAttachedLetterPrefixes = viewModel::saveNumberRecognitionAttachedLetterPrefixes,
                        onSaveNumericPrefixAliases = viewModel::saveNumberRecognitionNumericPrefixAliases
                    )

                    SettingsPage.CustomJsonScrape -> CustomJsonScrapePage(
                        uiState = uiState,
                        onConfigChange = viewModel::updateCustomJsonScrapeConfig,
                        onSelectConfig = viewModel::selectCustomJsonScrapeConfig,
                        onAddConfig = viewModel::addCustomJsonScrapeConfig,
                        onDuplicateConfig = viewModel::duplicateCustomJsonScrapeConfig,
                        onDeleteConfig = viewModel::deleteCustomJsonScrapeConfig,
                        onTestConfig = viewModel::testCustomJsonScrapeConfig
                    )

                    SettingsPage.ScrapeTasks -> ScrapeTasksPage(
                        uiState = uiState,
                        onStartManualScrapeTasks = viewModel::startManualScrapeTasks,
                        onStopManualScrapeTasks = viewModel::stopManualScrapeTasks,
                        onCancelManualScrapeTasks = viewModel::cancelManualScrapeTasks,
                        onRefreshScrapeTasks = viewModel::refreshScrapeTaskSummary,
                        onResetFailedScrapeTasks = viewModel::resetFailedScrapeTasks,
                        onStartCloudFolderBatchTasks = viewModel::startCloudFolderBatchTasks,
                        onStopCloudFolderBatchTasks = viewModel::stopCloudFolderBatchTasks,
                        onCancelCloudFolderBatchTasks = viewModel::cancelCloudFolderBatchTasks,
                        onRefreshCloudFolderBatchTasks = viewModel::refreshCloudFolderBatchTasks,
                        onPauseCloudFolderBatchTask = viewModel::pauseCloudFolderBatchTask,
                        onResumeCloudFolderBatchTask = viewModel::resumeCloudFolderBatchTask,
                        onCancelCloudFolderBatchTask = viewModel::cancelCloudFolderBatchTask,
                        onClearCompletedCloudVideoTasks = viewModel::clearCompletedCloudVideoTasks,
                        onStartMetadataRefresh = viewModel::startMetadataRefresh,
                        onCancelMetadataRefresh = viewModel::cancelMetadataRefresh
                    )

                    SettingsPage.Player -> PlayerSettingsPage(
                        uiState = uiState,
                        onSeekBackSecondsChange = viewModel::updatePlayerSeekBackSeconds,
                        onSeekForwardSecondsChange = viewModel::updatePlayerSeekForwardSeconds,
                        onDefaultEngineChange = viewModel::updateDefaultPlaybackEngine,
                        onMpvHwdecModeChange = viewModel::updateMpvHwdecMode,
                        onOpenSubtitle = { currentPage = SettingsPage.PlayerSubtitle },
                        onOpenProgressBar = { currentPage = SettingsPage.PlayerProgressBar },
                        onOpenColorGrade = { currentPage = SettingsPage.PlayerColorGrade },
                        onOpenLocalSubtitleLibrary = { currentPage = SettingsPage.PlayerSubtitleLibrary }
                    )

                    SettingsPage.PlayerSubtitle -> ExternalSubtitleSettingsPage(
                        uiState = uiState,
                        onExternalSubtitleFontSizeChange = viewModel::updateExternalSubtitleFontSizeSp,
                        onExternalSubtitleBottomPaddingChange = viewModel::updateExternalSubtitleBottomPaddingPercent,
                        onExternalSubtitleBackgroundAlphaChange = viewModel::updateExternalSubtitleBackgroundAlphaPercent,
                        onExternalSubtitleFontFamilyChange = viewModel::updateExternalSubtitleFontFamily,
                        onExternalSubtitleTextColorChange = viewModel::updateExternalSubtitleTextColor
                    )

                    SettingsPage.PlayerSubtitleLibrary -> LocalSubtitleLibrarySettingsPage(
                        uiState = uiState,
                        onPickDirectory = { localSubtitleLibraryPicker.launch(null) },
                        onRescan = viewModel::scanLocalSubtitleLibrary
                    )

                    SettingsPage.PlayerProgressBar -> PlayerProgressBarSettingsPage(
                        uiState = uiState,
                        onWidthChange = viewModel::updatePlayerProgressBarWidthDp,
                        onColorChange = viewModel::updatePlayerProgressBarColor,
                        onAlphaChange = viewModel::updatePlayerProgressBarAlphaPercent
                    )

                    SettingsPage.PlayerColorGrade -> PlayerColorGradeSettingsPage(
                        uiState = uiState,
                        onEnabledChange = viewModel::updateLutEnabled,
                        onParamChange = { key, value -> viewModel.updateLutParam(key, value) },
                        onStrengthChange = viewModel::updateLutStrength,
                        onLutSizeChange = viewModel::updateLutSize,
                        onPresetSelect = viewModel::applyLutPreset
                    )

                    SettingsPage.StartupAnimation -> StartupAnimationSettingsPage(
                        uiState = uiState,
                        onEnabledChange = viewModel::updateStartupAnimationEnabled,
                        onPickImage = {
                            startupImagePicker.launch(
                                PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageOnly
                                )
                            )
                        },
                        onClearImage = viewModel::clearStartupAnimationImage
                    )

                    SettingsPage.Update -> AppUpdateSettingsPage(
                        uiState = uiState,
                        onManifestUrlChange = viewModel::updateManifestUrl,
                        onProxyBaseUrlChange = viewModel::updateProxyBaseUrl,
                        onUseProxyChange = viewModel::updateUseUpdateProxyEnabled,
                        onAutoCheckStartupChange = viewModel::updateAutoCheckUpdateOnStartupEnabled,
                        onAutoDeleteApkChange = viewModel::updateAutoDeleteInstalledUpdateApkEnabled,
                        onCheckUpdate = viewModel::checkForAppUpdate,
                        onDownloadAndInstall = viewModel::downloadAndInstallUpdate,
                        onInstallDownloaded = viewModel::installDownloadedUpdate
                    )

                    SettingsPage.UsageStats -> UsageStatsPage(UsageStatsRepository(context))
                }
                if (currentPage != null && currentPage != SettingsPage.ScrapeTasks) {
                    Text(
                        text = "开关类设置即时生效；文本框内容需点击下方按钮保存",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                        modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
                    )
                    Button(
                        onClick = viewModel::save,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !uiState.isScraping,
                        shape = AppRadius.XL
                    ) {
                        Icon(Icons.Rounded.Save, contentDescription = null)
                        Text("保存设置", modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 72.dp, start = 16.dp, end = 16.dp)
        )

        if (showImageCacheDialog) {
            ClearImageCacheDialog(
                sizeText = imageCacheSizeText,
                onDismiss = { showImageCacheDialog = false },
                onConfirm = {
                    showImageCacheDialog = false
                    scope.launch {
                        val clearedBytes = withContext(Dispatchers.IO) {
                            MovieImageCacheStore.clear(context)
                        }
                        imageCacheSizeText = formatCacheSize(0L)
                        val snackbarJob = launch {
                            snackbarHostState.showSnackbar("已清理图片缓存：${formatCacheSize(clearedBytes)}")
                        }
                        delay(1_600)
                        snackbarHostState.currentSnackbarData?.dismiss()
                        snackbarJob.cancel()
                    }
                }
            )
        }
        uiState.customJsonTestResult?.let { result ->
            CustomJsonMappingDialog(
                result = result,
                config = uiState.customJsonScrapeConfig,
                pathCandidates = uiState.customJsonPathCandidates,
                onConfigChange = viewModel::updateCustomJsonScrapeConfig,
                onDismiss = viewModel::dismissCustomJsonTestResult
            )
        }
    }
}

@Composable
private fun CustomJsonMappingDialog(
    result: ScrapedMovieInfo,
    config: CustomJsonScrapeConfig,
    pathCandidates: List<CustomJsonPathCandidate>,
    onConfigChange: (CustomJsonScrapeConfig) -> Unit,
    onDismiss: () -> Unit
) {
    val targets = remember(config) { customJsonMappingTargets(config) }
    var selectedIndex by rememberSaveable { mutableStateOf(0) }
    var pathSegments by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var targetMenuExpanded by rememberSaveable { mutableStateOf(false) }
    val safeIndex = selectedIndex.coerceIn(0, targets.lastIndex.coerceAtLeast(0))
    val selectedTarget = targets.getOrNull(safeIndex)
    val selectedSampleValue = selectedTarget?.value?.let { pathCandidates.sampleValue(it) }
    val currentPath = pathSegments.toJsonPath()
    val nodes = remember(pathCandidates, pathSegments) { pathCandidates.childNodes(pathSegments) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurface,
        title = { Text("配置字段映射") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 620.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "测试成功：" + result.title.ifBlank { result.number },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "先选字段，再从 JSON 树逐级进入分支；点叶子节点后会自动绑定并跳到下一个字段。",
                    style = MaterialTheme.typography.bodySmall
                )
                CustomJsonMappingPreview(config)
                CustomJsonMappingTargetPanel(
                    selectedTarget = selectedTarget,
                    selectedSampleValue = selectedSampleValue,
                    targets = targets,
                    menuExpanded = targetMenuExpanded,
                    onMenuExpandedChange = { targetMenuExpanded = it },
                    onTargetSelected = { index ->
                        selectedIndex = index
                        pathSegments = emptyList()
                        targetMenuExpanded = false
                    },
                )
                CustomJsonMappingPathPanel(
                    selectedTarget = selectedTarget,
                    currentPath = currentPath,
                    pathSegments = pathSegments,
                    nodes = nodes,
                    pathCandidatesEmpty = pathCandidates.isEmpty(),
                    onGoUp = { pathSegments = pathSegments.dropLast(1) },
                    onNodeSelected = { node ->
                        if (node.hasChildren) {
                            pathSegments = node.segments
                        } else {
                            selectedTarget?.let { target ->
                                onConfigChange(target.apply(config, node.path))
                                pathSegments = emptyList()
                                if (safeIndex < targets.lastIndex) selectedIndex = safeIndex + 1
                            }
                        }
                    },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        }
    )
}

@Composable
private fun CustomJsonMappingTargetPanel(
    selectedTarget: CustomJsonMappingTarget?,
    selectedSampleValue: String?,
    targets: List<CustomJsonMappingTarget>,
    menuExpanded: Boolean,
    onMenuExpandedChange: (Boolean) -> Unit,
    onTargetSelected: (Int) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f), AppRadius.MD)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = selectedTarget?.let { "当前字段：${it.label}" } ?: "当前字段",
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = selectedTarget?.value?.ifBlank { "未选择" }.orEmpty(),
                    color = if (selectedTarget?.value.isNullOrBlank()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!selectedSampleValue.isNullOrBlank()) {
                    Text(
                        text = "样本值：$selectedSampleValue",
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Box {
                OutlinedButton(
                    onClick = { onMenuExpandedChange(true) },
                    shape = AppRadius.LG,
                ) {
                    Text("切换字段")
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { onMenuExpandedChange(false) },
                    modifier = Modifier.heightIn(max = 360.dp),
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    targets.forEachIndexed { index, target ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(target.label, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
                                    Text(
                                        target.value.ifBlank { "未选择" },
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            },
                            onClick = { onTargetSelected(index) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CustomJsonMappingPathPanel(
    selectedTarget: CustomJsonMappingTarget?,
    currentPath: String,
    pathSegments: List<String>,
    nodes: List<CustomJsonPathNode>,
    pathCandidatesEmpty: Boolean,
    onGoUp: () -> Unit,
    onNodeSelected: (CustomJsonPathNode) -> Unit,
) {
    Text(
        text = selectedTarget?.let { "给「${it.label}」选择路径" } ?: "选择路径",
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f), AppRadius.MD)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = currentPath,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontWeight = FontWeight.Bold,
        )
        TextButton(
            onClick = onGoUp,
            enabled = pathSegments.isNotEmpty(),
        ) {
            Text("上一级")
        }
    }
    if (pathCandidatesEmpty) {
        Text("没有生成可选路径，请检查结果路径是否正确。", color = MaterialTheme.colorScheme.error)
    } else if (nodes.isEmpty()) {
        Text("这个分支下面没有可选字段。", color = MaterialTheme.colorScheme.error)
    } else {
        nodes.forEach { node ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f), AppRadius.MD)
                    .clickable { onNodeSelected(node) }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = node.label.jsonSegmentDisplayLabel(),
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = if (node.hasChildren) "进入" else "选择",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Text(node.path, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (node.valuePreview.isNotBlank()) {
                    Text(
                        node.valuePreview,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun CustomJsonMappingPreview(config: CustomJsonScrapeConfig) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f), AppRadius.MD)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text("当前结果路径：${config.resultPath}", style = MaterialTheme.typography.bodySmall)
        Text("提示：如果想选评分 $.reviewSummary.average，请把结果路径设到包含 reviewSummary 的节点后重新测试。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f))
    }
}

private data class CustomJsonMappingTarget(
    val label: String,
    val value: String,
    val apply: (CustomJsonScrapeConfig, String) -> CustomJsonScrapeConfig
)

private data class CustomJsonPathNode(
    val label: String,
    val path: String,
    val segments: List<String>,
    val valuePreview: String,
    val hasChildren: Boolean,
)

private fun customJsonMappingTargets(config: CustomJsonScrapeConfig): List<CustomJsonMappingTarget> = listOf(
    CustomJsonMappingTarget("番号", config.numberPath) { current, path -> current.copy(numberPath = path) },
    CustomJsonMappingTarget("标题", config.titlePath) { current, path -> current.copy(titlePath = path) },
    CustomJsonMappingTarget("原始标题", config.originalTitlePath) { current, path -> current.copy(originalTitlePath = path) },
    CustomJsonMappingTarget("简介", config.plotPath) { current, path -> current.copy(plotPath = path) },
    CustomJsonMappingTarget("发行日期", config.premieredPath) { current, path -> current.copy(premieredPath = path) },
    CustomJsonMappingTarget("时长", config.runtimePath) { current, path -> current.copy(runtimePath = path) },
    CustomJsonMappingTarget("厂商", config.studioPath) { current, path -> current.copy(studioPath = path) },
    CustomJsonMappingTarget("系列", config.seriesPath) { current, path -> current.copy(seriesPath = path) },
    CustomJsonMappingTarget("演员", config.actorsPath) { current, path -> current.copy(actorsPath = path) },
    CustomJsonMappingTarget("类型", config.genresPath) { current, path -> current.copy(genresPath = path) },
    CustomJsonMappingTarget("标签", config.tagsPath) { current, path -> current.copy(tagsPath = path) },
    CustomJsonMappingTarget("评分", config.ratingPath) { current, path -> current.copy(ratingPath = path) },
    CustomJsonMappingTarget("Poster", config.posterPath) { current, path -> current.copy(posterPath = path) },
    CustomJsonMappingTarget("Thumb", config.thumbPath) { current, path -> current.copy(thumbPath = path) },
    CustomJsonMappingTarget("Fanart", config.fanartPath) { current, path -> current.copy(fanartPath = path) },
)

private fun List<CustomJsonPathCandidate>.childNodes(prefixSegments: List<String>): List<CustomJsonPathNode> {
    return asSequence()
        .map { candidate -> candidate to candidate.path.jsonPathSegments() }
        .filter { (_, segments) -> segments.size > prefixSegments.size && segments.take(prefixSegments.size) == prefixSegments }
        .groupBy { (_, segments) -> segments[prefixSegments.size] }
        .map { (segment, entries) ->
            val nodeSegments = prefixSegments + segment
            val exact = entries.firstOrNull { (_, segments) -> segments == nodeSegments }?.first
            val firstPreview = exact?.valuePreview
                ?: entries.firstOrNull { it.first.valuePreview.isNotBlank() }?.first?.valuePreview
                ?: ""
            CustomJsonPathNode(
                label = segment,
                path = nodeSegments.toJsonPath(),
                segments = nodeSegments,
                valuePreview = firstPreview,
                hasChildren = entries.any { (_, segments) -> segments.size > nodeSegments.size },
            )
        }
        .sortedWith(compareBy<CustomJsonPathNode> { !it.hasChildren }.thenBy { it.label.lowercase() })
        .toList()
}

private fun List<CustomJsonPathCandidate>.sampleValue(path: String): String =
    firstOrNull { it.path == path }?.valuePreview.orEmpty()

private fun String.jsonSegmentDisplayLabel(): String = when (this) {
    "[*]" -> "数组全部 [*]"
    else -> this
}

private fun String.jsonPathSegments(): List<String> =
    removePrefix("$.")
        .removePrefix("$")
        .trimStart('.')
        .split('.')
        .filter { it.isNotBlank() }

private fun List<String>.toJsonPath(): String =
    if (isEmpty()) "$" else "$." + joinToString(".")

@Composable
private fun CustomJsonTestResultDialog(
    result: ScrapedMovieInfo,
    requestedNumber: String,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("自定义 JSON 测试结果") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "请检查下面字段是否和接口返回一致。除番号可使用输入值兜底外，缺失字段会标记为缺失。",
                    style = MaterialTheme.typography.bodySmall
                )
                CustomJsonTestResultRow("番号", result.number, if (result.number == requestedNumber) "使用输入番号" else null)
                CustomJsonTestResultRow("标题", result.title)
                CustomJsonTestResultRow("原始标题", result.originalTitle)
                CustomJsonTestResultRow("简介", result.plot)
                CustomJsonTestResultRow("发行日期", result.premiered)
                CustomJsonTestResultRow("时长", result.runtime)
                CustomJsonTestResultRow("厂商", result.studio)
                CustomJsonTestResultRow("系列", result.series)
                CustomJsonTestResultRow("演员", result.actors.joinToString(", "))
                CustomJsonTestResultRow("类型", result.genres.joinToString(", "))
                CustomJsonTestResultRow("标签", result.tags.joinToString(", "))
                CustomJsonTestResultRow("评分", result.rating)
                CustomJsonTestResultRow("Poster", result.posterUrl)
                CustomJsonTestResultRow("Thumb", result.thumbUrl)
                CustomJsonTestResultRow("Fanart", result.fanartUrl)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("知道了")
            }
        }
    )
}

@Composable
private fun CustomJsonTestResultRow(
    label: String,
    value: String,
    note: String? = null
) {
    val missing = value.isBlank()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f), AppRadius.SM)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = note ?: if (missing) "缺失" else "已匹配",
                color = if (missing) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold
            )
        }
        Text(
            text = value.ifBlank { "未取得数据" },
            style = MaterialTheme.typography.bodySmall,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun SettingsPage.titleText(): String = when (this) {
    SettingsPage.Directory -> "目录设置"
    SettingsPage.Cloud -> "网盘设置"
    SettingsPage.Scrape -> "刮削设置"
    SettingsPage.CustomJsonScrape -> "自定义 JSON 刮削"
    SettingsPage.NumberRecognition -> "番号识别规则"
    SettingsPage.ScrapeTasks -> "刮削任务"
    SettingsPage.TopRanking -> "JavDB TOP 榜单"
    SettingsPage.Player -> "播放器设置"
    SettingsPage.PlayerSubtitle -> "外挂字幕"
    SettingsPage.PlayerProgressBar -> "进度条"
    SettingsPage.PlayerColorGrade -> "实时调色"
    SettingsPage.PlayerSubtitleLibrary -> "本地字幕库"
    SettingsPage.StartupAnimation -> "开场动画"
    SettingsPage.Update -> "应用更新"
    SettingsPage.UsageStats -> "使用统计"
}

@Composable
private fun SettingsOverviewPage(
    uiState: SettingsUiState,
    imageCacheSizeText: String,
    onOpenPage: (SettingsPage) -> Unit,
    onOpenLogs: () -> Unit,
    onOpenPlayerLogs: () -> Unit,
    onSave: () -> Unit,
    onLightThemeChange: (Boolean) -> Unit
) {
    SettingsGroupCard(title = "目录") {
        SettingsEntryRow(
            title = "本地影片库目录",
            subtitle = "影片库与 STRM 保存共用：${uiState.libraryRootDisplayName}",
            onClick = { onOpenPage(SettingsPage.Directory) }
        )
    }
    SettingsGroupCard(title = "网盘") {
        SettingsEntryRow(
            title = "115 网盘与光鸭云盘",
            subtitle = "115 账号：${if (uiState.cookies.isNotBlank()) "已登录" else "未登录"} · 光鸭：${if (uiState.guangyaLoggedIn) "已登录" else "未登录"}",
            onClick = { onOpenPage(SettingsPage.Cloud) }
        )
    }
    SettingsGroupCard(title = "刮削") {
        val movieTaskCount = uiState.scrapeTaskSummary.unfinished
        val folderTaskCount = uiState.cloudFolderBatchTasks.count {
            it.status != CloudFolderBatchTaskStatus.Completed.name
        }
        SettingsEntryRow(
            title = "默认刮削与图片缓存",
            subtitle = "${uiState.defaultScrapeSource.label} · 并发 ${uiState.scrapeConcurrencyLimitText} · 刮削重试 ${uiState.scrapeRetryCountText} 次 · 图片重试 ${uiState.imageDownloadRetryCountText} 次 · 缓存 $imageCacheSizeText",
            onClick = { onOpenPage(SettingsPage.Scrape) }
        )
        SettingsEntryRow(
            title = "番号识别规则",
            subtitle = "忽略后缀 ${uiState.numberRecognitionIgnoredSuffixes.size} · 分段标记 ${uiState.numberRecognitionPartMarkers.size}",
            onClick = { onOpenPage(SettingsPage.NumberRecognition) }
        )
        SettingsEntryRow(
            title = "JavDB TOP 榜单",
            subtitle = "榜单名次、卡片角标与数据源地址",
            onClick = { onOpenPage(SettingsPage.TopRanking) }
        )
        SettingsEntryRow(
            title = "刮削日志",
            subtitle = "查看每天的刮削事件和失败原因",
            onClick = onOpenLogs
        )
        SettingsEntryRow(
            title = "播放诊断日志",
            subtitle = "查看播放器状态/轨道/卡顿/报错，便于定位播放问题",
            onClick = onOpenPlayerLogs
        )
        SettingsEntryRow(
            title = "刮削任务",
            subtitle = if (movieTaskCount + folderTaskCount > 0) {
                "影片待处理 $movieTaskCount · 网盘文件夹待处理 $folderTaskCount"
            } else {
                "查看并手动启动影片刮削与网盘文件夹任务"
            },
            onClick = { onOpenPage(SettingsPage.ScrapeTasks) }
        )
    }
    SettingsGroupCard(title = "播放器") {
        SettingsEntryRow(
            title = "播放器设置",
            subtitle = "外挂字幕 ${uiState.externalSubtitleFontSizeSp}sp · ${externalSubtitleFontLabel(uiState.externalSubtitleFontFamily)} · ${externalSubtitleColorLabel(uiState.externalSubtitleTextColor)} · 位置 ${uiState.externalSubtitleBottomPaddingPercent}%",
            onClick = { onOpenPage(SettingsPage.Player) }
        )
    }
    SettingsGroupCard(title = "应用") {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("浅色主题", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text("切换后界面背景与文字颜色会自动适配", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f), style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = uiState.lightThemeEnabled, onCheckedChange = onLightThemeChange)
        }
        SettingsEntryRow(
            title = "开场动画",
            subtitle = if (uiState.startupAnimationEnabled) "已开启 · ${if (uiState.startupAnimationImageUri.isBlank()) "默认画面" else "自定义图片"}" else "默认关闭",
            onClick = { onOpenPage(SettingsPage.StartupAnimation) }
        )
        SettingsEntryRow(
            title = "应用更新",
            subtitle = "当前版本 ${uiState.appVersionName.ifBlank { "未知" }} · ${if (uiState.updateManifestUrl.isBlank()) "未配置更新地址" else "已配置更新地址"}",
            onClick = { onOpenPage(SettingsPage.Update) }
        )
        SettingsEntryRow(
            title = "使用统计",
            subtitle = "每天的使用时长与播放时长",
            onClick = { onOpenPage(SettingsPage.UsageStats) }
        )
    }
    Button(
        onClick = onSave,
        modifier = Modifier.fillMaxWidth(),
        enabled = !uiState.isScraping,
        shape = AppRadius.XL
    ) {
        Icon(Icons.Rounded.Save, contentDescription = null)
        Text("保存设置", modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun StartupAnimationSettingsPage(
    uiState: SettingsUiState,
    onEnabledChange: (Boolean) -> Unit,
    onPickImage: () -> Unit,
    onClearImage: () -> Unit
) {
    SettingsSectionTitle("开场动画")
    SettingsGroupCard(title = "启动过场") {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("启用开场动画", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text("进入应用时显示图片和加载动画，默认关闭。", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f), style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = uiState.startupAnimationEnabled, onCheckedChange = onEnabledChange)
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("动画图片", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            if (uiState.startupAnimationImageUri.isBlank()) {
                Text("未选择时将显示内置深色渐变画面。", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), style = MaterialTheme.typography.bodySmall)
            } else {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current).data(Uri.parse(uiState.startupAnimationImageUri)).crossfade(true).build(),
                    contentDescription = "开场动画预览",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp, max = 220.dp)
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onPickImage) { Text("选择图片") }
                if (uiState.startupAnimationImageUri.isNotBlank()) {
                    TextButton(onClick = onClearImage) { Text("恢复默认") }
                }
            }
        }
    }
}

@Composable
private fun PlayerSettingsPage(
    uiState: SettingsUiState,
    onSeekBackSecondsChange: (Int) -> Unit,
    onSeekForwardSecondsChange: (Int) -> Unit,
    onDefaultEngineChange: (Boolean) -> Unit,
    onMpvHwdecModeChange: (MpvHwdecMode) -> Unit,
    onOpenSubtitle: () -> Unit,
    onOpenProgressBar: () -> Unit,
    onOpenColorGrade: () -> Unit,
    onOpenLocalSubtitleLibrary: () -> Unit
) {
    SettingsSectionTitle("播放内核")
    SettingsGroupCard(title = "双内核共存") {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "默认使用 mpv 内核",
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (uiState.defaultPlaybackEngineIsMpv) {
                        "已开启 · 打不开的视频自动由 mpv 接管；缺 so 时回退 exo 并提示"
                    } else {
                        "默认关闭（ExoPlayer）· 可在播放页右上角随时切换内核，进度/字幕/倍速都会带过去"
                    },
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Switch(checked = uiState.defaultPlaybackEngineIsMpv, onCheckedChange = onDefaultEngineChange)
        }
    }
    SettingsGroupCard(title = "解码（mpv 默认档）") {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "默认「自动」：装载后按片源特征挑档——HDR / 广色域走零拷贝，普通 SDR 走回拷贝，" +
                    "解码器不支持时降级软解。播放页侧栏可以针对单部片临时改，改完即写回这里。",
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
                style = MaterialTheme.typography.bodySmall
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MpvHwdecMode.entries.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        pair.forEach { mode ->
                            val selected = mode == uiState.mpvHwdecMode
                            OutlinedButton(
                                onClick = { onMpvHwdecModeChange(mode) },
                                colors = if (selected) {
                                    ButtonDefaults.outlinedButtonColors(
                                        containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                                    )
                                } else {
                                    ButtonDefaults.outlinedButtonColors()
                                }
                            ) {
                                Text(if (selected) "✓ ${mode.label}" else mode.label)
                            }
                        }
                    }
                }
            }
            Text(
                text = uiState.mpvHwdecMode.subtitle,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
    SettingsSectionTitle("播放控制")
    SettingsGroupCard(title = "快进与后退") {
        SubtitleStyleSliderRow(
            title = "后退时间",
            value = uiState.playerSeekBackSeconds,
            range = AppSettingsRepository.MIN_PLAYER_SEEK_SECONDS..AppSettingsRepository.MAX_PLAYER_SEEK_SECONDS,
            valueText = "${uiState.playerSeekBackSeconds} 秒",
            onValueChange = onSeekBackSecondsChange
        )
        SubtitleStyleSliderRow(
            title = "快进时间",
            value = uiState.playerSeekForwardSeconds,
            range = AppSettingsRepository.MIN_PLAYER_SEEK_SECONDS..AppSettingsRepository.MAX_PLAYER_SEEK_SECONDS,
            valueText = "${uiState.playerSeekForwardSeconds} 秒",
            onValueChange = onSeekForwardSecondsChange
        )
    }
    SettingsSectionTitle("播放器控件")
    SettingsGroupCard(title = "样式与预览") {
        SettingsEntryRow(
            title = "外挂字幕",
            subtitle = "字号 ${uiState.externalSubtitleFontSizeSp}sp · ${externalSubtitleFontLabel(uiState.externalSubtitleFontFamily)} · ${externalSubtitleColorLabel(uiState.externalSubtitleTextColor)} · 点击进入实时预览",
            onClick = onOpenSubtitle
        )
        SettingsEntryRow(
            title = "进度条",
            subtitle = "宽度 ${uiState.playerProgressBarWidthDp}dp · 点击进入实时预览",
            onClick = onOpenProgressBar
        )
        SettingsEntryRow(
            title = "实时调色",
            subtitle = uiState.lutSettings.entrySubtitle(onMpv = uiState.defaultPlaybackEngineIsMpv),
            onClick = onOpenColorGrade
        )
        SettingsEntryRow(
            title = "本地字幕库",
            subtitle = "把手机里存字幕的文件夹选进来，播放时按番号直接命中（当前 ${uiState.localSubtitleLibraryDisplayName}）",
            onClick = onOpenLocalSubtitleLibrary
        )
    }
}

/**
 * 「实时调色」入口行的一行摘要。
 *
 * 三种状态必须能一眼分辨：关着 / 开着但参数恒等 / 开着且生效。中间那种是真实存在的
 * ——用户可能把滑杆全推回 0 却没关开关，此时画面其实没变化，副标题若写「已开启」
 * 会让人以为坏了。
 */
private fun LutSettings.entrySubtitle(onMpv: Boolean): String = when {
    !enabled -> "关闭 · 仅 mpv 内核实时调色（LUT）"
    !onMpv -> "已开启 · 但默认内核是 ExoPlayer，调色只在 MPV 内核生效"
    shouldApply -> "已开启 · ${lutSize}³ LUT · 强度 $strengthPercent% · 点击调整"
    else -> "已开启但参数为默认值 · 画面无变化"
}

/**
 * 实时调色设置页。
 *
 * 能力边界写在页面上而不是只在文档里：mpv 内核支持、exo 不支持。切换内核后本页的
 * 设置不会被清掉——它是全局偏好，mpv 下次接管时照旧生效。
 */
@Composable
private fun PlayerColorGradeSettingsPage(
    uiState: SettingsUiState,
    onEnabledChange: (Boolean) -> Unit,
    onParamChange: (LutParamKey, Int) -> Unit,
    onStrengthChange: (Int) -> Unit,
    onLutSizeChange: (Int) -> Unit,
    onPresetSelect: (LutPreset) -> Unit
) {
    val settings = uiState.lutSettings

    SettingsSectionTitle("实时调色")

    SettingsGroupCard(title = "总开关") {
        SettingsSwitchRow(
            title = "启用实时调色（LUT）",
            subtitle = "在 mpv 内核上按下方参数即时生成 3D LUT 并挂到播放管线；exo 内核不支持，切过去会自动失效。",
            checked = settings.enabled,
            onCheckedChange = onEnabledChange
        )
        if (!settings.enabled) {
            Text(
                text = "当前关闭。开启后可选择预设或手动拖动 7 个参数，改动会立即作用到正在播放的画面。",
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)
            )
        }
    }

    SettingsSectionTitle("预设")
    SettingsGroupCard(title = "一键套用") {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "以下四组数值与参考实现完全一致，套用时会同时打开总开关（选「关闭调色」则关闭）。",
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
                style = MaterialTheme.typography.bodySmall
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LutPreset.orderedForUi.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        pair.forEach { preset ->
                            OutlinedButton(onClick = { onPresetSelect(preset) }) {
                                Text(preset.label)
                            }
                        }
                    }
                }
            }
            val activePreset = LutPreset.orderedForUi.firstOrNull { it.params == settings.params }
            Text(
                text = when {
                    activePreset != null -> activePreset.description
                    settings.params.isNeutral() -> "所有参数为 0（画面无变化）"
                    else -> "自定义参数 · 已偏离全部预设"
                },
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }

    SettingsSectionTitle("色彩参数")
    SettingsGroupCard(title = "七项微调") {
        Text(
            text = "范围均为 -100 ~ 100，0 表示不介入。滑杆拖动即时生效，无需点保存。",
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 10.dp)
        )
        LutParamKey.entries.forEach { key ->
            val value = settings.params.get(key)
            SubtitleStyleSliderRow(
                title = key.label,
                value = value,
                range = LutParams.MIN..LutParams.MAX,
                valueText = if (value > 0) "+$value" else "$value",
                onValueChange = { next -> onParamChange(key, next) }
            )
            Text(
                text = key.hint,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 6.dp)
            )
        }
    }

    SettingsSectionTitle("强度与精度")
    SettingsGroupCard(title = "混合与 LUT 尺寸") {
        SubtitleStyleSliderRow(
            title = "叠加强度",
            value = settings.strengthPercent,
            range = LutSettings.MIN_STRENGTH..LutSettings.MAX_STRENGTH,
            valueText = "${settings.strengthPercent}%",
            onValueChange = onStrengthChange
        )
        Text(
            text = "0% 等同关闭；100% 为参数原样。默认 80% 是参考实现「搜到的最优值直接用会偏重」的经验值。",
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 8.dp)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = "LUT 尺寸",
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "越大过渡越平滑，生成越慢。${CubeLut.MIN_SIZE}³ 在暗部有可见台阶。",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CubeLut.Sizes.forEach { size ->
                OutlinedButton(
                    onClick = { onLutSizeChange(size) },
                    enabled = settings.lutSize != size
                ) { Text("$size³") }
            }
        }
        Spacer(Modifier.height(6.dp))
    }

    SettingsSectionTitle("说明")
    SettingsGroupCard(title = "能力边界") {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "· 仅 mpv 内核生效（通过 libmpv 的 target-lut 属性实时重载，不改文件、不重编码）。",
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = "· exo 内核无对应能力，切到 exo 后调色不生效但设置保留。",
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = "· 自动分析调参已内置：mpv 内核播放时点播放页右侧「调色」段里的「自动分析调参」，会截取当前帧分析肤色与亮度，自动回填参数。",
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = "· 下发失败（设备不支持 / 路径不可读 / 内核未走 gpu-next）时播放页「调色」段会显示具体原因，不会静默失效。",
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}


@Composable
private fun UsageStatsPage(repository: UsageStatsRepository) {
    var period by rememberSaveable { mutableStateOf(UsageStatsPeriod.Week) }
    val allStats = repository.all()
    val stats = when (period) {
        UsageStatsPeriod.Week -> repository.recent(7)
        UsageStatsPeriod.Month -> repository.recent(30)
        UsageStatsPeriod.All -> allStats.takeLast(30)
    }
    val maxMillis = stats.maxOfOrNull { maxOf(it.appMillis, it.playbackMillis) }?.coerceAtLeast(1L) ?: 1L
    val totalSource = if (period == UsageStatsPeriod.All) allStats else stats
    val totalApp = totalSource.sumOf { it.appMillis }
    val totalPlayback = totalSource.sumOf { it.playbackMillis }
    SettingsSectionTitle("使用统计")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        UsageStatsPeriod.entries.forEach { option ->
            OutlinedButton(onClick = { period = option }) { Text(option.label) }
        }
    }
    if (stats.isEmpty()) {
        SettingsGroupCard(title = "使用与播放时长") {
            Box(
                modifier = Modifier.fillMaxWidth().padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("暂无使用记录", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f))
            }
        }
    } else {
        SettingsGroupCard(title = "使用与播放时长") {
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp).padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                stats.forEach { item ->
                    Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Row(modifier = Modifier.heightIn(min = 120.dp), verticalAlignment = Alignment.Bottom) {
                            Box(modifier = Modifier.width(10.dp).height((120f * item.appMillis / maxMillis).dp.coerceAtLeast(3.dp)).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(topStart = AppRadius.XS_Dp, topEnd = AppRadius.XS_Dp)))
                            Spacer(Modifier.width(3.dp))
                            Box(modifier = Modifier.width(10.dp).height((120f * item.playbackMillis / maxMillis).dp.coerceAtLeast(3.dp)).background(MaterialTheme.colorScheme.tertiary, RoundedCornerShape(topStart = AppRadius.XS_Dp, topEnd = AppRadius.XS_Dp)))
                        }
                        if (period != UsageStatsPeriod.Month || stats.indexOf(item) % 5 == 0) Text(item.day, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }
            Text("蓝色：使用软件 · 紫色：实际播放", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp))
        }
        SettingsGroupCard(title = "汇总") {
            Text("${period.label}使用 ${formatUsageDuration(totalApp)} · 播放 ${formatUsageDuration(totalPlayback)}", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
        }
        if (period == UsageStatsPeriod.Week) SettingsGroupCard(title = "本周每日记录") {
            stats.forEach { item -> Text("${item.day}　使用 ${formatUsageDuration(item.appMillis)}　播放 ${formatUsageDuration(item.playbackMillis)}", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 7.dp)) }
        }
    }
}

private enum class UsageStatsPeriod(val label: String) { Week("7天"), Month("一个月"), All("总时间") }

private fun formatUsageDuration(millis: Long): String {
    val minutes = millis / 60_000L
    return if (minutes >= 60) "${minutes / 60}小时${minutes % 60}分" else "${minutes}分"
}

@Composable
private fun ExternalSubtitleSettingsPage(
    uiState: SettingsUiState,
    onExternalSubtitleFontSizeChange: (Int) -> Unit,
    onExternalSubtitleBottomPaddingChange: (Int) -> Unit,
    onExternalSubtitleBackgroundAlphaChange: (Int) -> Unit,
    onExternalSubtitleFontFamilyChange: (String) -> Unit,
    onExternalSubtitleTextColorChange: (Int) -> Unit
) {
    SettingsSectionTitle("实时预览")
    SubtitleStylePreview(uiState)
    SettingsSectionTitle("字幕显示")
    SettingsGroupCard(title = "外挂字幕样式") {
        SubtitleStyleSliderRow(
            title = "字号",
            value = uiState.externalSubtitleFontSizeSp,
            range = AppSettingsRepository.MIN_EXTERNAL_SUBTITLE_FONT_SIZE_SP..AppSettingsRepository.MAX_EXTERNAL_SUBTITLE_FONT_SIZE_SP,
            valueText = "${uiState.externalSubtitleFontSizeSp}sp",
            onValueChange = onExternalSubtitleFontSizeChange
        )
        SubtitleStyleSliderRow(
            title = "底部位置",
            value = uiState.externalSubtitleBottomPaddingPercent,
            range = AppSettingsRepository.MIN_EXTERNAL_SUBTITLE_BOTTOM_PADDING_PERCENT..AppSettingsRepository.MAX_EXTERNAL_SUBTITLE_BOTTOM_PADDING_PERCENT,
            valueText = "${uiState.externalSubtitleBottomPaddingPercent}%",
            onValueChange = onExternalSubtitleBottomPaddingChange
        )
        SubtitleStyleSliderRow(
            title = "背景不透明度",
            value = uiState.externalSubtitleBackgroundAlphaPercent,
            range = AppSettingsRepository.MIN_EXTERNAL_SUBTITLE_BACKGROUND_ALPHA_PERCENT..AppSettingsRepository.MAX_EXTERNAL_SUBTITLE_BACKGROUND_ALPHA_PERCENT,
            valueText = "${uiState.externalSubtitleBackgroundAlphaPercent}%",
            onValueChange = onExternalSubtitleBackgroundAlphaChange
        )
        // §5.2-8：字体族与文字颜色。为什么不给「任意字体文件导入」：外挂字幕走
        // media3 的 SubtitleView，它只认系统字体族，第三方 .ttf 得先注册到 Resources
        // 才能被 Typeface.create 解析；而对本项目的主要场景（看番/看电影）来说，
        // 系统族 + 6 档常用色的收益/成本比明显高于引入字体文件管理。
        SubtitleStyleChoiceRow(
            title = "字体",
            options = AppSettingsRepository.EXTERNAL_SUBTITLE_FONT_FAMILIES,
            labels = AppSettingsRepository.EXTERNAL_SUBTITLE_FONT_FAMILY_LABELS,
            selected = uiState.externalSubtitleFontFamily,
            onSelect = onExternalSubtitleFontFamilyChange
        )
        SubtitleStyleColorRow(
            title = "文字颜色",
            colors = AppSettingsRepository.EXTERNAL_SUBTITLE_TEXT_COLORS,
            labels = AppSettingsRepository.EXTERNAL_SUBTITLE_TEXT_COLOR_LABELS,
            selected = uiState.externalSubtitleTextColor,
            onSelect = onExternalSubtitleTextColorChange
        )
    }
}

/**
 * 单选行（字体族用）。选项少且互斥，用 chips 而不是下拉：下拉在设置页里要点两次，
 * 而这里改动要立刻在预览区看到效果，两次点击会让「调一调看看」的反馈循环变长。
 */
@Composable
private fun SubtitleStyleChoiceRow(
    title: String,
    options: List<String>,
    labels: List<String>,
    selected: String,
    onSelect: (String) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = title,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEachIndexed { index, value ->
                val isSelected = value == selected
                Text(
                    text = labels.getOrElse(index) { value },
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                            AppRadius.SM
                        )
                        .clickable { onSelect(value) }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        }
    }
}

/** 色板行（文字颜色用）。选中态用外圈描边表示，而不是加一号色块——加号会挤动整行。 */
@Composable
private fun SubtitleStyleColorRow(
    title: String,
    colors: List<Int>,
    labels: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = title,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            colors.forEachIndexed { index, argb ->
                val isSelected = argb == selected
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .background(Color(argb), CircleShape)
                        .border(
                            width = if (isSelected) 3.dp else 1.dp,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                            shape = CircleShape
                        )
                        .clickable { onSelect(argb) }
                        .semantics { contentDescription = labels.getOrElse(index) { "颜色$index" } }
                )
            }
        }
    }
}

/**
 * 本地字幕库设置页。
 *
 * 只做三件事：选目录、看索引状态、重扫。刻意不放「匹配策略」「语言偏好」这类开关——
 * 索引命中靠的是番号归一（与影片库同源），多一个策略就多一种「为什么没搜到」的解释成本。
 */
@Composable
private fun LocalSubtitleLibrarySettingsPage(
    uiState: SettingsUiState,
    onPickDirectory: () -> Unit,
    onRescan: () -> Unit
) {
    SettingsSectionTitle("字幕库目录")
    SettingsGroupCard(title = "手机本地字幕库") {
        SettingsEntryRow(
            title = "选择字幕库文件夹",
            subtitle = uiState.localSubtitleLibraryDisplayName,
            onClick = onPickDirectory
        )
        SettingsEntryRow(
            title = "重新扫描索引",
            subtitle = when {
                uiState.localSubtitleLibraryScanning -> uiState.localSubtitleLibraryScanProgress.ifBlank { "正在扫描…" }
                uiState.localSubtitleLibraryCount > 0 -> "已收录 ${uiState.localSubtitleLibraryCount} 条字幕，点击重扫"
                else -> "还没有索引，选完目录后会自动扫一遍"
            },
            onClick = { if (!uiState.localSubtitleLibraryScanning) onRescan() }
        )
    }
    if (uiState.localSubtitleLibraryScanning) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp))
    }
    uiState.localSubtitleLibraryMessage?.let { message ->
        Text(
            text = message,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall
        )
    }
    SettingsSectionTitle("工作方式")
    SettingsGroupCard(title = "说明") {
        Column(modifier = Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "· 只建索引，不改动你的文件：扫描只读取文件名与大小，字幕原文件留在原目录。",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = "· 播放时按番号命中：番号归一规则与影片库同源（AB-12 / ab012 都能对上）。",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = "· 不出网、不下载：选中后直接把文件交给播放器挂载，省流量也更快。",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun TopRankingSettingsPage() {
    val context = LocalContext.current
    val app = context.applicationContext as LocalMovieLibraryApp
    val settingsRepository = app.container.settingsRepository
    val repository = app.container.top250RankingRepository
    val scope = rememberCoroutineScope()

    val status by repository.status.collectAsStateWithLifecycle()
    var badgeEnabled by remember { mutableStateOf(settingsRepository.isCardTopGoldBadgeEnabled()) }
    var message by remember { mutableStateOf<String?>(null) }
    var refreshing by remember { mutableStateOf(false) }

    // 进页面只读磁盘重算状态，不联网：让「已缓存年份」反映真实文件，
    // 而不是上次刷新留在内存里的旧快照。
    LaunchedEffect(repository) {
        repository.readStatus()
    }

    SettingsGroupCard(title = "影片卡片角标") {
        SettingsSwitchRow(
            title = "TOP 金标",
            subtitle = "已缓存榜单中的上榜影片显示金标",
            checked = badgeEnabled,
            onCheckedChange = {
                badgeEnabled = it
                settingsRepository.saveCardTopGoldBadgeEnabled(it)
            }
        )
    }

    SettingsGroupCard(title = "TOP250 榜单缓存") {
        Column(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "仅点击按钮时联网。首次获取总榜和 2008–2026 年榜；之后只更新总榜与 2026 年榜。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = if (status.allCached) "总榜：已缓存" else "总榜：未缓存",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "已缓存年份：" + if (status.cachedYears.isEmpty()) {
                    "无"
                } else {
                    status.cachedYears.joinToString("、") { it.toString() }
                },
                style = MaterialTheme.typography.bodyMedium
            )
            if (status.isLoading) {
                Text(
                    text = "正在获取榜单 ${status.completed}/${status.total}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Button(
                onClick = {
                    if (refreshing) return@Button
                    refreshing = true
                    message = null
                    scope.launch {
                        val result = repository.fetchOnDemand()
                        refreshing = false
                        message = if (result.failed.isEmpty()) {
                            if (result.initial) {
                                "已获取并缓存 ${result.updated.size} 份榜单"
                            } else {
                                "榜单已更新：总榜与 2026 年榜"
                            }
                        } else {
                            "榜单获取完成，${result.failed.size} 份失败"
                        }
                    }
                },
                enabled = !refreshing && !status.isLoading
            ) {
                // 文案与 fetchOnDemand 的真实分支对齐：没跑过首次流程时拉全量 20 份，
                // 之后只补总榜与当年榜。注意这与 5.0.5 的反汇编读出顺序相反
                // （规格书 4.2 记的是 initialAttempted==false → "更新总榜与 2026 年榜"），
                // 但那与它自己的抓取分支矛盾，按行为一致性优先取后者。
                Text(
                    if (status.initialAttempted) {
                        "更新总榜与 2026 年榜"
                    } else {
                        "获取并缓存全部榜单"
                    }
                )
            }
            message?.let {
                Text(text = it, style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    SettingsGroupCard(title = "说明") {
        Column(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "· 名次按番号匹配：先归一化番号（只留字母数字并转大写），再查榜单。",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = "· 榜单数据来自预生成的静态文件，全部离线缓存；不联网、不拖慢首屏。",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = "· 同时上总榜与年度榜的影片，卡片只显示总榜名次；详情页会列出全部命中。",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

/**
 * JavDB 抓取口径：端点地址 + 海报偏好。
 *
 * 自包含（直接读 app.container.settingsRepository），不往 SettingsUiState 里加字段：
 * 这两项只被刮削器消费，改完由取值函数在下一次刮削时现读，不需要重组整个设置快照。
 */
@Composable
private fun JavdbScrapeSettingsCard() {
    val context = LocalContext.current
    val settingsRepository = (context.applicationContext as LocalMovieLibraryApp).container.settingsRepository
    var apiUrl by remember { mutableStateOf(settingsRepository.javdbApiUrl()) }
    var unwatermarked by remember { mutableStateOf(settingsRepository.isJavdbUnwatermarkedImageEnabled()) }
    var savedHint by remember { mutableStateOf<String?>(null) }

    SettingsGroupCard(title = "JavDB 接口") {
        Column(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedTextField(
                value = apiUrl,
                onValueChange = {
                    apiUrl = it
                    savedHint = null
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = AppRadius.LG,
                label = { Text("接口地址") }
            )
            Text(
                text = "镜像站换域名时在这里改，留空或非 http(s) 地址会回落内置地址。",
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
                style = MaterialTheme.typography.bodySmall
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = {
                        settingsRepository.saveJavdbApiUrl(apiUrl)
                        // 保存后立刻回读：用户填了非法地址时，这里显示的是真正会生效的
                        // 那个值，而不是他刚敲进去的字符串。
                        apiUrl = settingsRepository.javdbApiUrl()
                        savedHint = "已保存，下次刮削生效"
                    },
                    modifier = Modifier.weight(1f),
                    shape = AppRadius.LG
                ) {
                    Text("保存地址")
                }
                OutlinedButton(
                    onClick = {
                        apiUrl = AppSettingsRepository.DEFAULT_JAVDB_API_URL
                        settingsRepository.saveJavdbApiUrl(apiUrl)
                        savedHint = "已恢复内置地址"
                    },
                    modifier = Modifier.weight(1f),
                    shape = AppRadius.LG
                ) {
                    Text("恢复默认")
                }
            }
            savedHint?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }

    SettingsGroupCard(title = "海报") {
        Column(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SettingsSwitchRow(
                title = "优先无水印大图",
                subtitle = "开启：海报优先取原始大图（通常无水印），取不到再回落站方封面。" +
                    "关闭：沿用站方封面。已刮削过的影片不会自动换图，重新刮削才生效。",
                checked = unwatermarked,
                onCheckedChange = {
                    unwatermarked = it
                    settingsRepository.saveJavdbUnwatermarkedImageEnabled(it)
                }
            )
        }
    }
}

/** 三档角标的显示名。放在 UI 层：data 层的枚举只表达档位，不带用户可见文案。 */

@Composable
private fun PlayerProgressBarSettingsPage(
    uiState: SettingsUiState,
    onWidthChange: (Int) -> Unit,
    onColorChange: (Int) -> Unit,
    onAlphaChange: (Int) -> Unit
) {
    val progressColor = Color(uiState.playerProgressBarColor).copy(alpha = uiState.playerProgressBarAlphaPercent / 100f)
    SettingsSectionTitle("实时预览")
    SettingsGroupCard(title = "播放进度") {
        Column(modifier = Modifier.fillMaxWidth().padding(18.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("00:00", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f), style = MaterialTheme.typography.bodySmall)
                Text("预览", color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlphaMin), style = MaterialTheme.typography.labelSmall)
            }
            Box(
                modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp)
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f), AppRadius.Full)
                    .heightIn(min = uiState.playerProgressBarWidthDp.dp)
            ) {
                Box(
                    modifier = Modifier.fillMaxWidth(0.46f)
                        .heightIn(min = uiState.playerProgressBarWidthDp.dp)
                        .background(progressColor, AppRadius.Full)
                )
            }
            Text("99:99", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f), style = MaterialTheme.typography.bodySmall, modifier = Modifier.align(Alignment.End))
        }
    }
    SettingsSectionTitle("样式")
    SettingsGroupCard(title = "进度条") {
        SubtitleStyleSliderRow("宽度", uiState.playerProgressBarWidthDp, AppSettingsRepository.MIN_PLAYER_PROGRESS_BAR_WIDTH_DP..AppSettingsRepository.MAX_PLAYER_PROGRESS_BAR_WIDTH_DP, "${uiState.playerProgressBarWidthDp}dp", onWidthChange)
        SubtitleStyleSliderRow("不透明度", uiState.playerProgressBarAlphaPercent, AppSettingsRepository.MIN_PLAYER_PROGRESS_BAR_ALPHA_PERCENT..AppSettingsRepository.MAX_PLAYER_PROGRESS_BAR_ALPHA_PERCENT, "${uiState.playerProgressBarAlphaPercent}%", onAlphaChange)
        Row(modifier = Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(0xFFFFFFFF.toInt(), 0xFFFF5252.toInt(), 0xFF42A5F5.toInt(), 0xFF66BB6A.toInt(), 0xFFFFCA28.toInt()).forEach { color ->
                Box(modifier = Modifier.size(32.dp).background(Color(color), CircleShape).clickable { onColorChange(color) })
            }
        }
    }
}

@Composable
private fun SubtitleStylePreview(uiState: SettingsUiState) {
    val background = Color.Black.copy(alpha = uiState.externalSubtitleBackgroundAlphaPercent / 100f)
    SettingsGroupCard(title = "播放效果") {
        Box(modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, AppRadius.LG).padding(16.dp).heightIn(min = 180.dp), contentAlignment = Alignment.BottomCenter) {
            Text(
                text = "这是一段外挂字幕预览",
                // §5.2-8：预览区必须吃真实设置，否则「设置页看着对、播放页不对」——
                // 这类偏差是本工程历史上最容易反复的一类问题（见 LutSettings 的统一口径注释）。
                color = Color(uiState.externalSubtitleTextColor),
                fontSize = uiState.externalSubtitleFontSizeSp.sp,
                fontFamily = resolvePreviewFontFamily(uiState.externalSubtitleFontFamily),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.background(background, AppRadius.XS).padding(horizontal = 8.dp, vertical = 3.dp).padding(bottom = uiState.externalSubtitleBottomPaddingPercent.dp)
            )
        }
    }
}

/**
 * 入口摘要用的字体族中文名。设置页入口只有一行宽，族名直接显示 "sans-serif-condensed"
 * 这类英文标识会撑破行宽并和其它摘要项对不齐；所以走同一张候选表反查中文标签。
 * 查不到（存量配置里的未知值）时回落候选表首个中文名，而不是显示原始英文串。
 */
private fun externalSubtitleFontLabel(family: String): String {
    val index = AppSettingsRepository.EXTERNAL_SUBTITLE_FONT_FAMILIES.indexOf(family)
    return AppSettingsRepository.EXTERNAL_SUBTITLE_FONT_FAMILY_LABELS.getOrElse(
        if (index >= 0) index else 0
    ) { "系统默认" }
}

/** 入口摘要用的颜色中文名；未知 ARGB 同样回落首项（白），避免摘要里出现 #AARRGGBB。 */
private fun externalSubtitleColorLabel(argb: Int): String {
    val index = AppSettingsRepository.EXTERNAL_SUBTITLE_TEXT_COLORS.indexOf(argb)
    return AppSettingsRepository.EXTERNAL_SUBTITLE_TEXT_COLOR_LABELS.getOrElse(
        if (index >= 0) index else 0
    ) { "白色" }
}

/** 预览区的字体族解析：与播放页 resolveExternalSubtitleTypeface 保持同一映射口径。 */
private fun resolvePreviewFontFamily(family: String): androidx.compose.ui.text.font.FontFamily =
    when (family) {
        "sans-serif" -> androidx.compose.ui.text.font.FontFamily.SansSerif
        "serif" -> androidx.compose.ui.text.font.FontFamily.Serif
        "monospace" -> androidx.compose.ui.text.font.FontFamily.Monospace
        "sans-serif-light", "sans-serif-condensed" -> androidx.compose.ui.text.font.FontFamily.SansSerif
        else -> androidx.compose.ui.text.font.FontFamily.Default
    }

@Composable
private fun SubtitleStyleSliderRow(
    title: String,
    value: Int,
    range: IntRange,
    valueText: String,
    onValueChange: (Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = valueText,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f),
                style = MaterialTheme.typography.bodySmall
            )
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { next -> onValueChange(next.toInt().coerceIn(range.first, range.last)) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = (range.last - range.first - 1).coerceAtLeast(0),
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.onSurface,
                activeTrackColor = MaterialTheme.colorScheme.onSurface,
                inactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.24f)
            )
        )
    }
}

@Composable
private fun AppUpdateSettingsPage(
    uiState: SettingsUiState,
    onManifestUrlChange: (String) -> Unit,
    onProxyBaseUrlChange: (String) -> Unit,
    onUseProxyChange: (Boolean) -> Unit,
    onAutoCheckStartupChange: (Boolean) -> Unit,
    onAutoDeleteApkChange: (Boolean) -> Unit,
    onCheckUpdate: () -> Unit,
    onDownloadAndInstall: () -> Unit,
    onInstallDownloaded: () -> Unit
) {
    val update = uiState.latestAppUpdate
    SettingsSectionTitle("当前版本")
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.075f), AppRadius.LG)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "家庭电影院 ${uiState.appVersionName.ifBlank { "未知" }}",
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = "versionCode ${uiState.appVersionCode}",
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
            style = MaterialTheme.typography.bodySmall
        )
    }

    SettingsSectionTitle("更新配置")
    OutlinedTextField(
        value = uiState.updateManifestUrl,
        onValueChange = onManifestUrlChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        shape = AppRadius.LG,
        label = { Text("版本信息地址") },
        placeholder = { Text(AppSettingsRepository.DEFAULT_UPDATE_MANIFEST_URL) },
        supportingText = { Text("默认使用 GitHub Releases 最新版本中的 latest.json。") },
        colors = settingsTextFieldColors()
    )
    OutlinedTextField(
        value = uiState.updateProxyBaseUrl,
        onValueChange = onProxyBaseUrlChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        shape = AppRadius.LG,
        label = { Text("GitHub 代理地址") },
        placeholder = { Text(AppSettingsRepository.DEFAULT_UPDATE_PROXY_BASE_URL) },
        supportingText = { Text("开启代理更新时使用；关闭后会直接访问 GitHub。") },
        colors = settingsTextFieldColors()
    )
    UpdateSwitchRow(
        title = "使用代理更新",
        subtitle = "开启后检查版本和下载 APK 会经过上面的代理地址；关闭后直接访问 GitHub。",
        checked = uiState.useUpdateProxyEnabled,
        onCheckedChange = onUseProxyChange
    )
    UpdateSwitchRow(
        title = "启动 App 后自动检测更新",
        subtitle = "开启后每次重新打开 App，会在 8 秒后静默检查；只有发现新版本才提示。",
        checked = uiState.autoCheckUpdateOnStartupEnabled,
        onCheckedChange = onAutoCheckStartupChange
    )
    UpdateSwitchRow(
        title = "安装新版本后自动删除旧的 APK",
        subtitle = "开启后，成功安装新版本并再次启动 App 时，会删除缓存目录里的更新 APK。",
        checked = uiState.autoDeleteInstalledUpdateApkEnabled,
        onCheckedChange = onAutoDeleteApkChange
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.075f), AppRadius.LG)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = "APK 缓存位置",
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = uiState.updateApkDirectoryPath.ifBlank { "暂未创建缓存目录" },
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            text = uiState.downloadedUpdateApkPath.ifBlank { "当前没有已下载的更新 APK" },
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlphaMin),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.075f), AppRadius.LG)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = update?.let { "最新版本 ${it.versionName} · versionCode ${it.versionCode}" } ?: "尚未检查更新",
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )
        update?.sizeBytes?.let { size ->
            Text(
                text = "APK 大小：${formatCacheSize(size)}",
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
                style = MaterialTheme.typography.bodySmall
            )
        }
        if (update != null && update.notes.isNotEmpty()) {
            Text(
                text = update.notes.take(5).joinToString("\n") { "- $it" },
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.70f),
                style = MaterialTheme.typography.bodySmall
            )
        }
        if (uiState.updateMessage.isNotBlank()) {
            Text(
                text = uiState.updateMessage,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
                style = MaterialTheme.typography.bodySmall
            )
        }
        if (uiState.isDownloadingUpdate) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(
                text = "${uiState.updateDownloadProgress.coerceIn(0, 100)}%",
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
                style = MaterialTheme.typography.bodySmall
            )
        }
        Button(
            onClick = onCheckUpdate,
            enabled = !uiState.isCheckingUpdate && !uiState.isDownloadingUpdate,
            modifier = Modifier.fillMaxWidth(),
            shape = AppRadius.LG
        ) {
            Icon(Icons.Rounded.Refresh, contentDescription = null)
            Text(
                text = if (uiState.isCheckingUpdate) "正在检查" else "检查更新",
                modifier = Modifier.padding(start = 8.dp)
            )
        }
        if (uiState.hasAppUpdate) {
            Button(
                onClick = onDownloadAndInstall,
                enabled = !uiState.isCheckingUpdate && !uiState.isDownloadingUpdate,
                modifier = Modifier.fillMaxWidth(),
                shape = AppRadius.LG
            ) {
                Icon(Icons.Rounded.CheckCircle, contentDescription = null)
                Text(
                    text = if (uiState.isDownloadingUpdate) "正在下载" else "下载并安装",
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }
        if (uiState.downloadedUpdateReady) {
            OutlinedButton(
                onClick = onInstallDownloaded,
                enabled = !uiState.isCheckingUpdate && !uiState.isDownloadingUpdate,
                modifier = Modifier.fillMaxWidth(),
                shape = AppRadius.LG
            ) {
                Text("安装已下载 APK")
            }
        }
    }
}

@Composable
private fun UpdateSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.075f), AppRadius.LG)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = subtitle,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
                style = MaterialTheme.typography.bodySmall
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
private fun DirectorySettingsPage(
    uiState: SettingsUiState,
    onPickLibrary: () -> Unit,
    onNoMediaEnabledChange: (Boolean) -> Unit,
    onRebuildIndex: () -> Unit,
    onBackfillSources: () -> Unit
) {
    SettingsSectionTitle("影片库与 STRM 目录")
    DirectorySummary(
        title = uiState.libraryRootDisplayName,
        selected = uiState.libraryRootUri != null,
        emptyText = "尚未选择影片库目录"
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.075f), AppRadius.LG)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "生成 .nomedia 文件屏蔽图片",
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "开启后会在影片库目录创建 .nomedia，避免相册显示海报和剧照；关闭后删除该文件。",
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.56f),
                style = MaterialTheme.typography.bodySmall
            )
        }
        Switch(
            checked = uiState.libraryNoMediaEnabled,
            onCheckedChange = onNoMediaEnabledChange,
            enabled = uiState.libraryRootUri != null && !uiState.isScanning
        )
    }
    Button(
        onClick = onPickLibrary,
        enabled = !uiState.isScanning && !uiState.isScraping,
        shape = AppRadius.XL,
        colors = libraryScanButtonPalette(MaterialTheme.colorScheme).let { palette ->
            ButtonDefaults.buttonColors(
                containerColor = palette.containerColor,
                contentColor = palette.contentColor,
                disabledContainerColor = palette.disabledContainerColor,
                disabledContentColor = palette.disabledContentColor
            )
        }
    ) {
        if (uiState.isScanning) {
            CircularProgressIndicator(strokeWidth = 2.dp, color = Color.Black)
        } else {
            Icon(Icons.Rounded.FolderOpen, contentDescription = null)
        }
        Text(
            text = if (uiState.isScanning) {
                if (uiState.scanningCount > 0) "扫描中…已发现 ${uiState.scanningCount} 部" else "扫描中..."
            } else {
                "选择并扫描影片库"
            },
            modifier = Modifier.padding(start = 8.dp)
        )
    }
    if (uiState.isScanning && uiState.scanningCurrentPath.isNotBlank()) {
        Text(
            text = "正在扫描：${uiState.scanningCurrentPath}",
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.56f),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)
        )
    }

    OutlinedButton(
        onClick = onRebuildIndex,
        enabled = !uiState.isScraping && !uiState.isRebuildingStrmIndex,
        shape = AppRadius.XL
    ) {
        if (uiState.isRebuildingStrmIndex) {
            CircularProgressIndicator(strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onSurface)
        } else {
            Icon(Icons.Rounded.Article, contentDescription = null)
        }
        Text(
            text = if (uiState.isRebuildingStrmIndex) "正在重建索引..." else "重建 STRM 索引并规范分段命名",
            modifier = Modifier.padding(start = 8.dp)
        )
    }

    // ★ 第九轮：播放来源手动回填（v18 迁移后首次启动自动执行一次；此处供手动重跑/纠错）
    OutlinedButton(
        onClick = onBackfillSources,
        enabled = !uiState.isBackfillingSources && !uiState.isScraping && !uiState.isRebuildingStrmIndex,
        shape = AppRadius.XL
    ) {
        if (uiState.isBackfillingSources) {
            CircularProgressIndicator(strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onSurface)
        } else {
            Icon(Icons.Rounded.CloudSync, contentDescription = null)
        }
        Text(
            text = if (uiState.isBackfillingSources) "正在回填播放来源..." else "回填播放来源（115/光鸭）",
            modifier = Modifier.padding(start = 8.dp)
        )
    }
    uiState.sourceBackfillResult?.let { result ->
        Text(
            text = result,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)
        )
    }
}

@Composable
private fun CloudSettingsPage(
    uiState: SettingsUiState,
    guangyaLoginViewModel: GuangyaLoginViewModel,
    onOpenGuangyaBrowser: () -> Unit,
    onCloud115AppSelected: (Cloud115LoginApp) -> Unit,
    onSavedCloud115AccountSelected: (SavedCloud115Account) -> Unit,
    onSavedCloud115AccountDelete: (SavedCloud115Account) -> Unit,
    onRefreshSavedCloud115Accounts: () -> Unit,
    onStartCloud115QrLogin: () -> Unit,
    onCancelCloud115QrLogin: () -> Unit,
    onCloudAddButtonMessageEnabledChange: (Boolean) -> Unit,
    onCloudDeleteEnabledChange: (Boolean) -> Unit,
    onExcludedVideoNameDraftChange: (String) -> Unit,
    onAddExcludedVideoName: () -> Unit,
    onRemoveExcludedVideoName: (String) -> Unit,
    onCloudScrapeSkipBelowSizeMbChange: (String) -> Unit
) {
    SettingsSectionTitle("115 网盘登录")
    Cloud115QrLoginPanel(
        uiState = uiState,
        onCloud115AppSelected = onCloud115AppSelected,
        onSavedCloud115AccountSelected = onSavedCloud115AccountSelected,
        onSavedCloud115AccountDelete = onSavedCloud115AccountDelete,
        onRefreshSavedCloud115Accounts = onRefreshSavedCloud115Accounts,
        onStartCloud115QrLogin = onStartCloud115QrLogin,
        onCancelCloud115QrLogin = onCancelCloud115QrLogin
    )
    SettingsSectionTitle("光鸭云盘")
    GuangyaLoginPanel(viewModel = guangyaLoginViewModel)
    Button(
        onClick = onOpenGuangyaBrowser,
        modifier = Modifier.fillMaxWidth(),
        shape = AppRadius.LG
    ) {
        Text("浏览光鸭网盘 / 生成 STRM")
    }
    SettingsSectionTitle("网盘添加")
    CloudAddBehaviorPanel(
        enabled = uiState.cloudAddButtonMessageEnabled,
        onEnabledChange = onCloudAddButtonMessageEnabledChange,
        cloudDeleteEnabled = uiState.cloudDeleteEnabled,
        onCloudDeleteEnabledChange = onCloudDeleteEnabledChange
    )
    SettingsSectionTitle("排除视频")
    ExcludedCloudVideosPanel(
        names = uiState.cloudExcludedVideoNames,
        draft = uiState.newExcludedVideoName,
        onDraftChange = onExcludedVideoNameDraftChange,
        onAdd = onAddExcludedVideoName,
        onRemove = onRemoveExcludedVideoName
    )
    OutlinedTextField(
        value = uiState.cloudScrapeSkipBelowSizeMbText,
        onValueChange = onCloudScrapeSkipBelowSizeMbChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        shape = AppRadius.LG,
        label = { Text("小文件跳过刮削阈值（MB）") },
        supportingText = { Text("文件夹批量添加时，小于或等于该大小的视频会跳过。填 0 表示关闭大小排除。默认 100MB。115 仅文件夹批量生效；光鸭单个与批量均生效。") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        colors = settingsTextFieldColors()
    )
}

@Composable
private fun CloudAddBehaviorPanel(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    cloudDeleteEnabled: Boolean,
    onCloudDeleteEnabledChange: (Boolean) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.075f), AppRadius.LG)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "开启按钮提示",
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "关闭后，网盘点击添加不会弹出正在添加、已添加这类提示。",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = onEnabledChange
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "开启网盘删除",
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "开启后，影片详情删除弹窗会显示“删除(网盘)”，同时删除 115/光鸭网盘的真实视频文件。默认关闭。",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Switch(
                checked = cloudDeleteEnabled,
                onCheckedChange = onCloudDeleteEnabledChange
            )
        }
    }
}

@Composable
private fun ExcludedCloudVideosPanel(
    names: List<String>,
    draft: String,
    onDraftChange: (String) -> Unit,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit
) {
    var showManageDialog by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.075f), AppRadius.LG)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "排除视频名单",
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "已排除 ${names.size} 个视频。在网盘浏览时命中这些文件名不会显示添加按钮，但仍可直接播放。",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            OutlinedButton(
                onClick = { showManageDialog = true },
                shape = AppRadius.LG
            ) {
                Text("管理")
            }
        }
    }

    if (showManageDialog) {
        AlertDialog(
            onDismissRequest = { showManageDialog = false },
            title = { Text("排除视频名单") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("名单中的视频不会显示添加按钮。")
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = draft,
                            onValueChange = onDraftChange,
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            shape = AppRadius.LG,
                            label = { Text("视频文件名") },
                            placeholder = { Text("例如 广告.mp4") }
                        )
                        Button(
                            onClick = onAdd,
                            shape = AppRadius.LG
                        ) {
                            Text("添加")
                        }
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (names.isEmpty()) {
                            Text("暂无排除视频")
                        } else {
                            names.forEach { name ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(Color.Black.copy(alpha = 0.06f), AppRadius.MD)
                                        .padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = name,
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    TextButton(onClick = { onRemove(name) }) {
                                        Text("删除")
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showManageDialog = false }) {
                    Text("完成")
                }
            }
        )
    }
}

@Composable
private fun ScrapeSettingsPage(
    uiState: SettingsUiState,
    imageCacheSizeText: String,
    onOpenNumberRules: () -> Unit,
    onOpenCustomJsonScrape: () -> Unit,
    onAddPrioritySource: (ScrapeSource) -> Unit,
    onRemovePrioritySource: (ScrapeSource) -> Unit,
    onMovePrioritySourceUp: (ScrapeSource) -> Unit,
    onMovePrioritySourceDown: (ScrapeSource) -> Unit,
    onImageRetryCountChange: (String) -> Unit,
    onScrapeRetryCountChange: (String) -> Unit,
    onConcurrencyLimitChange: (String) -> Unit,
    onScrapeProxyAddressChange: (String) -> Unit,
    onScrapeProxyModeChange: (ScrapeProxyMode) -> Unit,
    onDmm2SkippedPrefixDraftChange: (String) -> Unit,
    onAddDmm2SkippedPrefix: () -> Unit,
    onRemoveDmm2SkippedPrefix: (String) -> Unit,
    onNumberPrefixRulePrefixDraftChange: (String) -> Unit,
    onNumberPrefixRuleNumericDraftChange: (String) -> Unit,
    onNumberPrefixRuleSourcesChange: (Set<ScrapeSource>) -> Unit,
    onAddNumberPrefixRule: () -> Unit,
    onRemoveNumberPrefixRule: (String) -> Unit,
    onMgstageAmateurPriorityChange: (Boolean) -> Unit,
    onScrapeChainModeChange: (ScrapeChainMode) -> Unit,
    onMgstagePrefixDraftChange: (String) -> Unit,
    onMgstageNumericPrefixDraftChange: (String) -> Unit,
    onAddMgstagePrefix: () -> Unit,
    onRemoveMgstagePrefix: (String) -> Unit,
    onRefreshCacheSize: () -> Unit,
    onClearImageCache: () -> Unit
) {
    SettingsSectionTitle("默认刮削（优先级）")
    // 刮削链模式放在来源列表**上面**：它决定「这份列表怎么被使用」，先看到开关再看列表，
    // 用户才不会以为调顺序就能绕开链的选择。
    SettingsSwitchRow(
        title = "桃链优先（默认）",
        subtitle = "开启：自动刮削先按 peach 的分档链路取数，取不到才回落到原有刮削链兜底。" +
            "关闭：只用原有刮削链（桃链一行都不执行）。提示：手动点名来源时不受此开关影响。",
        checked = uiState.scrapeChainMode == ScrapeChainMode.PeachPrimary,
        onCheckedChange = { peachPrimary ->
            onScrapeChainModeChange(
                if (peachPrimary) ScrapeChainMode.PeachPrimary else ScrapeChainMode.LegacyOnly
            )
        }
    )
    PriorityScrapeSourcePanel(
        sources = uiState.priorityScrapeSources,
        options = uiState.priorityScrapeSourceOptions,
        onAdd = onAddPrioritySource,
        onRemove = onRemovePrioritySource,
        onMoveUp = onMovePrioritySourceUp,
        onMoveDown = onMovePrioritySourceDown
    )
    // JavDB 端点与海报口径：两项都通过 AppSettingsRepository 的取值函数被刮削器
    // 每次调用现取，改完立刻生效（不需要重启）。放在「默认刮削」之后、「自定义 JSON
    // 刮削」之前——它属于内置源的口径调整，不参与来源优先级排序。
    SettingsSectionTitle("JavDB 抓取")
    JavdbScrapeSettingsCard()
    SettingsSectionTitle("自定义 JSON 刮削")
    CustomJsonScrapeEntryCard(
        config = uiState.customJsonScrapeConfig,
        onClick = onOpenCustomJsonScrape
    )
    SettingsSectionTitle("MGStage 刮削")
    MgstagePrefixPanel(
        customPrefixes = uiState.mgstageCustomPrefixes,
        mergedPrefixes = uiState.mgstageMergedPrefixes,
        draft = uiState.newMgstagePrefix,
        numericDraft = uiState.newMgstageNumericPrefix,
        amateurPriorityEnabled = uiState.mgstageAmateurPriorityEnabled,
        onDraftChange = onMgstagePrefixDraftChange,
        onNumericDraftChange = onMgstageNumericPrefixDraftChange,
        onAdd = onAddMgstagePrefix,
        onRemove = onRemoveMgstagePrefix,
        onAmateurPriorityChange = onMgstageAmateurPriorityChange
    )
    SettingsSectionTitle("数字前缀规则")
    NumberPrefixRewriteRulePanel(
        rules = uiState.numberPrefixRewriteRules,
        prefixDraft = uiState.newNumberPrefixRulePrefix,
        numericPrefixDraft = uiState.newNumberPrefixRuleNumericPrefix,
        selectedSources = uiState.newNumberPrefixRuleSources,
        onPrefixDraftChange = onNumberPrefixRulePrefixDraftChange,
        onNumericPrefixDraftChange = onNumberPrefixRuleNumericDraftChange,
        onSourcesChange = onNumberPrefixRuleSourcesChange,
        onAdd = onAddNumberPrefixRule,
        onRemove = onRemoveNumberPrefixRule
    )
    SettingsSectionTitle("番号识别")
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.075f), AppRadius.LG)
            .clickable(onClick = onOpenNumberRules)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = "番号识别规则",
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "忽略后缀 ${uiState.numberRecognitionIgnoredSuffixes.size} · 分段标记 ${uiState.numberRecognitionPartMarkers.size}",
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
    SettingsSectionTitle("DMM2 跳过")
    Dmm2SkippedPrefixPanel(
        prefixes = uiState.dmm2SkippedPrefixes,
        draft = uiState.newDmm2SkippedPrefix,
        onDraftChange = onDmm2SkippedPrefixDraftChange,
        onAdd = onAddDmm2SkippedPrefix,
        onRemove = onRemoveDmm2SkippedPrefix
    )
    SettingsSectionTitle("刮削并发")
    OutlinedTextField(
        value = uiState.scrapeConcurrencyLimitText,
        onValueChange = onConcurrencyLimitChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        shape = AppRadius.LG,
        label = { Text("同时刮削任务数") },
        supportingText = { Text("范围 1 到 ${AppSettingsRepository.MAX_SCRAPE_CONCURRENCY_LIMIT}。建议 2，过高可能导致请求失败或图片下载失败。") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        colors = settingsTextFieldColors()
    )
    SettingsSectionTitle("刮削重试")
    OutlinedTextField(
        value = uiState.scrapeRetryCountText,
        onValueChange = onScrapeRetryCountChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        shape = AppRadius.LG,
        label = { Text("每个来源尝试次数") },
        supportingText = { Text("范围 1 到 ${AppSettingsRepository.MAX_SCRAPE_RETRY_COUNT}，默认 ${AppSettingsRepository.DEFAULT_SCRAPE_RETRY_COUNT} 次；达到上限后再尝试下一个优先级来源。") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        colors = settingsTextFieldColors()
    )
    SettingsSectionTitle("刮削代理")
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.075f), AppRadius.LG)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("刮削出口", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Text(
            text = when (uiState.scrapeProxyMode) {
                ScrapeProxyMode.Environment ->
                    "跟随系统代理（Wi-Fi 代理、部分 VPN 下发的代理）。作用于刮削与封面图片下载；国内网络下这一档通常是刮削能不能通的关键。"
                ScrapeProxyMode.Direct ->
                    "不走任何代理，全部直连。系统代理配置损坏、或想让刮削绕过代理时选它。"
                ScrapeProxyMode.Proxy ->
                    "使用下方填写的固定代理。地址留空或格式非法时自动回到「跟随系统」，不会静默改成直连。"
            },
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
            style = MaterialTheme.typography.bodySmall
        )
        // 三档单选：对齐 peach 的 environment / direct / proxy。旧版只有一个「使用外部代理」
        // 开关，关掉时给 OkHttp 返回 NO_PROXY（显式直连），把系统代理一起跳过了。
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            ScrapeProxyMode.entries.forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = uiState.scrapeProxyMode == mode,
                    onClick = { onScrapeProxyModeChange(mode) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = ScrapeProxyMode.entries.size)
                ) { Text(mode.label) }
            }
        }
        OutlinedTextField(
            value = uiState.scrapeProxyAddress,
            onValueChange = onScrapeProxyAddressChange,
            modifier = Modifier.fillMaxWidth(),
            enabled = uiState.scrapeProxyMode == ScrapeProxyMode.Proxy,
            singleLine = true,
            shape = AppRadius.LG,
            label = { Text("代理地址") },
            supportingText = {
                Column {
                    Text("例如 192.168.1.10:7890、http://192.168.1.10:7890 或 socks5://192.168.1.10:1080。模式选择即时生效，地址需保存后生效。")
                    if (uiState.scrapeProxyMode == ScrapeProxyMode.Proxy && uiState.scrapeProxyAddress.isBlank()) {
                        Text(
                            text = "已选「自定义代理」但地址为空，当前会跟随系统代理",
                            color = Color(0xFFE67E22),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    if (uiState.scrapeProxyAddress != uiState.savedScrapeProxyAddress) {
                        Text(
                            text = "地址已修改，需点击页面底部保存后生效",
                            color = Color(0xFFE67E22),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            colors = settingsTextFieldColors()
        )
    }
    SettingsSectionTitle("图片下载")
    OutlinedTextField(
        value = uiState.imageDownloadRetryCountText,
        onValueChange = onImageRetryCountChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        shape = AppRadius.LG,
        label = { Text("图片下载重试次数") },
        supportingText = { Text("建议 3 到 6 次。DMM 图片地址优先请求。") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        colors = settingsTextFieldColors()
    )
    ImageCachePanel(
        sizeText = imageCacheSizeText,
        onRefresh = onRefreshCacheSize,
        onClear = onClearImageCache
    )
}

@Composable
private fun NumberRecognitionRulesPage(
    uiState: SettingsUiState,
    onSaveIgnoredSuffixes: (String) -> Unit,
    onSavePartMarkers: (String) -> Unit,
    onSaveAttachedLetterPrefixes: (String) -> Unit,
    onSaveNumericPrefixAliases: (Map<String, String>) -> Unit
) {
    // 修复四：番号前缀映射专用双字段编辑器（无需输入任何分隔符）
    var showAliasEditor by remember { androidx.compose.runtime.mutableStateOf(false) }

    // 需求 9：番号规则列表化——三条规则卡片均可点"编辑"用逗号分隔文本修改
    var editingRule by remember { androidx.compose.runtime.mutableStateOf<EditableRuleKind?>(null) }
    var editText by remember { androidx.compose.runtime.mutableStateOf("") }
    val editDialogValue by androidx.compose.runtime.rememberUpdatedState(editText)

    // 需求 3：按目标卡片导入 TXT（四张规则卡各有独立入口，互不影响、不误派）
    var importTarget by remember { androidx.compose.runtime.mutableStateOf<EditableRuleKind?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val txtLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val target = importTarget
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } }.getOrNull()
            } ?: ""
            if (text.isBlank() || target == null) return@launch
            val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
            when (target) {
                EditableRuleKind.IgnoredSuffixes -> onSaveIgnoredSuffixes(lines.joinToString("、"))
                EditableRuleKind.PartMarkers -> onSavePartMarkers(lines.joinToString("、"))
                EditableRuleKind.AttachedLetterPrefixes -> onSaveAttachedLetterPrefixes(lines.joinToString("、"))
                EditableRuleKind.NumericPrefixAliases ->
                    onSaveNumericPrefixAliases(lines.mapNotNull { parseAliasLine(it) }.toMap())
            }
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("当前规则", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
    }
    NumberRecognitionRuleCard(
        title = "忽略后缀",
        subtitle = "例如 HHB，识别番号时会先剥离这些尾部噪声。",
        values = uiState.numberRecognitionIgnoredSuffixes,
        onEdit = {
            editingRule = EditableRuleKind.IgnoredSuffixes
            editText = uiState.numberRecognitionIgnoredSuffixes.joinToString("、")
        },
        onImport = {
            importTarget = EditableRuleKind.IgnoredSuffixes
            txtLauncher.launch(arrayOf("text/plain", "text/*"))
        }
    )
    NumberRecognitionRuleCard(
        title = "分段标记",
        subtitle = "例如 RESTORED 会识别为 RESTORED 版本，RESTORED-A 会识别为 RESTORED-A。",
        values = uiState.numberRecognitionPartMarkers,
        onEdit = {
            editingRule = EditableRuleKind.PartMarkers
            editText = uiState.numberRecognitionPartMarkers.joinToString("、")
        },
        onImport = {
            importTarget = EditableRuleKind.PartMarkers
            txtLauncher.launch(arrayOf("text/plain", "text/*"))
        }
    )
    NumberRecognitionRuleCard(
        title = "连写字母分段前缀",
        subtitle = "这些前缀后面直接跟 A/B 时会当作分段，不会并入番号。",
        values = uiState.numberRecognitionAttachedLetterPrefixes,
        onEdit = {
            editingRule = EditableRuleKind.AttachedLetterPrefixes
            editText = uiState.numberRecognitionAttachedLetterPrefixes.joinToString("、")
        },
        onImport = {
            importTarget = EditableRuleKind.AttachedLetterPrefixes
            txtLauncher.launch(arrayOf("text/plain", "text/*"))
        }
    )
    NumberRecognitionRuleCard(
        title = "番号前缀映射",
        subtitle = "示例：DANDY=104DANDY（每条「前缀=补齐前缀」，MGStage 搜索时按映射补数字）",
        values = uiState.numberRecognitionNumericPrefixAliases.map { (k, v) -> "$k=$v" },
        onEdit = { showAliasEditor = true },   // 直接指向双字段编辑器
        onImport = {
            importTarget = EditableRuleKind.NumericPrefixAliases
            txtLauncher.launch(arrayOf("text/plain", "text/*"))
        }
    )

    // 修复四：双字段编辑器弹窗（每行两个输入框，全程无需输入特殊符号）
    if (showAliasEditor) {
        AliasPairsEditorDialog(
            initial = uiState.numberRecognitionNumericPrefixAliases,
            onSave = {
                onSaveNumericPrefixAliases(it)
                showAliasEditor = false
            },
            onDismiss = { showAliasEditor = false }
        )
    }

    // 需求 9：规则列表编辑弹窗
    editingRule?.let { kind ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { editingRule = null },
            title = { Text("编辑${kind.title()}") },
            text = {
                OutlinedTextField(
                    value = editText,
                    onValueChange = { editText = it },
                    placeholder = { Text("用逗号分隔多个规则，如 A、B、C") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    kind.save(editText, onSaveIgnoredSuffixes, onSavePartMarkers, onSaveAttachedLetterPrefixes, onSaveNumericPrefixAliases)
                    editingRule = null
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { editingRule = null }) { Text("取消") }
            }
        )
    }
}

/** 需求 9：可编辑的番号规则种类（含番号前缀映射）。 */
private enum class EditableRuleKind {
    IgnoredSuffixes, PartMarkers, AttachedLetterPrefixes, NumericPrefixAliases;

    fun title(): String = when (this) {
        IgnoredSuffixes -> "忽略后缀"
        PartMarkers -> "分段标记"
        AttachedLetterPrefixes -> "连写字母分段前缀"
        NumericPrefixAliases -> "番号前缀映射"
    }

    fun save(
        text: String,
        saveIgnored: (String) -> Unit,
        savePart: (String) -> Unit,
        saveAttached: (String) -> Unit,
        saveAliases: (Map<String, String>) -> Unit
    ) {
        when (this) {
            IgnoredSuffixes -> saveIgnored(text)
            PartMarkers -> savePart(text)
            AttachedLetterPrefixes -> saveAttached(text)
            NumericPrefixAliases -> saveAliases(parseAliasText(text))
        }
    }
}

/** 解析映射文本：每条 "A=B"（主推，另兼容 :、->、→、Tab）；条目以换行/顿号/逗号分隔。 */
private fun parseAliasText(text: String): Map<String, String> =
    text.split('\n', '、', ',', '，')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .mapNotNull { parseAliasLine(it) }
        .toMap()

/** 单条解析：定位首个分隔符，吞掉命中点起的整段分隔符连续串（正确处理 "->"；误输 "==" 亦兼容）。 */
private fun parseAliasLine(line: String): Pair<String, String>? {
    val idx = line.indexOfAny(listOf("=", ":", "->", "→", "\t"))
    if (idx <= 0) return null
    val sepLen = line.substring(idx).takeWhile { it in "=:->→\t" }.length
    val k = line.substring(0, idx).trim().uppercase()
    val v = line.substring(idx + sepLen).trim()
    return if (k.isEmpty() || v.isEmpty()) null else k to v
}

/** 番号前缀映射专用编辑器：每行两个输入框，无需任何分隔符号（修复四）。 */
@Composable
private fun AliasPairsEditorDialog(
    initial: Map<String, String>,
    onSave: (Map<String, String>) -> Unit,
    onDismiss: () -> Unit
) {
    var rows by remember {
        // 按 key 排序，避免 Map 无序导致每次打开行序跳动
        mutableStateOf(initial.entries.sortedBy { it.key }.map { it.key to it.value }.toMutableList())
    }
    // 允许保存空列表（= 清空全部映射）；仅校验行内非空与前缀不重复
    fun valid(): Boolean =
        rows.all { it.first.isNotBlank() && it.second.isNotBlank() } &&
        rows.map { it.first.trim().uppercase() }.toSet().size == rows.size

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("番号前缀映射") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("每行一条：前缀 = 映射为。例：DANDY = 104DANDY",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
                LazyColumn(modifier = Modifier.heightIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    itemsIndexed(rows) { i, row ->
                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedTextField(
                                value = row.first,
                                onValueChange = { v -> rows = rows.toMutableList().also { it[i] = v.uppercase() to row.second } },
                                label = { Text("前缀", style = MaterialTheme.typography.labelSmall) },
                                singleLine = true, shape = AppRadius.SM,
                                modifier = Modifier.weight(1f)
                            )
                            Text("=", style = MaterialTheme.typography.titleMedium)
                            OutlinedTextField(
                                value = row.second,
                                onValueChange = { v -> rows = rows.toMutableList().also { it[i] = row.first to v.trim() } },
                                label = { Text("映射为", style = MaterialTheme.typography.labelSmall) },
                                singleLine = true, shape = AppRadius.SM,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = { rows = rows.filterIndexed { idx, _ -> idx != i }.toMutableList() },
                                modifier = Modifier.sizeIn(minWidth = MinTouchTarget, minHeight = MinTouchTarget)) { Text("⨯", style = MaterialTheme.typography.labelMedium) }
                        }
                    }
                }
                TextButton(onClick = { rows = rows.toMutableList().also { it.add("" to "") } }) {
                    Text("＋ 添加一条")
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                onSave(rows.associate { it.first.trim().uppercase() to it.second.trim() })
            }, enabled = valid()) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun NumberRecognitionRuleCard(
    title: String,
    subtitle: String,
    values: List<String>,
    onEdit: () -> Unit,
    onImport: (() -> Unit)? = null
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.075f), AppRadius.LG)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (onImport != null) {
                    TextButton(onClick = onImport) { Text("导入", style = MaterialTheme.typography.labelSmall) }
                }
                TextButton(onClick = onEdit) { Text("编辑", style = MaterialTheme.typography.labelSmall) }
            }
        }
        Text(
            text = subtitle,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.56f),
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            text = if (values.isEmpty()) "暂无规则" else values.joinToString("、"),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 180.dp)
                .verticalScroll(rememberScrollState())
        )
    }
}

@Composable
private fun ScrapeTasksPage(
    uiState: SettingsUiState,
    onStartManualScrapeTasks: () -> Unit,
    onStopManualScrapeTasks: () -> Unit,
    onCancelManualScrapeTasks: () -> Unit,
    onRefreshScrapeTasks: () -> Unit,
    onResetFailedScrapeTasks: () -> Unit,
    onStartCloudFolderBatchTasks: () -> Unit,
    onStopCloudFolderBatchTasks: () -> Unit,
    onCancelCloudFolderBatchTasks: () -> Unit,
    onRefreshCloudFolderBatchTasks: () -> Unit,
    onPauseCloudFolderBatchTask: (Long) -> Unit,
    onResumeCloudFolderBatchTask: (Long) -> Unit,
    onCancelCloudFolderBatchTask: (Long) -> Unit,
    onClearCompletedCloudVideoTasks: () -> Unit,
    onStartMetadataRefresh: () -> Unit,
    onCancelMetadataRefresh: () -> Unit
) {
    SettingsSectionTitle("统一刮削任务")
    UnifiedScrapeTaskPanel(
        uiState = uiState,
        onStart = {
            onStartManualScrapeTasks()
            onStartCloudFolderBatchTasks()
        },
        onStop = {
            onStopManualScrapeTasks()
            onStopCloudFolderBatchTasks()
        },
        onCancel = {
            onCancelManualScrapeTasks()
            onCancelCloudFolderBatchTasks()
        },
        onRefresh = {
            onRefreshScrapeTasks()
            onRefreshCloudFolderBatchTasks()
        },
        onResetFailed = onResetFailedScrapeTasks
    )
    SettingsSectionTitle("元数据补全")
    MetadataRefreshPanel(
        uiState = uiState,
        onStart = onStartMetadataRefresh,
        onCancel = onCancelMetadataRefresh
    )
    SettingsSectionTitle("影片任务（${uiState.scrapeIssueMovies.size}）")
    ScrapeIssueList(movies = uiState.scrapeIssueMovies, isRunning = uiState.isManualScrapeRunning)
    SettingsSectionTitle("网盘视频任务（${uiState.cloudVideoTasks.size}）")
    if (uiState.cloudVideoTasks.any { it.status == CloudVideoTaskStatus.Completed.name }) {
        OutlinedButton(
            onClick = onClearCompletedCloudVideoTasks,
            modifier = Modifier.fillMaxWidth(),
            shape = AppRadius.LG
        ) {
            Icon(Icons.Rounded.Close, contentDescription = null)
            Text("清除已完成记录", modifier = Modifier.padding(start = 8.dp))
        }
    }
    CloudVideoTaskList(tasks = uiState.cloudVideoTasks, isRunning = uiState.isCloudVideoTaskRunning)
    SettingsSectionTitle("文件夹发现任务（${uiState.cloudFolderBatchTasks.size}）")
    CloudFolderBatchTaskList(
        tasks = uiState.cloudFolderBatchTasks,
        isRunning = uiState.isCloudFolderBatchRunning,
        onPauseTask = onPauseCloudFolderBatchTask,
        onResumeTask = onResumeCloudFolderBatchTask,
        onCancelTask = onCancelCloudFolderBatchTask
    )
}

/**
 * 元数据补全面板。
 *
 * 为什么单独一块而不是塞进「统一刮削任务」：两者的作用域不同——刮削是把还没入库的
 * 影片补全并写 NFO，补全只回填**已入库且已有 NFO** 的影片的空字段。混在一个面板里，
 * 用户会误以为点「开始」会连带把待刮削影片一起处理。
 */
@Composable
private fun MetadataRefreshPanel(
    uiState: SettingsUiState,
    onStart: () -> Unit,
    onCancel: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.075f), AppRadius.LG)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "为已入库影片补齐缺失的演员 / 厂商 / 类型 / 简介。已存在的字段不会被覆盖，原始文件不会被重写。",
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
            style = MaterialTheme.typography.bodySmall
        )
        if (uiState.isMetadataRefreshing) {
            LinearProgressIndicator(
                progress = { uiState.metadataRefreshProgress / 100f },
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (uiState.metadataRefreshMessage.isNotBlank()) {
            Text(
                text = uiState.metadataRefreshMessage,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
                style = MaterialTheme.typography.bodySmall
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            if (uiState.isMetadataRefreshing) {
                OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f), shape = AppRadius.LG) {
                    Text("停止")
                }
            } else {
                Button(
                    onClick = onStart,
                    modifier = Modifier.weight(1f),
                    shape = AppRadius.LG,
                    colors = taskPrimaryButtonColors()
                ) {
                    Text("开始补全")
                }
            }
        }
    }
}

@Composable
private fun UnifiedScrapeTaskPanel(
    uiState: SettingsUiState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onCancel: () -> Unit,
    onRefresh: () -> Unit,
    onResetFailed: () -> Unit
) {
    val movieSummary = uiState.scrapeTaskSummary
    val cloudVideoUnfinished = uiState.cloudVideoTasks.count { it.status != CloudVideoTaskStatus.Completed.name }
    val folderUnfinished = uiState.cloudFolderBatchTasks.count { it.status != CloudFolderBatchTaskStatus.Completed.name }
    val isRunning = uiState.isManualScrapeRunning || uiState.isCloudVideoTaskRunning || uiState.isCloudFolderBatchRunning
    val unfinished = movieSummary.unfinished + cloudVideoUnfinished + folderUnfinished
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.075f), AppRadius.LG)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("所有来源", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(
                    text = "影片 ${movieSummary.unfinished} · 网盘视频 $cloudVideoUnfinished · 文件夹 $folderUnfinished",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            IconButton(onClick = onRefresh, enabled = !isRunning) {
                Icon(Icons.Rounded.Refresh, contentDescription = "刷新刮削任务", tint = MaterialTheme.colorScheme.onSurface)
            }
        }
        if (isRunning) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Text(
            text = if (isRunning) "正在按统一队列处理任务。" else "所有入口共用同一套并发、暂停和失败恢复机制。",
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
            style = MaterialTheme.typography.bodySmall
        )
        Button(
            onClick = if (isRunning) onStop else onStart,
            modifier = Modifier.fillMaxWidth(),
            shape = AppRadius.LG,
            colors = taskPrimaryButtonColors()
        ) {
            Icon(if (isRunning) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, contentDescription = null)
            Text(if (isRunning) "暂停所有刮削任务" else "开始/继续所有刮削任务", modifier = Modifier.padding(start = 8.dp))
        }
        Button(
            onClick = onCancel,
            enabled = unfinished > 0,
            modifier = Modifier.fillMaxWidth(),
            shape = AppRadius.LG,
            colors = taskCancelButtonColors()
        ) {
            Icon(Icons.Rounded.Close, contentDescription = null)
            Text("取消所有未完成任务", modifier = Modifier.padding(start = 8.dp))
        }
        Button(
            onClick = onResetFailed,
            enabled = movieSummary.failed > 0 && !isRunning,
            modifier = Modifier.fillMaxWidth(),
            shape = AppRadius.LG,
            colors = manualTaskSecondaryButtonColors()
        ) {
            Icon(Icons.Rounded.Refresh, contentDescription = null)
            Text("重置失败的影片任务", modifier = Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
private fun CustomJsonScrapeEntryCard(
    config: CustomJsonScrapeConfig,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.075f), AppRadius.LG)
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = config.name.ifBlank { "自定义 JSON 来源" },
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = if (config.enabled) "已启用" else "未启用",
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (config.enabled) 0.9f else 0.46f),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold
            )
        }
        Text(
            text = config.urlTemplate.ifBlank { "未配置请求地址" },
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun CustomJsonScrapePage(
    uiState: SettingsUiState,
    onConfigChange: (CustomJsonScrapeConfig) -> Unit,
    onSelectConfig: (Int) -> Unit,
    onAddConfig: () -> Unit,
    onDuplicateConfig: () -> Unit,
    onDeleteConfig: () -> Unit,
    onTestConfig: () -> Unit
) {
    SettingsSectionTitle("自定义 JSON 刮削")
    CustomJsonScrapePanel(
        config = uiState.customJsonScrapeConfig,
        configs = uiState.customJsonScrapeConfigs,
        selectedConfigIndex = uiState.selectedCustomJsonScrapeConfigIndex,
        isTesting = uiState.isTestingCustomJsonScrape,
        pathCandidates = uiState.customJsonPathCandidates,
        onChange = onConfigChange,
        onSelectConfig = onSelectConfig,
        onAddConfig = onAddConfig,
        onDuplicateConfig = onDuplicateConfig,
        onDeleteConfig = onDeleteConfig,
        onTest = onTestConfig
    )
}

@Composable
private fun CustomJsonScrapePanel(
    config: CustomJsonScrapeConfig,
    configs: List<CustomJsonScrapeConfig>,
    selectedConfigIndex: Int,
    isTesting: Boolean,
    pathCandidates: List<CustomJsonPathCandidate>,
    onChange: (CustomJsonScrapeConfig) -> Unit,
    onSelectConfig: (Int) -> Unit,
    onAddConfig: () -> Unit,
    onDuplicateConfig: () -> Unit,
    onDeleteConfig: () -> Unit,
    onTest: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.075f), AppRadius.LG)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "自定义 JSON 来源",
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "先填 URL 和结果路径，点测试后可从样本 JSON 中选择字段。候选只来自当前结果路径。",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Switch(
                checked = config.enabled,
                onCheckedChange = { onChange(config.copy(enabled = it)) }
            )
        }
        CustomJsonConfigSelector(
            configs = configs,
            selectedConfigIndex = selectedConfigIndex,
            onSelectConfig = onSelectConfig,
            onAddConfig = onAddConfig,
            onDuplicateConfig = onDuplicateConfig,
            onDeleteConfig = onDeleteConfig,
        )
        CustomJsonTextField("来源名称", config.name) { onChange(config.copy(name = it)) }
        CustomJsonTextField("请求地址", config.urlTemplate, "例如：https://example.com/api/movie?q={number}") {
            onChange(config.copy(urlTemplate = it))
        }
        CustomJsonTextField("示例番号", config.sampleNumber) { onChange(config.copy(sampleNumber = it)) }
        CustomJsonTextField("结果路径", config.resultPath, "例如：$.data 或 $.results[0]") {
            onChange(config.copy(resultPath = it))
        }
        CustomJsonTextField(
            "后处理规则（可选）",
            config.postProcessSpec,
            "叠加在内置清洗之后，例如：replace /\\[中文字幕\\]// | trim"
        ) {
            onChange(config.copy(postProcessSpec = it))
        }
        CustomJsonMappingSummary(config = config, pathCandidates = pathCandidates)
        if (pathCandidates.isNotEmpty()) {
            Text(
                text = "已生成 ${pathCandidates.size} 个候选路径。需要调整字段时请重新点击测试并配置。",
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
                style = MaterialTheme.typography.bodySmall
            )
        }
        Button(
            onClick = onTest,
            enabled = !isTesting,
            modifier = Modifier.fillMaxWidth(),
            shape = AppRadius.LG,
            colors = taskPrimaryButtonColors()
        ) {
            if (isTesting) {
                CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onSurface)
            }
            Text(if (isTesting) "正在测试..." else "测试并生成字段候选")
        }
    }
}

@Composable
private fun CustomJsonConfigSelector(
    configs: List<CustomJsonScrapeConfig>,
    selectedConfigIndex: Int,
    onSelectConfig: (Int) -> Unit,
    onAddConfig: () -> Unit,
    onDuplicateConfig: () -> Unit,
    onDeleteConfig: () -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val selectedConfig = configs.getOrNull(selectedConfigIndex)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.055f), AppRadius.MD)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("配置方案", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth(),
                shape = AppRadius.LG,
            ) {
                Text(selectedConfig?.name?.ifBlank { "未命名配置" } ?: "未命名配置", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.heightIn(max = 360.dp),
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                configs.forEachIndexed { index, item ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(item.name.ifBlank { "未命名配置" }, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
                                Text(
                                    item.urlTemplate.ifBlank { "未配置请求地址" },
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        },
                        onClick = {
                            expanded = false
                            onSelectConfig(index)
                        }
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = onAddConfig, modifier = Modifier.weight(1f), shape = AppRadius.LG) {
                Text("新增")
            }
            OutlinedButton(onClick = onDuplicateConfig, modifier = Modifier.weight(1f), shape = AppRadius.LG) {
                Text("复制")
            }
            OutlinedButton(onClick = onDeleteConfig, modifier = Modifier.weight(1f), shape = AppRadius.LG) {
                Text("删除")
            }
        }
    }
}

@Composable
private fun CustomJsonMappingSummary(
    config: CustomJsonScrapeConfig,
    pathCandidates: List<CustomJsonPathCandidate>,
) {
    val targets = customJsonMappingTargets(config)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.055f), AppRadius.MD)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text("字段映射", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
        targets.chunked(2).forEach { rowTargets ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                rowTargets.forEach { target ->
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.055f), AppRadius.SM)
                            .padding(8.dp),
                    ) {
                        Text(target.label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                        Text(
                            target.value.ifBlank { "未选择" },
                            color = if (target.value.isBlank()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val sampleValue = pathCandidates.sampleValue(target.value)
                        Text(
                            sampleValue.ifBlank { if (target.value.isBlank()) "未映射" else "样本未匹配" },
                            // 未匹配到样本时降低强调（仍满足 WCAG AA：ContentAlphaMin=0.52 → 5.25:1）
                            color = if (sampleValue.isBlank()) {
                                MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlphaMin)
                            } else {
                                MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlphaSecondary)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (rowTargets.size == 1) {
                    Box(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun CustomJsonTextField(
    label: String,
    value: String,
    supportingText: String? = null,
    modifier: Modifier = Modifier.fillMaxWidth(),
    onChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = modifier,
        singleLine = true,
        shape = AppRadius.LG,
        label = { Text(label) },
        supportingText = supportingText?.let { text -> { Text(text) } },
        colors = settingsTextFieldColors()
    )
}

@Composable
private fun MgstagePrefixPanel(
    customPrefixes: Map<String, String>,
    mergedPrefixes: Map<String, String>,
    draft: String,
    numericDraft: String,
    amateurPriorityEnabled: Boolean,
    onDraftChange: (String) -> Unit,
    onNumericDraftChange: (String) -> Unit,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit,
    onAmateurPriorityChange: (Boolean) -> Unit
) {
    var showManageDialog by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.075f), AppRadius.LG)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "番号前缀规则",
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (mergedPrefixes.isEmpty()) {
                        "未设置 MGStage 前缀。"
                    } else {
                        "本地 ${customPrefixes.size} · 合并 ${mergedPrefixes.size} 个：${mergedPrefixes.entries.joinToString("、") { it.toMgstageRuleLabel() }}"
                    },
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            OutlinedButton(
                onClick = { showManageDialog = true },
                shape = AppRadius.LG,
                modifier = Modifier.padding(start = 8.dp)
            ) {
                Text("管理")
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("MGS素人优先", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                Text(
                    text = "开启后，命中 MGS 前缀规则的影片会优先尝试 MGStage；关闭则严格按刮削源优先级执行。",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.52f),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Switch(checked = amateurPriorityEnabled, onCheckedChange = onAmateurPriorityChange)
        }
    }

    if (showManageDialog) {
        AlertDialog(
            onDismissRequest = { showManageDialog = false },
            title = { Text("MGStage 番号规则") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = onDraftChange,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = AppRadius.LG,
                        label = { Text("标准番号前缀") },
                        placeholder = { Text("例如 MIUM、SCUTE") }
                    )
                    OutlinedTextField(
                        value = numericDraft,
                        onValueChange = onNumericDraftChange,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = AppRadius.LG,
                        label = { Text("MGStage 搜索时附加的数字") },
                        placeholder = { Text("例如 MIUM 填 300，SCUTE 填 229") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    Button(
                        onClick = onAdd,
                        modifier = Modifier.fillMaxWidth(),
                        shape = AppRadius.LG
                    ) {
                        Text("添加映射")
                    }
                    MgstagePrefixList(
                        title = "本地自定义",
                        prefixes = customPrefixes,
                        emptyText = "暂无本地前缀",
                        onRemove = onRemove
                    )
                    Text(
                        text = "最终合并 ${mergedPrefixes.size} 个前缀。",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showManageDialog = false }) {
                    Text("完成")
                }
            }
        )
    }
}

@Composable
private fun MgstagePrefixList(
    title: String,
    prefixes: Map<String, String>,
    emptyText: String,
    onRemove: ((String) -> Unit)?
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 160.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (prefixes.isEmpty()) {
                Text(emptyText)
            } else {
                prefixes.forEach { (prefix, numericPrefix) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.Black.copy(alpha = 0.06f), AppRadius.MD)
                            .padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "$prefix → ${numericPrefix.ifBlank { "无需附加" }}",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (onRemove != null) {
                            TextButton(onClick = { onRemove(prefix) }) {
                                Text("删除")
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun Map.Entry<String, String>.toMgstageRuleLabel(): String =
    "$key→${value.ifBlank { "无" }}"

@Composable
private fun NumberPrefixRewriteRulePanel(
    rules: List<NumberPrefixRewriteRule>,
    prefixDraft: String,
    numericPrefixDraft: String,
    selectedSources: Set<ScrapeSource>,
    onPrefixDraftChange: (String) -> Unit,
    onNumericPrefixDraftChange: (String) -> Unit,
    onSourcesChange: (Set<ScrapeSource>) -> Unit,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit
) {
    var showManageDialog by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.075f), AppRadius.LG)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("按刮削源补全数字前缀", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(
                    text = if (rules.isEmpty()) "未设置。命中后会用完整番号命名影片。" else "已设置 ${rules.size} 条：${rules.joinToString("、") { "${it.prefix}→${it.rewrittenPrefix}" }}",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            OutlinedButton(onClick = { showManageDialog = true }, shape = AppRadius.LG) {
                Text("管理")
            }
        }
        Text(
            text = "例如 DSVR + 3：仅对勾选来源以 3DSVR-1944 查询；无论最终由哪个来源成功，目录、STRM 和 NFO 都使用 3DSVR-1944。",
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.52f),
            style = MaterialTheme.typography.bodySmall
        )
    }
    if (showManageDialog) {
        AlertDialog(
            onDismissRequest = { showManageDialog = false },
            title = { Text("数字前缀规则") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = prefixDraft,
                        onValueChange = onPrefixDraftChange,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("标准番号前缀") },
                        placeholder = { Text("例如 DSVR") },
                        colors = settingsTextFieldColors()
                    )
                    OutlinedTextField(
                        value = numericPrefixDraft,
                        onValueChange = onNumericPrefixDraftChange,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("附加数字") },
                        placeholder = { Text("例如 3") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = settingsTextFieldColors()
                    )
                    Text("应用刮削源", style = MaterialTheme.typography.titleSmall)
                    ScrapeSource.entries.filter { it != ScrapeSource.Priority }.forEach { source ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onSourcesChange(
                                        if (source in selectedSources) selectedSources - source else selectedSources + source
                                    )
                                },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = source in selectedSources,
                                onCheckedChange = { checked ->
                                    onSourcesChange(if (checked) selectedSources + source else selectedSources - source)
                                }
                            )
                            Text(source.label)
                        }
                    }
                    Button(onClick = onAdd, modifier = Modifier.fillMaxWidth(), shape = AppRadius.LG) {
                        Text("添加或覆盖规则")
                    }
                    if (rules.isEmpty()) {
                        Text("暂无规则")
                    } else {
                        rules.forEach { rule ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "${rule.prefix} → ${rule.rewrittenPrefix}（${rule.sources.joinToString("、") { it.label }}）",
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodySmall
                                )
                                TextButton(onClick = { onRemove(rule.prefix) }) { Text("删除") }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showManageDialog = false }) { Text("完成") } }
        )
    }
}

@Composable
private fun CloudVideoTaskList(
    tasks: List<CloudVideoTaskEntity>,
    isRunning: Boolean
) {
    if (tasks.isEmpty()) {
        Text(
            text = "暂无单视频添加任务。点击网盘影片的添加按钮后会立即持久化到这里。",
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
            style = MaterialTheme.typography.bodyMedium
        )
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(items = tasks, key = CloudVideoTaskEntity::pickcode) { task ->
            val status = runCatching { CloudVideoTaskStatus.valueOf(task.status) }
                .getOrDefault(CloudVideoTaskStatus.Pending)
            val statusText = when (status) {
                CloudVideoTaskStatus.Pending -> "等待中"
                CloudVideoTaskStatus.Running -> if (isRunning) "处理中" else "处理中断"
                CloudVideoTaskStatus.Paused -> "已暂停"
                CloudVideoTaskStatus.Completed -> "已完成"
                CloudVideoTaskStatus.Failed -> "失败"
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.075f), AppRadius.MD)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = task.fileName,
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = statusText,
                        color = when (status) {
                            CloudVideoTaskStatus.Failed -> MaterialTheme.colorScheme.error
                            CloudVideoTaskStatus.Running -> MaterialTheme.colorScheme.info
                            CloudVideoTaskStatus.Pending, CloudVideoTaskStatus.Paused -> MaterialTheme.colorScheme.warning
                            CloudVideoTaskStatus.Completed -> MaterialTheme.colorScheme.primary
                        },
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 10.dp)
                    )
                }
                task.failureReason?.takeIf(String::isNotBlank)?.let { reason ->
                    Text(
                        text = reason,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

@Composable
private fun ScrapeIssueList(
    movies: List<MovieEntity>,
    isRunning: Boolean
) {
    if (movies.isEmpty()) {
        Text(
            text = "暂无刮削失败或未刮削的文件。",
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
            style = MaterialTheme.typography.bodyMedium
        )
        return
    }
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 520.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(items = movies, key = MovieEntity::id) { movie ->
            ScrapeIssueCard(movie = movie, isRunning = isRunning)
        }
    }
}

@Composable
private fun ScrapeIssueCard(movie: MovieEntity, isRunning: Boolean) {
    val status = runCatching { ScrapeTaskStatus.valueOf(movie.scrapeTaskStatus) }
        .getOrDefault(ScrapeTaskStatus.None)
    val statusText = when (status) {
        ScrapeTaskStatus.Failed -> "刮削失败"
        ScrapeTaskStatus.Running -> if (isRunning) "正在刮削" else "处理中断"
        ScrapeTaskStatus.Pending -> "待刮削"
        ScrapeTaskStatus.Completed -> "缺少 NFO"
        ScrapeTaskStatus.None -> "未刮削"
    }
    val statusColor = when (status) {
        ScrapeTaskStatus.Failed -> MaterialTheme.colorScheme.error
        ScrapeTaskStatus.Running -> MaterialTheme.colorScheme.info
        ScrapeTaskStatus.Pending -> MaterialTheme.colorScheme.warning
        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.075f), AppRadius.MD)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = movie.videoName.ifBlank { movie.title },
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = statusText,
                color = statusColor,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 10.dp)
            )
        }
        if (movie.title.isNotBlank() && movie.title != movie.videoName) {
            Text(
                text = movie.title,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        movie.scrapeFailureReason?.takeIf(String::isNotBlank)?.let { reason ->
            Text(
                text = reason,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun CloudFolderBatchTaskList(
    tasks: List<CloudFolderBatchTaskEntity>,
    isRunning: Boolean,
    onPauseTask: (Long) -> Unit,
    onResumeTask: (Long) -> Unit,
    onCancelTask: (Long) -> Unit
) {
    // ★ 2026-10-30 修正：原来只留「未完成」，而「整夹跑完但有刮削失败」的任务状态是
    // Completed（完成判定已改成「候选处理完就算结束」），于是列表直接变空、提示
    // 「暂无未完成的文件夹发现任务」——用户既看不到这一批跑了多少，也没有任何可点的
    // 按钮，反馈就是「查看不到具体进度，也无法操作暂停或继续」。
    // 现在：未完成的全部保留，另加最近 [RECENT_COMPLETED_FOLDER_TASKS] 条已完成做留痕。
    val activeTasks = tasks.filter { it.status != CloudFolderBatchTaskStatus.Completed.name }
    val recentCompleted = tasks
        .filter { it.status == CloudFolderBatchTaskStatus.Completed.name }
        .sortedByDescending { it.updatedAt }
        .take(RECENT_COMPLETED_FOLDER_TASKS)
    if (activeTasks.isEmpty() && recentCompleted.isEmpty()) {
        Text(
            text = "暂无文件夹发现任务。整夹添加后会在这里显示进度，可在设置页随时回来查看。",
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
            style = MaterialTheme.typography.bodyMedium
        )
        return
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 320.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        CloudFolderBatchSummary(activeTasks = activeTasks, isRunning = isRunning)
        activeTasks.forEach { task ->
            CloudFolderBatchTaskRow(
                task = task,
                runnerRunning = isRunning,
                onPause = { onPauseTask(task.id) },
                onResume = { onResumeTask(task.id) },
                onCancel = { onCancelTask(task.id) }
            )
        }
        if (recentCompleted.isNotEmpty()) {
            Text(
                text = "最近完成",
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold
            )
            recentCompleted.forEach { task ->
                CloudFolderBatchTaskRow(
                    task = task,
                    runnerRunning = isRunning,
                    onPause = { onPauseTask(task.id) },
                    onResume = { onResumeTask(task.id) },
                    onCancel = { onCancelTask(task.id) }
                )
            }
        }
    }
}

/** 设置页「文件夹发现任务」里保留多少条已完成任务做留痕（未完成的全部保留）。 */
private const val RECENT_COMPLETED_FOLDER_TASKS = 3

/**
 * 文件夹批量的**整体**进度。
 *
 * 单条任务各自有进度条，但用户加的是「一整个网盘文件夹」，关心的是「这一批到底跑了多少」。
 * 这里把尚未完成的每条任务的分母/分子累加起来给一个总览，并且只统计 `queuedVideos > 0`
 * 的任务——还在收集候选（分母未知）的那条算进来会把百分比算错。
 */
@Composable
private fun CloudFolderBatchSummary(
    activeTasks: List<CloudFolderBatchTaskEntity>,
    isRunning: Boolean
) {
    val countable = activeTasks.filter { it.status != CloudFolderBatchTaskStatus.Cancelled.name }
    val aggregate = countable.folderBatchAggregateProgress()
    val totalAdded = countable.sumOf { it.addedVideos.toLong() }
    val totalQueued = countable.sumOf { it.queuedVideos.toLong() }
    val totalProcessed = countable.sumOf { it.processedVideos.coerceAtMost(it.queuedVideos).toLong() }
    val runningCount = countable.count {
        it.status == CloudFolderBatchTaskStatus.Running.name ||
            it.status == CloudFolderBatchTaskStatus.Pending.name
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f), AppRadius.MD)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = when (aggregate) {
                is FolderBatchProgress.Determinate ->
                    "整体进度 ${aggregate.completed}/${aggregate.total}（${(aggregate.ratio * 100).toInt()}%）"
                is FolderBatchProgress.Indeterminate -> "整体进度：${aggregate.label}"
            },
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold
        )
        when (aggregate) {
            is FolderBatchProgress.Determinate -> LinearProgressIndicator(
                progress = { aggregate.ratio },
                modifier = Modifier.fillMaxWidth()
            )
            is FolderBatchProgress.Indeterminate -> LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth()
            )
        }
        Text(
            text = if (totalQueued > 0) {
                "任务 ${activeTasks.size} 个（在跑/排队 $runningCount）· 已入库 $totalAdded · 已处理 $totalProcessed 部"
            } else {
                "任务 ${activeTasks.size} 个（在跑/排队 $runningCount）· 已入库 $totalAdded"
            },
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun CloudFolderBatchTaskRow(
    task: CloudFolderBatchTaskEntity,
    runnerRunning: Boolean,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit
) {
    val rowProgress = task.folderBatchProgress()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.10f), AppRadius.MD)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = task.folderName,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = task.statusLabel(runnerRunning),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.66f),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1
            )
        }
        when (rowProgress) {
            is FolderBatchProgress.Determinate -> LinearProgressIndicator(
                progress = { rowProgress.ratio },
                modifier = Modifier.fillMaxWidth()
            )
            is FolderBatchProgress.Indeterminate -> LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth()
            )
        }
        Text(
            text = when (rowProgress) {
                is FolderBatchProgress.Determinate ->
                    "进度 ${rowProgress.completed}/${rowProgress.total}"
                is FolderBatchProgress.Indeterminate -> rowProgress.label
            },
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        val currentText = listOfNotNull(
            task.currentPath?.takeIf { it.isNotBlank() },
            task.currentFileName?.takeIf { it.isNotBlank() }
        ).joinToString(" / ")
        if (currentText.isNotBlank()) {
            Text(
                text = "当前：$currentText",
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            text = "入库 ${task.addedVideos} · 跳过 ${task.skippedVideos} · 刮削失败 ${task.scrapeFailedVideos} · 失败 ${task.failedVideos + task.failedFolders}",
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.54f),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        task.failureMessage
            ?.takeIf { it.isNotBlank() }
            ?.let { message ->
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlphaMin),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        // 单条任务的操作按钮。
        //
        // 为什么要按状态分岔而不是「三个按钮常驻」：暂停对已取消的任务没有意义，继续对正在跑的
        // 任务也没有意义。常驻+禁用也能做，但那会让每一行都长出一排灰按钮，用户反而看不出
        // 「现在能做什么」。这里按状态只给有效动作。
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val canPause = task.status == CloudFolderBatchTaskStatus.Pending.name ||
                (task.status == CloudFolderBatchTaskStatus.Running.name && runnerRunning)
            val canResume = task.status == CloudFolderBatchTaskStatus.Paused.name ||
                task.status == CloudFolderBatchTaskStatus.Cancelled.name ||
                task.status == CloudFolderBatchTaskStatus.Failed.name ||
                (task.status == CloudFolderBatchTaskStatus.Running.name && !runnerRunning)
            // 已完成的任务不该再出现「取消」：它已经跑完，取消按钮只会误导。
            val canCancel = task.status != CloudFolderBatchTaskStatus.Cancelled.name &&
                task.status != CloudFolderBatchTaskStatus.Completed.name
            if (canPause) {
                OutlinedButton(
                    onClick = onPause,
                    modifier = Modifier.weight(1f),
                    shape = AppRadius.LG,
                    contentPadding = ButtonDefaults.ContentPadding
                ) {
                    Text("暂停", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (canResume) {
                OutlinedButton(
                    onClick = onResume,
                    modifier = Modifier.weight(1f),
                    shape = AppRadius.LG,
                    contentPadding = ButtonDefaults.ContentPadding
                ) {
                    Text("继续", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (canCancel) {
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.weight(1f),
                    shape = AppRadius.LG,
                    contentPadding = ButtonDefaults.ContentPadding,
                    colors = manualTaskSecondaryButtonColors()
                ) {
                    Text("取消", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

private fun CloudFolderBatchTaskEntity.statusLabel(runnerRunning: Boolean): String =
    when (status) {
        CloudFolderBatchTaskStatus.Pending.name -> "待执行"
        CloudFolderBatchTaskStatus.Running.name -> if (runnerRunning) "运行中" else "中断待继续"
        CloudFolderBatchTaskStatus.Paused.name -> "已暂停"
        CloudFolderBatchTaskStatus.Cancelled.name -> "已取消"
        CloudFolderBatchTaskStatus.Completed.name -> "已完成"
        CloudFolderBatchTaskStatus.Failed.name -> "失败"
        else -> status
    }

@Composable
private fun manualTaskSecondaryButtonColors() = ButtonDefaults.buttonColors(
    containerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f),
    contentColor = MaterialTheme.colorScheme.onSurface,
    disabledContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
    disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.42f)
)

@Composable
private fun taskPrimaryButtonColors() = ButtonDefaults.buttonColors(
    containerColor = MaterialTheme.colorScheme.primary,
    contentColor = MaterialTheme.colorScheme.onPrimary,
    disabledContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
    disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f)
)

internal data class LibraryScanButtonPalette(
    val containerColor: Color,
    val contentColor: Color,
    val disabledContainerColor: Color,
    val disabledContentColor: Color
)

/**
 * 库扫描按钮配色。
 *
 * 颜色由调用方传入当前主题（@Composable 侧传 [MaterialTheme.colorScheme]），因此本函数
 * 是**纯函数**：JVM 单元测试可以直接校验 alpha 派生规则，不需要 Compose 运行时。
 * 写成 @Composable 读 MaterialTheme 会让测试无法在不启动 Compose 的情况下校验这条规则
 * ——禁用态配色曾经因此退化成「白字压浅底」，到这里才被回归测试抓住。
 */
internal fun libraryScanButtonPalette(scheme: ColorScheme) = LibraryScanButtonPalette(
    containerColor = scheme.primary,
    contentColor = scheme.onPrimary,
    disabledContainerColor = scheme.onSurface.copy(alpha = 0.55f),
    disabledContentColor = scheme.onSurface.copy(alpha = 0.62f)
)

@Composable
private fun taskCancelButtonColors() = ButtonDefaults.buttonColors(
    containerColor = MaterialTheme.colorScheme.errorContainer,
    contentColor = MaterialTheme.colorScheme.onSurface,
    disabledContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f),
    disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.42f)
)

@Composable
private fun PriorityScrapeSourcePanel(
    sources: List<ScrapeSource>,
    options: List<ScrapeSource>,
    onAdd: (ScrapeSource) -> Unit,
    onRemove: (ScrapeSource) -> Unit,
    onMoveUp: (ScrapeSource) -> Unit,
    onMoveDown: (ScrapeSource) -> Unit
) {
    var showManageDialog by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.075f), AppRadius.LG)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "优先级刮削顺序",
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = sources.joinToString(" -> ") { it.label },
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            OutlinedButton(
                onClick = { showManageDialog = true },
                shape = AppRadius.LG
            ) {
                Text("管理")
            }
        }
        Text(
            text = "默认刮削会按这里的顺序依次尝试，成功一个就停止。手动指定 TheJavDB、JavBus 等来源时不受这个顺序影响。",
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.52f),
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            text = "DMM、DMM2、TheJavDB刮削都需要日本节点",
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall
        )
    }

    if (showManageDialog) {
        var addMenuExpanded by remember { mutableStateOf(false) }
        val available = options.filter { it !in sources }
        AlertDialog(
            onDismissRequest = { showManageDialog = false },
            title = { Text("优先级刮削顺序") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("例如 DMM2 -> TheJavDB -> JavBus，前一个失败后会自动尝试下一个。")
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        sources.forEachIndexed { index, source ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color.Black.copy(alpha = 0.06f), AppRadius.MD)
                                    .padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${index + 1}. ${source.label}",
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                TextButton(
                                    enabled = index > 0,
                                    onClick = { onMoveUp(source) }
                                ) {
                                    Text("上移")
                                }
                                TextButton(
                                    enabled = index < sources.lastIndex,
                                    onClick = { onMoveDown(source) }
                                ) {
                                    Text("下移")
                                }
                                TextButton(
                                    enabled = sources.size > 1,
                                    onClick = { onRemove(source) }
                                ) {
                                    Text("删除")
                                }
                            }
                        }
                    }
                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = { addMenuExpanded = true },
                            enabled = available.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth(),
                            shape = AppRadius.LG
                        ) {
                            Text(
                                text = if (available.isEmpty()) "所有可用来源都已加入" else "添加来源",
                                modifier = Modifier.weight(1f)
                            )
                            Text("选择")
                        }
                        DropdownMenu(
                            expanded = addMenuExpanded,
                            onDismissRequest = { addMenuExpanded = false }
                        ) {
                            available.forEach { source ->
                                DropdownMenuItem(
                                    text = { Text(source.label) },
                                    onClick = {
                                        addMenuExpanded = false
                                        onAdd(source)
                                    }
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showManageDialog = false }) {
                    Text("完成")
                }
            }
        )
    }
}

@Composable
private fun Dmm2SkippedPrefixPanel(
    prefixes: List<String>,
    draft: String,
    onDraftChange: (String) -> Unit,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit
) {
    var showManageDialog by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.075f), AppRadius.LG)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "跳过番号开头",
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (prefixes.isEmpty()) {
                        "未设置。只影响 DMM2 刮削方式。"
                    } else {
                        "已设置 ${prefixes.size} 个：${prefixes.joinToString("、")}"
                    },
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            OutlinedButton(
                onClick = { showManageDialog = true },
                shape = AppRadius.LG
            ) {
                Text("管理")
            }
        }
    }

    if (showManageDialog) {
        AlertDialog(
            onDismissRequest = { showManageDialog = false },
            title = { Text("DMM2 跳过番号开头") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("例如添加 ABF 后，DMM2 遇到 ABF-123 会直接跳过。")
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = draft,
                            onValueChange = onDraftChange,
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            shape = AppRadius.LG,
                            label = { Text("番号开头") },
                            placeholder = { Text("例如 ABF") }
                        )
                        Button(
                            onClick = onAdd,
                            shape = AppRadius.LG
                        ) {
                            Text("添加")
                        }
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (prefixes.isEmpty()) {
                            Text("暂无跳过前缀")
                        } else {
                            prefixes.forEach { prefix ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(Color.Black.copy(alpha = 0.06f), AppRadius.MD)
                                        .padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = prefix,
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    TextButton(onClick = { onRemove(prefix) }) {
                                        Text("删除")
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showManageDialog = false }) {
                    Text("完成")
                }
            }
        )
    }
}

@Composable
private fun SettingsGroupCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SettingsSectionTitle(title)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f), AppRadius.LG)
                .padding(vertical = 4.dp),
            content = content
        )
    }
}

@Composable
private fun SettingsEntryRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = title,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = subtitle,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = subtitle,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ClearImageCacheDialog(
    sizeText: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("清理图片缓存") },
        text = { Text("当前图片缓存约 $sizeText。确认清理后，海报和缩略图会在下次显示时重新缓存。") },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("确认清理")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@Composable
private fun Cloud115QrLoginPanel(
    uiState: SettingsUiState,
    onCloud115AppSelected: (Cloud115LoginApp) -> Unit,
    onSavedCloud115AccountSelected: (SavedCloud115Account) -> Unit,
    onSavedCloud115AccountDelete: (SavedCloud115Account) -> Unit,
    onRefreshSavedCloud115Accounts: () -> Unit,
    onStartCloud115QrLogin: () -> Unit,
    onCancelCloud115QrLogin: () -> Unit
) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.075f), AppRadius.LG)
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
                    text = "115 二维码登录",
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "扫码成功后会自动保存 Cookie，并写入文件 115cookie_userId_app.txt。",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        SavedCloud115AccountSelector(
            accounts = uiState.savedCloud115Accounts,
            selectedFileName = uiState.selectedCloud115AccountFileName,
            onSelected = onSavedCloud115AccountSelected,
            onDelete = onSavedCloud115AccountDelete,
            onRefresh = onRefreshSavedCloud115Accounts
        )
        Box(modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { expanded = true },
                enabled = !uiState.isCloud115QrLoginActive,
                modifier = Modifier.fillMaxWidth(),
                shape = AppRadius.LG
            ) {
                Text(
                    text = uiState.selectedCloud115LoginApp.description,
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(text = "登录方式", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f))
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                Cloud115LoginApps.all.forEach { app ->
                    DropdownMenuItem(
                        text = { Text(app.description) },
                        onClick = {
                            expanded = false
                            onCloud115AppSelected(app)
                        }
                    )
                }
            }
        }
        if (uiState.cloud115QrToken != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 220.dp, max = 260.dp)
                    .background(MaterialTheme.colorScheme.onSurface, AppRadius.MD)
                    .padding(12.dp),
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(uiState.cloud115QrToken.qrImageUrl)
                        .crossfade(true)
                        .build(),
                    contentDescription = "115 登录二维码",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            }
            Text(
                text = "二维码地址：${uiState.cloud115QrToken.qrImageUrl}",
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlphaMin),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (uiState.cloud115QrStatusText.isNotBlank()) {
            Text(
                text = uiState.cloud115QrStatusText,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
                style = MaterialTheme.typography.bodySmall
            )
        }
        uiState.cloud115QrSavedFile?.let { path ->
            Text(
                text = "保存位置：$path",
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlphaMin),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = onStartCloud115QrLogin,
                enabled = !uiState.isCloud115QrLoginActive,
                modifier = Modifier.weight(1f),
                shape = AppRadius.LG
            ) {
                Text(if (uiState.cloud115QrToken == null) "获取二维码" else "重新获取")
            }
            if (uiState.isCloud115QrLoginActive) {
                OutlinedButton(
                    onClick = onCancelCloud115QrLogin,
                    modifier = Modifier.weight(1f),
                    shape = AppRadius.LG
                ) {
                    Text("取消登录")
                }
            }
        }
    }
}

@Composable
private fun SavedCloud115AccountSelector(
    accounts: List<SavedCloud115Account>,
    selectedFileName: String?,
    onSelected: (SavedCloud115Account) -> Unit,
    onDelete: (SavedCloud115Account) -> Unit,
    onRefresh: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = accounts.firstOrNull { it.fileName == selectedFileName }
    var pending by remember(accounts, selectedFileName) { mutableStateOf(selected) }
    var deleteTarget by remember { mutableStateOf<SavedCloud115Account?>(null) }
    deleteTarget?.let { account ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("删除 115 账号？", color = MaterialTheme.colorScheme.onSurface) },
            text = {
                Text(
                    text = "确定删除账号「${account.displayName}」的本地 Cookie 文件吗？删除后不会影响 115 网盘真实账号。",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteTarget = null
                        if (pending?.fileName == account.fileName) pending = null
                        onDelete(account)
                    }
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text("取消")
                }
            }
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(modifier = Modifier.weight(1f)) {
                OutlinedButton(
                    onClick = {
                        onRefresh()
                        expanded = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = AppRadius.LG
                ) {
                    Text(
                        text = selected?.displayName
                            ?: pending?.displayName
                            ?: if (accounts.isEmpty()) "暂无已保存账号" else "选择已保存账号",
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(text = "账号", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f))
                }
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    if (accounts.isEmpty()) {
                        DropdownMenuItem(
                            text = { Text("没有找到已保存 Cookie 文件") },
                            onClick = { expanded = false }
                        )
                    } else {
                        accounts.forEach { account ->
                            DropdownMenuItem(
                                text = { Text(account.displayName) },
                                onClick = {
                                    expanded = false
                                    pending = account
                                }
                            )
                        }
                    }
                }
            }
            OutlinedButton(
                onClick = onRefresh,
                shape = AppRadius.LG
            ) {
                Text("刷新")
            }
            OutlinedButton(
                onClick = { pending?.let { deleteTarget = it } },
                enabled = pending != null,
                shape = AppRadius.LG
            ) {
                Text("删除")
            }
        }
        Button(
            onClick = { pending?.let(onSelected) },
            enabled = pending != null && pending?.fileName != selectedFileName,
            modifier = Modifier.fillMaxWidth(),
            shape = AppRadius.LG
        ) {
            Text(if (pending == null) "请选择账号" else "确定使用 ${pending?.displayName.orEmpty()}")
        }
        Text(
            text = "可识别 115cookie_a_ios.txt、115cookie_b_os_linux.txt 这类文件，并切换为当前网盘 Cookie。",
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun ImageCachePanel(
    sizeText: String,
    onRefresh: () -> Unit,
    onClear: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.075f), AppRadius.LG)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "图片缓存",
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = sizeText,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            OutlinedButton(onClick = onRefresh, shape = AppRadius.LG) {
                Text("刷新")
            }
        }
        OutlinedButton(
            onClick = onClear,
            modifier = Modifier.fillMaxWidth(),
            shape = AppRadius.LG
        ) {
            Icon(Icons.Rounded.DeleteSweep, contentDescription = null)
            Text("清理图片缓存", modifier = Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
private fun SettingsTopBar(title: String = "设置", onBack: (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.background)))
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(start = 14.dp, end = 16.dp, top = 6.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回设置", tint = MaterialTheme.colorScheme.onSurface)
            }
        } else {
            Icon(Icons.Rounded.Settings, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface)
        }
        Text(
            text = title,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.padding(start = 10.dp)
        )
    }
}

@Composable
private fun DirectorySummary(title: String, selected: Boolean, emptyText: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.075f), AppRadius.LG)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = if (selected) title else emptyText,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = if (selected) "已选择" else "未配置",
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlphaMin),
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun SettingsSectionTitle(text: String) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.onSurface,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold
    )
}

@Composable
internal fun settingsTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = MaterialTheme.colorScheme.onSurface,
    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
    focusedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.42f),
    unfocusedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f),
    focusedContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
    unfocusedContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
    focusedPlaceholderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlphaMin),
    unfocusedPlaceholderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlphaMin),
    focusedLabelColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
    unfocusedLabelColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
    focusedSupportingTextColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.52f),
    unfocusedSupportingTextColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.52f)
)

@Composable
private fun rememberTreePicker(onPicked: (Uri) -> Unit) =
    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            onPicked(uri)
        }
    }

private fun formatTopRankTime(millis: Long): String {
    if (millis <= 0L) return "—"
    val calendar = java.util.Calendar.getInstance().apply { timeInMillis = millis }
    return String.format(
        "%04d-%02d-%02d %02d:%02d",
        calendar.get(java.util.Calendar.YEAR),
        calendar.get(java.util.Calendar.MONTH) + 1,
        calendar.get(java.util.Calendar.DAY_OF_MONTH),
        calendar.get(java.util.Calendar.HOUR_OF_DAY),
        calendar.get(java.util.Calendar.MINUTE)
    )
}

private fun formatCacheSize(bytes: Long): String {
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    return when {
        gb >= 1.0 -> String.format("%.2f GB", gb)
        mb >= 1.0 -> String.format("%.1f MB", mb)
        kb >= 1.0 -> String.format("%.0f KB", kb)
        else -> "$bytes B"
    }
}

