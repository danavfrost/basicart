package com.halworks.basicart

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.core.content.IntentCompat
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import com.halworks.basicart.data.AppContainer
import com.halworks.basicart.ui.BasicArtTheme
import com.halworks.basicart.ui.LocalIsDark
import com.halworks.basicart.ui.editor.EditorScreen
import com.halworks.basicart.ui.home.HomeScreen
import com.halworks.basicart.ui.settings.LicensesScreen
import com.halworks.basicart.ui.settings.SettingsScreen

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = AppContainer.get(this)
        com.halworks.basicart.ui.AppType.init(assets)
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            val theme by app.settings.theme.collectAsState()
            BasicArtTheme(theme) {
                val dark = LocalIsDark.current
                DisposableEffect(dark) {
                    enableEdgeToEdge(
                        statusBarStyle = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                        else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
                        navigationBarStyle = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                        else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
                    )
                    onDispose {}
                }
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppNav(app)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /** A project file opened with / shared to Basic Art (specs §11a). Only content:// is accepted. */
    private fun handleIntent(intent: Intent?) {
        val uri: Uri? = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            else -> null
        }
        if (uri != null && uri.scheme == "content") AppContainer.get(this).pendingImport.value = uri
    }
}

/** Screens: "home", "settings", "licenses", "editor:<projectId>". */
@Composable
private fun AppNav(app: AppContainer) {
    val stack = rememberSaveable(saver = listSaver(save = { it.toList() }, restore = { mutableStateListOf(*it.toTypedArray()) })) {
        mutableStateListOf("home")
    }
    val top = stack.last()
    // An incoming project file goes back to Home, which imports it (the editor has already saved on stop).
    val pending by app.pendingImport.collectAsState()
    androidx.compose.runtime.LaunchedEffect(pending) {
        if (pending != null) while (stack.size > 1) stack.removeAt(stack.lastIndex)
    }
    fun push(s: String) { stack.add(s) }
    fun pop() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
    BackHandler(enabled = stack.size > 1 && !top.startsWith("editor:")) { pop() }
    AnimatedContent(targetState = top, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "nav") { screen ->
        when {
            screen == "home" -> HomeScreen(
                app = app,
                onOpen = { push("editor:$it") },
                onSettings = { push("settings") },
            )
            screen == "settings" -> SettingsScreen(app, onBack = ::pop, onLicenses = { push("licenses") })
            screen == "licenses" -> LicensesScreen(app, onBack = ::pop)
            screen.startsWith("editor:") -> EditorScreen(app, screen.removePrefix("editor:"), onClose = ::pop)
        }
    }
}
