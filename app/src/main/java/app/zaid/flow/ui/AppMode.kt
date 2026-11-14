package app.zaid.flow.ui

import androidx.navigation.NavController
import app.zaid.flow.ui.components.layout.navigation.FlowTab
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Whether the app is showing its video experience or its music experience (own navbar and screens). */
/** A pending switch between modes: [action] runs once, while the overlay hides the screen swap. */
class ModeTransition(val toMusic: Boolean, val action: () -> Unit)

object AppModeState {
    val transition = MutableStateFlow<ModeTransition?>(null)

    fun requestTransition(toMusic: Boolean, action: () -> Unit) {
        if (transition.value == null) transition.value = ModeTransition(toMusic, action)
    }

    private val _isMusic = MutableStateFlow(false)
    val isMusic: StateFlow<Boolean> = _isMusic

    fun setMusic(value: Boolean) {
        _isMusic.value = value
    }
}

/** Enters music mode on the music counterpart of the screen the person is leaving ([target]). */
internal fun NavController.enterMusicMode(
    startRoute: String,
    target: FlowTab = FlowTab.Music,
) {
    AppModeState.requestTransition(toMusic = true) {
        AppModeState.setMusic(true)
        navigateToTab(target, startRoute)
    }
}

/** Back to video mode on [targetRoute] (a tab route such as "library"), or the start tab when null. */
internal fun NavController.enterVideoMode(
    startRoute: String,
    targetRoute: String? = null,
) {
    AppModeState.requestTransition(toMusic = false) {
        AppModeState.setMusic(false)
        val route = targetRoute ?: startRoute
        navigateToTab(flowTabForRoute(route) ?: FlowTab.Home, startRoute)
    }
}
