package com.example.localmovielibrarz.scraper

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import com.example.localmovielibrarz.util.TextSanitizer

/**
 * r18.dev 的作品 JSON（DMM 目录的镜像）。
 *
 * 为什么加这一个源而不是 peach 那一整串：
 * - DMM 两家（[DmmScraper]/[Dmm2Scraper]）在本机实测连不上——`curl https://video.dmm.co.jp`
 *   直接 `SSL connect error`（exit 35）。DMM 按出口 IP 封锁日本以外，这不是偶发。DMM 一断，
 *   整条有码链就只剩 Javbus/JavDB 两个 HTML 站，而 r18.dev 是同一份目录的 JSON 镜像，
 *   换一条传输路径把数据取回来，收益是「链路整体不可用」这一档，不是「多一家来源」。
 * - 它免解析 HTML：字段全是 JSON，改版不会像 Javbus 那样把正则全部打废。
 * - 顺带覆盖 peach 归在 `amane_official` 档的 FALENO（FSDSS/FCDSS）与 DAHLIA（DLDSS）——
 *   实测 `dvd_id=FSDSS-001`、`dvd_id=DLDSS-001` 都返回 200，而这两家的官网搜索要么 403
 *   （faleno.jp）要么是 WordPress 站（dahlia-av.jp），单独写爬虫不划算。
 *
 * 为什么只问有码：素人号（MIUM/SIRO/STP）、无码号（1PONDO）、FC2 号在同一接口上实测全部 404，
 * 与 peach 的注释一致（r18.dev 只镜像 DMM 的数字版目录）。所以路由层把它当有码专用源。
 *
 * 两次请求的分工照 peach 的取舍：`dvd_id` 页给结构与英文，`combined` 页给日文（标题、系列、
 * 演员汉字名、分类日文词）。日文那份取不到时**标题不拿英文顶上**——peach 的账本经验是英文标题
 * 一旦落库就成了实体规范名，系列与演员也一样。鸭影这边的对应做法是：标题留空交回来源链，
 * 但发行日/厂牌/封面/genre 两个页面一样可用，照常交出，一次失败不吞掉整条资料。
 */
