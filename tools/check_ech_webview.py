"""WebView ECH 接线的静态检查；不代替编译、注入脚本断言与真机验证。"""
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
NATIVE = ROOT / 'shared/core-native/src/androidMain/kotlin/com/xiaoyv/bangumi/shared/libnative/ech'


def read(path: pathlib.Path) -> str:
    return path.read_text() if path.exists() else ''


client = read(NATIVE / 'EchWebViewClient.kt')
bridge = read(NATIVE / 'EchWebBridge.kt')
script = read(NATIVE / 'EchWebBridgeJs.kt')
transfer = read(NATIVE / 'EchWebTransfer.kt')
common = read(ROOT / 'shared/core-native/src/commonMain/kotlin/com/xiaoyv/bangumi/shared/libnative/component/EchWebView.kt')
android = read(ROOT / 'shared/core-native/src/androidMain/kotlin/com/xiaoyv/bangumi/shared/libnative/component/EchWebView.android.kt')

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
    '非 GET 不在拦截层放行': '需要传输桥，已被阻断' in client,
    '响应体交给 WebView 消费（不提前关流）': 'body?.byteStream()' in client and '.use {' not in client,
    '注入脚本只在受保护页面生效': 'if (!BgmEchPolicy.isProtected(host)) return' in script,
    '桥在 WebView 创建时安装': 'addJavascriptInterface' in script and 'EchWebBridgeJs.install(it)' in android,
    '域名清单与策略同源': '.replace("__PROTECTED__", BgmEchPolicy.scriptDomains())' in script,
    '脚本无 Kotlin 插值风险': '$' not in script.split('"""')[1] if '"""' in script else False,
    '桥原生侧再判一次受保护域名': 'BgmEchPolicy.isProtected(url.host)' in bridge
        and '拒绝代发' in bridge,
    '桥拒绝明文 HTTP 与不安全方法': '禁止明文 HTTP' in bridge and 'ALLOWED_METHODS' in bridge,
    '桥有体积上限与逐跳头过滤': 'MAX_BODY_BYTES' in bridge and 'BLOCKED_HEADERS' in bridge,
    'Cookie 与 WebView 共用一份': 'CookieManager.getInstance()' in transfer
        and 'getCookie' in transfer and 'setCookie' in transfer,
    '不自动跟跳（保住重定向的 Set-Cookie）': 'followRedirects' not in transfer
        and 'followSslRedirects(false)' in read(NATIVE / 'BgmEchTransport.kt'),
    'WebView 传输复用 ECH 栈': 'BgmEchTransport.configure(this)' in transfer,
    '安卓平台参数与工厂已接线': 'PlatformWebViewParams(client = EchWebViewClient())' in android
        and 'expect fun echWebViewFactory' in common,
    '其他平台保持原行为': 'rememberEchWebViewParams(): PlatformWebViewParams? = null'
        in read(ROOT / 'shared/core-native/src/iosMain/kotlin/com/xiaoyv/bangumi/shared/libnative/component/EchWebView.ios.kt'),
}

for name, path in hosts.items():
    text = read(path)
    checks[f'{name}已接 ECH WebView'] = 'rememberEchWebViewParams()' in text and 'echWebViewFactory' in text

for name, passed in checks.items():
    print(f'{name}：实际={"通过" if passed else "失败"}，期望=通过')
print(f'WebView ECH 接线静态检查：{sum(checks.values())}/{len(checks)}；不代表编译与真机结果')
sys.exit(not all(checks.values()))
