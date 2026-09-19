# H3 + ECH 技术文档（安卓 · 可复用到其它 App）

> 适用：中国大陆网络环境下，需要**抗 SNI 封锁**并尽量**贴近浏览器速度**的原生 App。
> 本文所有"实测"结论都来自真机 / HAR / 探针，未验证的会标【未验证】。

---

## 一、结论速览（先看这个）

| 通道 | 用的技术 | 适合谁 | 不适合谁 |
|---|---|---|---|
| 原生请求（API、登录、WebView 转发） | **TCP + TLS1.3 + ECH**（OkHttp + Conscrypt，in-process） | 所有被墙/需隐藏 SNI 的域名 | — |
| 图片 | **HTTP/3（QUIC）+ ECH**（Rust quiche，JNI） | 真正在高丢包线路上受益、且原生不支持 H3 的图床 | 未启用 H3 的域名、要求人机验证的站点 |
| WebView 页面/登录/人机验证 | **本机拦截 + 原生栈转发**（WebView 无法配置 ECH/H3） | 必须跑 JS 的登录、挑战页 | — |

**一句话**：ECH 解决"能不能连"，H3 解决"连上之后快不快"，WebView 只能靠拦截兜底。

---

## 二、整体架构

```
                    ┌─────────────────────────────────────────────┐
   App 原生请求      │ OkHttp + Conscrypt（in-process TLS/ECH）      │
   (API/登录/图片兜底) │  · 自定义 TrustManager 暴露 ENABLED            │
                    │  · DoH 取优选 IP，ECH 取官方活值                │
                    └─────────────────────────────────────────────┘
   App 图片（H3 白名单）┌───────────────────────────────────────────┐
                    │ Rust quiche（H3+ECH）→ JNI → Coil Fetcher    │
                    │  · 与 TCP 通道互补；失败回落 TCP+ECH           │
                    └─────────────────────────────────────────────┘
   WebView           ┌─────────────────────────────────────────────┐
   (登录/人机验证/页面) │ shouldInterceptRequest → 本机 → 原生栈        │
                    │  · GET 走原生代理；非 GET 走 JS 桥 + 原生发送   │
                    └─────────────────────────────────────────────┘
```

分工原则：**各组件只干自己强项**。OkHttp 管有状态、复杂的 API；quiche 管量大、无状态的图片 GET；WebView 只做页面渲染。

---

## 三、关键机制与坑

### 3.1 DoH 网关（自有 CF Worker）

- **为什么自建**：`1.1.1.1` / `cloudflare-dns.com` 在墙内实测不可用；Worker 本身可达官方 DNS，手机则不可达。
- **网关返回两样东西**：
  1. **优选 IP**（A/AAAA）——按目标域名走自有 DoH，IP 由自己掌控；
  2. **ECH 配置**——取 **`cloudflare-ech.com` 的实时值**（经网关查询），而不是自己 zone 里的静态记录。
- **"地址来自自己、ECH 取官方活值"**：CF 的 ECH key 约 5 小时轮换一次；静态值会过期，过期后握手被拒（`EchRejectedException` / `OPENSSL_internal:ECH...`）。
- **缓存语义按浏览器来**：**不怕过期**（过期后台刷新，不阻塞请求），**只怕无效**（真正无效才丢弃重取）。冷启动：内存 → 落盘（上次成功的地址与配置）→ 在线，且冷启动失败**不触发冷却**。
- **落盘与冷却**：配置/地址缓存 ≤5 小时；DoH 按主机加锁，避免并发风暴。

### 3.2 Conscrypt ECH（in-process，必须）

- **为什么必须 in-process**："外挂线程 + 本地端口"的 Go 代理在安卓上实测被系统杀 / 卡死，已否决。
- **必须自写 TrustManager**：要让 ECH 真正发出，TrustManager 必须暴露 `getNetworkSecurityPolicy()` → `ENABLED`，否则 **ECH 静默不发**（握手"成功"但 SNI 明文，等于白做）。
- **主机名校验**：`Domain specific configurations require that hostname aware checkServerTrusted` → 需把带 hostname 的 `checkServerTrusted` 委派给系统的 `X509TrustManagerExtensions`。
- **fail-closed**：宁可请求失败，也不回落到明文 SNI。

### 3.3 H3（quiche + ECH）

- **选型过程**（都实测过，别再走回头路）：
  - Cronet：public API **既不支持自定义 DoH，也不暴露 ECH 注入** → 不可用；
  - `quiche`：自带完整 BoringSSL，**只缺一个入口** → patch 暴露 `SSL_set1_ech_config_list` 即可；
  - 自写 QUIC 客户端：成本过高，不划算。
- **patch 方式**：`quiche 0.22` 的 `src/tls/mod.rs` 暴露 ECH 设置入口；构建前用脚本打补丁（见 `native-h3/tools/patch_quiche.py`）。
- **每请求开销**：ECH 握手增量约 **1ms** 量级（探针实测），慢的是网络而不是 ECH。
- **回落**：H3 失败（UDP 被限速/被断）→ 立即回落 TCP+ECH；并有**熔断**（同一 host 连续失败 2 次 → 冷却 60 秒），避免每张图白等一次握手。
- **待优化**：当前实现**每请求新建 socket + 新 QUIC 握手**；改成按 host 保活连接后，首图之后每次握手成本≈0。

### 3.4 Coil 图片接入（H3 Fetcher）

