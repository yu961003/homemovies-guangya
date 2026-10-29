package com.example.localmovielibrarz.scraper

/**
 * 从片商官网首页**当场发现**它的站内搜索入口。
 *
 * ## 为什么需要这一步
 *
 * [OfficialMakerCatalog.BUILT_IN] 的 12 家站点一律留空了详情页/搜索页模板，理由写在那里：
 * 模板写错会把「我们写错了地址」伪装成「这家站上没有这部片」，而后者会让链安静地换下一家，
 * 这类错误在日志里看不出来。可是模板全空又走另一个极端——[OfficialSiteScraper] 会跳过每一家，
 * 三个来源（[ScrapeSource.Faleno] / [ScrapeSource.Dahlia] / [ScrapeSource.MakerSite]）永远是
 * [PeachFailureReason.ParseError]，等于接进来三个必然失败的源。
 *
 * 这条缝隙用一个**有据可查**的办法补上：首页上本来就有一个搜索框，它的 `action` 与
 * 输入框 `name` 是站点自己声明的取数入口，读它既不是猜路径，也不依赖预先核实。
 * 一次请求换来「这家站怎么搜」这个事实，之后在进程内缓存。
 *
 * ## 它不做什么
 *
 * - **不发请求**：只吃一段 HTML 字符串，取页交给调用方（它们知道该走 HTTP 还是浏览器）；
 * - **不猜**：找不到表单就返回 null，由调用方如实报「这家片商没有配置取数模板」。
 *   宁可继续失败，也不构造一条自己没证据的地址；
 * - **不改写站点知识**：发现的地址只活在运行期缓存里，不写回目录、不落库——
 *   站点改版后下一次发现会自然跟上，而写死的模板需要等一次发版。
 */
// 不写 `internal`：测试源集是独立编译单元，`internal` 在那里不可见（同 `PeachFetchBridge`）。
// 换成可见性反而更该锁行为——这一层「猜到路径」的代价是静默失败，用测试钉住它。
object OfficialSearchForm {

    /**
     * 搜索输入框的候选参数名，按「越具体越优先」排列。
     *
     * 这些名字不是从某一家站抄来的，而是「搜索框通常叫什么」这个通用集合：
     * `q` / `s` / `keyword` / `search` / `key` / `word` / `query` / `kw`。
     * 命中哪一个就用哪一个——用错了站点会回 200 + 没有结果，与「搜不到」同形，
     * 所以只取**输入框真的存在**的那一个，不去拼一串候选挨个试。
     */
    private val QUERY_PARAMS = listOf("q", "s", "keyword", "search", "key", "word", "query", "kw")

    /** 输入框 `type` 里哪些算「可以放番号」。`hidden` 不算：它带的是 csrf 之类，塞番号进去无效。 */
    private val USABLE_INPUT_TYPES = setOf("", "text", "search")

