"""ECH 策略源码门禁；不是 Kotlin 编译、握手或真机测试。"""
from pathlib import Path
import re
import sys

root = Path(__file__).resolve().parents[1]
source = root / 'shared/core-native/src/androidMain/kotlin/com/xiaoyv/bangumi/shared/libnative/ech/BgmEchPolicy.kt'
text = source.read_text() if source.exists() else ''
build = (root / 'shared/core-native/build.gradle.kts').read_text()
rules = (root / 'android/proguard.pro').read_text()
checks = {
    '新增策略文件存在': source.is_file(),
    '受保护域名使用 REQUIRED 而非可降级模式': 'DomainEncryptionMode.REQUIRED' in text and 'DomainEncryptionMode.ENABLED' not in text,
    '保留公开反射入口': 'fun getNetworkSecurityPolicy(): NetworkSecurityPolicy = policy' in text,
    '证书校验委托原信任管理器': 'delegate.checkServerTrusted(chain, authType)' in text,
    '域名匹配包含点边界': 'host.endsWith(".$domain")' in text,
    '仅安卓依赖升级': 'implementation("org.conscrypt:conscrypt-android:2.7.0")' in build and 'implementation(libs.conscrypt.openjdk)' in build,
    'R8 保留反射策略入口': '-keep class com.xiaoyv.bangumi.shared.libnative.ech.BgmEchPolicy$PolicyTrustManager { *; }' in rules,
    '源码未嵌入私有网关': not re.search(r'https?://', text),
}
for name, passed in checks.items():
    print(f'{name}：实际={"通过" if passed else "失败"}，期望=通过')
print(f'静态门禁：{sum(checks.values())}/{len(checks)}；未验证编译和 ECH 握手')
sys.exit(0 if all(checks.values()) else 1)
