package com.scorebook.navigation

import android.net.Uri
import androidx.lifecycle.ViewModel
import com.scorebook.core.input.ExternalIntentBus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class AppViewModel @Inject constructor(bus: ExternalIntentBus) : ViewModel() {
    val pendingImages: StateFlow<List<Uri>> = bus.images
}
