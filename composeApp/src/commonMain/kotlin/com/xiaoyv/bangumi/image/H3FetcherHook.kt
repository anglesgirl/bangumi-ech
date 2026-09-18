package com.xiaoyv.bangumi.image

import coil3.Uri
import coil3.fetch.Fetcher

/**
 * 图片的 H3 快速通道。
 *
 * 只有 Android 有实现（quiche JNI）；iOS/JVM 返回 null —— 那边继续走原有链路，
 * 不引入任何行为变化。
 *
 * @param fallback 原有网络 Fetcher 工厂：H3 失败时由它兜底（fail-closed，不暴露 SNI）
 */
expect fun h3ImageFetcherFactory(fallback: Fetcher.Factory<Uri>): Fetcher.Factory<Uri>?
