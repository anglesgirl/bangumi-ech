"""WebView ECH 接线的静态检查；不代替编译、注入脚本断言与真机验证。"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
NATIVE = ROOT / 'shared/core-native/src/androidMain/kotlin/com/xiaoyv/bangumi/shared/libnative/ech'


def read(path: pathlib.Path) -> str:
    return path.read_text() if path.exists() else ''


def raw_script(text: str, anchor: str) -> str:
    """取 anchor 之后第一个三引号块的内容。

    不能按固定下标切三引号：文件里多一段三引号字符串就会测错对象，所以按锚点定位。
    """
    start = text.find(anchor)
    if start < 0:
        return ''
    open_at = text.find('"""', start)
    close_at = text.find('"""', open_at + 3)
    if open_at < 0 or close_at < open_at:
        return ''
    return text[open_at + 3:close_at]



client = read(NATIVE / 'EchWebViewClient.kt')
bridge = read(NATIVE / 'EchWebBridge.kt')
script = read(NATIVE / 'EchWebBridgeJs.kt')
transfer = read(NATIVE / 'EchWebTransfer.kt')
common = read(ROOT / 'shared/core-native/src/commonMain/kotlin/com/xiaoyv/bangumi/shared/libnative/component/EchWebView.kt')
android = read(ROOT / 'shared/core-native/src/androidMain/kotlin/com/xiaoyv/bangumi/shared/libnative/component/EchWebView.android.kt')
proxy = read(ROOT / 'shared/core-native/src/androidMain/kotlin/com/xiaoyv/bangumi/shared/libnative/sni/AntiSniWebProxy.android.kt')

# 派生量必须在文件内容读进来之后再算，否则拿到的是空值。
candidates_match = re.search(r'private fun candidateAddresses\(.*?\n    \}', proxy, re.S)
candidate_body = candidates_match.group(0) if candidates_match else ''
script_body = raw_script(script, 'private val SCRIPT')

hosts = {
    '内置浏览器页': ROOT / 'features/web/src/commonMain/kotlin/com/xiaoyv/bangumi/features/web/WebScreen.kt',
    '人机验证页': ROOT / 'shared/ui/src/commonMain/kotlin/com/xiaoyv/bangumi/shared/ui/component/turnstile/BgmTurnstile.kt',
    '工作流弹窗': ROOT / 'shared/ui/src/commonMain/kotlin/com/xiaoyv/bangumi/shared/ui/component/workflow/WorkflowSideEffectDialog.kt',
}

checks = {
    '子请求入口接管受保护域名': 'override fun shouldInterceptRequest' in client
        and 'BgmEchPolicy.isProtected(url.host.orEmpty())' in client,
    '受保护域名失败即 502（不返回 null 放行）': 'blockedResponse(' in client
        and 'return super.shouldInterceptRequest(view, request)' in client
        and 'WebResourceResponse(' in client,
    '非 GET/HEAD 不在拦截层放行': '需要传输桥，已被阻断' in client
        and 'method != "GET" && method != "HEAD"' in client,
    'HEAD 走 head() 且与 JS 桥分工一致': 'builder.head()' in client and 'HEAD' in script_body,
    '响应体交给 WebView 消费（不提前关流）': 'body?.byteStream()' in client and '.use {' not in client,
    '主文档跳转交给 WebView 自己走（base URL 才不错位）': 'isForMainFrame' in client
        and 'response.isRedirect' in client and 'location.replace' in client,
    '拦截路径有磁盘缓存（否则每次打开重下一遍）': '.cache(cache)' in transfer
        and 'ech-webview' in transfer and 'Cache(' in transfer,
    '注入脚本只在受保护页面生效': 'if (!BgmEchPolicy.isProtected(host)) return' in script,
    '桥在 WebView 创建时安装': 'addJavascriptInterface' in script and 'EchWebBridgeJs.install(it)' in android,
    '域名清单与策略同源': '.replace("__PROTECTED__", BgmEchPolicy.scriptDomains())' in script,
    '脚本无 Kotlin 插值风险': script_body != '' and '$' not in script_body and 'fetch' in script_body,
    '桥原生侧再判一次受保护域名': 'BgmEchPolicy.isProtected(url.host)' in bridge
        and '拒绝代发' in bridge,
    '桥拒绝明文 HTTP 与不安全方法': '禁止明文 HTTP' in bridge and 'ALLOWED_METHODS' in bridge,
    '桥只服务受保护页面（防 CSRF）': '当前页面不在受保护范围' in bridge,
    '桥有体积上限与逐跳头过滤': 'MAX_BODY_BYTES' in bridge and 'BLOCKED_HEADERS' in bridge,
    'Cookie 与 WebView 共用一份': 'CookieManager.getInstance()' in transfer
        and 'getCookie' in transfer and 'setCookie' in transfer,
    '不自动跟跳（保住重定向的 Set-Cookie）': 'followRedirects' not in transfer
        and 'followSslRedirects(false)' in read(NATIVE / 'BgmEchTransport.kt'),
    'WebView 传输复用 ECH 栈': 'BgmEchTransport.configure(this)' in transfer,
    '代理拒绝受保护域名（防绕过明文）': 'ECH Required' in proxy
        and 'BgmEchPolicy.isProtected(target.host)' in proxy,
    '安卓平台参数与工厂已接线': 'PlatformWebViewParams(client = EchWebViewClient())' in android
        and 'expect fun echWebViewFactory' in common,
    'WebView 代理优先用实测固定 IP': 'BgmEchPolicy.pinnedAddresses(host)' in candidate_body
        and '(pinned + resolved + configured)' in candidate_body,
    '仅换地址的域名不动 TLS': 'AntiSniSocket(socket, fragmentationPolicy, host)' in proxy
        and 'isProtected' not in candidate_body,
    '代理覆盖不被别的实例踩掉': 'ACTIVE_SEQ' in proxy and 'appliedSeq == ACTIVE_SEQ' in proxy,
    '其他平台保持原行为': 'rememberEchWebViewParams(): PlatformWebViewParams? = null'
        in read(ROOT / 'shared/core-native/src/iosMain/kotlin/com/xiaoyv/bangumi/shared/libnative/component/EchWebView.ios.kt'),
}

for name, path in hosts.items():
    text = read(path)
    # 光看 import 行会漏掉"传参被删"：必须在同一个 WebView(...) 调用里共现。
    checks[f'{name}已接 ECH WebView'] = (
        'platformWebViewParams = rememberEchWebViewParams()' in text
        and 'factory = ::echWebViewFactory' in text
    )

for name, passed in checks.items():
    print(f'{name}：实际={"通过" if passed else "失败"}，期望=通过')
print(f'WebView ECH 接线静态检查：{sum(checks.values())}/{len(checks)}；不代表编译与真机结果')
sys.exit(not all(checks.values()))
