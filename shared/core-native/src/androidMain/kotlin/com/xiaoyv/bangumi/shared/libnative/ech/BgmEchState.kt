package com.xiaoyv.bangumi.shared.libnative.ech

import android.content.Context
import android.util.Base64
import java.util.Locale

/**
 * ECH 配置（ECHConfigList，wire 格式含 2 字节长度前缀）的**落盘存储**。
 *
 * 为什么需要：配置以前只放在内存里，**每次冷启动都要重新去网上查一遍** —— 那是首屏最明显的一段等待。
 * 落盘后冷启动直接读本地，只要没过期就不必再查；过期即视为没有，调用方据此 fail-closed，
 * **绝不回落明文 SNI**（与 [BgmEchDoh] 的语义一致：拿不到配置宁可不连）。
 *
 * 与 [BgmEchDoh] 里 `ech_doh_state` 那份缓存的分工：
 * 这里是"ECH 配置专用"的一份，键与过期时间都带独立前缀，便于整体丢弃/排查；
 * 两者都只是**旧值**，不会造成任何明文回落。
 */
internal object BgmEchState {
    /** 专用 SharedPreferences 文件名。 */
    private const val PREFERENCES = "ech_state"
    private const val KEY_PREFIX = "ech:"

    @Volatile
    private var appContext: Context? = null

    /** 在 Application/初始化入口调用一次（幂等，重复调用只覆盖同一个 applicationContext）。 */
    fun attach(context: Context) {
        appContext = context.applicationContext
    }

    private fun preferences() = appContext?.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    /** 读上次落盘的配置；不存在或已过期返回 null。 */
    fun load(host: String): ByteArray? {
        val store = preferences() ?: return null
        return try {
            val key = keyOf(host)
            val text = store.getString(key, null) ?: return null
            val parts = text.split('|', limit = 2)
            if (parts.size != 2) return null
            val expireAt = parts[0].toLongOrNull() ?: return null
            if (now() >= expireAt) {
                store.edit().remove(key).apply()
                return null
            }
            Base64.decode(parts[1], Base64.DEFAULT).takeIf { it.isNotEmpty() }
        } catch (t: Throwable) {
            // 落盘数据损坏只当没有：宁可重新取，也绝不拿半个配置去握手。
            null
        }
    }

    /** 落盘一份配置（wire 格式，含 2 字节长度前缀）；[ttlMillis] 是本次可用时长。 */
    fun save(host: String, wire: ByteArray, ttlMillis: Long) {
        if (wire.isEmpty() || ttlMillis <= 0) return
        val store = preferences() ?: return
        try {
            val value = (now() + ttlMillis).toString() + "|" + Base64.encodeToString(wire, Base64.NO_WRAP)
            store.edit().putString(keyOf(host), value).apply()
        } catch (t: Throwable) {
            // 落盘失败只影响冷启动提速，不影响本次连接。
        }
    }

    /** 配置被服务器拒过（密钥轮换 / 不被该 zone 接受）：丢掉，逼下一次重新取。 */
    fun drop(host: String) {
        try {
            preferences()?.edit()?.remove(keyOf(host))?.apply()
        } catch (t: Throwable) {
        }
    }

    private fun keyOf(host: String): String = KEY_PREFIX + host.lowercase(Locale.ROOT).trimEnd('.')

    private fun now(): Long = java.lang.System.currentTimeMillis()
}
