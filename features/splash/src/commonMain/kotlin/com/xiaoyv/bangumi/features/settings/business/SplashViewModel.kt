package com.xiaoyv.bangumi.features.settings.business

import com.xiaoyv.bangumi.shared.core.mvi.BaseViewModel
import com.xiaoyv.bangumi.shared.core.mvi.postEffect
import com.xiaoyv.bangumi.shared.ui.component.navigation.Screen

class SplashViewModel : BaseViewModel<SplashState, SplashSideEffect, SplashEvent.Action>() {

    override fun createInitialState(): SplashState = SplashState

    override fun onEvent(event: SplashEvent.Action) {
        when (event) {
            SplashEvent.Action.OnLaunch -> intent {
                postEffect { SplashSideEffect.Navigate(Screen.Main) }
            }
        }
    }

}
