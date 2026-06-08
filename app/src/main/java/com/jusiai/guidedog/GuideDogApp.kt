package com.jusiai.guidedog

import android.app.Application
import com.jusiai.guidedog.core.AudioPlayer
import com.jusiai.guidedog.core.RelayClient
import com.jusiai.guidedog.core.Settings

/**
 * Application = hand-rolled service locator (matches the we-meet convention). Owns
 * the singletons: settings, the relay client, the audio player, and the shared
 * GuideState that the service and UI both observe.
 */
class GuideDogApp : Application() {
    lateinit var settings: Settings
        private set
    lateinit var relayClient: RelayClient
        private set

    val audioPlayer = AudioPlayer()
    val guideState = GuideState()

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        relayClient = RelayClient(settings)
    }
}
