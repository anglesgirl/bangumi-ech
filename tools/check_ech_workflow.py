"""CI 出包链路的静态检查：保证"发出去的包"一定是带网关、能自证的那个包。

只核对工作流文本与固定事实，不代替一次真实构建。
"""
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
WORKFLOW = ROOT / '.github/workflows/ech-android.yml'
PACKAGE = 'com.anglesgirl.bangumi.ech'

text = WORKFLOW.read_text()
release_step = text.split('构建正式包')[-1].split('校验正式包')[0]

checks = {
    '调试包与正式包都先校验网关 secret': text.count('test -n "$ECH_DOH_POOL"') >= 2
        and 'test -n "$ECH_DOH_POOL"' in release_step,
    'Gradle 退出码如实传递': text.count('result=${PIPESTATUS[0]}') >= 2 and 'exit "$result"' in text,
    '正式包产物断言网关资源在位': 'ech_doh_pool' in text and 'resources.arsc' in text,
    '发布说明不再声称 WebView 未接入': 'WebView 尚未接入' not in text and 'WebView' in text,
    '包名与 Firebase 工程一致': PACKAGE in text and 'bangumi-49d41' in text,
    '编译 SDK 包名与 sdkmanager 命名自洽': 'platforms;android-37.0' in text,
    '门禁脚本全部纳入 CI': all(
        name in text
        for name in (
            'check_ech_policy.py',
            'check_ech_transport.py',
            'check_ech_webview.py',
            'check_ech_workflow.py',
            'check_firebase_config.py',
            'check_image_source.py',
        )
    ),
    '注入脚本编译前过语法与行为断言': 'node --check' in text and 'webview_bridge_test.js' in text,
}
for name, passed in checks.items():
    print(f'{name}：实际={"通过" if passed else "失败"}，期望=通过')
print(f'CI 出包链路检查：{sum(checks.values())}/{len(checks)}；不代表构建成功')
sys.exit(0 if all(checks.values()) else 1)