class R18DevScraper(
    private val client: OkHttpClient = OkHttpClient(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : MovieScraper {
    override val source: ScrapeSource = ScrapeSource.R18Dev

    override suspend fun scrape(number: String): ScrapedMovieInfo = withContext(ioDispatcher) {
        val normalized = ScraperResponseGuard.requireSafeScrapeNumber(number).uppercase()
        val english = fetchJson(dvdIdUrl(normalized), "r18.dev 没有收录该番号：$normalized")
        val contentId = english.optString("content_id").trim()
        if (contentId.isBlank()) error("r18.dev 响应缺少 content_id：$normalized")

        // 日文页失败不该拖垮整次刮削：它只是「更好的标题与演员写法」，
        // 发行日、厂牌、封面、genre 在英文页里是同一份数据。
        val japanese = runCatching { fetchJson(combinedUrl(contentId), "r18.dev 日文页缺失：$contentId") }
            .getOrNull()
            ?.takeIf { it.optString("content_id").trim().equals(contentId, ignoreCase = true) }

        parse(english, japanese, normalized, contentId)
    }

    private fun parse(
        english: JSONObject,
        japanese: JSONObject?,
        number: String,
        contentId: String
    ): ScrapedMovieInfo {
        val titleJa = japanese?.optString("title_ja").orEmpty().cleanText()
        val titleEn = english.optString("title").cleanText()
        val title = titleJa.ifBlank { titleEn }

        val series = japanese?.optString("series_name_ja").orEmpty().cleanText()
            .ifBlank { english.optJSONObject("series")?.optString("name").orEmpty().cleanText() }

        val label = japanese?.optString("label_name_ja").orEmpty().cleanText()
            .ifBlank { english.optJSONObject("label")?.optString("name").orEmpty().cleanText() }
        val maker = japanese?.optString("maker_name_ja").orEmpty().cleanText()
            .ifBlank { english.optJSONObject("maker")?.optString("name").orEmpty().cleanText() }

        // 演员：日文页给 name_kanji（DMM 登记的艺名）+ name_kana + 头像文件名；
        // 英文页只有 name（罗马字）。取名字带假名或汉字的那一份，罗马字当兜底。
        val actresses = japanese?.optJSONArray("actresses") ?: english.optJSONArray("actresses")
        val actorNames = mutableListOf<String>()
        val actorImages = mutableMapOf<String, String>()
        for (index in 0 until (actresses?.length() ?: 0)) {
            val row = actresses?.optJSONObject(index) ?: continue
            val name = row.optString("name_kanji").cleanText()
                .ifBlank { row.optString("name").cleanText() }
                .ifBlank { row.optString("name_romaji").cleanText() }
            if (name.isBlank()) continue
            actorNames += name
            // 头像在 DMM 的固定目录下，JSON 里只给文件名（`ikuta_minami.jpg`）；
            // 带路径的值说明对方已经给了完整地址，直接用。
            val imageName = row.optString("image_url").trim()
            if (imageName.isNotEmpty()) {
                val thumbUrl = if (imageName.contains('/')) {
                    imageName
                } else {
                    "https://pics.dmm.co.jp/mono/actjpgs/$imageName"
                }
                actorImages[name] = thumbUrl
            }
        }

        // genre 取日文词（DMM 自己那套），英文是 r18 在上面再译一层：
        // `その他フェチ` 从 `Other Fetishes` 反推不回去。
        val genreRows = japanese?.optJSONArray("categories") ?: english.optJSONArray("categories")
        val genres = (0 until (genreRows?.length() ?: 0)).mapNotNull { index ->
            val row = genreRows?.optJSONObject(index) ?: return@mapNotNull null
            row.optString("name_ja").cleanText().ifBlank { row.optString("name").cleanText() }
                .takeIf { it.isNotBlank() }
        }.distinct()

        val director = japanese?.optJSONArray("directors")?.let { rows ->
            (0 until rows.length()).mapNotNull { index ->
                (rows.optJSONObject(index)?.optString("name_kanji").orEmpty()).cleanText()
                    .takeIf { it.isNotBlank() }
            }
        }.orEmpty().ifEmpty {
            listOfNotNull(english.optString("director").cleanText().takeIf { it.isNotBlank() })
        }

        val releaseDate = english.optString("release_date").cleanText()
        val runtimeMinutes = english.optInt("runtime_minutes", 0)
        val jacket = english.optJSONObject("images")
            ?.optJSONObject("jacket_image")
            ?.let { image ->
                image.optString("large2").cleanText().ifBlank { image.optString("large").cleanText() }
            }
            .orEmpty()

        val gallery = mutableListOf<String>()
        val galleryRows = japanese?.optJSONArray("gallery") ?: english.optJSONArray("gallery")
        for (index in 0 until (galleryRows?.length() ?: 0)) {
            val row = galleryRows?.optJSONObject(index) ?: continue
            row.optString("image_full").cleanText().takeIf { it.isNotBlank() }?.let { gallery += it }
        }

        val sample = english.optJSONObject("sample")?.let { row ->
            row.optString("high").cleanText().ifBlank { row.optString("low").cleanText() }
        }.orEmpty()

        return ScrapedMovieInfo(
            number = number,
            title = title.ifBlank { number },
            originalTitle = title.ifBlank { number },
            plot = english.optString("comment_en").cleanText(),
            outline = english.optString("comment_en").cleanText(),
            year = Regex("""\d{4}""").find(releaseDate)?.value.orEmpty(),
            premiered = releaseDate,
            runtime = if (runtimeMinutes > 0) runtimeMinutes.toString() else "",
            studio = maker,
            publisher = label.ifBlank { maker },
            series = series,
            directors = director,
            actors = actorNames.distinct(),
            actorImageUrls = actorImages,
            genres = genres,
            tags = genres,
            trailer = sample,
            website = "https://r18.dev/videos/vod/movies/detail/-/dvd_id=$number/json",
            source = "r18.dev",
            thumbUrl = jacket,
            posterUrl = jacket,
            thumbImageUrls = gallery.take(6),
            posterImageUrls = gallery.take(6)
        ).also {
            require(contentId.isNotBlank()) { "r18.dev content_id 为空" }
        }
    }

    private fun dvdIdUrl(number: String): String = "$BASE_URL/videos/vod/movies/detail/-/dvd_id=$number/json"

    private fun combinedUrl(contentId: String): String =
        "$BASE_URL/videos/vod/movies/detail/-/combined=$contentId/json"

    private fun fetchJson(url: String, missingMessage: String): JSONObject {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", "$BASE_URL/")
            .header("Accept", "application/json")
            .build()
        return client.newCall(request).execute().use { response ->
            val body = response.body ?: error("$missingMessage（响应为空）")
            val bytes = ScraperResponseGuard.readLimited(body, ScrapeSource.R18Dev)
            if (!response.isSuccessful) {
                // 404 是确定性失败（这个号不在 DMM 目录里），交给分类器判 NotFound；
                // 403/429 才是风控，分类器按状态码归到 Forbidden/RateLimited，冷却交给来源健康度。
                error("r18.dev 请求失败 HTTP ${response.code}：$missingMessage")
            }
            val text = bytes.decodeToString()
            runCatching { JSONObject(text) }
                .getOrElse { error("r18.dev 响应不是合法 JSON：$missingMessage") }
        }
    }

    private fun String.cleanText(): String = TextSanitizer.cleanSingleLine(this).trim()

    private companion object {
        const val BASE_URL = "https://r18.dev"
        const val USER_AGENT = ScrapeUserAgent.VALUE
    }
}
