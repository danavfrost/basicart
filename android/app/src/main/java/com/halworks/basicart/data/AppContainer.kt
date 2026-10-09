package com.halworks.basicart.data

import kotlinx.coroutines.launch

import android.content.Context
import com.halworks.basicart.BuildConfig
import com.halworks.basicart.fonts.FontCatalog
import com.halworks.basicart.model.Palette
import com.halworks.basicart.model.Palettes
import com.halworks.basicart.model.ProjectStore
import com.halworks.basicart.model.TextPreset
import com.halworks.basicart.model.TextPresets
import java.io.File

/** Process-wide singletons. Heavy things are lazy so cold start stays fast. */
class AppContainer(context: Context) {
    val app: Context = context.applicationContext
    val settings = Settings(app)
    val store = ProjectStore(File(app.filesDir, "projects"), "Basic Art Android ${BuildConfig.VERSION_NAME}")
    val fonts: FontCatalog by lazy { FontCatalog(app.assets) }
    val palettes: List<Palette> by lazy { Palettes.parse(app.assets.open("palettes.json").use { it.readBytes().decodeToString() }) }
    val presets: List<TextPreset> by lazy { TextPresets.parse(app.assets.open("text-presets.json").use { it.readBytes().decodeToString() }) }
    val images = ImageStore(store)


    /** A project file handed to the app (Open with / Share) waiting to be imported. */
    val pendingImport = kotlinx.coroutines.flow.MutableStateFlow<android.net.Uri?>(null)
    /** Export format chosen last in this session. */
    @Volatile var lastExportFormat: ExportFormat? = null
    /** Outlives screens: autosaves launched here finish even if the editor closes. */
    val appScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val saveDispatcher = kotlinx.coroutines.Dispatchers.IO.limitedParallelism(1)

    init {
        // Off the main thread (cold start): never leave half-imported projects behind
        // (FORMAT.md §13.2), and warm the font catalog before the first editor opens.
        appScope.launch(saveDispatcher) {
            try { com.halworks.basicart.model.ProjectPackage.cleanupLeftovers(store.root) } catch (e: Exception) { }
            // Shared/saved export files older than an hour (share targets have read them by now),
            // and any import copies left by a crash.
            try { ExportCache.sweep(File(app.cacheDir, "exports")) } catch (e: Exception) { }
            try { app.cacheDir.listFiles { f -> f.name.startsWith("import-") && f.name.endsWith(".zip") }?.forEach { it.delete() } } catch (e: Exception) { }
        }
        appScope.launch(kotlinx.coroutines.Dispatchers.IO) { try { fonts } catch (e: Throwable) { } }
    }
    fun rendererFor(projectId: String) = com.halworks.basicart.render.Renderer(fonts, images.forProject(projectId))

    companion object {
        @Volatile private var instance: AppContainer? = null
        fun get(context: Context): AppContainer =
            instance ?: synchronized(this) { instance ?: AppContainer(context).also { instance = it } }
    }
}
