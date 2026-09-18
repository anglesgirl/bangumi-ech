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

三类域名，处理方式不同：

| 类别 | 域名 | 做法 |
|---|---|---|
| **强制 ECH** | `bgm.tv`、`bangumi.tv`、`chii.in`、`pixiv.net`、`pximg.net` 及子域（网关已发布 `ech=`） | 走 ECH 通道；地址只来自自有 DoH；拿不到配置就阻断 |
| **仅换地址** | `challenges.cloudflare.com`（网关无 `ech=`） | 只替换连接地址（首选实测固定 IP `104.18.40.152` / `172.64.147.104`，其次网关 DoH，最后系统解析）；**TLS 仍由 WebView 自己完成**，保持浏览器指纹 |
| 原样 | 其余域名 | 不动 |

- 只换地址这一类**不能进 ECH 通道**：人机验证对 TLS 指纹敏感，换栈反而更容易被识别；
  换 IP 已经解决污染问题，SNI 本来也不是它的痛点。
- 固定 IP 必须先用 `/cdn-cgi/trace` 的 `h=` 字段确认真能服务该域名（换 IP 后证书与资源都要正常），
  否则表现为 403/404 或证书错误。
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

## 分工与边界（本轮核实）

- **方法分工**：拦截层放行 **GET 与 HEAD**（HEAD 无响应体、不必过桥），其余方法一律 502，交给 JS 桥代发。
  本轮发现两边曾经不一致：JS 把 HEAD 归给拦截层，拦截层却只放行 GET —— 受保护域名的 HEAD 请求会直接 502。
- **桥要连发起页面一起校验**：`addJavascriptInterface` 在 WebView 创建时就装上，桥对页面里任何脚本都可见。
  只校验目标域名的话，正文区打开的任意第三方页面都能借用户 Cookie 去打受保护域名（CSRF）。
  现在除目标域名外，还要求**发起页面自身**在受保护范围内。
  **残留面**：受保护页面里的第三方子框架仍可调用桥，未再细化。
- **代理覆盖是进程级的**：用自增序号标记"当前生效的是哪次设置"，旧的 `stop()` 不再无条件 `clearProxyOverride`，
  避免人机验证与登录两处 WebView 互踩（一方 dispose 会让另一方静默退回直连）。
- **主文档跳转不内部跟跳**：https→https 的 301/302 如果被客户端吃掉，WebView 收到 200 却仍以为
  地址是原 URL，页面里相对路径会全部解析错位（表现为"加载很久/半残"）。主文档一律换成
  `location.replace(Location)` 的最小页面，让 WebView 自己走真实跳转；子资源保持内部跟跳。
- **磁盘缓存**：拦截式响应会绕过 WebView 自身的 HTTP 缓存，必须给这条客户端配 `okhttp3.Cache`
  （`cacheDir/ech-webview`，128MB）。不配就是每次打开把页面与图片整套重下一遍——
  外部浏览器有缓存所以"秒开"，内置浏览器"加载很久"，差别主要在此。缓存策略交给服务器头。
- **跳转**：传输层只关掉了 `followSslRedirects`，https→https 的重定向由客户端内部跟随（浏览器语义），
  每一跳的 `Set-Cookie` 都会写回 `CookieManager`，因此页面通常直接看到最终响应。
