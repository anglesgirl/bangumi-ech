# WebView 流量接 ECH

## 为什么不是在那个 loopback 代理里改

`AntiSniWebProxy` 是 `ProxyController.setProxyOverride` 装的 **WebView 专用代理**（`127.0.0.1:随机端口`），
它只做**字节转发 + TLS 分片**，不终结 TLS。ECH 必须由**发起 TLS 的一方**在 ClientHello 里发出去，
代理既看不到明文也不该看 —— 所以在那儿接不出 ECH。真正的接法是往上一层：**WebView 的请求入口**。

## 两条路，都在客户端这一侧

| 流量 | 接法 | 失败时 |
|---|---|---|
| GET（主文档 + 子资源） | `EchWebViewClient.shouldInterceptRequest` 代发 | 返回 502 页面，**绝不返回 null 让 WebView 自己连** |
| 非 GET（fetch / XHR / 表单） | 注入 JS 桥 → `EchWebBridge.send` 代发 | promise reject / XHR 伪装成 502，**绝不回落明文** |

- 非 GET 的 body 不在 `WebResourceRequest` 里，拦不到；所以由注入脚本把请求参数交给原生。
- 拦截层与注入桥**不重叠**：GET 归拦截层，非 GET 归桥。桥只在受保护域名的页面注入，且**原生侧再判一次**域名与请求形态。
- 两者都用 `EchWebTransfer` 的 OkHttp 客户端：`BgmEchTransport.configure()` 同一条 Conscrypt ECH 栈 + 同一套 DoH 策略。

## Cookie：和 WebView 共用一份

`EchWebTransfer` 的 `CookieJar` 直接读写 WebView 的 `CookieManager`：
出站从 `getCookie` 取，入站把响应里的 `Set-Cookie` 写回（去掉 `Secure`、`SameSite=None` 放宽成 `Lax`，保留 `HttpOnly`）。
页面里看到的登录态与原生请求因此不会分叉。客户端**不自动跟跳**：重定向的 `Set-Cookie` 必须先落库再交给页面。

## 覆盖范围与已知边界

- 四个 WebView 宿主全部接线：内置浏览器页、人机验证页（`next.bgm.tv`）、工作流弹窗、以及它们的默认工厂。
- ECH 只对**发布过 ECH 记录**的域名有意义（本项目受保护域名：`bgm.tv`、`bangumi.tv`、`chii.in` 及子域）。
  Pixiv、CF 挑战域名等没有 `ech=` 记录，仍走原来的加固链路，不会因为这次改动变差。
- 含文件的表单（`multipart` / `input[type=file]`）**不接管**：桥搬不动文件流，这类请求按原路走并会明确报错，不会假装已保护。
- 表单 POST 返回 200 且不带 `Location` 时，页面按“跳到 action”处理 —— 登录类站点都是 302，够用；这类少数场景未做额外渲染。

## 注入脚本必须离线断言

注入 JS 写坏属于**静默失效**（页面看起来正常、实际在明文发包），所以它在 **CI 编译前**就要跑：

```bash
python3 tools/extract_webview_bridge.py /tmp/bgm-ech-bridge.js   # 从 Kotlin 原始字符串抽出并替换域名清单
node --check /tmp/bgm-ech-bridge.js
node tools/webview_bridge_test.js /tmp/bgm-ech-bridge.js          # 22 项接管行为断言
```

断言覆盖：受保护域名非 GET 被接管、非受保护域名与 GET 放行、XHR 状态/正文/事件伪装、页面自设 `Content-Type` 不被覆盖、
同步 XHR 不接管、只劫持含密码框且无文件的表单、原生报错时**不回落明文**。
