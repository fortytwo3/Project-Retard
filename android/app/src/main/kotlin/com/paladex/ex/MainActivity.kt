package com.paladex.ex

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.paladex.ex.ui.HistoryScreen
import com.paladex.ex.ui.PaladexTheme
import com.paladex.ex.ui.ScanScreen
import com.paladex.ex.ui.SourcesScreen
import com.paladex.ex.ui.Surface1

private enum class Destination(val label: String, val icon: ImageVector) {
    SCAN("Scan", Icons.Filled.CameraAlt),
    HISTORY("History", Icons.Filled.History),
    SOURCES("Sources", Icons.Filled.Tune),
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            PaladexTheme {
                val viewModel: ScanViewModel = viewModel()
                val state by viewModel.state.collectAsStateWithLifecycle()
                var destination by remember { mutableStateOf(Destination.SCAN) }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    bottomBar = {
                        NavigationBar(containerColor = Surface1) {
                            Destination.entries.forEach { item ->
                                NavigationBarItem(
                                    selected = destination == item,
                                    onClick = { destination = item },
                                    icon = { Icon(item.icon, contentDescription = item.label) },
                                    label = { Text(item.label) },
                                )
                            }
                        }
                    },
                ) { padding ->
                    Surface(Modifier.fillMaxSize().padding(padding)) {
                        when (destination) {
                            Destination.SCAN -> ScanScreen(viewModel, state)
                            Destination.HISTORY -> HistoryScreen(viewModel)
                            Destination.SOURCES -> SourcesScreen(viewModel)
                        }
                    }
                }
            }
        }
    }
}
