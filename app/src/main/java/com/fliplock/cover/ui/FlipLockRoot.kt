package com.fliplock.cover.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fliplock.cover.R

enum class Screen(@StringRes val titleRes: Int) {
    HOME(R.string.screen_home),
    DIAGNOSTIC(R.string.screen_diagnostic),
    CALIBRATION(R.string.screen_calibration),
    ADVANCED(R.string.screen_advanced),
    TECH_INFO(R.string.screen_tech_info),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlipLockRoot(viewModel: MainViewModel) {
    var screen by remember { mutableStateOf(Screen.HOME) }
    val snackbarHostState = remember { SnackbarHostState() }
    val message by viewModel.message.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> viewModel.onForeground()
                Lifecycle.Event.ON_PAUSE -> viewModel.onBackground()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(message) {
        val resId = message
        if (resId != null) {
            snackbarHostState.showSnackbar(context.getString(resId))
            viewModel.consumeMessage()
        }
    }

    BackHandler(enabled = screen != Screen.HOME) { screen = Screen.HOME }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(screen.titleRes),
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp,
                    )
                },
                navigationIcon = {
                    if (screen != Screen.HOME) {
                        IconButton(onClick = { screen = Screen.HOME }) {
                            Text("←", fontSize = 22.sp)
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        val contentModifier = Modifier.padding(
            top = innerPadding.calculateTopPadding(),
            bottom = innerPadding.calculateBottomPadding() + 16.dp,
        )
        when (screen) {
            Screen.HOME -> HomeScreen(viewModel, onNavigate = { screen = it }, modifier = contentModifier)
            Screen.DIAGNOSTIC -> DiagnosticScreen(viewModel, modifier = contentModifier)
            Screen.CALIBRATION -> CalibrationScreen(viewModel, modifier = contentModifier)
            Screen.ADVANCED -> AdvancedSettingsScreen(viewModel, modifier = contentModifier)
            Screen.TECH_INFO -> TechnicalInfoScreen(viewModel, modifier = contentModifier)
        }
    }
}
