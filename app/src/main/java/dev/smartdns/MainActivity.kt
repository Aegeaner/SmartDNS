package dev.smartdns

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.smartdns.ui.MonitorRoute
import dev.smartdns.ui.MonitorViewModel
import dev.smartdns.ui.theme.SmartDnsTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val factory = MonitorViewModel.factory(application)
        setContent {
            SmartDnsTheme {
                MonitorRoute(viewModel(factory = factory))
            }
        }
    }
}
