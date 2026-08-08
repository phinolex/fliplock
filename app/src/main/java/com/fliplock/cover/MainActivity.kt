package com.fliplock.cover

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fliplock.cover.ui.FlipLockRoot
import com.fliplock.cover.ui.FlipLockTheme
import com.fliplock.cover.ui.MainViewModel

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppGraph.init(this)
        enableEdgeToEdge()
        setContent {
            FlipLockTheme {
                val viewModel: MainViewModel = viewModel()
                FlipLockRoot(viewModel)
            }
        }
    }
}
