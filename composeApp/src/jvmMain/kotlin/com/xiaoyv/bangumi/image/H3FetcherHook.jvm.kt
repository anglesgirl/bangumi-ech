package com.xiaoyv.bangumi.image

import coil3.Uri
import coil3.fetch.Fetcher

/** 非 Android 平台没有 quiche JNI：明确不启用，行为与改动前一致。 */
actual fun h3ImageFetcherFactory(fallback: Fetcher.Factory<Uri>): Fetcher.Factory<Uri>? = null
