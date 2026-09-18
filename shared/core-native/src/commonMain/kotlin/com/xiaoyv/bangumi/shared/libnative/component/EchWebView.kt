package com.xiaoyv.bangumi.shared.libnative.component

import androidx.compose.runtime.Composable
import com.multiplatform.webview.web.NativeWebView
import com.multiplatform.webview.web.PlatformWebViewParams
import com.multiplatform.webview.web.WebViewFactoryParam

/**
 * 受保护域名的 WebView 参数。安卓侧换成走 ECH 的客户端；其他平台没有 ECH 能力，返回 null 保持原样。
 */
@Composable
expect fun rememberEchWebViewParams(): PlatformWebViewParams?

/**
 * 创建 WebView 时安装非 GET 传输桥（桥必须早于页面脚本存在）。
 * 未接 ECH 的平台返回默认 WebView。
 */
expect fun echWebViewFactory(param: WebViewFactoryParam): NativeWebView
