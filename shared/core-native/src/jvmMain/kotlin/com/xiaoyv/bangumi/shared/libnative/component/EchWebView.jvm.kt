package com.xiaoyv.bangumi.shared.libnative.component

import androidx.compose.runtime.Composable
import com.multiplatform.webview.web.NativeWebView
import com.multiplatform.webview.web.PlatformWebViewParams
import com.multiplatform.webview.web.WebViewFactoryParam
import com.multiplatform.webview.web.defaultWebViewFactory

/** 该平台没有 in-process ECH，保持库的默认行为。 */
@Composable
actual fun rememberEchWebViewParams(): PlatformWebViewParams? = null

actual fun echWebViewFactory(param: WebViewFactoryParam): NativeWebView = defaultWebViewFactory(param)
