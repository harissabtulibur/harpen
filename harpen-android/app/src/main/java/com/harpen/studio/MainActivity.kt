package com.harpen.studio

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.harpen.studio.ui.debug.TouchDebuggerScreen
import com.harpen.studio.ui.home.HomeScreen
import com.harpen.studio.ui.ocr.CameraScanScreen
import com.harpen.studio.ui.settings.SettingsScreen
import com.harpen.studio.ui.state.HarpenViewModel
import com.harpen.studio.ui.theme.HarpenTheme
import com.harpen.studio.ui.workspace.WorkspaceScreen

object HarpenRoutes {
    const val HOME = "home"
    const val WORKSPACE = "workspace"
    const val SETTINGS = "settings"
    const val CAMERA_OCR = "camera_ocr"
    const val TOUCH_DEBUGGER = "touch_debugger"
}

class MainActivity : ComponentActivity() {

    private val viewModel: HarpenViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val settings by viewModel.settings.collectAsState()
            val navController = rememberNavController()

            HarpenTheme(
                themeMode = settings.themeMode,
                highContrast = settings.highContrastMode
            ) {
                NavHost(
                    navController = navController,
                    startDestination = HarpenRoutes.HOME
                ) {
                    composable(HarpenRoutes.HOME) {
                        HomeScreen(
                            viewModel = viewModel,
                            onOpenWorkspace = { navController.navigate(HarpenRoutes.WORKSPACE) },
                            onOpenSettings = { navController.navigate(HarpenRoutes.SETTINGS) }
                        )
                    }
                    composable(HarpenRoutes.WORKSPACE) {
                        WorkspaceScreen(
                            viewModel = viewModel,
                            onBackHome = { navController.popBackStack() },
                            onOpenCameraOcr = { navController.navigate(HarpenRoutes.CAMERA_OCR) },
                            onOpenSettings = { navController.navigate(HarpenRoutes.SETTINGS) },
                            onOpenTouchDebugger = { navController.navigate(HarpenRoutes.TOUCH_DEBUGGER) }
                        )
                    }
                    composable(HarpenRoutes.SETTINGS) {
                        SettingsScreen(
                            viewModel = viewModel,
                            onBack = { navController.popBackStack() },
                            onOpenTouchDebugger = { navController.navigate(HarpenRoutes.TOUCH_DEBUGGER) }
                        )
                    }
                    composable(HarpenRoutes.CAMERA_OCR) {
                        CameraScanScreen(
                            viewModel = viewModel,
                            onClose = { navController.popBackStack() }
                        )
                    }
                    composable(HarpenRoutes.TOUCH_DEBUGGER) {
                        TouchDebuggerScreen(
                            viewModel = viewModel,
                            onBack = { navController.popBackStack() }
                        )
                    }
                }
            }
        }

        handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        val uri: Uri = intent?.data ?: return
        val mime = intent.type.orEmpty()
        val path = uri.toString().lowercase()
        when {
            path.endsWith(".harpen") || mime == "application/vnd.harpen.project" -> {
                viewModel.importHarpenArchive(uri)
            }
            mime == "application/pdf" || path.endsWith(".pdf") -> {
                viewModel.importPdfAsNote(uri, title = "Opened PDF")
            }
        }
    }
}
