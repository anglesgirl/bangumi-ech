"""Firebase 接线静态门禁；不替代 CI 编译与真机上报验证。"""
import json
import sys
from pathlib import Path
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
source = Path(sys.argv[1] if len(sys.argv) > 1 else root / 'android/google-services.json')
if not source.exists():
    print('未找到 google-services.json（CI 由 secret 注入），跳过 Firebase 接线检查')
    sys.exit(0)
config = json.loads(source.read_text())
package = 'com.anglesgirl.bangumi.ech'
assert config['project_info']['project_id'] == 'bangumi-49d41'
assert any(c['client_info']['android_client_info']['package_name'] == package for c in config['client'])
gradle = (root / 'android/build.gradle.kts').read_text()
assert f'applicationId = "{package}"' in gradle
assert 'id("com.google.gms.google-services")' in gradle
assert 'implementation("com.google.firebase:firebase-analytics")' in gradle
assert 'implementation(platform("com.google.firebase:firebase-bom:' in gradle
ET.parse(root / 'android/src/main/AndroidManifest.xml')
assert '/android/google-services.json' in (root / '.gitignore').read_text()
print('Firebase 配置/包名/依赖/清单/忽略规则检查通过；尚未验证编译和事件上报')
