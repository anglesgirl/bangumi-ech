package com.xiaoyv.bangumi.shared.libnative

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Process
import com.xiaoyv.bangumi.shared.libnative.ech.BgmEchDoh
import com.xiaoyv.bangumi.shared.libnative.ech.BgmEchPolicy
import kotlin.system.exitProcess

open class AppApplication : Application() {
    private val activityList: MutableList<Activity> = ArrayList()

    override fun onCreate() {
        super.onCreate()
        application = this

        // 冷启动预热：尽早把常用主机的地址与 ECH 配置取回缓存（内部走线程池，失败静默）。
        BgmEchDoh.warmUp(BgmEchPolicy.warmUpHosts())

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                activityList.add(activity)
            }

            override fun onActivityStarted(activity: Activity) {}

            override fun onActivityResumed(activity: Activity) {}

            override fun onActivityPaused(activity: Activity) {}

            override fun onActivityStopped(activity: Activity) {}

            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}

            override fun onActivityDestroyed(activity: Activity) {
                activityList.remove(activity)
            }
        })
    }

    fun exitApp(kill: Boolean = false) {
        for (activity in activityList) activity.finish()
        activityList.clear()
        if (kill) {
            Process.killProcess(Process.myPid())
            exitProcess(0)
        }
    }
}