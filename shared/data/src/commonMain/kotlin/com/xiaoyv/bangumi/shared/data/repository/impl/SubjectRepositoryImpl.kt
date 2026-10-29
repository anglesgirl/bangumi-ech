package com.xiaoyv.bangumi.shared.data.repository.impl

import androidx.paging.PagingConfig
import com.xiaoyv.bangumi.shared.core.types.CollectionType
import com.xiaoyv.bangumi.shared.core.types.EpisodeType
import com.xiaoyv.bangumi.shared.core.types.MonoType
import com.xiaoyv.bangumi.shared.core.types.PersonType
import com.xiaoyv.bangumi.shared.core.types.SubjectCategory
import com.xiaoyv.bangumi.shared.core.types.SubjectSortBrowserType
import com.xiaoyv.bangumi.shared.core.types.SubjectType
import com.xiaoyv.bangumi.shared.core.types.SubjectWebPath
import com.xiaoyv.bangumi.shared.core.types.list.ListSubjectType
import com.xiaoyv.bangumi.shared.core.types.list.ListTagType
import com.xiaoyv.bangumi.shared.core.utils.fetchAllPages
import com.xiaoyv.bangumi.shared.core.utils.runResult
import com.xiaoyv.bangumi.shared.core.utils.toApiPage
import com.xiaoyv.bangumi.shared.data.api.client.ApiClient
import com.xiaoyv.bangumi.shared.data.api.next.SubjectApi
import com.xiaoyv.bangumi.shared.data.manager.app.UserManager
import com.xiaoyv.bangumi.shared.data.model.request.bgm.LikeCommentParam
import com.xiaoyv.bangumi.shared.data.model.request.list.subject.ListSubjectParam
import com.xiaoyv.bangumi.shared.data.model.request.list.subject.SubjectBrowserBody
import com.xiaoyv.bangumi.shared.data.model.request.list.subject.SubjectSearchBody
import com.xiaoyv.bangumi.shared.data.model.request.list.tag.ListTagParam
import com.xiaoyv.bangumi.shared.data.model.response.bgm.ComposeEpisode
import com.xiaoyv.bangumi.shared.data.model.response.bgm.ComposeHomeSection
import com.xiaoyv.bangumi.shared.data.model.response.bgm.ComposeMonoDisplay
import com.xiaoyv.bangumi.shared.data.model.response.bgm.ComposeParade
import com.xiaoyv.bangumi.shared.data.model.response.bgm.ComposeReply
import com.xiaoyv.bangumi.shared.data.model.response.bgm.ComposeTag
import com.xiaoyv.bangumi.shared.data.model.response.bgm.normalizedReplies
import com.xiaoyv.bangumi.shared.data.model.response.bgm.subject.Airtime
import com.xiaoyv.bangumi.shared.data.model.response.bgm.subject.ComposeSubject
import com.xiaoyv.bangumi.shared.data.model.response.bgm.subject.ComposeSubjectRelation
import com.xiaoyv.bangumi.shared.data.model.response.bgm.subject.ComposeSubjectStats
import com.xiaoyv.bangumi.shared.data.model.response.bgm.subject.ComposeSubjectWebInfo
import com.xiaoyv.bangumi.shared.data.model.response.db.ComposeDoubanPhoto
import com.xiaoyv.bangumi.shared.data.model.response.db.ComposeDoubanSuggest
import com.xiaoyv.bangumi.shared.data.model.response.db.ComposeDoubanSuggestCard
import com.xiaoyv.bangumi.shared.data.parser.bgm.SubjectParser
import com.xiaoyv.bangumi.shared.data.repository.DatabaseRepository
import com.xiaoyv.bangumi.shared.data.repository.SubjectRepository
import com.xiaoyv.bangumi.shared.data.repository.datasource.MemoryPagingController
import com.xiaoyv.bangumi.shared.data.repository.datasource.createMemoryOffsetLimitPagingController
import com.xiaoyv.bangumi.shared.data.repository.datasource.createMemoryPageLimitPagingController
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlin.math.abs

/**
 * [SubjectRepositoryImpl]
 *
 * @author why
 * @since 2025/1/25
 */
