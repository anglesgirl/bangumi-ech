package com.xiaoyv.bangumi.shared.data.model.request.bgm

import androidx.compose.runtime.Immutable
import com.xiaoyv.bangumi.shared.data.model.response.bgm.user.ComposeUserPrivacy
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * [UpdatePrivacyParam]
 *
 * `PATCH /p1/privacy` 的请求体。
 *
 * 服务端只接受 `settings` 与 `preferences.showNsfwSubject`；
 * `preferences.canSetNsfwSubject` 与 `preferences.allowNsfw` 是服务端计算出的只读字段，
 * 一并回传会被拒绝（400，历史上曾返回 500），表现为开关看起来打开了但实际没有保存。
 *
 * @param settings 隐私设置；null 表示本次不修改
 * @param preferences 内容偏好；null 表示本次不修改
 */
@Immutable
@Serializable
data class UpdatePrivacyParam(
    @SerialName("settings")
    val settings: ComposeUserPrivacy.ComposeUserPrivacySettings? = null,
    @SerialName("preferences")
    val preferences: Preferences? = null,
) {

    @Immutable
    @Serializable
    data class Preferences(
        @SerialName("showNsfwSubject")
        val showNsfwSubject: Boolean? = null,
    )

    companion object {
        /**
         * 由完整隐私配置构造可提交的补丁，只保留可写字段。
         *
         * @param privacy 服务端返回的完整隐私配置
         */
        fun from(privacy: ComposeUserPrivacy): UpdatePrivacyParam = UpdatePrivacyParam(
            settings = privacy.settings,
            preferences = Preferences(showNsfwSubject = privacy.preferences.showNsfwSubject),
        )
    }
}
