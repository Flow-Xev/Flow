package app.zaid.flow.ai

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/** Gives the Compose shell the app-wide [FlowAiController]. The chat itself outlives this ViewModel. */
@HiltViewModel
class FlowAiViewModel
    @Inject
    constructor(
        val controller: FlowAiController,
    ) : ViewModel()