class SubjectRepositoryImpl(
    private val client: ApiClient,
    private val pagingConfig: PagingConfig,
    private val subjectParser: SubjectParser,
    private val databaseRepository: DatabaseRepository,
    private val userManager: UserManager
) : SubjectRepository {

    /**
     * 排行榜搜索翻页游标：key -> 下一页起始 rank（不含）。
     *
     * 搜索接口最多只能取前 1000 条（Meilisearch 的 `maxTotalHits`，offset 一旦超过 1000 就返回空），
     * 而排行榜是 `rank:asc`，所以可以用「上一页最后一名的 rank」作为游标继续查 `rank > 游标`，
     * 每次 offset 都是 0，从而绕开 1000 条上限。
     */
    private val searchStateLock = SynchronizedObject()
    private val seekCursors = mutableMapOf<String, Int>()

    /** key -> (请求的 offset -> 该页使用的游标)。同一页重试时复用，避免跳过或重复。 */
    private val seekCursorByOffset = mutableMapOf<String, MutableMap<Int, Int>>()

    private fun seekCursor(key: String, offset: Int): Int = synchronized(searchStateLock) {
        if (offset <= 0) {
            seekCursors.remove(key)
            seekCursorByOffset.remove(key)
            0
        } else {
            seekCursorByOffset[key]?.get(offset) ?: seekCursors[key] ?: 0
        }
    }

    private fun advanceSeek(key: String, offset: Int, usedCursor: Int, maxRank: Int) = synchronized(searchStateLock) {
        val next = maxOf(usedCursor, maxRank)
        seekCursors[key] = next
        val byOffset = seekCursorByOffset.getOrPut(key) { mutableMapOf() }
        byOffset[offset] = usedCursor
        byOffset[offset + pagingConfig.pageSize] = next
    }

    /** 客户端日期过滤的扫描状态：按 rank 游标往前扫，命中项留在 buffer 里，保证翻页不丢条。 */
    private class DateScanState {
        val mutex = Mutex()
        var cursor: Int = 0
        var exhausted: Boolean = false
        val buffer: MutableList<ComposeSubject> = mutableListOf()
    }

    /** key -> 扫描状态。key 由 类型/分类/年/月/series 组成，换筛选条件就是新的一条队列。 */
    private val dateScanStates = mutableMapOf<String, DateScanState>()

    /** `info` 里的中文日期前缀，例如 `2024年1月5日` / `2024年1月` / `2024年`。 */
    private val airtimeYearMonthCn = Regex("^(\\d{4})年(?:(\\d{1,2})月)?")

    /** `info` 里的 ISO 日期前缀，例如 `2024-01` / `2024-01-05`。 */
    private val airtimeYearMonthIso = Regex("^(\\d{4})-(\\d{1,2})")

    /** 条目是否落在筛选的年/月里；拿不到年份（只有“1月5日”）时按不匹配处理。 */
    private fun matchesDate(subject: ComposeSubject, year: Int, month: Int): Boolean {
        val date = Airtime.fromInfo(subject.info).date
        val cn = airtimeYearMonthCn.find(date)
        val iso = if (cn == null) airtimeYearMonthIso.find(date) else null
        val itemYear = (cn?.groupValues?.get(1) ?: iso?.groupValues?.get(1))?.toIntOrNull() ?: return false
        val itemMonth = (cn?.groupValues?.get(2) ?: iso?.groupValues?.get(2))?.toIntOrNull()
        if (year > 0 && itemYear != year) return false
        if (month > 0 && itemMonth != month) return false
        return true
    }

    private companion object {
        /** 日期筛选时单次向搜索接口要多少条（接口上限 100），一次多要点少跑几趟。 */
        const val DATE_SCAN_LIMIT = 100
    }

    override fun fetchSubjectPager(param: ListSubjectParam): MemoryPagingController<ComposeSubjectRelation, Long> {
        return createMemoryOffsetLimitPagingController(
            pagingConfig = pagingConfig,
            idSelector = { it.subject.id },
            onLoadData = {
                fetchSubjectList(param, it, pagingConfig.pageSize).getOrThrow()
            }
        )
    }

    override fun fetchSubjectCommentPager(subjectId: Long, collectionType: Int): MemoryPagingController<ComposeReply, Long> {
        return createMemoryOffsetLimitPagingController(
            idSelector = { it.id },
            pagingConfig = pagingConfig,
            transformData = { it.normalizedReplies() },
            onLoadData = {
                client.requestNextSubjectApi {
                    getSubjectComments(
                        subjectID = subjectId,
                        type = collectionType.takeIf { type -> type != CollectionType.UNKNOWN },
                        limit = pagingConfig.pageSize,
                        offset = it
                    ).result
                }.getOrThrow()
            }
        )
    }


    override fun fetchSubjectTagPager(param: ListTagParam): MemoryPagingController<ComposeTag, String> {
        return createMemoryPageLimitPagingController(
            pagingConfig = pagingConfig,
            idSelector = { it.name },
            onlyOnePage = param.type == ListTagType.SEARCH,
            onLoadData = {
                when (param.type) {
                    ListTagType.SEARCH -> fetchSearchSubjectTags(param.search.keyword, param.search.subjectType).getOrThrow()
                    ListTagType.BROWSER -> fetchBrowserSubjectTags(param.subjectType, it).getOrThrow()
                    else -> error("不支持的标签类型: ${param.type}")
                }
            }
        )
    }

    override suspend fun fetchSearchSubjectTags(
        query: String,
        type: Int,
    ): Result<List<ComposeTag>> = client.requestWebApi {
        with(subjectParser) {
            fetchSearchSubjectTags(type = SubjectWebPath.from(type), keyword = query)
                .fetchSearchSubjectTagsConverted()
        }
    }

    override suspend fun fetchBrowserSubjectTags(type: Int, page: Int): Result<List<ComposeTag>> = client.requestWebApi {
        with(subjectParser) {
            fetchBorwserSubjectTags(type = SubjectWebPath.from(type), page = page)
                .fetchSearchSubjectTagsConverted()
        }
    }


    override fun fetchSearchSuggestion(query: String): Flow<Result<ComposeDoubanSuggest>> {
        return flow {
            if (query.isBlank()) {
                emit(Result.success(ComposeDoubanSuggest.Empty))
            } else {
                emit(runResult { client.dbApi.queryDouBanSuggestion(query) })
            }
        }
    }

    override suspend fun fetchSubjectPreview(subject: ComposeSubject): Result<ComposeDoubanPhoto> = runResult {
        val key = "DB6-${subject.id}"
        var suggestCard = databaseRepository.get(key, ComposeDoubanSuggestCard.Empty, ComposeDoubanSuggestCard.serializer())

        if (suggestCard == ComposeDoubanSuggestCard.Empty) {
            val subjectYear = subject.airtime.year
            val searchItems = coroutineScope {
                awaitAll(
                    async { client.dbApi.queryDouBanSubjects(q = subject.nameCn) },
                    async { client.dbApi.queryDouBanSubjects(q = subject.name) },
                ).flatMap { searchResponse ->
                    searchResponse.items.ifEmpty { searchResponse.subjects.items }
                }.distinctBy { it.targetType to it.targetId }
            }
            val mediaCards = searchItems.filter { it.targetType == "tv" || it.targetType == "movie" }
            val card = mediaCards.firstOrNull { it.target.year.toIntOrNull() == subjectYear }
                ?: mediaCards.minByOrNull { abs((it.target.year.toIntOrNull() ?: Int.MAX_VALUE) - subjectYear) }

            if (card != null) databaseRepository.put(key, card, ComposeDoubanSuggestCard.serializer())
            suggestCard = card ?: ComposeDoubanSuggestCard.Empty
        }

        if (suggestCard == ComposeDoubanSuggestCard.Empty) {
            ComposeDoubanPhoto.Empty
        } else {
            client.dbApi
                .queryDouBanPhotoList(suggestCard.targetId, suggestCard.targetType)
                .copy(doubanMediaId = suggestCard.targetId, doubanMediaType = suggestCard.targetType)
        }
    }

    override suspend fun fetchCalendar(): Result<Map<String, List<ComposeHomeSection>>> = client.requestNextSubjectApi {
        getCalendar()
    }

    override suspend fun fetchTrends(
        type: Int,
        limit: Int,
    ): Result<List<ComposeHomeSection>> = client.requestNextSubjectApi {
        getTrends(type = type, limit = limit).result
    }

    override suspend fun fetchSubjectEpisode(episodeId: Long): Result<ComposeEpisode> = client.requestNextEpisodeApi {
        getEpisode(episodeId).normalized()
    }

    override suspend fun fetchSubjectEpisodes(
        subjectId: Long,
        @EpisodeType type: Int?,
        offset: Int,
        limit: Int
    ): Result<List<ComposeEpisode>> = client.requestNextSubjectApi {
        getSubjectEpisodes(
            subjectID = subjectId,
            type = type,
            offset = offset,
            limit = limit
        ).result
    }

    override suspend fun fetchSubjectAllEpisodes(subjectId: Long, @EpisodeType type: Int?): Result<List<ComposeEpisode>> =
        client.requestNextSubjectApi {
            fetchAllPages(1000) { offset, limit ->
                getSubjectEpisodes(
                    subjectID = subjectId,
                    type = type,
                    offset = offset,
                    limit = limit
                ).result
            }
        }

    override suspend fun fetchSubjectEpisodeComments(episodeId: Long): Result<List<ComposeReply>> =
        client.requestNextEpisodeApi {
            getEpisodeComments(episodeId).normalizedReplies()
        }


    override suspend fun fetchSubjectStats(id: Long): Result<ComposeSubjectStats> = client.requestWebApi {
        with(subjectParser) {
            fetchSubjectStats(subjectId = id)
                .fetchSubjectStatsConverted()
        }
    }

    override suspend fun fetchSubjectDetail(subjectId: Long): Result<ComposeSubject> = client.requestNextSubjectApi {
        getSubject(subjectId).normalized()
    }

    override suspend fun fetchSubjectDetailByWeb(subjectId: Long): Result<ComposeSubjectWebInfo> = client.requestWebApi {
        with(subjectParser) {
            fetchSubjectDetail(subjectId)
                .fetchSubjectDetailConverted()
        }
    }

    override suspend fun fetchSubjectCharacter(subjectId: Long, type: Int?, offset: Int, limit: Int) = client.requestNextSubjectApi {
        getSubjectCharacters(subjectID = subjectId, type = null, limit = limit).result.map {
            ComposeMonoDisplay(type = MonoType.CHARACTER, info = it)
        }
    }

    override suspend fun fetchSubjectPerson(subjectId: Long, position: Int?, offset: Int, limit: Int) = client.requestNextSubjectApi {
        getSubjectStaffPersons(subjectID = subjectId, position = position, limit = limit).result.map {
            ComposeMonoDisplay(type = MonoType.PERSON, info = it)
        }
    }


    override suspend fun fetchMySubjectTags(subjectId: Long): Result<List<ComposeTag>> = client.requestWebApi {
        with(subjectParser) {
            fetchSubjectDetail(subjectId)
                .fetchMySubjectTagsCovConverted()
        }
    }

    override suspend fun fetchSubjectRelated(subjectId: Long): Result<List<ComposeSubjectRelation>> = client.requestNextSubjectApi {
        getSubjectRelations(subjectID = subjectId).result.map {
            it.copy(subject = it.subject.normalized())
        }
    }

    override suspend fun fetchSubjectList(param: ListSubjectParam, offset: Int, pageSize: Int) =
        when (param.type) {
            ListSubjectType.PERSON_WORK -> client.requestNextPersonApi {
                getPersonWorks(
                    personID = param.personWork.personId,
                    subjectType = param.personWork.subjectType.takeIf { it != SubjectType.UNKNOWN },
                    position = param.personWork.position.takeIf { it != PersonType.UNKNOWN },
                    offset = offset,
                    limit = pagingConfig.pageSize
                ).result
            }

            ListSubjectType.SUBJECT_RELATED -> client.requestNextSubjectApi {
                getSubjectRelations(
                    subjectID = param.related.subjectId,
                    offset = offset,
                    limit = pagingConfig.pageSize
                ).result
            }

            ListSubjectType.SEARCH -> {
                client.requestNextSearchApi {
                    searchSubjects(
                        body = param.search,
                        offset = offset,
                        limit = pagingConfig.pageSize
                    ).result.map { ComposeSubjectRelation(subject = it) }
                }
            }

            ListSubjectType.BROWSER -> {
                // /p1/subjects 在服务端回填详情时漏传 allowNsfw，受限条目会被整条丢弃；
                // 搜索接口的权限处理是正确的，所以带标签的浏览与排行榜都走搜索。
                // 搜索接口没有 cat 参数，分类要翻译成平台标签（metaTags）才能继续用搜索。
                val browser = param.browser
                // cat 是按类型各自编号的：切换类型时若沿用上一个类型的 cat，接口会返回空列表。
                val cat = browser.cat.takeIf { SubjectCategory.isValidCat(browser.subjectType, it) } ?: 0
                val platformMetaTag = SubjectCategory.platformMetaTag(browser.subjectType, cat)
                val isRankSearch = browser.sort == SubjectSortBrowserType.RANK && browser.tags.isEmpty()
                val useSearchApi = browser.tags.isNotEmpty() ||
                    (browser.sort == SubjectSortBrowserType.RANK && (cat == 0 || platformMetaTag != null))
                if (useSearchApi) {
                    client.requestNextSearchApi {
                        val seekKey = "${browser.subjectType}:$cat:${browser.year}:${browser.month}:${browser.series}"
                        if (isRankSearch && (browser.year > 0 || browser.month > 0)) {
                            // 搜索接口的 date 过滤在服务端是坏的（任何值都返回 0 条），只能先按 rank 取回，
                            // 再在客户端按 info 里的日期和 series 过滤；用扫描游标 + 缓冲，保证翻页不丢条。
                            val pageSize = pagingConfig.pageSize
                            val state = synchronized(searchStateLock) {
                                if (offset <= 0) {
                                    DateScanState().also { dateScanStates[seekKey] = it }
                                } else {
                                    dateScanStates.getOrPut(seekKey) { DateScanState() }
                                }
                            }
                            state.mutex.lock()
                            try {
                                while (state.buffer.size < pageSize && !state.exhausted) {
                                    val page = searchSubjects(
                                        body = SubjectSearchBody(
                                            keyword = "",
                                            filter = SubjectSearchBody.SubjectSearchFilter(
                                                nsfw = true,
                                                rank = if (state.cursor > 0) persistentListOf(">${state.cursor}") else persistentListOf(">0"),
                                                metaTags = platformMetaTag?.let { persistentListOf(it) },
                                                type = if (browser.subjectType != SubjectType.UNKNOWN) persistentListOf(browser.subjectType) else null,
                                            ),
                                            sort = SubjectSortBrowserType.toSearchType(browser.sort),
                                        ),
                                        offset = 0,
                                        limit = DATE_SCAN_LIMIT
                                    )
                                    val result = page.result
                                    if (result.isEmpty()) {
                                        state.exhausted = true
                                        break
                                    }
                                    for (subject in result) {
                                        if ((!browser.series || subject.series) && matchesDate(subject, browser.year, browser.month)) {
                                            state.buffer += subject
                                        }
                                    }
                                    val nextCursor = result.maxOf { it.rating.rank }
                                    if (nextCursor <= state.cursor) {
                                        state.exhausted = true
                                        break
                                    }
                                    state.cursor = nextCursor
                                }
                                val take = minOf(pageSize, state.buffer.size)
                                val result = state.buffer.subList(0, take).toList()
                                state.buffer.subList(0, take).clear()
                                result.map { ComposeSubjectRelation(subject = it.normalized()) }
                            } finally {
                                state.mutex.unlock()
                            }
                        } else if (isRankSearch) {
                            // 搜索接口只能取前 1000 条（Meilisearch maxTotalHits，offset 超过就空），
                            // 排行榜按 rank 升序，所以用“上一页最后一名的 rank”作游标续页，绕开 1000 条上限。
                            // 按 rank 游标继续消化短页，避免回填缺项或 series 过滤使分页提前结束。
                            val pageSize = pagingConfig.pageSize
                            val pageStartCursor = seekCursor(seekKey, offset)
                            var cursor = pageStartCursor
                            val result = mutableListOf<ComposeSubject>()
                            var exhausted = false
                            while (result.size < pageSize && !exhausted) {
                                val page = searchSubjects(
                                    body = SubjectSearchBody(
                                        keyword = "",
                                        filter = SubjectSearchBody.SubjectSearchFilter(
                                            nsfw = true,
                                            rank = if (cursor > 0) persistentListOf(">$cursor") else persistentListOf(">0"),
                                            metaTags = platformMetaTag?.let { persistentListOf(it) },
                                            type = if (browser.subjectType != SubjectType.UNKNOWN) persistentListOf(browser.subjectType) else null,
                                        ),
                                        sort = SubjectSortBrowserType.toSearchType(browser.sort),
                                    ),
                                    offset = if (cursor > 0) 0 else offset,
                                    limit = pageSize
                                )
                                if (page.result.isEmpty()) {
                                    exhausted = true
                                    break
                                }
                                val nextCursor = page.result.maxOf { it.rating.rank }
                                if (nextCursor <= cursor) {
                                    exhausted = true
                                    break
                                }
                                page.result.forEach { subject ->
                                    if (!browser.series || subject.series) result += subject
                                }
                                cursor = nextCursor
                            }
                            if (cursor > pageStartCursor) {
                                advanceSeek(seekKey, offset, pageStartCursor, cursor)
                            }
                            result.take(pageSize).map { ComposeSubjectRelation(subject = it.normalized()) }
                        } else {
                            val page = searchSubjects(
                                body = SubjectSearchBody(
                                    keyword = "",
                                    filter = SubjectSearchBody.SubjectSearchFilter(
                                        nsfw = true,
                                        rank = null,
                                        metaTags = platformMetaTag?.let { persistentListOf(it) },
                                        type = if (browser.subjectType != SubjectType.UNKNOWN) persistentListOf(browser.subjectType) else null,
                                        tags = browser.tags.takeIf { it.isNotEmpty() },
                                        date = browser.searchData()
                                    ),
                                    sort = SubjectSortBrowserType.toSearchType(browser.sort),
                                ),
                                offset = offset,
                                limit = pagingConfig.pageSize
                            )
                            page.result.map { ComposeSubjectRelation(subject = it.normalized()) }
                        }
                    }
                } else {
                    client.requestNextSubjectApi {
                        getSubjects(
                            type = browser.subjectType,
                            cat = cat,
                            series = browser.series,
                            sort = browser.sort,
                            year = browser.year,
                            tags = browser.tags,
                            month = browser.month,
                            page = offset.toApiPage(pageSize),
                        ).result.map {
                            ComposeSubjectRelation(subject = it.normalized())
                        }
                    }
                }
            }

            ListSubjectType.USER_COLLECTION -> {
                if (userManager.isLogin && userManager.userInfo.username == param.collection.username) {
                    client.requestNextCollectionApi {
                        getMySubjectCollections(
                            subjectType = param.collection.subjectType,
                            type = param.collection.collectionType,
                            offset = offset,
                            limit = pagingConfig.pageSize
                        ).result.map { ComposeSubjectRelation(subject = it.normalized()) }
                    }
                } else {
                    client.requestNextUserApi {
                        getUserSubjectCollections(
                            username = param.collection.username,
                            subjectType = param.collection.subjectType,
                            type = param.collection.collectionType,
                            offset = offset,
                            limit = pagingConfig.pageSize
                        ).result.map { ComposeSubjectRelation(subject = it.normalized()) }
                    }
                }
            }

            else -> error("不支持的列表类型: ${param.type}")
        }

    override suspend fun fetchSubjectParade(subjectId: Long): Result<ComposeParade> = client.requestChoreApi {
        fetchSubjectParade(subjectId)
    }

    override suspend fun submitEpisodeCommentReaction(commentId: Long, value: String?): Result<Unit> = client.requestNextEpisodeApi {
        if (value.isNullOrBlank()) {
            unlikeEpisodeComment(commentID = commentId)
        } else {
            likeEpisodeComment(commentId, LikeCommentParam(value.toInt()))
        }
    }
}
