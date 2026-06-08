package com.jusiai.guidedog

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jusiai.guidedog.ui.GuideScreen
import com.jusiai.guidedog.ui.GuideViewModel
import com.jusiai.guidedog.ui.theme.GuideDogTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GuideDogTheme {
                val app = applicationContext as GuideDogApp
                val vm: GuideViewModel = viewModel(factory = GuideViewModel.Factory(app))
                GuideScreen(vm)
            }
        }
    }
}
