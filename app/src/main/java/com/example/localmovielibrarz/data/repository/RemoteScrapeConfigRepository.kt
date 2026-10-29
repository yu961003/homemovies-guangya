package com.example.localmovielibrarz.data.repository

import com.example.localmovielibrarz.util.NumberRecognitionRules

/**
 * 番号识别规则仓库（本地缓存）。
 *
 * 需求 9/3.2 调整后不再提供远程热更新，仅保留本地缓存规则的读取与加载：
 * - 刮削前通过 [getMgstageNumberPrefixes] / [getLocalMgstageSearchPrefixAliases] 取合并后的本地前缀；
 * - 用户编辑的规则由 [AppSettingsRepository] 持久化，[loadCachedNumberRecognitionRules] 负责回灌到
 *   内存中的 [NumberRecognitionRules]。
 */
class RemoteScrapeConfigRepository(
    private val settingsRepository: AppSettingsRepository
) {
    fun getMgstageNumberPrefixes(): Set<String> {
        loadCachedNumberRecognitionRules()
        return settingsRepository.getMergedMgstageNumberPrefixes()
    }

    fun loadCachedNumberRecognitionRules(): Set<String> {
        NumberRecognitionRules.updateIgnoredSuffixes(settingsRepository.getCachedNumberRecognitionIgnoredSuffixes())
        NumberRecognitionRules.updatePartMarkers(settingsRepository.getCachedNumberRecognitionPartMarkers())
        NumberRecognitionRules.updateNumericPrefixAliases(settingsRepository.getMergedMgstageSearchPrefixAliases())
        return settingsRepository.getCachedNumberRecognitionIgnoredSuffixes()
    }

    fun getLocalMgstageSearchPrefixAliases(): Map<String, String> =
        settingsRepository.getMergedMgstageSearchPrefixAliases()
}