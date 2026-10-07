package com.xiaoyv.bangumi.features.settings.network

/**
 * 将 H3 调试日志开关同步到原生层（BgmEchH3 的 SharedPreferences）。
 *
 * @param context 平台 Context（Android 上为 android.content.Context）
 * @param enabled 是否开启
 */
expect fun syncH3DebugLogToNative(context: Any, enabled: Boolean)
