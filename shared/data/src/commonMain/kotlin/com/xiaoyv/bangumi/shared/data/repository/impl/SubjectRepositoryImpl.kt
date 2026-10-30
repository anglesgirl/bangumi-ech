package com.xiaoyv.bangumi.shared.data.repository.impl

import androidx.paging.PagingConfig
import com.xiaoyv.bangumi.shared.core.types.CollectionType
import com.xiaoyv.bangumi.shared.core.types.EpisodeType
import com.xiaoyv.bangumi.shared.core.types.MonoType
import com.xiaoyv.bangumi.shared.core.types.PersonType
import com.xiaoyv.bangumi.shared.core.types.SubjectCategory
import com.xiaoyv.bangumi.shared.core.types.SubjectSortBrowserType
import com.xiaoyv.bangumi.shared.core.types.SubjectSortSearchType
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

    /** 非排名排序下的日期扫描状态：只能按 offset 往前扫，搜索接口最多覆盖前 1000 条。 */
    private class OffsetDateScanState {
        val mutex = Mutex()
        var offset: Int = 0
        var exhausted: Boolean = false
        val buffer: MutableList<ComposeSubject> = mutableListOf()
    }

    /** key -> 非排名排序的日期扫描状态。 */
    private val offsetDateScanStates = mutableMapOf<String, OffsetDateScanState>()

    /**
     * id -> 详情接口给出的 (年, 月)。搜索结果的 info 里不一定有日期（新条目常见只有 “1话”），
     * 这种情况回查一次详情拿结构化日期，缓存避免翻页时重复请求。
     */
    private val detailAirtimes = mutableMapOf<Long, Pair<Int, Int>?>()
    private val detailAirtimeLock = Any()

    /** 高级筛选里的日期条件，例如 `>=2020-07-01`、`<2020-10-01`；也兼容 `YYYY-MM`、`YYYY`。 */
    private data class DateCondition(
        val op: Int,
        val year: Int,
        val month: Int,
        val day: Int,
    )

    /** `param.search.filter.date` 里每个字符串解析成一个条件；解析不了的忽略。 */
    private fun parseDateConditions(raw: List<String>?): List<DateCondition> {
        if (raw.isNullOrEmpty()) return emptyList()
        val regex = Regex("^(>=|<=|>|<)?(\\d{4})(?:-(\\d{1,2}))?(?:-(\\d{1,2}))?$")
        return raw.mapNotNull { token ->
            val match = regex.find(token.trim()) ?: return@mapNotNull null
            val op = when (match.groupValues[1]) {
                ">=" -> 0
                ">" -> 1
                "<=" -> 2
                "<" -> 3
                else -> 0
            }
            DateCondition(
                op = op,
                year = match.groupValues[2].toInt(),
                month = match.groupValues[3].toIntOrNull() ?: 0,
                day = match.groupValues[4].toIntOrNull() ?: 0,
            )
        }
    }

    /** 从 info 里解析出 (年, 月, 日)。 */
    private fun subjectDate(info: String): Triple<Int, Int, Int>? {
        val iso = Regex("(\\d{4})-(\\d{1,2})(?:-(\\d{1,2}))?").find(info)
        if (iso != null) {
            return Triple(
                iso.groupValues[1].toInt(),
                iso.groupValues[2].toInt(),
                iso.groupValues[3].toIntOrNull() ?: 0,
            )
        }
        val cn = Regex("(\\d{4})年(?:(\\d{1,2})月)?(?:(\\d{1,2})日)?").find(info)
        if (cn != null) {
            return Triple(
                cn.groupValues[1].toInt(),
                cn.groupValues[2].toIntOrNull() ?: 0,
                cn.groupValues[3].toIntOrNull() ?: 0,
            )
        }
        return null
    }

    private fun compareDate(a: Triple<Int, Int, Int>, b: Triple<Int, Int, Int>): Int {
        val year = a.first.compareTo(b.first)
        if (year != 0) return year
        val month = a.second.compareTo(b.second)
        if (month != 0) return month
        return a.third.compareTo(b.third)
    }

    private fun matchesDateConditions(
        date: Triple<Int, Int, Int>,
        conditions: List<DateCondition>,
    ): Boolean = conditions.all { condition ->
        val compare = compareDate(date, Triple(condition.year, condition.month, condition.day))
        when (condition.op) {
            0 -> compare >= 0
            1 -> compare > 0
            2 -> compare <= 0
            else -> compare < 0
        }
    }

    /** 把一批搜索结果里符合日期表达式的条目放进 [out]；info 没有日期时回查详情。 */
    private suspend fun collectDateRangeMatches(
        subjects: List<ComposeSubject>,
        conditions: List<DateCondition>,
        out: MutableList<ComposeSubject>,
    ) {
        var detailLookups = 0
        for (subject in subjects) {
            val date = subjectDate(subject.info)
            if (date != null) {
                if (matchesDateConditions(date, conditions)) out += subject
                continue
            }
            if (detailLookups >= MAX_DETAIL_LOOKUPS) continue
            detailLookups++
            val detail = client.requestNextSubjectApi { getSubject(subject.id) }.getOrNull() ?: continue
            val detailDate = Regex("^(\\d{4})-(\\d{1,2})-(\\d{1,2})").find(detail.airtime.date)
                ?.let { Triple(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3].toInt()) }
                ?: continue
            if (matchesDateConditions(detailDate, conditions)) out += subject
        }
    }

    /** 把一批搜索结果里符合日期（和 series）条件的条目放进 [out]。 */
    private suspend fun collectDateMatches(
        subjects: List<ComposeSubject>,
        browser: SubjectBrowserBody,
        out: MutableList<ComposeSubject>,
    ) {
        var detailLookups = 0
        for (subject in subjects) {
            if (browser.series && !subject.series) continue
            if (matchesDate(subject, browser.year, browser.month)) {
                out += subject
                continue
            }
            if (infoHasYear(subject.info)) continue
            if (detailLookups >= MAX_DETAIL_LOOKUPS) continue
            detailLookups++
            val yearMonth = loadAirtimeFromDetail(subject.id) ?: continue
            if (matchesYearMonth(yearMonth, browser.year, browser.month)) out += subject
        }
    }

    /** `info` 里是否带四位年份；没带时说明 info 可能只有 “1话” 这种，需要回查详情。 */
    private fun infoHasYear(info: String): Boolean = Regex("\\d{4}").containsMatchIn(info)

    /** 年/月是否命中筛选条件。 */
    private fun matchesYearMonth(yearMonth: Pair<Int, Int>, year: Int, month: Int): Boolean {
        if (year > 0 && yearMonth.first != year) return false
        if (month > 0 && yearMonth.second != month) return false
        return true
    }

    /** 回查详情接口的 airtime，返回 (年, 月)；失败或没有日期时返回 null。 */
    private suspend fun loadAirtimeFromDetail(id: Long): Pair<Int, Int>? {
        synchronized(detailAirtimeLock) {
            if (detailAirtimes.containsKey(id)) return detailAirtimes[id]
        }
        val subject = client.requestNextSubjectApi { getSubject(id) }.getOrNull() ?: return null
        val match = Regex("^(\\d{4})-(\\d{1,2})").find(subject.airtime.date)
        val value = match?.let { it.groupValues[1].toInt() to it.groupValues[2].toInt() }
        synchronized(detailAirtimeLock) { detailAirtimes[id] = value }
        return value
    }

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

        /** 单页最多为「info 里没有日期」的条目回查多少次详情，避免异常数据把请求拖死。 */
        const val MAX_DETAIL_LOOKUPS = 12

        /** 搜索接口的 offset 上限（Meilisearch maxTotalHits），非排名排序只能扫到这里。 */
        const val SEARCH_MAX_OFFSET = 1000
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
                // 搜索接口的日期过滤（air_date）在服务端不工作，这里解析高级筛选里的日期表达式，
                // 改成客户端过滤：排名排序用 rank 游标（无上限），其他排序 offset 扫描（≤1000 条）。
                val dateConditions = parseDateConditions(param.search.filter.date)
                client.requestNextSearchApi {
                    if (dateConditions.isEmpty()) {
                        // 服务端 sort=rank 会把 rank=0（未上榜）当最小值排在最前，
                        // 按排名排序时补一个 rank>0 过滤；用户自己在高级筛选里写了 rank 条件则不覆盖。
                        val body = if (param.search.sort == SubjectSortSearchType.RANK &&
                            param.search.filter.rank.isNullOrEmpty()
                        ) {
                            param.search.copy(
                                filter = param.search.filter.copy(rank = persistentListOf(">0"))
                            )
                        } else {
                            param.search
                        }
                        searchSubjects(
                            body = body,
                            offset = offset,
                            limit = pagingConfig.pageSize
                        ).result.map { ComposeSubjectRelation(subject = it) }
                    } else {
                        val pageSize = pagingConfig.pageSize
                        val scanKey = "search:${param.uniqueKey}"
                        val canRankScan = param.search.sort == SubjectSortSearchType.RANK &&
                            param.search.filter.rank.isNullOrEmpty()
                        if (canRankScan) {
                            val state = synchronized(searchStateLock) {
                                if (offset <= 0) {
                                    DateScanState().also { dateScanStates[scanKey] = it }
                                } else {
                                    dateScanStates.getOrPut(scanKey) { DateScanState() }
                                }
                            }
                            state.mutex.lock()
                            try {
                                while (state.buffer.size < pageSize && !state.exhausted) {
                                    val page = searchSubjects(
                                        body = param.search.copy(
                                            filter = param.search.filter.copy(
                                                rank = if (state.cursor > 0) {
                                                    persistentListOf(">${state.cursor}")
                                                } else {
                                                    persistentListOf(">0")
                                                }
                                            )
                                        ),
                                        offset = 0,
                                        limit = DATE_SCAN_LIMIT
                                    )
                                    val result = page.result
                                    if (result.isEmpty()) {
                                        state.exhausted = true
                                        break
                                    }
                                    collectDateRangeMatches(result, dateConditions, state.buffer)
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
                        } else {
                            val state = synchronized(searchStateLock) {
                                if (offset <= 0) {
                                    OffsetDateScanState().also { offsetDateScanStates[scanKey] = it }
                                } else {
                                    offsetDateScanStates.getOrPut(scanKey) { OffsetDateScanState() }
                                }
                            }
                            state.mutex.lock()
                            try {
                                while (state.buffer.size < pageSize &&
                                    !state.exhausted &&
                                    state.offset < SEARCH_MAX_OFFSET
                                ) {
                                    val page = searchSubjects(
                                        body = param.search,
                                        offset = state.offset,
                                        limit = DATE_SCAN_LIMIT
                                    )
                                    val result = page.result
                                    if (result.isEmpty()) {
                                        state.exhausted = true
                                        break
                                    }
                                    collectDateRangeMatches(result, dateConditions, state.buffer)
                                    state.offset += DATE_SCAN_LIMIT
                                    if (result.size < DATE_SCAN_LIMIT) state.exhausted = true
                                }
                                if (state.offset >= SEARCH_MAX_OFFSET) state.exhausted = true
                                val take = minOf(pageSize, state.buffer.size)
                                val result = state.buffer.subList(0, take).toList()
                                state.buffer.subList(0, take).clear()
                                result.map { ComposeSubjectRelation(subject = it.normalized()) }
                            } finally {
                                state.mutex.unlock()
                            }
                        }
                    }
                }
            }

            ListSubjectType.BROWSER -> {
                // /p1/subjects 在服务端回填详情时漏传 allowNsfw，受限条目会被整条丢弃；
                // 搜索接口的权限处理是正确的，所以带标签/关键词的浏览与排行榜都走搜索。
                // 搜索接口没有 cat 参数，分类要翻译成平台标签（metaTags）才能继续用搜索。
                val browser = param.browser
                // cat 是按类型各自编号的：切换类型时若沿用上一个类型的 cat，接口会返回空列表。
                val cat = browser.cat.takeIf { SubjectCategory.isValidCat(browser.subjectType, it) } ?: 0
                val platformMetaTag = SubjectCategory.platformMetaTag(browser.subjectType, cat)
                val keyword = browser.keyword.trim()
                val isRankSort = browser.sort == SubjectSortBrowserType.RANK
                val hasDate = browser.year > 0 || browser.month > 0
                val useSearchApi = keyword.isNotEmpty() || browser.tags.isNotEmpty() ||
                    (isRankSort && (cat == 0 || platformMetaTag != null))
                if (useSearchApi) {
                    client.requestNextSearchApi {
                        val seekKey = buildString {
                            append(browser.subjectType).append(':')
                            append(cat).append(':')
                            append(browser.year).append(':')
                            append(browser.month).append(':')
                            append(browser.series).append(':')
                            append(browser.sort).append(':')
                            append(keyword).append(':')
                            append(browser.tags.joinToString("|"))
                        }
                        val baseFilter = SubjectSearchBody.SubjectSearchFilter(
                            nsfw = true,
                            metaTags = platformMetaTag?.let { persistentListOf(it) },
                            type = if (browser.subjectType != SubjectType.UNKNOWN) {
                                persistentListOf(browser.subjectType)
                            } else {
                                null
                            },
                            tags = browser.tags.takeIf { it.isNotEmpty() },
                        )
                        val pageSize = pagingConfig.pageSize
                        when {
                            // 排名排序 + 日期：按 rank 游标扫描（可越过 1000 条上限），客户端过滤日期。
                            hasDate && isRankSort -> {
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
                                                keyword = keyword,
                                                filter = baseFilter.copy(
                                                    rank = if (state.cursor > 0) {
                                                        persistentListOf(">${state.cursor}")
                                                    } else {
                                                        persistentListOf(">0")
                                                    }
                                                ),
                                                sort = SubjectSortSearchType.RANK,
                                            ),
                                            offset = 0,
                                            limit = DATE_SCAN_LIMIT
                                        )
                                        val result = page.result
                                        if (result.isEmpty()) {
                                            state.exhausted = true
                                            break
                                        }
                                        collectDateMatches(result, browser, state.buffer)
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
                            }

                            // 其他排序 + 日期：只能按 offset 扫描，搜索接口最多覆盖前 1000 条。
                            hasDate -> {
                                val state = synchronized(searchStateLock) {
                                    if (offset <= 0) {
                                        OffsetDateScanState().also { offsetDateScanStates[seekKey] = it }
                                    } else {
                                        offsetDateScanStates.getOrPut(seekKey) { OffsetDateScanState() }
                                    }
                                }
                                state.mutex.lock()
                                try {
                                    while (state.buffer.size < pageSize &&
                                        !state.exhausted &&
                                        state.offset < SEARCH_MAX_OFFSET
                                    ) {
                                        val page = searchSubjects(
                                            body = SubjectSearchBody(
                                                keyword = keyword,
                                                filter = baseFilter,
                                                sort = SubjectSortBrowserType.toSearchType(browser.sort),
                                            ),
                                            offset = state.offset,
                                            limit = DATE_SCAN_LIMIT
                                        )
                                        val result = page.result
                                        if (result.isEmpty()) {
                                            state.exhausted = true
                                            break
                                        }
                                        collectDateMatches(result, browser, state.buffer)
                                        state.offset += DATE_SCAN_LIMIT
                                        if (result.size < DATE_SCAN_LIMIT) state.exhausted = true
                                    }
                                    if (state.offset >= SEARCH_MAX_OFFSET) state.exhausted = true
                                    val take = minOf(pageSize, state.buffer.size)
                                    val result = state.buffer.subList(0, take).toList()
                                    state.buffer.subList(0, take).clear()
                                    result.map { ComposeSubjectRelation(subject = it.normalized()) }
                                } finally {
                                    state.mutex.unlock()
                                }
                            }

                            // 排名排序、无日期：rank 游标续页，绕开 1000 条上限。
                            isRankSort -> {
                                val pageStartCursor = seekCursor(seekKey, offset)
                                var cursor = pageStartCursor
                                val result = mutableListOf<ComposeSubject>()
                                var exhausted = false
                                while (result.size < pageSize && !exhausted) {
                                    val page = searchSubjects(
                                        body = SubjectSearchBody(
                                            keyword = keyword,
                                            filter = baseFilter.copy(
                                                rank = if (cursor > 0) {
                                                    persistentListOf(">$cursor")
                                                } else {
                                                    persistentListOf(">0")
                                                }
                                            ),
                                            sort = SubjectSortSearchType.RANK,
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
                            }

                            // 其他排序、无日期：普通 offset 分页（关键词与标签都交给搜索接口）。
                            else -> {
                                val page = searchSubjects(
                                    body = SubjectSearchBody(
                                        keyword = keyword,
                                        filter = baseFilter,
                                        sort = SubjectSortBrowserType.toSearchType(browser.sort),
                                    ),
                                    offset = offset,
                                    limit = pageSize
                                )
                                page.result
                                    .filter { !browser.series || it.series }
                                    .map { ComposeSubjectRelation(subject = it.normalized()) }
                            }
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
