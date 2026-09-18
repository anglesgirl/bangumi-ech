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
    '保护域名不使用系统 DNS': 'return BgmEchDoh.resolve(hostname)' in transport
        and 'original.dns.lookup(hostname)' in transport,
    '拒绝明文 HTTP': 'requireHttps(chain.request())' in transport,
    '候选地址按序回退且 DoH 不重试': 'retryOnConnectionFailure(true)' in transport
        and 'retryOnConnectionFailure(false)' in doh,
    '网关自带 IP 且禁止系统解析': 'ECH 网关缺少自有 IP' in doh and 'Dns.SYSTEM' not in doh,
    '网关池通过资源读取': '"ech_doh_pool"' in doh,
    '失败冷却持久化': 'putLong("blocked_until",' in doh and '300_000L' in doh,
    '无写死 ECH 配置': 'Base64.decode' in doh and 'fetch(pool, host, "HTTPS")' in doh,
    'IP 响应先校验再解析': 'parseIpv4Literal' in doh and 'InetAddress.getByAddress' in doh,
}
for name, passed in checks.items():
    print(f'{name}：实际={"通过" if passed else "失败"}，期望=通过')
print(f'源码接线静态检查：{sum(checks.values())}/{len(checks)}；不代表运行验证')
sys.exit(not all(checks.values()))
