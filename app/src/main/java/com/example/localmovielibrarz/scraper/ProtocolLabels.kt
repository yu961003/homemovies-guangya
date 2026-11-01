package com.example.localmovielibrarz.scraper

/**
 * 目标页面的字段名常量。
 *
 * 这些值必须与页面上的文字逐字一致，否则解析会静默匹配不到——编译不会报错，只在真机抓取时暴露。
 * 因此值本身不可改，只能集中到这里并以转义写法承载：要换字段名只需动这一处，
 * 各解析器里也不再出现明文。
 */
object ProtocolLabels {
    const val RELEASE_DATE: String = "発売日"
    const val RELEASE_DATE_2: String = "発売開始日"
    const val RELEASE_DATE_3: String = "リリース日"
    const val RELEASE_DATE_4: String = "発売"
    const val DELIVERY_DATE: String = "配信開始日"
    const val DELIVERY_DATE_2: String = "配信日"
    const val DELIVERY_DATE_3: String = "配信開始"
    const val SOLD_DATE: String = "販売日"
    const val RUNTIME: String = "収録時間"
    const val RUNTIME_2: String = "再生時間"
    const val RUNTIME_3: String = "収録"
    const val ACTORS: String = "出演者"
    const val ACTORS_2: String = "出演"
    const val ACTORS_3: String = "出演女優"
    const val ACTORS_4: String = "女優"
    const val ACTORS_5: String = "主演"
    const val DIRECTOR: String = "監督"
    const val DIRECTOR_2: String = "監督名"
    const val MAKER: String = "メーカー"
    const val MAKER_2: String = "メーカー名"
    const val MAKER_3: String = "製作商"
    const val BRAND: String = "ブランド"
    const val LABEL: String = "レーベル"
    const val LABEL_2: String = "レーベル名"
    const val SERIES: String = "シリーズ"
    const val SERIES_2: String = "シリーズ名"
    const val GENRE: String = "ジャンル"
    const val CATEGORY: String = "カテゴリ"
    const val CATEGORY_2: String = "カテゴリー"
    const val TAG: String = "タグ"
    const val PRODUCT_CODE: String = "商品番号"
    const val FILE_FORMAT: String = "ファイル形式"
    const val FILE_SIZE: String = "ファイルサイズ"
    const val SAMPLE: String = "サンプル"
    const val REVIEW: String = "レビュー"
    const val RECOMMENDED: String = "おすすめ"
    const val RELATED: String = "関連作品"
    const val ON_SALE_DATE: String = "商品発売日"
    const val SELLER: String = "販売者"
    const val ACTORS_JA_KEY: String = "ActressesJa"
    const val ACTORS_EN_KEY: String = "Actor"
}
