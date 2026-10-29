@file:Suppress("SpellCheckingInspection")

package com.xiaoyv.bangumi.shared.core.types

object SubjectCategory {

    /**
     * 分类（[com.xiaoyv.bangumi.shared.core.types.SubjectBrowserBody] 的 `cat` 参数）对应的公共标签。
     *
     * `/p1/search/subjects` 只支持 `metaTags` 过滤，没有 `cat` 参数，所以走搜索接口的浏览必须把
     * 分类翻译成平台标签（名称取自 `bangumi/common` 的 `subject_platforms.json` 的 `type_cn`）。
     * 只收录实测能被搜索索引命中的类型；返回 null 表示该分类只能走 `/p1/subjects`。
     *
     * @param type 条目类型，见 [SubjectType]
     * @param cat 分类 id，0 表示不筛分类
     */
    fun platformMetaTag(type: Int, cat: Int): String? = when (type) {
        SubjectType.ANIME -> when (cat) {
            SubjectAnimeCategory.TV.value -> "TV"
            SubjectAnimeCategory.OVA.value -> "OVA"
            SubjectAnimeCategory.MOVIE.value -> "剧场版"
            SubjectAnimeCategory.WEB.value -> "WEB"
            2006 -> "动态漫画"
            else -> null
        }

        SubjectType.BOOK -> when (cat) {
            1001 -> "漫画"
            1002 -> "小说"
            1003 -> "画集"
            1004 -> "绘本"
            1005 -> "写真"
            1006 -> "公式书"
            else -> null
        }

        SubjectType.GAME -> when (cat) {
            // 4001（游戏）与类型标签“游戏”重名，过滤不出子类，交给 /p1/subjects
            4002 -> "软件"
            4003 -> "扩展包"
            4004 -> "体验版"
            4005 -> "桌游"
            else -> null
        }

        else -> null
    }

    // 书籍类型
    enum class SubjectBookCategory(val value: Int) {
        OTHER(0),
        MANGA(1001),    // 漫画
        NOVEL(1002),    // 小说
        ARTBOOK(1003);  // 画集
    }

    // 动画类型
    enum class SubjectAnimeCategory(val value: Int) {
        OTHER(0),
        TV(1),
        OVA(2),
        MOVIE(3),
        WEB(5)
    }

    // 游戏类型
    enum class SubjectGameCategory(val value: Int) {
        OTHER(0),
        GAME(4001),
        SOFTWARE(4002),
        EXPANSION(4003),
        BOARDGAME(4005) // 桌游
    }

    // 影视类型
    enum class SubjectRealCategory(val value: Int) {
        OTHER(0),
        JAPANESE_DRAMA(1),   // 日剧
        WESTERN_DRAMA(2),    // 欧美剧
        CHINESE_DRAMA(3),    // 华语剧
        TV_SERIES(6001),     // 电视剧
        MOVIE(6002),         // 电影
        PERFORMANCE(6003),   // 演出
        VARIETY(6004)        // 综艺
    }

    /**
     * 该 [cat] 是否属于 [type] 这个条目类型。
     *
     * 分类 id 是按条目类型各自编号的（动画 1/2/3/5、书籍 1001…、游戏 4001…），
     * 切换类型时如果沿用上一个类型的 cat，接口会返回空列表，所以用它做一次兜底校验。
     *
     * @param type 条目类型，见 [SubjectType]
     * @param cat 分类 id，0 表示不筛分类
     */
    fun isValidCat(type: Int, cat: Int): Boolean {
        if (cat == 0) return true
        return when (type) {
            SubjectType.ANIME -> cat in setOf(1, 2, 3, 5, 2006)
            SubjectType.BOOK -> cat in 1001..1006
            SubjectType.GAME -> cat in 4001..4005
            SubjectType.MUSIC -> cat in 3001..3004
            SubjectType.REAL -> cat in setOf(1, 2, 3, 6001, 6002, 6003, 6004)
            else -> true
        }
    }
}