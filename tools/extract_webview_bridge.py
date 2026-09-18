"""把注入用的 JS 从 Kotlin 原始字符串里抽出来，供 node 做语法与接管行为断言。

注入脚本写坏属于静默失效（页面看起来正常、实际在明文发包），所以这一步放在编译前。
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
SOURCE = (
    ROOT
    / 'shared/core-native/src/androidMain/kotlin/com/xiaoyv/bangumi/shared/libnative/ech/EchWebBridgeJs.kt'
)
POLICY = (
    ROOT
    / 'shared/core-native/src/androidMain/kotlin/com/xiaoyv/bangumi/shared/libnative/ech/BgmEchPolicy.kt'
)


def main() -> int:
    text = SOURCE.read_text()
    match = re.search(r'private val SCRIPT: String = """(.*?)"""', text, re.S)
    if not match:
        print('找不到注入脚本', file=sys.stderr)
        return 1
    script = match.group(1)
    if '$' in script:
        print('脚本里出现了 $，会被 Kotlin 当插值处理', file=sys.stderr)
        return 1

    policy = POLICY.read_text()
    block = re.search(r'private val domains = setOf\((.*?)\)\n', policy, re.S)
    if not block:
        print('找不到受保护域名清单', file=sys.stderr)
        return 1
    # 清单里可能有注释，先去注释再取域名，否则会把注释拼进 JS 造成语法错误。
    body = re.sub(r'//[^\n]*', '', block.group(1))
    items = re.findall(r'"([^"]+)"', body)
    if not items:
        print('受保护域名清单为空', file=sys.stderr)
        return 1
    listed = ','.join(f"'{item}'" for item in items)
    script = script.replace('__PROTECTED__', '[' + listed + ']')
    script = script.replace('__BRIDGE_NAME__', 'bgmEchBridge')
    script = script.replace('__CALLBACK__', '__bgmEchResolve')
    if '__PROTECTED__' in script or '__BRIDGE_NAME__' in script or '__CALLBACK__' in script:
        print('占位符没有全部替换', file=sys.stderr)
        return 1

    target = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else '/tmp/bgm-ech-bridge.js')
    target.write_text(script)
    print(f'已抽出注入脚本：{target}（{len(script)} 字节）')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
