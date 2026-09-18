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