- **只接管真正受益的图床**（白名单）。实测：`i.pximg.net`（经自有 CF 网关）走 H3 有收益；**`lain.bgm.tv` 在 CF 侧未启用 H3**（握手 alert 40），**AnimePic 站点自身拒绝非浏览器请求**（403/302）→ 一律不接管，直接走原链路。
- **H3 请求必须带 `User-Agent` + `Accept: image/*`**，否则 CDN 会返回 HTML（403 页），Coil 拿 HTML 解码失败 → **黑屏**。
- **临时文件要保留原扩展名**，并按文件头嗅探 `mimeType`（AVIF 依赖它挑解码器），否则 Coil 选错解码器。
- **Referer 按站点政策加**（防盗链站点才加，别一刀切）。
- **R8 会删掉资源**：网关地址放在 `res/raw/keep.xml` 的 `tools:keep` 里，否则正式包读不到。

### 3.5 WebView

- WebView（Chromium）**本身支持 ECH/H3，但没有配置入口**：不能注入 ECH，也不能指定 DoH。
- 因此：拦截请求 → 本机 → 原生栈（带 ECH）发出；非 GET（登录表单等）走注入的 JS 桥，由原生发送并把响应回填。
- **拦截返回的响应不会进入 Chromium 的 HTTP 栈**，所以"注入 `Alt-Svc: h3` 让 WebView 自己升级到 H3"**实测无效**，别再试。
- loopback 代理必须**拒绝受保护域名**，否则 Worker/WebSocket 会绕过拦截走明文。

---

## 四、复用与集成步骤（搬到别的 App）

### 4.1 需要搬的东西

1. **Rust crate**（`native-h3`）：H3+ECH 取图，导出 JNI 函数（返回文件路径 / 字节 + 扩展名）。
2. **Kotlin 层**：JNI 包装（含白名单、熔断、DoH/ECH 取值）——对外只暴露一个函数即可：
   ```kotlin
   fun fetchImageToFile(url: String, headers: Map<String, String>): Result<File>
   ```
3. **图片库适配层**：Coil 用 `Fetcher`；Glide 用 `ModelLoader`；React Native 走 `Image` 的原生模块；iOS 直接调 quiche（BoringSSL 支持 iOS）。
4. **ECH 策略与 DoH**：域名白名单 + 网关地址（构建期注入，**不要硬编码进公开仓库**）。

### 4.2 构建

- Rust：`cargo ndk -t arm64-v8a ...`（当前只出 arm64；要 armv7 需加 `armv7-linux-androideabi` 目标，需自行验证）。
- 打包：`.so` 进 `jniLibs`；Gradle 里 `resValues` 打开（网关地址注入用）。
- CI 步骤：Rust 工具链 → patch quiche → NDK 构建 → Gradle 打包 → **验证 APK 内 `.so` 存在** + **资源里网关地址存在**（R8 会删）。

### 4.3 接入其它 App 的最小清单

| App | 图片库 | 接入点 | 备注 |
|---|---|---|---|
| Bangumi（本仓库） | Coil 3 | `Fetcher` + `ImageInterceptor` | 已完成 |
| Han1meViewer | 待确认 | 同 Coil 思路 | 需确认其图片库与 ECH 传输层现状 |
| CO3（RN） | RN Image | 原生模块（Android/iOS 各一） | iOS 侧复用 Go 代理更省事 |
| iOS 任何 App | — | 直接调 quiche（gomobile 或静态库） | ECH 走 Go 代理也可 |

---

## 五、实测数据与选型依据（避免重复踩）

- H3（带/不带 ECH）在**电信、广电（移动网络）**均可用，112KB 图片总耗时约 **200–330ms**；**不带 ECH 的明文 SNI 也能过**（说明 GFW 的 SNI 阻断主要作用于 TCP）。
- WebView 直连被墙域名：`ERR_CONNECTION_RESET`（TCP 首连被断），**因此永远升级不到 H3**。
- 探针/机房 IP 上 curl 得到的 403/302 **不代表真机行为**：CF 会对机房 IP 直接给 `Just a moment...`。**一切以真机 HAR 为准。**
- `i.pximg.net` 是 Pixiv 自建（直连被墙、无原生 ECH/H3），需经自有 CF 网关；`www.pixiv.net` 等本身在 CF 上，直接用 CF 的 A 记录 + 官方 ECH 活值，避免二次回源（两跳）。
- AnimePic 图床后缀：列表用 `opreviews/..._cp.avif` / 详情用 `_bp.avif`，原图 `oimages/...jpg`（HAR 实测全 200）；**AVIF 在 `zoomimage` 这类靠 subsampling 的组件里会整片空白**，改用普通 `AsyncImage` 正常。

---

## 六、复查清单（发布前）

- [ ] 正式包里 `libbgm_h3.so` 存在（readelf/zip 校验）
- [ ] 正式包里能读到网关地址资源（R8 keep 生效）
- [ ] 冷启动：先落盘兜底、后台刷新、失败不冷却
- [ ] ECH 配置：取 `cloudflare-ech.com` 活值；过期自动刷新、无效才丢弃
- [ ] H3 白名单只留真正受益的域名；失败必须回落 TCP+ECH
- [ ] WebView loopback 拒绝受保护域名
- [ ] 真机验证：图片能出、暗色模式可见、滑动不卡、日志有成功侧上报
