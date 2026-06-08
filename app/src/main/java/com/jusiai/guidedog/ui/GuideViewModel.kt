package com.jusiai.guidedog.ui

import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.jusiai.guidedog.GuideDogApp
import com.jusiai.guidedog.service.GuideService

class GuideViewModel(private val app: GuideDogApp) : AndroidViewModel(app) {
    val status = app.guideState.status
    val preview = app.guideState.preview
    val settings get() = app.settings

    fun start() = GuideService.start(app)
    fun stop() = GuideService.stop(app)

    class Factory(private val app: GuideDogApp) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = GuideViewModel(app) as T
    }
}
