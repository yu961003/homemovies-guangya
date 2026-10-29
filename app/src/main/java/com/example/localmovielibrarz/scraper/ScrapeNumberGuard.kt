package com.example.localmovielibrarz.scraper

/**
 * 刮削结果的番号一致性校验。
 *
 * 为什么需要：`scrapeWithFallback` 成功收尾时会用 `info.copy(number = outputNumber)` 把结果番号
 * 改成文件名番号，**这等于假定了「站点返回的就是这一部」**。但社区站按番号搜索时常见的失败形态
 * 不是「搜不到」，而是「搜到了同号异片」——`259LUXU-891` 被答成 `259LUXU-1891` 这类。
 * 如果不校验，错片的标题、演员、封面就会被写到正确文件名的影片上，而且用户没有任何提示。
 *
 * 为什么是「判冲突」而不是「判相等」：本仓库的番号形态太多（补零 `SSIS-0123`、数字前缀
 * `300MIUM-123`、FC2 `FC2-PPV-1234567`、带分段/版本的 `ABC-123-4K`），任何一处判strict 相等
 * 都会把**正确**的结果拒掉。误拒的代价是用户看到「刮削失败」，误收的代价是元数据被污染，
 * 所以这里采用两层策略：
 * 1. 能归一化成「字母前缀 + 数字」的，比归一化结果，补零与数字前缀视为同一部；
 * 2. 归一化不了的（FC2、带分段、纯数字），一律返回「不冲突」——拿不准就不拦。
 *
 * 即：只有「两边都认得出、且认出来确实不是同一部」时才拦。
 */
object ScrapeNumberGuard {

    /** 归一化后的形状：可选的前导数字 + 字母 + 数字（`300MIUM-123` → `MIUM123`）。 */
    private val SHAPE = Regex("^[0-9]*([A-Z]+)([0-9]+)$")

    /**
     * 判断站点返回的 [returned] 是否与本次查询的 [requested] 冲突。
     *
     * @return true 表示「可以确认不是同一部」，调用方应当拒绝这份结果；
     *         false 表示「是同一部」或「认不出来，放行」。
     */
    fun conflicts(requested: String, returned: String): Boolean {
        val a = canonical(requested) ?: return false
        val b = canonical(returned) ?: return false
        return a != b
    }

    /**
     * 归一化番号：只保留字母数字 → 大写 → 拆成「字母前缀 + 数字」→ 去掉数字的前导零。
     *
     * 返回 null 表示「形态认不出」，调用方据此放行——见类注释的取舍说明。
     */
    fun canonical(raw: String): String? {
        val compact = raw.filter { it.isLetterOrDigit() }.uppercase()
        if (compact.isEmpty()) return null
        val match = SHAPE.matchEntire(compact) ?: return null
        val prefix = match.groupValues[1]
        val digits = match.groupValues[2].trimStart('0').ifEmpty { "0" }
        return prefix + digits
    }
}
