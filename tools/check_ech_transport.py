"""只检查源码接线，不代替 Kotlin 编译或 ECH 握手测试。"""
from pathlib import Path
import sys

root = Path(__file__).resolve().parents[1]
native = root / 'shared/core-native/src/androidMain/kotlin/com/xiaoyv/bangumi/shared/libnative'
def read(path):
    return path.read_text() if path.exists() else ''
transport = read(native / 'ech/BgmEchTransport.kt')
doh = read(native / 'ech/BgmEchDoh.kt')
system = read(native / 'System.android.kt')
app = read(native / 'AppApplication.kt')
checks = {
    '统一入口无条件安装 ECH': 'BgmEchTransport.configure(this)' in system,
    '统一入口仅保留 ECH 接线': 'config {\n                    BgmEchTransport.configure(this)\n                }' in system,
    '统一入口无旧分片和 DNS 接线': not any(token in system for token in (
        'if (customResolve)', 'AntiSni', 'antiSniTlsEngine', 'DomainTlsFragmentationPolicy',
        'socketFactory(', 'sslSocketFactory(', 'dns(',
    )),
    '旧分片工厂和 TLS 引擎已移除': not (native / 'sni/AntiSniSocketFactory.kt').exists()
        and not (native / 'sni/AntiSniTlsEngine.kt').exists(),
    '握手前注入真实配置': 'Conscrypt.setEchConfigList(socket, config)' in transport,
    '使用带策略的信任管理器': 'BgmEchPolicy.PolicyTrustManager' in transport,
    '保护域名不使用系统 DNS': 'BgmEchDoh.resolve(hostname)' in transport
        and 'original.dns.lookup(hostname)' in transport,
    '默认 A 记录优先、ECH hint 兜底': 'val preferred = BgmEchDoh.resolve(hostname)' in transport
        and 'hinted + preferred else preferred + hinted' in transport,
    'DoH 按主机加锁（不全局串行）': 'synchronized(lockFor(host))' in doh
        and 'ConcurrentHashMap<String, Any>()' in doh,
    '冷启动预热且失败不进冷却': 'fun warmUp(' in doh and 'DoH 预热失败' in doh
        and 'BgmEchDoh.warmUp(BgmEchPolicy.warmUpHosts())' in transport,
    'App 启动即预热（不等首个客户端）': 'BgmEchDoh.warmUp(BgmEchPolicy.warmUpHosts())' in app,
    '预热并发度不低于 4': 'newFixedThreadPool(4)' in doh,
    # 冷启动策略：先用上次成功的落盘结果开路，后台再取在线数据覆盖。
    '冷启动优先用落盘结果': 'persistedAddresses(host)?.let { cached ->' in doh
        and 'persistedConfig(host)?.let { cached ->' in doh
        and 'CACHED_HOLD_MILLIS' in doh,
    '缓存值后台刷新且不写冷却': 'refreshLater(host)' in doh
        and 'fetchAddresses(host, bestEffort = true)' in doh
        and 'fetchConfig(host, bestEffort = true)' in doh,
    # ECH 配置约 5 小时轮换：缓存上限不能超过 5 小时，过期就重取一次再缓存。
    'ECH 配置缓存不超过 5 小时': 'CACHED_CONFIG_MAX_AGE_MILLIS' in doh and '5 * 60 * 60 * 1000L' in doh,
    '地址同样按 5 小时缓存': 'CACHED_ADDRESS_MAX_AGE_MILLIS' in doh and '5 * 60 * 60 * 1000L' in doh,
    'hints 与配置同源同有效期': 'persisted("hint:$host", CACHED_CONFIG_MAX_AGE_MILLIS)' in doh,
    '服务器拒绝后优先用 ECH 记录里的地址': 'markPreferHints' in transport
        and 'BgmEchDoh.preferHints(hostname)' in transport
        and 'hintfirst:' in doh,
    '地址顺序按标志位切换': 'hinted + preferred else preferred + hinted' in transport,
    # 手写/注入的记录过期后再拉还是旧的（实测：连它自己的 zone 都握手不过），
    # 所以配置一律先取 CF 官方 ECH 域名 cloudflare-ech.com 的实时值。
    '配置首选 CF 官方 ECH 域名的实时值': 'REFERENCE_ECH_HOST = "cloudflare-ech.com"' in doh
        and 'val first = if (ownRecordFirst(host)) host else REFERENCE_ECH_HOST' in doh
        and 'adoptConfig(host, config)' in doh,
    '被拒后翻成用该域名自己的记录': 'markOwnRecordFirst' in transport and 'ownfirst:' in doh,
    '失效时连官方缓存一起丢': 'configs.remove(REFERENCE_ECH_HOST)' in doh,
    '握手失败丢缓存并用在线配置重试一次': 'invalidateConfig(request.url.host)' in transport
        and 'isTlsFailure()' in transport
        and 'chain.proceed(request)' in transport,
    'DoH 失败先换端点重试再冷却': 'rotateEndpoint()' in doh and 'RETRY_DELAY_MILLIS' in doh
        and '已阻断并冷却 5 分钟' in doh,
    '最近成功的地址可兜底且有有效期': 'persistedAddresses(host)' in doh
        and 'CACHED_ADDRESS_MAX_AGE_MILLIS' in doh,
    '最近成功的 ECH 配置可兜底': 'persistedConfig(host)' in doh
        and 'Base64.encodeToString(result' in doh,
    'hint 取自 ECH 记录且会落盘': 'ipv4hint=([0-9.,]+)' in doh
        and 'hints[host] = Entry(parsed' in doh
        and 'persist("hint:$host"' in doh,
    '拒绝明文 HTTP': 'requireHttps(chain.request())' in transport,
    '候选地址按序回退且 DoH 不重试': 'retryOnConnectionFailure(true)' in transport
        and 'retryOnConnectionFailure(false)' in doh,
    '网关自带 IP 且禁止系统解析': 'ECH 网关缺少自有 IP' in doh and 'Dns.SYSTEM' not in doh,
    '网关池通过资源读取': '"ech_doh_pool"' in doh,
    '资源压缩保留网关资源': 'tools:keep="@string/ech_doh_pool"' in read(root / 'android/src/main/res/raw/keep.xml'),
    '失败冷却持久化': 'putLong("blocked_until",' in doh and '300_000L' in doh,
    '无写死 ECH 配置': 'Base64.decode' in doh and 'fetch(pool, host, "HTTPS")' in doh,
    'IP 响应先校验再解析': 'parseIpv4Literal' in doh and 'InetAddress.getByAddress' in doh,
}
for name, passed in checks.items():
    print(f'{name}：实际={"通过" if passed else "失败"}，期望=通过')
print(f'源码接线静态检查：{sum(checks.values())}/{len(checks)}；不代表运行验证')
sys.exit(not all(checks.values()))
