# ECH 网关配置与正式构建

## 网关池格式（构建时从 `ECH_DOH_POOL` 注入）

```
https://网关域名/dns-query|IP|IP,https://备用网关/dns-query|IP
```

- 逗号分隔多条网关；每条**必须**用 `|` 带上它自己的 IP。
- 缺 IP 直接阻断并提示 `ECH 网关缺少自有 IP`：受污染的网络里绝不能用系统 DNS 解析网关域名。
- 源码里不再内置任何网关 IP，也不对网关做系统解析（静态门禁会拒绝 `Dns.SYSTEM`）。
- 编译期正则已放开 `|` 字符，其他字符仍受限。

## 受保护域名与地址来源

- ECH 强制生效范围：`bgm.tv`、`bangumi.tv`、`chii.in` 及子域（`api.bgm.tv`、`next.bgm.tv`、`lain.bgm.tv` 都覆盖）。
- 这些域名的地址**只**来自网关 DoH 的 A 记录，拿不到就阻断，不回落系统解析。
- 网关通常一次给出多个地址，因此允许连接层按序回退（`retryOnConnectionFailure(true)`）：
  单个地址不可达时换下一个，而不是整页报错。这只是连接建立层面的回退，
  收到响应后不重发请求，DoH 查询自身仍然不重试。
- 单次 DoH 失败会写入 5 分钟冷却（持久化，重启不绕过），期间受保护域名不可用——这是 fail-closed 的设计代价。

## 速度相关的三处设计与踩坑

- **DoH 按主机加锁**：缓存与锁都按主机粒度（`ConcurrentHashMap` + `lockFor(host)`）。
  早期用对象级 `@Synchronized`，一屏图片会一个个排队等取地址，是"加载慢"的主因之一。
- **冷启动预热**：`BgmEchDoh.warmUp(...)` 在首个 OkHttp 客户端构建时并发取回常用主机的地址与
  ECH 配置（`BgmEchPolicy.warmUpHosts()`），把两次 DoH 往返从用户第一屏的关键路径上挪走；
  预热失败**不写冷却**，否则一次网关抖动会被放大成 5 分钟整体不可用。
- **A 记录优先、`ipv4hint` 兜底**：网关的 A 记录是用户按国内实测优选过的地址，要优先；
  ECH 记录里的 hint 常是另一组地址，只作兜底候选。两者不同时不能只取 hint。

## 冷启动策略：先用上次成功的落盘结果开路

- **顺序**：内存缓存 → **落盘缓存（上次成功的结果，24 小时内有效）** → 在线 DoH 查询。
  冷启动直接拿落盘结果建连，首屏不再等 DoH；同时后台（`ech-doh-refresh` 线程池，同一主机 60 秒内只刷一次）
  去取在线数据覆盖它——这就是"先用现在的缓存结果做冷启动，拿到真正的数据再改用在线"。
- **缓存过期怎么办**：落盘配置最坏是握手失败（不会明文外泄）。传输层捕获 `SSLException` 后
  调用 `BgmEchDoh.invalidateConfig` 丢掉配置与 hints（**地址保留**，重试更快），
  然后重试一次，这一次取的是在线配置；仍失败就如实抛错（fail-closed）。
- ECH 记录里的 `ipv4hint` 也会一起落盘，冷启动时作为额外候选地址。
- 在线查询仍保留"先换端点重试一次、仍失败才冷却 5 分钟"的逻辑。

## ECH 配置有有效期（实测教训）

- **CF 侧 ECH 密钥约 5 小时轮换**：拿过期配置去握手会被服务器拒绝
  （Conscrypt 报 `OPENSSL_internal:ECH...`），表现为"某些图片/接口突然打不开"。
- **缓存上限 5 小时**（配置与地址同）：超过 5 小时的配置拿去握手是匹配不上的，
  所以过期就不再使用，而是**重新取一次在线配置并缓存**（这就是"自动刷新"）；
  用缓存开路时也会**同时**后台刷新。无效（被服务器拒绝）的则丢弃、重取、重试一次。
- 用缓存开路时**同时**后台刷新；一旦握手失败判定为"配置过期或不被该地址接受"：
  丢弃配置与 hints、记住该主机改用 ECH 记录里的 `ipv4hint` 地址（CF 为 ECH 推荐的地址），再重试一次。
- 教训：给"冷启动优先用缓存"设的窗口不能比密钥轮换周期长，否则第一次请求必然握手失败。

## 冷启动可靠性（用户实测：冷启动会"卡一下"再刷新才通）

两个补丁，针对的是"首次查询失败 → 5 分钟整体不可用"这个放大效应：

- **失败先换端点重试一次**（等 400ms），仍失败才写 5 分钟冷却。冷启动时网络刚唤醒、
  第一次 DoH 握手容易瞬时失败，单次抖动不该变成分钟级不可用。
- **最近一次成功的地址与 ECH 配置落盘兜底**（10 分钟内有效）：网络查询失败时用旧值继续，
  地址与配置都不涉及明文回落，SNI 仍受 ECH 保护；旧配置最坏结果是握手失败被阻断。

## 正式包（release）

- CI 同时产出 `assembleDebug` 与 `assembleRelease`，两个包都放进私有 Release（`ech-<run_id>`）。
- release 启用 R8 混淆、资源压缩与 baseline profile；debug 仅用于对比排查。
- 两者使用同一签名（`android/keystore/why.keystore`），可直接覆盖安装，不用卸载。
- R8 必须保留 `com.xiaoyv.bangumi.shared.libnative.ech.BgmEchPolicy$PolicyTrustManager`：
  Conscrypt 靠反射取 `getNetworkSecurityPolicy()` 与带主机名的 `checkServerTrusted`，被改名即静默失去 ECH。
- **资源压缩会删掉运行时按名称读取的资源**：网关地址用 `resources.getIdentifier("ech_doh_pool", …)`
  读取，没有 `R.string` 引用，`isShrinkResources = true` 会判定它没人用，把资源名和值一起删掉。
  实测同一提交：debug 包 `resources.arsc` 里有 `ech_doh_pool`×1、`162.159.36.20`×2，
  release 包两样都是 0 —— 装上后所有受保护域名静默阻断。
  因此 `android/src/main/res/raw/keep.xml` 必须写着 `tools:keep="@string/ech_doh_pool"`。
- **这类问题源码门禁查不出来，必须对产物断言**：CI 在构建后读正式包 `resources.arsc`，
  断言资源名和注入的网关域名都在，缺一即失败（只比对资源名不够，值被替换同样会坏）。

## 图片域默认值改回 P 站官方

- 网络设置里 `pixivImageHost` 的默认值原为作者的代理 `https://xget.xiaoyv.com.cn/pximg/`，
  现改为官方 `https://i.pximg.net/`；候选清单也把 `i.pximg.net（官方）` 提到第一位，
  否则下拉框里选不中当前值。
- 走官方域的两个前提都已实测：
  1. **有 ECH**：经自有网关查 `i.pximg.net` 的 HTTPS 记录，含 `ech=AEX+DQBB…`，
     A 记录 `172.64.229.6`（日本段，非污染地址）；`source/imp/i-f.pximg.net`、`imgaz.pixiv.net` 同样带 ECH。
  2. **带 Referer**：`ImageInterceptor` 对含 `i.pximg.net` 的图片请求已设置 `Referer: https://www.pixiv.net/`，
     官方图床的反盗链要求满足。
- 作者代理仍保留为可选项（它本身也有 ECH），但不再是默认值。
