package com.xiaoyv.bangumi.features.settings.network

import android.content.Context
import com.xiaoyv.bangumi.shared.libnative.ech.BgmEchH3

actual fun syncH3DebugLogToNative(context: Any, enabled: Boolean) {
    val androidContext = context as? Context ?: return
    BgmEchH3.setDebugLogEnabled(androidContext, enabled)
}
