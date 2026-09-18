"""图源搜索词的静态检查：防止「纯汉字即过滤」再次把日文原名删掉。

anime-pictures 的标签索引按原名（日文汉字/假名）建立；用它搜索时，
纯汉字过滤会把日文汉字名一起删掉，搜索词变空 -> 任何角色都返回 0 条。
"""
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
SOURCE = (ROOT / 'shared/data/src/commonMain/kotlin/com/xiaoyv/bangumi/'
                 'shared/data/repository/impl/ImageRepositoryImpl.kt')
text = SOURCE.read_text() if SOURCE.exists() else ''

checks = {
    '图源实现存在': SOURCE.is_file(),
    '原名无条件保留': 'return@runResult listOf(original)' in text,
    '不再对搜索词做纯汉字过滤': '.matches(Regex(' not in text,
    '别名仅在原名缺失时使用': '仅当原名缺失时才退回别名' in text,
    '排除中文译名': 'it != data.nameCN' in text,
    # 默认图片域必须是 P 站官方（作者代理已不需要），否则等于回到"依赖第三方中转"。
    '默认图片域为 P 站官方': '"pixivImageHost") val pixivImageHost: String = "https://i.pximg.net/"' in (
        ROOT / 'shared/data/src/commonMain/kotlin/com/xiaoyv/bangumi/shared/data/model/response/bgm/ComposeSetting.kt'
    ).read_text(),
    '官方域在候选清单里且居首': (
        'ComposeTextTab("https://i.pximg.net/", labelText = "i.pximg.net（官方）"),\n'
        '        ComposeTextTab("https://xget.xiaoyv.com.cn/pximg/"'
    ) in (ROOT / 'shared/ui/src/commonMain/kotlin/com/xiaoyv/bangumi/shared/ui/composition/TabTokens.kt').read_text(),
}
_preview = (ROOT / 'features/preview/main/src/commonMain/kotlin/com/xiaoyv/bangumi/'
                   'features/preivew/main/PreviewMainScreen.kt')
_preview_text = _preview.read_text() if _preview.is_file() else ''
checks['预览页有缩略图占位（不白屏等原图）'] = 'placeholders' in _preview_text \
    and 'AsyncImage(' in _preview_text and 'placeholder.isNotEmpty()' in _preview_text

for name, passed in checks.items():
    print(f'{name}：实际={"通过" if passed else "失败"}，期望=通过')
print(f'图源搜索词检查：{sum(checks.values())}/{len(checks)}；不代表运行验证')
sys.exit(0 if all(checks.values()) else 1)