    private val FORM = Regex("<form\\b([^>]*)>(.*?)</form>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val ACTION = Regex("\\baction\\s*=\\s*[\"']([^\"']*)[\"']", RegexOption.IGNORE_CASE)
    private val METHOD = Regex("\\bmethod\\s*=\\s*[\"']([^\"']*)[\"']", RegexOption.IGNORE_CASE)
    private val INPUT = Regex("<input\\b([^>]*)>", RegexOption.IGNORE_CASE)
    private val ATTR_NAME = Regex("\\bname\\s*=\\s*[\"']([^\"']*)[\"']", RegexOption.IGNORE_CASE)
    private val ATTR_TYPE = Regex("\\btype\\s*=\\s*[\"']([^\"']*)[\"']", RegexOption.IGNORE_CASE)

    /**
     * 首页 HTML → 一条可以直接拼番号的搜索地址（不含 `=` 之后的番号）。
     *
     * 返回值形如 `https://faleno.jp/search?q=`，调用方接上 URL 编码后的番号即可；
     * 用「带尾随 `=` 的前缀」而不是「带 `{code}` 的模板」表达，是因为这里发现的只是一个
     * 参数名，拼番号那一步属于调用方（它才需要做 URL 编码）。
     *
     * @param homeHtml 首页（或任意一页）HTML。
     * @param homeUrl 用于把表单的相对 `action` 补成绝对地址；同时是没有任何 `action` 时的基准。
     */
    fun discoverSearchPrefix(homeHtml: String?, homeUrl: String): String? {
        val html = homeHtml.orEmpty()
        if (html.isEmpty()) return null

        // 优先 GET 表单：POST 表单不能靠拼 URL 复用，而这里只做「拼一条地址」这件事。
        // `findAll` 按文档顺序产出，所以列表本身的顺序就是「页面靠上」的顺序，
        // 不需要再记 MatchResult 的偏移（`MatchResult` 没有 `index` 属性，只有 `range`）。
        val candidates = FORM.findAll(html).mapNotNull { match ->
            val attrs = match.groupValues[1]
            val method = METHOD.find(attrs)?.groupValues?.get(1)?.trim()?.lowercase().orEmpty()
            if (method.isNotEmpty() && method != "get") return@mapNotNull null
            val action = ACTION.find(attrs)?.groupValues?.get(1)?.trim().orEmpty()
            val param = queryParamOf(match.groupValues[2]) ?: return@mapNotNull null
            action to param
        }.toList()

        if (candidates.isEmpty()) return null

        // 多条时优先「真的有 action 的」，其次页面靠上的（导航区搜索框通常在最前面）。
        val chosen = candidates.firstOrNull { it.first.isNotEmpty() } ?: candidates.first()
        val base = resolve(homeUrl, chosen.first.ifEmpty { homeUrl })
        val param = chosen.second
        return if (base.contains('?')) "$base&$param=" else "$base?$param="
    }

    /** 在表单正文里找一个可用的查询参数名；同一表单里有多个时按 [QUERY_PARAMS] 的顺序取。 */
    private fun queryParamOf(formBody: String): String? {
        val found = INPUT.findAll(formBody).mapNotNull { input ->
            val attrs = input.groupValues[1]
            val name = ATTR_NAME.find(attrs)?.groupValues?.get(1)?.trim().orEmpty()
            if (name.isEmpty()) return@mapNotNull null
            val type = ATTR_TYPE.find(attrs)?.groupValues?.get(1)?.trim()?.lowercase().orEmpty()
            if (type !in USABLE_INPUT_TYPES) return@mapNotNull null
            name
        }.toList()
        if (found.isEmpty()) return null
        return QUERY_PARAMS.firstOrNull { candidate -> found.any { it.equals(candidate, ignoreCase = true) } }
            // 一个都不在通用名里：如果表单**只有一个**文本输入，就用它。
            // 这是「表单自己说是搜索框」的证据，而不是我们编一个参数名。
            ?: found.singleOrNull()
    }

    /**
     * 把表单 `action` 补成绝对地址。
     *
     * 只处理三种最常见形状，够用且不引入 URL 解析依赖：绝对地址、`//host/...`、
     * 以 `/` 开头的站内绝对路径。其余（相对路径、`?` 开头）全部退到 [homeUrl] ——
     * 补错的概率比「用首页自己搜」更高，而后者至少是一条真实存在的地址。
     */
    private fun resolve(homeUrl: String, action: String): String {
        val base = homeUrl.trim()
        val target = action.trim()
        if (target.isEmpty()) return base.trimEnd('/', '?', '&')
        if (target.startsWith("http://", ignoreCase = true) || target.startsWith("https://", ignoreCase = true)) {
            return target.trimEnd('?', '&')
        }
        if (target.startsWith("//")) {
            val scheme = if (base.startsWith("https://", ignoreCase = true)) "https:" else "http:"
            return (scheme + target).trimEnd('?', '&')
        }
        if (target.startsWith("/")) {
            val origin = Regex("^(https?://[^/]+)", RegexOption.IGNORE_CASE).find(base)?.groupValues?.get(1)
                ?: return base.trimEnd('/')
            return (origin + target).trimEnd('?', '&')
        }
        return base.trimEnd('/')
    }
}