package com.xiaoyv.bangumi.shared.libnative.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.multiplatform.webview.web.NativeWebView
import com.multiplatform.webview.web.PlatformWebViewParams
import com.multiplatform.webview.web.WebViewFactoryParam
import com.multiplatform.webview.web.defaultWebViewFactory
import com.xiaoyv.bangumi.shared.libnative.ech.EchWebBridgeJs
import com.xiaoyv.bangumi.shared.libnative.ech.EchWebViewClient

@Composable
actual fun rememberEchWebViewParams(): PlatformWebViewParams =
    remember { PlatformWebViewParams(client = EchWebViewClient()) }

actual fun echWebViewFactory(param: WebViewFactoryParam): NativeWebView =
    defaultWebViewFactory(param).also { EchWebBridgeJs.install(it) }
