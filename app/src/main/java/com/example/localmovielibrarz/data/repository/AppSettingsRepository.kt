package com.example.localmovielibrarz.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.example.localmovielibrarz.cloud115.Cloud115CookieProvider
import com.example.localmovielibrarz.cloud115.Cloud115LoginApps
import com.example.localmovielibrarz.playback.engine.MpvHwdecMode
import com.example.localmovielibrarz.playback.lut.CubeLut
import com.example.localmovielibrarz.playback.lut.LutParams
import com.example.localmovielibrarz.playback.lut.LutSettings
import com.example.localmovielibrarz.scraper.ScrapeProxyMode
import com.example.localmovielibrarz.scraper.ScrapeChainMode
import com.example.localmovielibrarz.scraper.ScrapeSource
import com.example.localmovielibrarz.scraper.CustomJsonScrapeConfig
import com.example.localmovielibrarz.scraper.NumberPrefixRewriteRule
import com.example.localmovielibrarz.subtitle.SubtitleSearchProvider
import com.example.localmovielibrarz.util.NumberRecognitionRules
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.net.InetSocketAddress
import java.net.Proxy
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

class AppSettingsRepository(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(Cloud115CookieProvider.PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * 敏感凭据加密存储（115 Cookie / 字幕站 Cookie），与光鸭令牌同级。
     * 使用 EncryptedSharedPreferences + Android Keystore，明文不落盘、不进系统备份（配合 backup rules 双保险）。
     */
    private val securePrefs: SharedPreferences = runCatching {
        EncryptedSharedPreferences.create(
            appContext,
            SECURE_PREFS_NAME,
            MasterKey.Builder(appContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }.getOrElse { e ->
        // 审查 I5：Keystore 不可用/初始化失败时降级为明文 SharedPreferences，避免 App 起不来
        Log.w("AppSettingsRepository", "EncryptedSharedPreferences 初始化失败，降级为明文 SharedPreferences", e)
        appContext.getSharedPreferences(SECURE_PREFS_NAME, Context.MODE_PRIVATE)
    }

    init {
        // 存量迁移：旧明文 → 加密存储 → 清空明文（一次性）
        migrateSecret(Cloud115CookieProvider.KEY_COOKIES)
        migrateSecret(KEY_AVSUBTITLES_COOKIES)
        NumberRecognitionRules.updateIgnoredSuffixes(getCachedNumberRecognitionIgnoredSuffixes())
        NumberRecognitionRules.updatePartMarkers(getCachedNumberRecognitionPartMarkers())
        NumberRecognitionRules.updateNumericPrefixAliases(getMergedMgstageSearchPrefixAliases())
        NumberRecognitionRules.updateAttachedLetterPrefixes(getCachedNumberRecognitionAttachedLetterPrefixes())
    }

    private fun migrateSecret(key: String) {
        if (securePrefs.contains(key)) return
        val legacy = prefs.getString(key, null)
        if (!legacy.isNullOrEmpty()) {
            securePrefs.edit().putString(key, legacy).apply()
            prefs.edit().remove(key).apply()
        }
    }

    fun getCookies(): String = securePrefs.getString(Cloud115CookieProvider.KEY_COOKIES, null).orEmpty()

    fun isLightThemeEnabled(): Boolean = prefs.getBoolean(KEY_LIGHT_THEME_ENABLED, false)

    fun saveLightThemeEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_LIGHT_THEME_ENABLED, enabled).apply()
    }

    fun observeLightThemeEnabled(): Flow<Boolean> = callbackFlow {
        trySend(isLightThemeEnabled())
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_LIGHT_THEME_ENABLED) trySend(isLightThemeEnabled())
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    fun saveCookies(value: String) {
        securePrefs.edit().putString(Cloud115CookieProvider.KEY_COOKIES, value.trim()).commit()
    }

    fun getCloud115LoginApp(): String =
        prefs.getString(KEY_CLOUD115_LOGIN_APP, null)
            ?.takeIf { app -> Cloud115LoginApps.all.any { it.app == app } }
            ?: Cloud115LoginApps.default.app

    fun saveCloud115LoginApp(value: String) {
        val normalized = Cloud115LoginApps.find(value).app
        prefs.edit().putString(KEY_CLOUD115_LOGIN_APP, normalized).apply()
    }

    fun isGuangyaLoggedIn(): Boolean = prefs.getBoolean(KEY_GUANGYA_LOGGED_IN, false)

    fun setGuangyaLoggedIn(value: Boolean) {
        prefs.edit().putBoolean(KEY_GUANGYA_LOGGED_IN, value).apply()
    }

    /** ★ 第九轮：播放来源回填完成标志（v18 迁移后首次启动自动执行一次，设置页可手动重跑）。 */
    fun isSourceBackfillDone(): Boolean = prefs.getBoolean(KEY_SOURCE_BACKFILL_DONE, false)

    fun setSourceBackfillDone() {
        prefs.edit().putBoolean(KEY_SOURCE_BACKFILL_DONE, true).apply()
    }

    fun getAvsubtitlesCookies(): String = securePrefs.getString(KEY_AVSUBTITLES_COOKIES, null).orEmpty()

    fun saveAvsubtitlesCookies(value: String) {
        securePrefs.edit().putString(KEY_AVSUBTITLES_COOKIES, value.trim()).commit()
    }

    // ---------------- 网盘 tab 状态保持（供应商 + 光鸭目录） ----------------

    /** 网盘 tab 当前选中的供应商（0=115，1=光鸭），跨进程/重启保留。 */
    fun getCloudProviderIndex(): Int = prefs.getInt(KEY_CLOUD_PROVIDER_INDEX, 0)

    fun saveCloudProviderIndex(index: Int) {
        prefs.edit().putInt(KEY_CLOUD_PROVIDER_INDEX, index.coerceIn(0, 1)).apply()
    }

    /** 光鸭浏览位置快照：父链栈 + 当前目录 + 目录名链。 */
    fun getGuangyaLocation(): GuangyaLocationSnapshot {
        val raw = prefs.getString(KEY_GUANGYA_LOCATION, null) ?: return GuangyaLocationSnapshot()
        return runCatching {
            val obj = JSONObject(raw)
            GuangyaLocationSnapshot(
                stack = obj.optJSONArray("stack").toStringList(),
                currentParentId = obj.optString("current", ""),
                names = obj.optJSONArray("names").toStringList()
            )
        }.getOrDefault(GuangyaLocationSnapshot())
    }

    fun saveGuangyaLocation(snapshot: GuangyaLocationSnapshot) {
        val obj = JSONObject()
            .put("stack", JSONArray().apply { snapshot.stack.take(32).forEach { put(it) } })
            .put("current", snapshot.currentParentId)
            .put("names", JSONArray().apply { snapshot.names.take(32).forEach { put(it) } })
        prefs.edit().putString(KEY_GUANGYA_LOCATION, obj.toString()).apply()
    }

    fun clearGuangyaLocation() {
        prefs.edit().remove(KEY_GUANGYA_LOCATION).apply()
    }

    private fun JSONArray.toStringList(): List<String> =
        (0 until length()).mapNotNull { optString(it).takeIf { s -> s.isNotBlank() } }

    fun getSubtitleSearchProvider(): SubtitleSearchProvider =
        SubtitleSearchProvider.fromId(prefs.getString(KEY_SUBTITLE_SEARCH_PROVIDER, null))

    fun saveSubtitleSearchProvider(provider: SubtitleSearchProvider) {
        prefs.edit().putString(KEY_SUBTITLE_SEARCH_PROVIDER, provider.id).apply()
    }

    fun getUpdateManifestUrl(): String =
        prefs.getString(KEY_UPDATE_MANIFEST_URL, null)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_UPDATE_MANIFEST_URL

    fun saveUpdateManifestUrl(value: String) {
        prefs.edit()
            .putString(KEY_UPDATE_MANIFEST_URL, value.trim().ifBlank { DEFAULT_UPDATE_MANIFEST_URL })
            .apply()
    }

    fun getUpdateProxyBaseUrl(): String {
        val stored = prefs.getString(KEY_UPDATE_PROXY_BASE_URL, null)
        return if (stored == null) DEFAULT_UPDATE_PROXY_BASE_URL else normalizeUpdateProxyBaseUrl(stored)
    }

    fun saveUpdateProxyBaseUrl(value: String) {
        prefs.edit().putString(KEY_UPDATE_PROXY_BASE_URL, normalizeUpdateProxyBaseUrl(value)).apply()
    }

    fun isUpdateProxyEnabled(): Boolean =
        prefs.getBoolean(KEY_UPDATE_PROXY_ENABLED, true)

    fun saveUpdateProxyEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_UPDATE_PROXY_ENABLED, enabled).apply()
    }

    fun isUpdateAutoCheckOnStartupEnabled(): Boolean =
        prefs.getBoolean(KEY_UPDATE_AUTO_CHECK_ON_STARTUP_ENABLED, true)

    fun saveUpdateAutoCheckOnStartupEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_UPDATE_AUTO_CHECK_ON_STARTUP_ENABLED, enabled).apply()
    }

    fun isUpdateAutoDeleteInstalledApkEnabled(): Boolean =
        prefs.getBoolean(KEY_UPDATE_AUTO_DELETE_INSTALLED_APK_ENABLED, true)

    fun saveUpdateAutoDeleteInstalledApkEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_UPDATE_AUTO_DELETE_INSTALLED_APK_ENABLED, enabled).apply()
    }

    fun isStartupAnimationEnabled(): Boolean =
        prefs.getBoolean(KEY_STARTUP_ANIMATION_ENABLED, false)

    fun saveStartupAnimationEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_STARTUP_ANIMATION_ENABLED, enabled).apply()
    }

    fun getStartupAnimationImageUri(): String =
        prefs.getString(KEY_STARTUP_ANIMATION_IMAGE_URI, null).orEmpty()

    fun saveStartupAnimationImageUri(uri: String) {
        prefs.edit().putString(KEY_STARTUP_ANIMATION_IMAGE_URI, uri).apply()
    }

    fun clearStartupAnimationImageUri() {
        prefs.edit().remove(KEY_STARTUP_ANIMATION_IMAGE_URI).apply()
    }

    fun getPendingUpdateInstallVersionCode(): Int =
        prefs.getInt(KEY_PENDING_UPDATE_INSTALL_VERSION_CODE, 0)

    fun getPendingUpdateInstallApkPath(): String =
        prefs.getString(KEY_PENDING_UPDATE_INSTALL_APK_PATH, null).orEmpty()

    fun savePendingUpdateInstallApk(versionCode: Int, apkPath: String) {
        prefs.edit()
            .putInt(KEY_PENDING_UPDATE_INSTALL_VERSION_CODE, versionCode.coerceAtLeast(0))
            .putString(KEY_PENDING_UPDATE_INSTALL_APK_PATH, apkPath)
            .apply()
    }

    fun clearPendingUpdateInstallApk() {
        prefs.edit()
            .remove(KEY_PENDING_UPDATE_INSTALL_VERSION_CODE)
            .remove(KEY_PENDING_UPDATE_INSTALL_APK_PATH)
            .apply()
    }

    fun getStrmTreeUri(): String? = getLibraryRootUri()

    fun getStrmTreeDisplayName(): String = getLibraryRootDisplayName()

    fun saveStrmTreeUri(uri: Uri) {
        saveLibraryRootUri(uri)
    }

    fun getLibraryRootUri(): String? = prefs.getString(KEY_LIBRARY_ROOT_URI, null)

    fun getLibraryRootDisplayName(): String = getTreeDisplayName(getLibraryRootUri()) ?: "尚未选择目录"

    fun saveLibraryRootUri(uri: Uri) {
        appContext.contentResolver.takePersistableUriPermission(
            uri,
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        val uriString = uri.toString()
        prefs.edit()
            .putString(KEY_LIBRARY_ROOT_URI, uriString)
            .putString(KEY_STRM_TREE_URI, uriString)
            .putStringSet(KEY_LIBRARY_ROOT_URI_HISTORY, getKnownLibraryRootUris() + uriString)
            .putStringSet(KEY_STRM_TREE_URI_HISTORY, getKnownStrmTreeUris() + uriString)
            .apply()
        syncNoMediaForLibraryRoot(uriString)
    }

    fun getKnownStrmTreeUris(): Set<String> = getKnownLibraryRootUris()

    // ---- 本地字幕库 ----
    // 刻意不复用 saveLibraryRootUri：那个方法会把 uri 同时写进 STRM 树键并触发
    // .nomedia 同步（会在用户的字幕目录里建/删占位文件），字幕库不该有这些副作用。

    fun getLocalSubtitleLibraryUri(): String? = prefs.getString(KEY_LOCAL_SUBTITLE_LIBRARY_URI, null)

    fun getLocalSubtitleLibraryDisplayName(): String =
        getTreeDisplayName(getLocalSubtitleLibraryUri()) ?: "尚未选择字幕库目录"

    fun saveLocalSubtitleLibraryUri(uri: Uri) {
        appContext.contentResolver.takePersistableUriPermission(
            uri,
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
        prefs.edit()
            .putString(KEY_LOCAL_SUBTITLE_LIBRARY_URI, uri.toString())
            .putLong(KEY_LOCAL_SUBTITLE_LIBRARY_INDEXED_AT, 0L)
            .apply()
    }

    fun getLocalSubtitleLibraryIndexedAt(): Long =
        prefs.getLong(KEY_LOCAL_SUBTITLE_LIBRARY_INDEXED_AT, 0L)

    fun saveLocalSubtitleLibraryIndexedAt(millis: Long) {
        prefs.edit().putLong(KEY_LOCAL_SUBTITLE_LIBRARY_INDEXED_AT, millis).apply()
    }

    fun getKnownLibraryRootUris(): Set<String> =
        (prefs.getStringSet(KEY_LIBRARY_ROOT_URI_HISTORY, emptySet()) ?: emptySet()) +
            listOfNotNull(getLibraryRootUri())

    fun getStrmBaseUrl(): String {
        val stored = prefs.getString(KEY_STRM_BASE_URL, null)
            ?.trim()
            ?.trimEnd('/')
            ?.takeIf { it.isNotBlank() }
        return when (stored) {
            null, OLD_DEFAULT_STRM_BASE_URL -> DEFAULT_STRM_BASE_URL
            else -> stored
        }
    }

    fun saveStrmBaseUrl(value: String) {
        val normalized = value.trim().trimEnd('/').ifBlank { DEFAULT_STRM_BASE_URL }
        prefs.edit().putString(KEY_STRM_BASE_URL, normalized).apply()
    }

    fun getDefaultScrapeSource(): ScrapeSource {
        val stored = prefs.getString(KEY_DEFAULT_SCRAPE_SOURCE, null)
        return ScrapeSource.entries.firstOrNull { it.name == stored && it == ScrapeSource.Priority } ?: ScrapeSource.Priority
    }

    fun saveDefaultScrapeSource(source: ScrapeSource) {
        prefs.edit().putString(KEY_DEFAULT_SCRAPE_SOURCE, ScrapeSource.Priority.name).apply()
    }

    fun getPriorityScrapeSources(): List<ScrapeSource> {
        val stored = prefs.getString(KEY_PRIORITY_SCRAPE_SOURCES, null)
            ?.split(",")
            ?.mapNotNull { name ->
                ScrapeSource.fromStoredName(name)
            }
            ?.filter { it in PRIORITY_SCRAPE_SOURCE_OPTIONS }
            ?.distinct()
            .orEmpty()
        return when {
            stored.isEmpty() -> DEFAULT_PRIORITY_SCRAPE_SOURCES
            stored == OLD_DEFAULT_PRIORITY_SCRAPE_SOURCES_AFTER_MIGRATION -> DEFAULT_PRIORITY_SCRAPE_SOURCES
            stored == OLD_DMM_DEFAULT_PRIORITY_SCRAPE_SOURCES_AFTER_MIGRATION -> DEFAULT_PRIORITY_SCRAPE_SOURCES
            else -> stored
        }
    }

    fun savePriorityScrapeSources(sources: List<ScrapeSource>) {
        val normalized = sources
            .filter { it in PRIORITY_SCRAPE_SOURCE_OPTIONS }
            .distinct()
            .ifEmpty { DEFAULT_PRIORITY_SCRAPE_SOURCES }
        prefs.edit()
            .putString(KEY_PRIORITY_SCRAPE_SOURCES, normalized.joinToString(",") { it.name })
            .apply()
    }

    fun getImageDownloadRetryCount(): Int {
        return prefs.getInt(KEY_IMAGE_DOWNLOAD_RETRY_COUNT, DEFAULT_IMAGE_DOWNLOAD_RETRY_COUNT)
            .coerceIn(1, 10)
    }

    fun saveImageDownloadRetryCount(count: Int) {
        prefs.edit().putInt(KEY_IMAGE_DOWNLOAD_RETRY_COUNT, count.coerceIn(1, 10)).apply()
    }

    fun getScrapeRetryCount(): Int {
        return prefs.getInt(KEY_SCRAPE_RETRY_COUNT, DEFAULT_SCRAPE_RETRY_COUNT)
            .coerceIn(1, MAX_SCRAPE_RETRY_COUNT)
    }

    fun saveScrapeRetryCount(count: Int) {
        prefs.edit().putInt(KEY_SCRAPE_RETRY_COUNT, count.coerceIn(1, MAX_SCRAPE_RETRY_COUNT)).apply()
    }

    fun getScrapeConcurrencyLimit(): Int {
        return prefs.getInt(KEY_SCRAPE_CONCURRENCY_LIMIT, DEFAULT_SCRAPE_CONCURRENCY_LIMIT)
            .coerceIn(1, MAX_SCRAPE_CONCURRENCY_LIMIT)
    }

    fun saveScrapeConcurrencyLimit(count: Int) {
        prefs.edit().putInt(KEY_SCRAPE_CONCURRENCY_LIMIT, count.coerceIn(1, MAX_SCRAPE_CONCURRENCY_LIMIT)).apply()
    }

    fun getScrapeProxyAddress(): String = prefs.getString(KEY_SCRAPE_PROXY_ADDRESS, null).orEmpty().trim()

    fun saveScrapeProxyAddress(value: String) {
        prefs.edit().putString(KEY_SCRAPE_PROXY_ADDRESS, value.trim()).apply()
    }

    /**
     * 刮削出口的代理模式（对齐 peach 的 environment / direct / proxy 三档）。
     *
     * 首次读取时做一次**单向迁移**：旧版本只有一个布尔开关
     * （`scrape_proxy_enabled`），且「关」在传输层的实际语义是 `Proxy.NO_PROXY`
     * ——显式直连，会把 Wi-Fi 代理、VPN 下发的系统代理一并跳过。这正是
     * 「编译通过后所有刮削都报网络错误」的根因，所以这里把「关」迁成
     * [ScrapeProxyMode.Environment]（跟随系统），把「开」迁成 [ScrapeProxyMode.Proxy]。
     *
     * 迁移结果立刻落盘，后续读取不再看旧键；旧键保留不删，方便降级回滚。
     */
    fun getScrapeProxyMode(): ScrapeProxyMode {
        val stored = prefs.getString(KEY_SCRAPE_PROXY_MODE, null)
        if (stored != null) return ScrapeProxyMode.fromStorage(stored)
        val migrated = ScrapeProxyMode.migrateFromLegacyEnabled(
            prefs.getBoolean(KEY_SCRAPE_PROXY_ENABLED, false)
        )
        prefs.edit().putString(KEY_SCRAPE_PROXY_MODE, migrated.storageValue).apply()
        return migrated
    }

    fun saveScrapeProxyMode(mode: ScrapeProxyMode) {
        prefs.edit().putString(KEY_SCRAPE_PROXY_MODE, mode.storageValue).apply()
    }

    /**
     * 解析用户填写的固定代理；为空或格式非法时返回 null。
     *
     * 这里**不再看开关状态**：是否启用由 [getScrapeProxyMode] 决定，
     * 返回 null 的含义从原来的「直连」变成「这个地址不可用，由选择器决定回退到哪」。
     */
    fun getScrapeProxy(): Proxy? {
        val raw = getScrapeProxyAddress()
        if (raw.isBlank()) return null
        val uri = runCatching {
            java.net.URI(if ("://" in raw) raw else "http://$raw")
        }.getOrNull() ?: return null
        val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
        val port = uri.port.takeIf { it in 1..65535 } ?: return null
        val type = if (uri.scheme.equals("socks5", ignoreCase = true) || uri.scheme.equals("socks", ignoreCase = true)) {
            Proxy.Type.SOCKS
        } else {
            Proxy.Type.HTTP
        }
        return Proxy(type, InetSocketAddress.createUnresolved(host, port))
    }

    fun getDmm2SkippedNumberPrefixes(): Set<String> =
        prefs.getStringSet(KEY_DMM2_SKIPPED_NUMBER_PREFIXES, DEFAULT_DMM2_SKIPPED_NUMBER_PREFIXES)
            .orEmpty()
            .mapNotNull { it.normalizedNumberPrefixOrNull() }
            .toSet()

    fun saveDmm2SkippedNumberPrefixes(prefixes: Set<String>) {
        prefs.edit()
            .putStringSet(KEY_DMM2_SKIPPED_NUMBER_PREFIXES, prefixes.mapNotNull { it.normalizedNumberPrefixOrNull() }.toSet())
            .apply()
    }

    fun addDmm2SkippedNumberPrefix(prefix: String) {
        val normalized = prefix.normalizedNumberPrefixOrNull() ?: return
        saveDmm2SkippedNumberPrefixes(getDmm2SkippedNumberPrefixes() + normalized)
    }

    fun removeDmm2SkippedNumberPrefix(prefix: String) {
        val normalized = prefix.normalizedNumberPrefixOrNull() ?: return
        saveDmm2SkippedNumberPrefixes(getDmm2SkippedNumberPrefixes() - normalized)
    }

    fun getNumberPrefixRewriteRules(): List<NumberPrefixRewriteRule> =
        prefs.getString(KEY_NUMBER_PREFIX_REWRITE_RULES, null)
            ?.let(::parseNumberPrefixRewriteRules)
            .orEmpty()

    fun saveNumberPrefixRewriteRules(rules: List<NumberPrefixRewriteRule>) {
        val normalized = rules.mapNotNull { rule ->
            val prefix = rule.prefix.normalizedNumberPrefixOrNull()
                ?.dropWhile(Char::isDigit)
                ?.takeIf { it.isNotBlank() && it.any(Char::isLetter) }
                ?: return@mapNotNull null
            val numericPrefix = rule.numericPrefix.filter(Char::isDigit)
            if (numericPrefix.isBlank()) return@mapNotNull null
            val sources = rule.sources.filter { it != ScrapeSource.Priority }.toSet()
            if (sources.isEmpty()) return@mapNotNull null
            NumberPrefixRewriteRule(prefix, numericPrefix, sources)
        }.distinctBy { it.prefix }
        prefs.edit().putString(KEY_NUMBER_PREFIX_REWRITE_RULES, normalized.toJsonString()).apply()
    }

    fun isMgstageAmateurPriorityEnabled(): Boolean =
        prefs.getBoolean(KEY_MGSTAGE_AMATEUR_PRIORITY_ENABLED, false)

    fun saveMgstageAmateurPriorityEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_MGSTAGE_AMATEUR_PRIORITY_ENABLED, enabled).apply()
    }

    /**
     * 当前刮削链模式。
     *
     * 缺键 → [ScrapeChainMode.DEFAULT]（桃链优先）。老安装升级上来 prefs 里没有这个键，
     * 读出来就必须是桃链优先，所以默认值是**读时兜底**而不是「首次启动写一次」——
     * 后者一旦漏跑（清数据、备份还原、设置页尚未接线）就会悄悄退回 legacy。
     */
    fun getScrapeChainMode(): ScrapeChainMode =
        ScrapeChainMode.fromStoredValue(prefs.getString(KEY_SCRAPE_CHAIN_MODE, null))

    fun saveScrapeChainMode(mode: ScrapeChainMode) {
        prefs.edit().putString(KEY_SCRAPE_CHAIN_MODE, mode.storedValue).apply()
    }

    fun getLocalMgstageNumberPrefixes(): Set<String> =
        (DEFAULT_MGSTAGE_NUMBER_PREFIXES + prefs.getStringSet(KEY_CACHED_MGSTAGE_NUMBER_PREFIXES, emptySet()).orEmpty())
            .orEmpty()
            .mapNotNull { it.normalizedNumberPrefixOrNull() }
            .filterNot { it == "CUTE" || it.firstOrNull()?.isDigit() == true }
            .toSet()
            .plus(getLocalMgstageSearchPrefixAliases().keys)

    fun saveLocalMgstageNumberPrefixes(prefixes: Set<String>) {
        prefs.edit()
            .putStringSet(KEY_CACHED_MGSTAGE_NUMBER_PREFIXES, prefixes.mapNotNull { it.normalizedNumberPrefixOrNull() }.toSet())
            .apply()
    }

    fun getLocalMgstageSearchPrefixAliases(): Map<String, String> =
        DEFAULT_MGSTAGE_SEARCH_PREFIX_ALIASES + (prefs.getString(KEY_CACHED_MGSTAGE_SEARCH_PREFIX_ALIASES, null)
            ?.let { parseMgstageSearchPrefixAliases(it) }
            .orEmpty())

    fun saveLocalMgstageSearchPrefixAliases(aliases: Map<String, String>) {
        val normalized = normalizeMgstageSearchPrefixAliases(aliases)
        prefs.edit()
            .putString(
                KEY_CACHED_MGSTAGE_SEARCH_PREFIX_ALIASES,
                normalized.toJsonObjectString()
            )
            .apply()
        NumberRecognitionRules.updateNumericPrefixAliases(getMergedMgstageSearchPrefixAliases())
    }

    fun getCachedNumberRecognitionIgnoredSuffixes(): Set<String> =
        prefs.getStringSet(KEY_CACHED_NUMBER_RECOGNITION_IGNORED_SUFFIXES, DEFAULT_NUMBER_RECOGNITION_IGNORED_SUFFIXES)
            .orEmpty()
            .let(NumberRecognitionRules::normalizeIgnoredSuffixes)
            .ifEmpty { DEFAULT_NUMBER_RECOGNITION_IGNORED_SUFFIXES }

    fun saveCachedNumberRecognitionIgnoredSuffixes(suffixes: Set<String>) {
        val normalized = NumberRecognitionRules.normalizeIgnoredSuffixes(suffixes)
            .ifEmpty { DEFAULT_NUMBER_RECOGNITION_IGNORED_SUFFIXES }
        prefs.edit()
            .putStringSet(KEY_CACHED_NUMBER_RECOGNITION_IGNORED_SUFFIXES, normalized)
            .apply()
        NumberRecognitionRules.updateIgnoredSuffixes(normalized)
    }

    fun getCachedNumberRecognitionPartMarkers(): Set<String> =
        prefs.getStringSet(KEY_CACHED_NUMBER_RECOGNITION_PART_MARKERS, DEFAULT_NUMBER_RECOGNITION_PART_MARKERS)
            .orEmpty()
            .let(NumberRecognitionRules::normalizePartMarkers)
            .ifEmpty { DEFAULT_NUMBER_RECOGNITION_PART_MARKERS }

    fun saveCachedNumberRecognitionPartMarkers(markers: Set<String>) {
        val normalized = NumberRecognitionRules.normalizePartMarkers(markers)
            .ifEmpty { DEFAULT_NUMBER_RECOGNITION_PART_MARKERS }
        prefs.edit()
            .putStringSet(KEY_CACHED_NUMBER_RECOGNITION_PART_MARKERS, normalized)
            .apply()
        NumberRecognitionRules.updatePartMarkers(normalized)
    }

    // 需求 9：连写字母分段前缀列表化（持久化，合并默认值）
    fun getCachedNumberRecognitionAttachedLetterPrefixes(): Set<String> {
        val saved = prefs.getStringSet(KEY_CACHED_NUMBER_RECOGNITION_ATTACHED_LETTER_PREFIXES, null)
            .orEmpty()
            .mapNotNull { it.trim().uppercase().filter(Char::isLetterOrDigit).takeIf { t -> t.isNotBlank() && t.any(Char::isLetter) } }
            .toSet()
        return NumberRecognitionRules.DEFAULT_ATTACHED_LETTER_SEGMENT_PREFIXES + saved
    }

    fun saveCachedNumberRecognitionAttachedLetterPrefixes(prefixes: Set<String>) {
        val normalized = prefixes
            .mapNotNull { it.trim().uppercase().filter(Char::isLetterOrDigit).takeIf { t -> t.isNotBlank() && t.any(Char::isLetter) } }
            .toSet()
        prefs.edit()
            .putStringSet(KEY_CACHED_NUMBER_RECOGNITION_ATTACHED_LETTER_PREFIXES, normalized)
            .apply()
        NumberRecognitionRules.updateAttachedLetterPrefixes(normalized)
    }

    fun getCustomMgstageSearchPrefixAliases(): Map<String, String> {
        val saved = prefs.getString(KEY_CUSTOM_MGSTAGE_SEARCH_PREFIX_ALIASES, null)
            ?.let { parseMgstageSearchPrefixAliases(it) }
            .orEmpty()
        if (saved.isNotEmpty()) return saved
        return normalizeMgstageSearchPrefixAliases(
            prefs.getStringSet(KEY_CUSTOM_MGSTAGE_NUMBER_PREFIXES, emptySet())
                .orEmpty()
                .mapNotNull { it.normalizedMgstageNumberPrefixOrNull() }
                .associateWith { it }
        )
    }

    fun saveCustomMgstageSearchPrefixAliases(aliases: Map<String, String>) {
        val normalized = normalizeMgstageSearchPrefixAliases(aliases)
        prefs.edit()
            .putString(KEY_CUSTOM_MGSTAGE_SEARCH_PREFIX_ALIASES, normalized.toJsonObjectString())
            .remove(KEY_CUSTOM_MGSTAGE_NUMBER_PREFIXES)
            .apply()
        NumberRecognitionRules.updateNumericPrefixAliases(getMergedMgstageSearchPrefixAliases())
    }

    fun addCustomMgstageNumberPrefix(prefix: String, numericPrefix: String = "") {
        val normalized = prefix.normalizedMgstageNumberPrefixOrNull() ?: return
        val numeric = numericPrefix.filter(Char::isDigit)
        saveCustomMgstageSearchPrefixAliases(
            getCustomMgstageSearchPrefixAliases() + (normalized to "$numeric$normalized")
        )
    }

    fun removeCustomMgstageNumberPrefix(prefix: String) {
        val normalized = prefix.normalizedMgstageNumberPrefixOrNull() ?: return
        saveCustomMgstageSearchPrefixAliases(getCustomMgstageSearchPrefixAliases() - normalized)
    }

    fun getCustomMgstageNumberPrefixes(): Set<String> = getCustomMgstageSearchPrefixAliases().keys

    fun getMergedMgstageNumberPrefixes(): Set<String> =
        getLocalMgstageNumberPrefixes() + getCustomMgstageNumberPrefixes()

    fun getMergedMgstageSearchPrefixAliases(): Map<String, String> =
        getLocalMgstageSearchPrefixAliases() + getCustomMgstageSearchPrefixAliases()

    fun getCustomMgstagePrefixNumberMappings(): Map<String, String> =
        getCustomMgstageSearchPrefixAliases().toPrefixNumberMappings()

    fun getLocalMgstagePrefixNumberMappings(): Map<String, String> =
        getLocalMgstageNumberPrefixes().associateWith { "" } +
            getLocalMgstageSearchPrefixAliases().toPrefixNumberMappings()

    fun getMergedMgstagePrefixNumberMappings(): Map<String, String> =
        getMergedMgstageNumberPrefixes().associateWith { "" } +
            getMergedMgstageSearchPrefixAliases().toPrefixNumberMappings()

    fun saveCustomMgstagePrefixNumberMappings(mappings: Map<String, String>) {
        saveCustomMgstageSearchPrefixAliases(
            mappings.mapNotNull { (prefix, numericPrefix) ->
                val normalizedPrefix = prefix.normalizedMgstageNumberPrefixOrNull() ?: return@mapNotNull null
                normalizedPrefix to "${numericPrefix.filter(Char::isDigit)}$normalizedPrefix"
            }.toMap()
        )
    }

    fun getHomeSortOptionName(): String? = prefs.getString(KEY_HOME_SORT_OPTION, null)

    fun saveHomeSortOptionName(value: String) {
        prefs.edit().putString(KEY_HOME_SORT_OPTION, value).apply()
    }

    fun getHomeSortDirectionName(): String? = prefs.getString(KEY_HOME_SORT_DIRECTION, null)

    fun saveHomeSortDirectionName(value: String) {
        prefs.edit().putString(KEY_HOME_SORT_DIRECTION, value).apply()
    }

    // ---------------- 需求 3 补充：光鸭批量重命名模板 ----------------

    fun getGuangyaRenameTemplates(): List<String> =
        prefs.getStringSet(KEY_GUANGYA_RENAME_TEMPLATES, emptySet())
            ?.sorted()
            ?: emptyList()

    fun saveGuangyaRenameTemplate(template: String) {
        val normalized = template.trim().takeIf { it.isNotBlank() } ?: return
        val updated = getGuangyaRenameTemplates().toMutableSet().apply { add(normalized) }
        prefs.edit().putStringSet(KEY_GUANGYA_RENAME_TEMPLATES, updated).apply()
    }

    fun deleteGuangyaRenameTemplate(template: String) {
        val updated = getGuangyaRenameTemplates().toMutableSet().apply { remove(template) }
        prefs.edit().putStringSet(KEY_GUANGYA_RENAME_TEMPLATES, updated).apply()
    }

    fun getCloudSortOptionName(): String? = prefs.getString(KEY_CLOUD_SORT_OPTION, null)

    fun saveCloudSortOptionName(value: String) {
        prefs.edit().putString(KEY_CLOUD_SORT_OPTION, value).apply()
    }

    fun isCloudSortAscending(): Boolean = prefs.getBoolean(KEY_CLOUD_SORT_ASCENDING, false)

    fun saveCloudSortAscending(ascending: Boolean) {
        prefs.edit().putBoolean(KEY_CLOUD_SORT_ASCENDING, ascending).apply()
    }

    fun getHomeImageModeName(): String? = prefs.getString(KEY_HOME_IMAGE_MODE, null)

    fun saveHomeImageModeName(value: String) {
        prefs.edit().putString(KEY_HOME_IMAGE_MODE, value).apply()
    }

    fun isLibraryNoMediaEnabled(): Boolean =
        prefs.getBoolean(KEY_LIBRARY_NOMEDIA_ENABLED, true)

    fun saveLibraryNoMediaEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_LIBRARY_NOMEDIA_ENABLED, enabled).apply()
        syncNoMediaForCurrentLibraryRoot()
    }

    fun syncNoMediaForCurrentLibraryRoot() {
        syncNoMediaForLibraryRoot(getLibraryRootUri())
    }

    private fun syncNoMediaForLibraryRoot(uriString: String?) {
        val root = uriString
            ?.let { Uri.parse(it) }
            ?.let { DocumentFile.fromTreeUri(appContext, it) }
            ?: return
        if (isLibraryNoMediaEnabled()) {
            val noMedia = root.findFile(NOMEDIA_FILE_NAME) ?: createNoMediaFile(root)
            noMedia?.uri?.let { uri ->
                appContext.contentResolver.openOutputStream(uri, "wt")?.use { output ->
                    output.write(ByteArray(0))
                }
            }
        } else {
            root.findFile(NOMEDIA_FILE_NAME)?.delete()
            root.findFile(NOMEDIA_TEMP_FILE_NAME)?.delete()
        }
    }

    private fun createNoMediaFile(root: DocumentFile): DocumentFile? {
        root.createFile("application/octet-stream", NOMEDIA_FILE_NAME)
            ?.let { created ->
                if (created.name == NOMEDIA_FILE_NAME) return created
                if (created.renameTo(NOMEDIA_FILE_NAME)) {
                    return root.findFile(NOMEDIA_FILE_NAME) ?: created
                }
                created.delete()
            }

        root.findFile(NOMEDIA_TEMP_FILE_NAME)?.delete()
        val temp = root.createFile("application/octet-stream", NOMEDIA_TEMP_FILE_NAME) ?: return null
        return if (temp.renameTo(NOMEDIA_FILE_NAME)) {
            root.findFile(NOMEDIA_FILE_NAME) ?: temp
        } else {
            temp.delete()
            null
        }
    }

    fun isCloudAddButtonMessageEnabled(): Boolean =
        prefs.getBoolean(KEY_CLOUD_ADD_BUTTON_MESSAGE_ENABLED, true)

    fun saveCloudAddButtonMessageEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_CLOUD_ADD_BUTTON_MESSAGE_ENABLED, enabled).apply()
    }

    fun isCloudDeleteEnabled(): Boolean =
        prefs.getBoolean(KEY_CLOUD_DELETE_ENABLED, false)

    fun saveCloudDeleteEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_CLOUD_DELETE_ENABLED, enabled).apply()
    }

    // ===== JavDB TOP 榜单 =====

    /**
     * 榜单端点。空串或非法值回落到默认端点：这个值由用户在设置页手填，
     * 允许填错但不允许把功能整体弄坏，所以读的时候做一次兜底。
     */
    fun top250Endpoint(): String {
        val saved = prefs.getString(KEY_TOP250_ENDPOINT, null)?.trim().orEmpty()
        return if (saved.startsWith("http://") || saved.startsWith("https://")) {
            saved
        } else {
            DEFAULT_TOP250_ENDPOINT
        }
    }

    fun saveTop250Endpoint(endpoint: String) {
        prefs.edit().putString(KEY_TOP250_ENDPOINT, endpoint.trim()).apply()
    }

    /** 卡片 TOP 角标开关，默认开启：榜单是纯增量信息，不挡视线就该默认可见。 */
    fun isCardTopGoldBadgeEnabled(): Boolean =
        prefs.getBoolean(KEY_CARD_TOP_GOLD_BADGE_ENABLED, true)

    fun saveCardTopGoldBadgeEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_CARD_TOP_GOLD_BADGE_ENABLED, enabled).apply()
    }

    /**
     * 角标开关的响应式读法。
     *
     * 卡片角标在首页组合树的最外层决定是否参与布局，用 Flow 而不是
     * 「设置页返回时重读」：返回键路径有多个（系统返回、手势返回、
     * 底部导航切页），逐个挂重读点必然漏掉其中一条。
     */
    fun observeCardTopGoldBadgeEnabled(): Flow<Boolean> = callbackFlow {
        trySend(isCardTopGoldBadgeEnabled())
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_CARD_TOP_GOLD_BADGE_ENABLED) trySend(isCardTopGoldBadgeEnabled())
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    fun getCloudExcludedVideoNames(): Set<String> =
        (prefs.getStringSet(KEY_CLOUD_EXCLUDED_VIDEO_NAMES, null) ?: DEFAULT_CLOUD_EXCLUDED_VIDEO_NAMES)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toSet()

    fun saveCloudExcludedVideoNames(names: Set<String>) {
        prefs.edit()
            .putStringSet(
                KEY_CLOUD_EXCLUDED_VIDEO_NAMES,
                names.map { it.trim() }.filter { it.isNotBlank() }.toSet()
            )
            .apply()
    }

    fun addCloudExcludedVideoName(name: String) {
        val cleaned = name.trim()
        if (cleaned.isBlank()) return
        saveCloudExcludedVideoNames(getCloudExcludedVideoNames() + cleaned)
    }

    fun removeCloudExcludedVideoName(name: String) {
        saveCloudExcludedVideoNames(getCloudExcludedVideoNames() - name)
    }

    // ---------------- 批量重命名模板 V2（全操作类型） ----------------

    fun getGuangyaBatchRenameTemplates(): List<String> =
        prefs.getStringSet(KEY_GUANGYA_BATCH_RENAME_TEMPLATES, emptySet())?.toList() ?: emptyList()

    fun saveGuangyaBatchRenameTemplate(json: String) {
        val cur = getGuangyaBatchRenameTemplates().toMutableSet()
        cur.add(json)
        prefs.edit().putStringSet(KEY_GUANGYA_BATCH_RENAME_TEMPLATES, cur).apply()
    }

    fun deleteGuangyaBatchRenameTemplate(json: String) {
        val cur = getGuangyaBatchRenameTemplates().toMutableSet()
        cur.remove(json)
        prefs.edit().putStringSet(KEY_GUANGYA_BATCH_RENAME_TEMPLATES, cur).apply()
    }

    fun getCloudScrapeSkipBelowSizeMb(): Int =
        prefs.getInt(KEY_CLOUD_SCRAPE_SKIP_BELOW_SIZE_MB, DEFAULT_CLOUD_SCRAPE_SKIP_BELOW_SIZE_MB)
            .coerceIn(0, MAX_CLOUD_SCRAPE_SKIP_BELOW_SIZE_MB)

    fun saveCloudScrapeSkipBelowSizeMb(value: Int) {
        prefs.edit()
            .putInt(KEY_CLOUD_SCRAPE_SKIP_BELOW_SIZE_MB, value.coerceIn(0, MAX_CLOUD_SCRAPE_SKIP_BELOW_SIZE_MB))
            .apply()
    }

    fun getCloudScrapeSkipBelowSizeBytes(): Long =
        getCloudScrapeSkipBelowSizeMb().toLong() * 1024L * 1024L

    fun getPlayerSeekBackSeconds(): Int =
        prefs.getInt(KEY_PLAYER_SEEK_BACK_SECONDS, DEFAULT_PLAYER_SEEK_SECONDS)
            .coerceIn(MIN_PLAYER_SEEK_SECONDS, MAX_PLAYER_SEEK_SECONDS)

    fun savePlayerSeekBackSeconds(value: Int) {
        prefs.edit()
            .putInt(KEY_PLAYER_SEEK_BACK_SECONDS, value.coerceIn(MIN_PLAYER_SEEK_SECONDS, MAX_PLAYER_SEEK_SECONDS))
            .apply()
    }

    fun getPlayerSeekForwardSeconds(): Int =
        prefs.getInt(KEY_PLAYER_SEEK_FORWARD_SECONDS, DEFAULT_PLAYER_SEEK_SECONDS)
            .coerceIn(MIN_PLAYER_SEEK_SECONDS, MAX_PLAYER_SEEK_SECONDS)

    fun savePlayerSeekForwardSeconds(value: Int) {
        prefs.edit()
            .putInt(KEY_PLAYER_SEEK_FORWARD_SECONDS, value.coerceIn(MIN_PLAYER_SEEK_SECONDS, MAX_PLAYER_SEEK_SECONDS))
            .apply()
    }

    /**
     * 默认播放内核（"exo" / "mpv"）。
     *
     * 全局单值而非按片记录：内核选择属于设备能力偏好，逐片记录会让同一部影片在两个内核下
     * 出现两套进度/字幕观感，反而增加解释成本。
     */
    fun getDefaultPlaybackEngine(): String =
        prefs.getString(KEY_PLAYER_ENGINE, PLAYER_ENGINE_EXO)
            ?.takeIf { it == PLAYER_ENGINE_EXO || it == PLAYER_ENGINE_MPV }
            ?: PLAYER_ENGINE_EXO

    fun saveDefaultPlaybackEngine(engine: String) {
        val normalized = if (engine == PLAYER_ENGINE_MPV) PLAYER_ENGINE_MPV else PLAYER_ENGINE_EXO
        prefs.edit().putString(KEY_PLAYER_ENGINE, normalized).apply()
    }

    /**
     * mpv 硬件解码开关（默认开启）。
     *
     * mpv 的 hwdec 与 exo 的 MediaCodec 行为差异较大：部分老机型上 hwdec 会触发绿屏/花屏，
     * 因此暴露开关，并在设置项文案里说明"画面异常时关闭"。
     */
    fun getMpvHwdecMode(): MpvHwdecMode {
        val stored = prefs.getString(KEY_MPV_HWDEC_MODE, null)
        if (stored != null) return MpvHwdecMode.fromId(stored)
        // 迁移：早期版本只有一个布尔 `mpv_hwdec`（而且从来没有 UI 入口）。
        // 首次升级时把旧值翻译成新档位，避免用户「明明关了硬解，升级后又被打开」。
        return if (prefs.getBoolean(KEY_MPV_HWDEC, true)) MpvHwdecMode.HW else MpvHwdecMode.SW
    }

    fun saveMpvHwdecMode(mode: MpvHwdecMode) {
        prefs.edit()
            .putString(KEY_MPV_HWDEC_MODE, mode.id)
            // 旧键继续跟着写：万一用户回滚到旧版本，行为与这次选择保持一致。
            .putBoolean(KEY_MPV_HWDEC, mode != MpvHwdecMode.SW)
            .apply()
    }

    /** 旧布尔接口保留：只关心「有没有开硬解」的调用点（日志字段等）继续可用。 */
    fun isMpvHardwareDecodingEnabled(): Boolean = getMpvHwdecMode() != MpvHwdecMode.SW

    fun saveMpvHardwareDecodingEnabled(enabled: Boolean) {
        saveMpvHwdecMode(if (enabled) MpvHwdecMode.HW else MpvHwdecMode.SW)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 实时调色 LUT（移植自 MPV 实时 LUT 参考实现）
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 读取当前 LUT 设置。
     *
     * 7 个参数走 [LutParams.fromPrefMap]，缺键/越界一律回落 0 —— 用户降级 App 后残留的
     * 旧偏好不该让播放器起不来。尺寸与强度单独校验：它们是"生成代价"旋钮，
     * 一旦被写坏（比如手工改 prefs）就会出现 65³ = 274k 格点 × 每格 3 次三角函数的
     * 生成风暴，所以必须夹到合法档位而不是照单全收。
     */
    fun getLutSettings(): LutSettings {
        val size = prefs.getInt(KEY_LUT_SIZE, LutSettings.DEFAULT_LUT_SIZE)
            .coerceIn(CubeLut.MIN_SIZE, CubeLut.MAX_SIZE)
        val strength = prefs.getInt(KEY_LUT_STRENGTH, LutSettings.DEFAULT_STRENGTH)
            .coerceIn(LutSettings.MIN_STRENGTH, LutSettings.MAX_STRENGTH)
        val params = LutParams.fromPrefMap { key -> prefs.getInt(key, 0) }
        return LutSettings(
            enabled = prefs.getBoolean(KEY_LUT_ENABLED, LutSettings.DEFAULT_ENABLED),
            lutSize = size,
            strengthPercent = strength,
            params = params
        )
    }

    /** 保存整套 LUT 设置。7 个参数与 3 个旋钮一次性提交，避免中途被杀留下半套。 */
    fun saveLutSettings(settings: LutSettings) {
        prefs.edit().apply {
            putBoolean(KEY_LUT_ENABLED, settings.enabled)
            putInt(KEY_LUT_SIZE, settings.lutSize)
            putInt(KEY_LUT_STRENGTH, settings.strengthPercent)
            settings.params.toPrefMap().forEach { (key, value) -> putInt(key, value) }
        }.apply()
    }

    /**
     * 订阅 LUT 设置变化。
     *
     * 播放页用它实现「调色滑杆一动，画面立刻跟着变」：设置页写 prefs → 这里收到回调
     * → 播放器重新生成并下发 LUT。用 [callbackFlow] 而不是轮询，是因为轮询要选一个
     * 间隔，选短了费电、选长了滑杆拖起来发涩。
     */
    fun observeLutSettings(): Flow<LutSettings> = callbackFlow {
        trySend(getLutSettings())
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key != null && key.startsWith("lut_")) trySend(getLutSettings())
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    fun getExternalSubtitleFontSizeSp(): Int =
        prefs.getInt(KEY_EXTERNAL_SUBTITLE_FONT_SIZE_SP, DEFAULT_EXTERNAL_SUBTITLE_FONT_SIZE_SP)
            .coerceIn(MIN_EXTERNAL_SUBTITLE_FONT_SIZE_SP, MAX_EXTERNAL_SUBTITLE_FONT_SIZE_SP)

    fun saveExternalSubtitleFontSizeSp(value: Int) {
        prefs.edit()
            .putInt(KEY_EXTERNAL_SUBTITLE_FONT_SIZE_SP, value.coerceIn(MIN_EXTERNAL_SUBTITLE_FONT_SIZE_SP, MAX_EXTERNAL_SUBTITLE_FONT_SIZE_SP))
            .apply()
    }

    fun getExternalSubtitleBottomPaddingPercent(): Int =
        prefs.getInt(KEY_EXTERNAL_SUBTITLE_BOTTOM_PADDING_PERCENT, DEFAULT_EXTERNAL_SUBTITLE_BOTTOM_PADDING_PERCENT)
            .coerceIn(MIN_EXTERNAL_SUBTITLE_BOTTOM_PADDING_PERCENT, MAX_EXTERNAL_SUBTITLE_BOTTOM_PADDING_PERCENT)

    fun saveExternalSubtitleBottomPaddingPercent(value: Int) {
        prefs.edit()
            .putInt(KEY_EXTERNAL_SUBTITLE_BOTTOM_PADDING_PERCENT, value.coerceIn(MIN_EXTERNAL_SUBTITLE_BOTTOM_PADDING_PERCENT, MAX_EXTERNAL_SUBTITLE_BOTTOM_PADDING_PERCENT))
            .apply()
    }

    fun getExternalSubtitleBackgroundAlphaPercent(): Int =
        prefs.getInt(KEY_EXTERNAL_SUBTITLE_BACKGROUND_ALPHA_PERCENT, DEFAULT_EXTERNAL_SUBTITLE_BACKGROUND_ALPHA_PERCENT)
            .coerceIn(MIN_EXTERNAL_SUBTITLE_BACKGROUND_ALPHA_PERCENT, MAX_EXTERNAL_SUBTITLE_BACKGROUND_ALPHA_PERCENT)

    fun saveExternalSubtitleBackgroundAlphaPercent(value: Int) {
        prefs.edit()
            .putInt(KEY_EXTERNAL_SUBTITLE_BACKGROUND_ALPHA_PERCENT, value.coerceIn(MIN_EXTERNAL_SUBTITLE_BACKGROUND_ALPHA_PERCENT, MAX_EXTERNAL_SUBTITLE_BACKGROUND_ALPHA_PERCENT))
            .apply()
    }

    fun getPlayerProgressBarWidthDp(): Int =
        prefs.getInt(KEY_PLAYER_PROGRESS_BAR_WIDTH_DP, DEFAULT_PLAYER_PROGRESS_BAR_WIDTH_DP)
            .coerceIn(MIN_PLAYER_PROGRESS_BAR_WIDTH_DP, MAX_PLAYER_PROGRESS_BAR_WIDTH_DP)

    fun savePlayerProgressBarWidthDp(value: Int) {
        prefs.edit().putInt(KEY_PLAYER_PROGRESS_BAR_WIDTH_DP, value.coerceIn(MIN_PLAYER_PROGRESS_BAR_WIDTH_DP, MAX_PLAYER_PROGRESS_BAR_WIDTH_DP)).apply()
    }

    fun getPlayerProgressBarColor(): Int = prefs.getInt(KEY_PLAYER_PROGRESS_BAR_COLOR, DEFAULT_PLAYER_PROGRESS_BAR_COLOR)

    fun savePlayerProgressBarColor(value: Int) {
        prefs.edit().putInt(KEY_PLAYER_PROGRESS_BAR_COLOR, value).apply()
    }

    fun getPlayerProgressBarAlphaPercent(): Int =
        prefs.getInt(KEY_PLAYER_PROGRESS_BAR_ALPHA_PERCENT, DEFAULT_PLAYER_PROGRESS_BAR_ALPHA_PERCENT)
            .coerceIn(MIN_PLAYER_PROGRESS_BAR_ALPHA_PERCENT, MAX_PLAYER_PROGRESS_BAR_ALPHA_PERCENT)

    fun savePlayerProgressBarAlphaPercent(value: Int) {
        prefs.edit().putInt(KEY_PLAYER_PROGRESS_BAR_ALPHA_PERCENT, value.coerceIn(MIN_PLAYER_PROGRESS_BAR_ALPHA_PERCENT, MAX_PLAYER_PROGRESS_BAR_ALPHA_PERCENT)).apply()
    }

    fun isExternalSubtitleEnabled(mediaKey: String): Boolean =
        prefs.getBoolean(externalSubtitleKey(KEY_EXTERNAL_SUBTITLE_ENABLED_PREFIX, mediaKey), false)

    fun saveExternalSubtitleEnabled(mediaKey: String, enabled: Boolean) {
        prefs.edit()
            .putBoolean(externalSubtitleKey(KEY_EXTERNAL_SUBTITLE_ENABLED_PREFIX, mediaKey), enabled)
            .apply()
    }

    fun getPreferredExternalSubtitleName(mediaKey: String): String =
        prefs.getString(externalSubtitleKey(KEY_EXTERNAL_SUBTITLE_NAME_PREFIX, mediaKey), null).orEmpty()

    fun savePreferredExternalSubtitle(mediaKey: String, subtitleName: String, enabled: Boolean = true) {
        prefs.edit()
            .putString(externalSubtitleKey(KEY_EXTERNAL_SUBTITLE_NAME_PREFIX, mediaKey), subtitleName)
            .putBoolean(externalSubtitleKey(KEY_EXTERNAL_SUBTITLE_ENABLED_PREFIX, mediaKey), enabled)
            .apply()
    }

    fun getCustomJsonScrapeConfigs(): List<CustomJsonScrapeConfig> {
        val stored = prefs.getString(KEY_CUSTOM_JSON_SCRAPE_CONFIGS, null).orEmpty()
        val parsed = runCatching {
            val array = JSONArray(stored)
            (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.toCustomJsonScrapeConfig()
            }
        }.getOrDefault(emptyList())
        return parsed.ifEmpty { listOf(getLegacyCustomJsonScrapeConfig()) }
    }

    fun getSelectedCustomJsonScrapeConfigIndex(): Int =
        prefs.getInt(KEY_CUSTOM_JSON_SCRAPE_SELECTED_INDEX, 0)
            .coerceIn(0, (getCustomJsonScrapeConfigs().size - 1).coerceAtLeast(0))

    fun getCustomJsonScrapeConfig(): CustomJsonScrapeConfig {
        val configs = getCustomJsonScrapeConfigs()
        return configs.getOrElse(getSelectedCustomJsonScrapeConfigIndex()) { configs.firstOrNull() ?: CustomJsonScrapeConfig() }
    }

    private fun getLegacyCustomJsonScrapeConfig(): CustomJsonScrapeConfig =
        CustomJsonScrapeConfig(
            enabled = prefs.getBoolean(KEY_CUSTOM_JSON_SCRAPE_ENABLED, false),
            name = prefs.getString(KEY_CUSTOM_JSON_SCRAPE_NAME, null).orEmpty().ifBlank { "自定义 JSON" },
            urlTemplate = prefs.getString(KEY_CUSTOM_JSON_SCRAPE_URL_TEMPLATE, null).orEmpty(),
            sampleNumber = prefs.getString(KEY_CUSTOM_JSON_SCRAPE_SAMPLE_NUMBER, null).orEmpty().ifBlank { "SSIS-115" },
            resultPath = prefs.getString(KEY_CUSTOM_JSON_SCRAPE_RESULT_PATH, null).orEmpty().ifBlank { "$" },
            numberPath = prefs.getString(KEY_CUSTOM_JSON_SCRAPE_NUMBER_PATH, null).orEmpty().ifBlank { "$.number" },
            titlePath = prefs.getString(KEY_CUSTOM_JSON_SCRAPE_TITLE_PATH, null).orEmpty().ifBlank { "$.title" },
            originalTitlePath = prefs.getString(KEY_CUSTOM_JSON_SCRAPE_ORIGINAL_TITLE_PATH, null).orEmpty(),
            plotPath = prefs.getString(KEY_CUSTOM_JSON_SCRAPE_PLOT_PATH, null).orEmpty().ifBlank { "$.plot" },
            premieredPath = prefs.getString(KEY_CUSTOM_JSON_SCRAPE_PREMIERED_PATH, null).orEmpty().ifBlank { "$.premiered" },
            runtimePath = prefs.getString(KEY_CUSTOM_JSON_SCRAPE_RUNTIME_PATH, null).orEmpty().ifBlank { "$.runtime" },
            studioPath = prefs.getString(KEY_CUSTOM_JSON_SCRAPE_STUDIO_PATH, null).orEmpty().ifBlank { "$.studio" },
            seriesPath = prefs.getString(KEY_CUSTOM_JSON_SCRAPE_SERIES_PATH, null).orEmpty().ifBlank { "$.series" },
            actorsPath = prefs.getString(KEY_CUSTOM_JSON_SCRAPE_ACTORS_PATH, null).orEmpty().ifBlank { "$.actors" },
            genresPath = prefs.getString(KEY_CUSTOM_JSON_SCRAPE_GENRES_PATH, null).orEmpty().ifBlank { "$.genres" },
            tagsPath = prefs.getString(KEY_CUSTOM_JSON_SCRAPE_TAGS_PATH, null).orEmpty().ifBlank { "$.tags" },
            ratingPath = prefs.getString(KEY_CUSTOM_JSON_SCRAPE_RATING_PATH, null).orEmpty().ifBlank { "$.rating" },
            posterPath = prefs.getString(KEY_CUSTOM_JSON_SCRAPE_POSTER_PATH, null).orEmpty().ifBlank { "$.poster" },
            thumbPath = prefs.getString(KEY_CUSTOM_JSON_SCRAPE_THUMB_PATH, null).orEmpty().ifBlank { "$.thumb" },
            fanartPath = prefs.getString(KEY_CUSTOM_JSON_SCRAPE_FANART_PATH, null).orEmpty().ifBlank { "$.fanart" }
        )

    fun saveCustomJsonScrapeConfig(config: CustomJsonScrapeConfig) {
        val configs = getCustomJsonScrapeConfigs().toMutableList()
        val selected = getSelectedCustomJsonScrapeConfigIndex().coerceIn(0, (configs.size - 1).coerceAtLeast(0))
        if (configs.isEmpty()) configs += config else configs[selected] = config
        saveCustomJsonScrapeConfigs(configs, selected)
    }

    fun saveCustomJsonScrapeConfigs(configs: List<CustomJsonScrapeConfig>, selectedIndex: Int) {
        val normalized = configs.ifEmpty { listOf(CustomJsonScrapeConfig(name = "自定义 JSON")) }
        val selected = selectedIndex.coerceIn(0, normalized.lastIndex)
        val selectedConfig = normalized[selected]
        prefs.edit()
            .putString(KEY_CUSTOM_JSON_SCRAPE_CONFIGS, JSONArray(normalized.map { it.toJsonObject() }).toString())
            .putInt(KEY_CUSTOM_JSON_SCRAPE_SELECTED_INDEX, selected)
            .putLegacyCustomJsonScrapeConfig(selectedConfig)
            .apply()
    }

    private fun android.content.SharedPreferences.Editor.putLegacyCustomJsonScrapeConfig(config: CustomJsonScrapeConfig): android.content.SharedPreferences.Editor =
        putBoolean(KEY_CUSTOM_JSON_SCRAPE_ENABLED, config.enabled)
            .putString(KEY_CUSTOM_JSON_SCRAPE_NAME, config.name.trim())
            .putString(KEY_CUSTOM_JSON_SCRAPE_URL_TEMPLATE, config.urlTemplate.trim())
            .putString(KEY_CUSTOM_JSON_SCRAPE_SAMPLE_NUMBER, config.sampleNumber.trim())
            .putString(KEY_CUSTOM_JSON_SCRAPE_RESULT_PATH, config.resultPath.trim())
            .putString(KEY_CUSTOM_JSON_SCRAPE_NUMBER_PATH, config.numberPath.trim())
            .putString(KEY_CUSTOM_JSON_SCRAPE_TITLE_PATH, config.titlePath.trim())
            .putString(KEY_CUSTOM_JSON_SCRAPE_ORIGINAL_TITLE_PATH, config.originalTitlePath.trim())
            .putString(KEY_CUSTOM_JSON_SCRAPE_PLOT_PATH, config.plotPath.trim())
            .putString(KEY_CUSTOM_JSON_SCRAPE_PREMIERED_PATH, config.premieredPath.trim())
            .putString(KEY_CUSTOM_JSON_SCRAPE_RUNTIME_PATH, config.runtimePath.trim())
            .putString(KEY_CUSTOM_JSON_SCRAPE_STUDIO_PATH, config.studioPath.trim())
            .putString(KEY_CUSTOM_JSON_SCRAPE_SERIES_PATH, config.seriesPath.trim())
            .putString(KEY_CUSTOM_JSON_SCRAPE_ACTORS_PATH, config.actorsPath.trim())
            .putString(KEY_CUSTOM_JSON_SCRAPE_GENRES_PATH, config.genresPath.trim())
            .putString(KEY_CUSTOM_JSON_SCRAPE_TAGS_PATH, config.tagsPath.trim())
            .putString(KEY_CUSTOM_JSON_SCRAPE_RATING_PATH, config.ratingPath.trim())
            .putString(KEY_CUSTOM_JSON_SCRAPE_POSTER_PATH, config.posterPath.trim())
            .putString(KEY_CUSTOM_JSON_SCRAPE_THUMB_PATH, config.thumbPath.trim())
            .putString(KEY_CUSTOM_JSON_SCRAPE_FANART_PATH, config.fanartPath.trim())

    private fun CustomJsonScrapeConfig.toJsonObject(): JSONObject = JSONObject().apply {
        put("enabled", enabled)
        put("name", name)
        put("urlTemplate", urlTemplate)
        put("sampleNumber", sampleNumber)
        put("resultPath", resultPath)
        put("numberPath", numberPath)
        put("titlePath", titlePath)
        put("originalTitlePath", originalTitlePath)
        put("plotPath", plotPath)
        put("premieredPath", premieredPath)
        put("runtimePath", runtimePath)
        put("studioPath", studioPath)
        put("seriesPath", seriesPath)
        put("actorsPath", actorsPath)
        put("genresPath", genresPath)
        put("tagsPath", tagsPath)
        put("ratingPath", ratingPath)
        put("posterPath", posterPath)
        put("thumbPath", thumbPath)
        put("fanartPath", fanartPath)
        put("postProcessSpec", postProcessSpec)
    }

    private fun JSONObject.toCustomJsonScrapeConfig(): CustomJsonScrapeConfig = CustomJsonScrapeConfig(
        enabled = optBoolean("enabled", false),
        name = optString("name").ifBlank { "自定义 JSON" },
        urlTemplate = optString("urlTemplate"),
        sampleNumber = optString("sampleNumber").ifBlank { "SSIS-115" },
        resultPath = optString("resultPath").ifBlank { "$" },
        numberPath = optString("numberPath").ifBlank { "$.number" },
        titlePath = optString("titlePath").ifBlank { "$.title" },
        originalTitlePath = optString("originalTitlePath"),
        plotPath = optString("plotPath").ifBlank { "$.plot" },
        premieredPath = optString("premieredPath").ifBlank { "$.premiered" },
        runtimePath = optString("runtimePath").ifBlank { "$.runtime" },
        studioPath = optString("studioPath").ifBlank { "$.studio" },
        seriesPath = optString("seriesPath").ifBlank { "$.series" },
        actorsPath = optString("actorsPath").ifBlank { "$.actors" },
        genresPath = optString("genresPath").ifBlank { "$.genres" },
        tagsPath = optString("tagsPath").ifBlank { "$.tags" },
        ratingPath = optString("ratingPath").ifBlank { "$.rating" },
        posterPath = optString("posterPath").ifBlank { "$.poster" },
        thumbPath = optString("thumbPath").ifBlank { "$.thumb" },
        fanartPath = optString("fanartPath").ifBlank { "$.fanart" },
        postProcessSpec = optString("postProcessSpec"),
    )

    fun getExternalSubtitleOffsetMs(mediaKey: String, subtitleKey: String): Long =
        prefs.getLong(externalSubtitleOffsetKey(mediaKey, subtitleKey), 0L)
            .coerceIn(MIN_EXTERNAL_SUBTITLE_OFFSET_MS, MAX_EXTERNAL_SUBTITLE_OFFSET_MS)

    fun saveExternalSubtitleOffsetMs(mediaKey: String, subtitleKey: String, offsetMs: Long) {
        val key = externalSubtitleOffsetKey(mediaKey, subtitleKey)
        val normalized = offsetMs.coerceIn(MIN_EXTERNAL_SUBTITLE_OFFSET_MS, MAX_EXTERNAL_SUBTITLE_OFFSET_MS)
        prefs.edit().apply {
            if (normalized == 0L) {
                remove(key)
            } else {
                putLong(key, normalized)
            }
        }.apply()
    }

    private fun externalSubtitleKey(prefix: String, mediaKey: String): String =
        prefix + mediaKey.sha256()

    private fun externalSubtitleOffsetKey(mediaKey: String, subtitleKey: String): String =
        KEY_EXTERNAL_SUBTITLE_OFFSET_PREFIX + "$mediaKey|$subtitleKey".sha256()

    private fun normalizeUpdateProxyBaseUrl(value: String): String {
        val trimmed = value.trim()
        if (trimmed.isBlank()) return ""
        return trimmed.trimEnd('/') + "/"
    }

    private fun String.normalizedNumberPrefixOrNull(): String? =
        trim()
            .uppercase()
            .filter { it.isLetterOrDigit() }
            .takeIf { it.isNotBlank() }

    private fun String.normalizedMgstageNumberPrefixOrNull(): String? =
        normalizedNumberPrefixOrNull()
            ?.takeIf { value -> value.any { it.isLetter() } }

    private fun parseMgstageSearchPrefixAliases(jsonText: String): Map<String, String>? =
        runCatching {
            val json = JSONObject(jsonText)
            normalizeMgstageSearchPrefixAliases(
                json.keys().asSequence()
                .mapNotNull { key ->
                    val normalizedKey = key.normalizedMgstageNumberPrefixOrNull() ?: return@mapNotNull null
                    val normalizedValue = json.optString(key).normalizedMgstageNumberPrefixOrNull() ?: return@mapNotNull null
                    normalizedKey to normalizedValue
                }
                .toMap()
            )
        }.getOrNull()

    private fun parseNumberPrefixRewriteRules(jsonText: String): List<NumberPrefixRewriteRule>? =
        runCatching {
            JSONArray(jsonText).let { array ->
                buildList {
                    for (index in 0 until array.length()) {
                        val item = array.optJSONObject(index) ?: continue
                        val prefix = item.optString("prefix").normalizedNumberPrefixOrNull()
                            ?.dropWhile(Char::isDigit)
                            ?.takeIf { it.isNotBlank() && it.any(Char::isLetter) }
                            ?: continue
                        val numericPrefix = item.optString("numericPrefix").filter(Char::isDigit)
                        val sources = item.optJSONArray("sources")
                            ?.let { values ->
                                buildSet {
                                    for (sourceIndex in 0 until values.length()) {
                                        ScrapeSource.fromStoredName(values.optString(sourceIndex))
                                            ?.takeIf { it != ScrapeSource.Priority }
                                            ?.let(::add)
                                    }
                                }
                            }
                            .orEmpty()
                        if (numericPrefix.isNotBlank() && sources.isNotEmpty()) {
                            add(NumberPrefixRewriteRule(prefix, numericPrefix, sources))
                        }
                    }
                }.distinctBy { it.prefix }
            }
        }.getOrNull()

    private fun List<NumberPrefixRewriteRule>.toJsonString(): String =
        JSONArray().also { array ->
            forEach { rule ->
                array.put(
                    JSONObject()
                        .put("prefix", rule.prefix)
                        .put("numericPrefix", rule.numericPrefix)
                        .put("sources", JSONArray(rule.sources.map(ScrapeSource::name)))
                )
            }
        }.toString()

    private fun normalizeMgstageSearchPrefixAliases(aliases: Map<String, String>): Map<String, String> =
        aliases.mapNotNull { (key, value) ->
            val normalizedKey = key.normalizedMgstageNumberPrefixOrNull() ?: return@mapNotNull null
            val normalizedValue = value.normalizedMgstageNumberPrefixOrNull() ?: return@mapNotNull null
            val numericPrefix = normalizedValue.takeWhile(Char::isDigit)
            val valuePrefix = normalizedValue.drop(numericPrefix.length)
                .normalizedMgstageNumberPrefixOrNull()
            val canonicalPrefix = if (numericPrefix.isNotEmpty() && valuePrefix != null) {
                valuePrefix
            } else {
                normalizedKey
            }
            canonicalPrefix to if (numericPrefix.isEmpty()) canonicalPrefix else "$numericPrefix$canonicalPrefix"
        }.toMap()

    private fun Map<String, String>.toJsonObjectString(): String {
        val json = JSONObject()
        forEach { (key, value) -> json.put(key, value) }
        return json.toString()
    }

    private fun Map<String, String>.toPrefixNumberMappings(): Map<String, String> =
        mapValues { (prefix, searchPrefix) ->
            searchPrefix.removeSuffix(prefix).filter(Char::isDigit)
        }

    private fun String.sha256(): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun getTreeDisplayName(uriString: String?): String? {
        val uri = uriString?.let { Uri.parse(it) } ?: return null
        DocumentFile.fromTreeUri(appContext, uri)?.name?.takeIf { it.isNotBlank() }?.let { return it }
        return uri.lastPathSegment
            ?.substringAfterLast(':')
            ?.substringAfterLast('/')
            ?.takeIf { it.isNotBlank() }
    }

    companion object {
        /** 敏感凭据加密存储文件名（与 Cloud115CookieProvider 共享，备份规则会排除此文件）。 */
        const val SECURE_PREFS_NAME = Cloud115CookieProvider.SECURE_PREFS_NAME
        private const val OLD_DEFAULT_STRM_BASE_URL = "http://118.145.114.4:5000"
        const val DEFAULT_STRM_BASE_URL = "http://127.0.0.1"
        const val KEY_STRM_TREE_URI = "strm_tree_uri"
        const val KEY_STRM_TREE_URI_HISTORY = "strm_tree_uri_history"
        const val KEY_STRM_BASE_URL = "strm_base_url"
        const val KEY_LIBRARY_ROOT_URI = "library_root_uri"
        const val KEY_LIBRARY_ROOT_URI_HISTORY = "library_root_uri_history"
        const val KEY_AVSUBTITLES_COOKIES = "avsubtitles_cookies"
        const val KEY_SUBTITLE_SEARCH_PROVIDER = "subtitle_search_provider"
        const val KEY_CLOUD115_LOGIN_APP = "cloud115_login_app"
        const val KEY_GUANGYA_LOGGED_IN = "guangya_logged_in"
        const val KEY_CLOUD_PROVIDER_INDEX = "cloud_provider_index"
        const val KEY_SOURCE_BACKFILL_DONE = "source_backfill_done"
        const val KEY_GUANGYA_LOCATION = "guangya_location"
        const val KEY_UPDATE_MANIFEST_URL = "update_manifest_url"
        const val DEFAULT_UPDATE_MANIFEST_URL =
            "https://github.com/water2100/HomeMovie/releases/latest/download/latest.json"
        const val KEY_UPDATE_PROXY_BASE_URL = "update_proxy_base_url"
        const val DEFAULT_UPDATE_PROXY_BASE_URL = "https://v4.gh-proxy.org/"
        const val KEY_UPDATE_PROXY_ENABLED = "update_proxy_enabled"
        const val KEY_UPDATE_AUTO_CHECK_ON_STARTUP_ENABLED = "update_auto_check_on_startup_enabled"
        const val KEY_UPDATE_AUTO_DELETE_INSTALLED_APK_ENABLED = "update_auto_delete_installed_apk_enabled"
        const val KEY_STARTUP_ANIMATION_ENABLED = "startup_animation_enabled"
        const val KEY_STARTUP_ANIMATION_IMAGE_URI = "startup_animation_image_uri"
        const val KEY_PENDING_UPDATE_INSTALL_VERSION_CODE = "pending_update_install_version_code"
        const val KEY_PENDING_UPDATE_INSTALL_APK_PATH = "pending_update_install_apk_path"
        const val KEY_DEFAULT_SCRAPE_SOURCE = "default_scrape_source"
        const val KEY_PRIORITY_SCRAPE_SOURCES = "priority_scrape_sources"
        const val KEY_IMAGE_DOWNLOAD_RETRY_COUNT = "image_download_retry_count"
        const val KEY_SCRAPE_RETRY_COUNT = "scrape_retry_count"
        const val KEY_SCRAPE_CONCURRENCY_LIMIT = "scrape_concurrency_limit"
        const val KEY_SCRAPE_PROXY_ADDRESS = "scrape_proxy_address"
        const val KEY_SCRAPE_PROXY_ENABLED = "scrape_proxy_enabled"

        /** 三档代理模式（environment / direct / proxy）；旧布尔键只在迁移时读一次。 */
        const val KEY_SCRAPE_PROXY_MODE = "scrape_proxy_mode"
        const val KEY_DMM2_SKIPPED_NUMBER_PREFIXES = "dmm2_skipped_number_prefixes"
        const val KEY_NUMBER_PREFIX_REWRITE_RULES = "number_prefix_rewrite_rules"
        const val KEY_MGSTAGE_AMATEUR_PRIORITY_ENABLED = "mgstage_amateur_priority_enabled"

        /**
         * 刮削链模式（`peach_primary` / `legacy_only`）。
         *
         * 键名与候选值见 `scraper/ScrapeChainMode`。缺键时读成桃链优先——老安装升级上来
         * prefs 里没有这个键，默认值必须落在读侧，不能依赖任何一次写入。
         */
        const val KEY_SCRAPE_CHAIN_MODE = "scrape_chain_mode"
        const val KEY_CACHED_MGSTAGE_NUMBER_PREFIXES = "cached_mgstage_number_prefixes"
        const val KEY_CACHED_MGSTAGE_SEARCH_PREFIX_ALIASES = "cached_mgstage_search_prefix_aliases"
        const val KEY_CACHED_NUMBER_RECOGNITION_IGNORED_SUFFIXES = "cached_number_recognition_ignored_suffixes"
        const val KEY_CACHED_NUMBER_RECOGNITION_PART_MARKERS = "cached_number_recognition_part_markers"
        const val KEY_CACHED_NUMBER_RECOGNITION_ATTACHED_LETTER_PREFIXES = "cached_number_recognition_attached_letter_prefixes"
        const val KEY_CUSTOM_MGSTAGE_NUMBER_PREFIXES = "custom_mgstage_number_prefixes"
        const val KEY_CUSTOM_MGSTAGE_SEARCH_PREFIX_ALIASES = "custom_mgstage_search_prefix_aliases"
        const val KEY_HOME_SORT_OPTION = "home_sort_option"
        const val KEY_HOME_SORT_DIRECTION = "home_sort_direction"
        const val KEY_GUANGYA_RENAME_TEMPLATES = "guangya_rename_templates"
        const val KEY_LOCAL_SUBTITLE_LIBRARY_URI = "local_subtitle_library_uri"
        const val KEY_LOCAL_SUBTITLE_LIBRARY_INDEXED_AT = "local_subtitle_library_indexed_at"
        const val KEY_CLOUD_SORT_OPTION = "cloud_sort_option"
        const val KEY_CLOUD_SORT_ASCENDING = "cloud_sort_ascending"
        const val KEY_PLAYER_SEEK_BACK_SECONDS = "player_seek_back_seconds"
        const val KEY_PLAYER_SEEK_FORWARD_SECONDS = "player_seek_forward_seconds"
        const val KEY_PLAYER_ENGINE = "player_engine"
        const val KEY_MPV_HWDEC = "mpv_hwdec"

        /** mpv 解码档位（hw_plus / hw / sw）；见 [MpvHwdecMode]。 */
        const val KEY_MPV_HWDEC_MODE = "mpv_hwdec_mode"

        /** 实时调色开关 / 尺寸 / 强度。7 个色彩参数的键在 [LutParamKey.prefKey] 里。 */
        const val KEY_LUT_ENABLED = "lut_enabled"
        const val KEY_LUT_SIZE = "lut_size"
        const val KEY_LUT_STRENGTH = "lut_strength"

        /**
         * JavDB TOP 榜单端点。
         *
         * 与 4.0.0 的 `javdb_api_url` 同样是可配置项：镜像站点会换域名，
         * 写死端点意味着一次域名迁移就让功能整体失效。
         */
        const val KEY_TOP250_ENDPOINT = "top250_endpoint"

        /** 影片卡片左上角的 TOP 榜角标开关（对应 5.0.5 的 card_top_gold_badge_enabled）。 */
        const val KEY_CARD_TOP_GOLD_BADGE_ENABLED = "card_top_gold_badge_enabled"

        /** 榜单默认端点：5.0.5 实际使用的 `top250/` 子路径。 */
        const val DEFAULT_TOP250_ENDPOINT = "https://catembylegacy.fastcdn.dpdns.org/top250/"
        const val PLAYER_ENGINE_EXO = "exo"
        const val PLAYER_ENGINE_MPV = "mpv"
        const val KEY_HOME_IMAGE_MODE = "home_image_mode"
        const val KEY_LIBRARY_NOMEDIA_ENABLED = "library_nomedia_enabled"
        const val KEY_CLOUD_ADD_BUTTON_MESSAGE_ENABLED = "cloud_add_button_message_enabled"
        const val KEY_CLOUD_DELETE_ENABLED = "cloud_delete_enabled"
        const val KEY_CLOUD_EXCLUDED_VIDEO_NAMES = "cloud_excluded_video_names"
        const val KEY_CLOUD_SCRAPE_SKIP_BELOW_SIZE_MB = "cloud_scrape_skip_below_size_mb"
        const val KEY_GUANGYA_BATCH_RENAME_TEMPLATES = "guangya_batch_rename_templates_v2"
        const val KEY_EXTERNAL_SUBTITLE_FONT_SIZE_SP = "external_subtitle_font_size_sp"
        const val KEY_LIGHT_THEME_ENABLED = "light_theme_enabled"
        const val KEY_EXTERNAL_SUBTITLE_BOTTOM_PADDING_PERCENT = "external_subtitle_bottom_padding_percent"
        const val KEY_EXTERNAL_SUBTITLE_BACKGROUND_ALPHA_PERCENT = "external_subtitle_background_alpha_percent"
        const val KEY_PLAYER_PROGRESS_BAR_WIDTH_DP = "player_progress_bar_width_dp"
        const val KEY_PLAYER_PROGRESS_BAR_COLOR = "player_progress_bar_color"
        const val KEY_PLAYER_PROGRESS_BAR_ALPHA_PERCENT = "player_progress_bar_alpha_percent"
        const val KEY_EXTERNAL_SUBTITLE_ENABLED_PREFIX = "external_subtitle_enabled_"
        const val KEY_EXTERNAL_SUBTITLE_NAME_PREFIX = "external_subtitle_name_"
        const val KEY_EXTERNAL_SUBTITLE_OFFSET_PREFIX = "external_subtitle_offset_"
        const val KEY_CUSTOM_JSON_SCRAPE_CONFIGS = "custom_json_scrape_configs"
        const val KEY_CUSTOM_JSON_SCRAPE_SELECTED_INDEX = "custom_json_scrape_selected_index"
        const val KEY_CUSTOM_JSON_SCRAPE_ENABLED = "custom_json_scrape_enabled"
        const val KEY_CUSTOM_JSON_SCRAPE_NAME = "custom_json_scrape_name"
        const val KEY_CUSTOM_JSON_SCRAPE_URL_TEMPLATE = "custom_json_scrape_url_template"
        const val KEY_CUSTOM_JSON_SCRAPE_SAMPLE_NUMBER = "custom_json_scrape_sample_number"
        const val KEY_CUSTOM_JSON_SCRAPE_RESULT_PATH = "custom_json_scrape_result_path"
        const val KEY_CUSTOM_JSON_SCRAPE_NUMBER_PATH = "custom_json_scrape_number_path"
        const val KEY_CUSTOM_JSON_SCRAPE_TITLE_PATH = "custom_json_scrape_title_path"
        const val KEY_CUSTOM_JSON_SCRAPE_ORIGINAL_TITLE_PATH = "custom_json_scrape_original_title_path"
        const val KEY_CUSTOM_JSON_SCRAPE_PLOT_PATH = "custom_json_scrape_plot_path"
        const val KEY_CUSTOM_JSON_SCRAPE_PREMIERED_PATH = "custom_json_scrape_premiered_path"
        const val KEY_CUSTOM_JSON_SCRAPE_RUNTIME_PATH = "custom_json_scrape_runtime_path"
        const val KEY_CUSTOM_JSON_SCRAPE_STUDIO_PATH = "custom_json_scrape_studio_path"
        const val KEY_CUSTOM_JSON_SCRAPE_SERIES_PATH = "custom_json_scrape_series_path"
        const val KEY_CUSTOM_JSON_SCRAPE_ACTORS_PATH = "custom_json_scrape_actors_path"
        const val KEY_CUSTOM_JSON_SCRAPE_GENRES_PATH = "custom_json_scrape_genres_path"
        const val KEY_CUSTOM_JSON_SCRAPE_TAGS_PATH = "custom_json_scrape_tags_path"
        const val KEY_CUSTOM_JSON_SCRAPE_RATING_PATH = "custom_json_scrape_rating_path"
        const val KEY_CUSTOM_JSON_SCRAPE_POSTER_PATH = "custom_json_scrape_poster_path"
        const val KEY_CUSTOM_JSON_SCRAPE_THUMB_PATH = "custom_json_scrape_thumb_path"
        const val KEY_CUSTOM_JSON_SCRAPE_FANART_PATH = "custom_json_scrape_fanart_path"
        const val DEFAULT_IMAGE_DOWNLOAD_RETRY_COUNT = 5
        /**
         * 每个来源的**总尝试次数**（含首次），对齐 peach：`range(len(NETWORK_RETRY_DELAYS) + 1)`
         * = 1 次首发 + 4 次重试 = 5 次，退避 2/4/6/8 秒（见 `StrmScrapeRepository.SCRAPE_RETRY_DELAYS_MILLIS`）。
         *
         * 旧默认是 3（只用到退避表的前两跳）。改 5 不改变「只重试传输层失败」的策略——
         * 403/404/429 仍是一次就换源，多出来的两跳只会在连接被重置这类场景里兑现。
         * 老用户已保存的值不受影响（prefs 里有值就用值），这里只影响新装与重置后。
         */
        const val DEFAULT_SCRAPE_RETRY_COUNT = 5
        const val MAX_SCRAPE_RETRY_COUNT = 10
        const val DEFAULT_SCRAPE_CONCURRENCY_LIMIT = 2
        const val MAX_SCRAPE_CONCURRENCY_LIMIT = 4
        const val DEFAULT_CLOUD_SCRAPE_SKIP_BELOW_SIZE_MB = 100
        const val MAX_CLOUD_SCRAPE_SKIP_BELOW_SIZE_MB = 102400
        val PRIORITY_SCRAPE_SOURCE_OPTIONS = listOf(
            ScrapeSource.Dmm2,
            ScrapeSource.Official,
            ScrapeSource.Mgstage,
            ScrapeSource.Javbus,
            ScrapeSource.TheJavDB,
            // r18.dev 只进选项、不进 DEFAULT_PRIORITY_SCRAPE_SOURCES：
            // 它是 DMM 目录的镜像，与 Dmm2 在数据上高度重叠，默认链里再排一家只是让
            // 每个番号多打一次；但 DMM 在本机网络完全不可达（实测 SSL connect error），
            // 用户需要能手动把它顶到前面当替代路径。
            ScrapeSource.R18Dev,
            // 桃链新接入的三家。必须进选项而不是只活在链内部：用户要能点名它们
            // （详情页「用这家重刮」），否则等于接了个看不见的源。
            //
            // 只进选项、不进 DEFAULT_PRIORITY_SCRAPE_SOURCES：有码链的 peach ROUTES 会先问
            // prestige/faleno/dahlia/makers（本工程前三家未接），再轮到 avbase——把它塞进默认链，
            // 等于给每个有码番号多打一次站。无码/FC2 番号才有必要问 1pondo/fc2，
            // 而这两类番号本来就不会在默认链的三家（Dmm2/TheJavDB/Javbus）拿到结果，
            // 用户遇到时自己勾上即可；默认开启会让既有用户凭空多出陌生站点的请求。
            ScrapeSource.Avbase,
            ScrapeSource.OnePondo,
            ScrapeSource.Fc2,
            // peach 第三批接入的六家：片商官网三家 + FC2 系社区索引三家。
            //
            // 必须进选项，否则它们就是**死代码**：`getPriorityScrapeSources()` 会按这个
            // 白名单过滤存储值，用户即便把名字写进偏好也会被丢掉，桃链的 `configured`
            // 里永远没有它们，`routeForPeach` 排得再对也问不到。设置页「刮削来源优先级」
            // 直接枚举 `ScrapeSource.entries`，所以加进选项后会自动出现，UI 无需改动。
            //
            // 同样只进选项、不进 `DEFAULT_PRIORITY_SCRAPE_SOURCES`：这六家各有明确的
            // 番号形态（片商前缀 / FC2 番号），默认全开等于让每个番号先白打三到六次。
            // 它们在链上的位置由 `ScrapeRouteRules` 按内容类型裁剪与排序决定，
            // 用户勾上之后才会真正进入对应的那条链。
            ScrapeSource.Faleno,
            ScrapeSource.Dahlia,
            ScrapeSource.MakerSite,
            ScrapeSource.Fc2Cmadb,
            ScrapeSource.Fc2PpvDb,
            ScrapeSource.Javten,
            ScrapeSource.CustomJson
        )
        val DEFAULT_PRIORITY_SCRAPE_SOURCES = listOf(ScrapeSource.Dmm2, ScrapeSource.TheJavDB, ScrapeSource.Javbus)
        private val OLD_DEFAULT_PRIORITY_SCRAPE_SOURCES_AFTER_MIGRATION =
            listOf(ScrapeSource.Dmm2, ScrapeSource.Javbus, ScrapeSource.TheJavDB)
        private val OLD_DMM_DEFAULT_PRIORITY_SCRAPE_SOURCES_AFTER_MIGRATION =
            listOf(ScrapeSource.Dmm2, ScrapeSource.Javbus)
        val DEFAULT_DMM2_SKIPPED_NUMBER_PREFIXES = setOf("CUTE")
        // key=规范化前缀，value=带数字的搜索前缀
        val DEFAULT_MGSTAGE_SEARCH_PREFIX_ALIASES = mapOf(
            "SHN" to "116SHN",
            "GANA" to "200GANA",
            "SCUTE" to "229SCUTE",
            "LUXU" to "259LUXU",
            "ARA" to "261ARA",
            "DCV" to "277DCV",
            "EWDX" to "299EWDX",
            "MAAN" to "300MAAN",
            "MIUM" to "300MIUM",
            "NTK" to "300NTK",
            "KIRAY" to "314KIRAY",
            "KJO" to "326KJO",
            "NAMA" to "332NAMA",
            "KNB" to "336KNB",
            "SIMM" to "345SIMM",
            "NTR" to "348NTR",
            "JAC" to "390JAC",
            "KIWVR" to "408KIWVR",
            "INST" to "413INST",
            "SRYA" to "417SRYA",
            "SUKE" to "428SUKE",
            "MFC" to "435MFC",
            "HHH" to "451HHH",
            "TEN" to "459TEN",
            "MLA" to "476MLA",
            "SGK" to "483SGK",
            "GCB" to "485GCB",
            "SEI" to "502SEI",
            "STCV" to "529STCV",
            "MY" to "292MY",
            "DANDY" to "104DANDY",
            "ICHK" to "368ICHK"
        )
        val DEFAULT_MGSTAGE_NUMBER_PREFIXES =
            DEFAULT_MGSTAGE_SEARCH_PREFIX_ALIASES.keys + setOf("SIRO")
        val DEFAULT_NUMBER_RECOGNITION_IGNORED_SUFFIXES = NumberRecognitionRules.DEFAULT_IGNORED_SUFFIXES
        val DEFAULT_NUMBER_RECOGNITION_PART_MARKERS = NumberRecognitionRules.DEFAULT_PART_MARKERS
        const val DEFAULT_EXTERNAL_SUBTITLE_FONT_SIZE_SP = 22
        const val DEFAULT_PLAYER_SEEK_SECONDS = 10
        const val MIN_PLAYER_SEEK_SECONDS = 1
        const val MAX_PLAYER_SEEK_SECONDS = 120
        const val MIN_EXTERNAL_SUBTITLE_FONT_SIZE_SP = 14
        const val MAX_EXTERNAL_SUBTITLE_FONT_SIZE_SP = 40
        const val DEFAULT_EXTERNAL_SUBTITLE_BOTTOM_PADDING_PERCENT = 8
        const val MIN_EXTERNAL_SUBTITLE_BOTTOM_PADDING_PERCENT = 0
        const val MAX_EXTERNAL_SUBTITLE_BOTTOM_PADDING_PERCENT = 30
        const val DEFAULT_EXTERNAL_SUBTITLE_BACKGROUND_ALPHA_PERCENT = 0
        const val MIN_EXTERNAL_SUBTITLE_BACKGROUND_ALPHA_PERCENT = 0
        const val MAX_EXTERNAL_SUBTITLE_BACKGROUND_ALPHA_PERCENT = 80
        const val DEFAULT_PLAYER_PROGRESS_BAR_WIDTH_DP = 4
        const val MIN_PLAYER_PROGRESS_BAR_WIDTH_DP = 2
        const val MAX_PLAYER_PROGRESS_BAR_WIDTH_DP = 12
        const val DEFAULT_PLAYER_PROGRESS_BAR_COLOR = 0xFFFFFFFF.toInt()
        const val DEFAULT_PLAYER_PROGRESS_BAR_ALPHA_PERCENT = 100
        const val MIN_PLAYER_PROGRESS_BAR_ALPHA_PERCENT = 20
        const val MAX_PLAYER_PROGRESS_BAR_ALPHA_PERCENT = 100
        const val MIN_EXTERNAL_SUBTITLE_OFFSET_MS = -600_000L
        const val MAX_EXTERNAL_SUBTITLE_OFFSET_MS = 600_000L
        private const val NOMEDIA_FILE_NAME = ".nomedia"
        private const val NOMEDIA_TEMP_FILE_NAME = "nomedia.tmp"
        // 占位；真实排除项由用户在设置页录入（getCloudExcludedVideoNames 保留「空默认 + 用户自定义」合并语义）
        val DEFAULT_CLOUD_EXCLUDED_VIDEO_NAMES = setOf<String>()
    }
}

/** 光鸭浏览位置快照（持久化用）。 */
data class GuangyaLocationSnapshot(
    val stack: List<String> = emptyList(),
    val currentParentId: String = "",
    val names: List<String> = emptyList()
)
