package com.halworks.basicart.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import com.halworks.basicart.data.ThemeMode

// Basic Art palette: quiet neutral surfaces and one confident indigo accent taken from the
// brand icon (blue #3378F5 → violet #8C40E6). The icon's yellow is kept for small highlights.
val BrandBlue = Color(0xFF3378F5)
val BrandViolet = Color(0xFF8C40E6)
val BrandYellow = Color(0xFFFFD140)

private val Light = lightColorScheme(
    primary = Color(0xFF4A55E0),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE2E4FF),
    onPrimaryContainer = Color(0xFF111A6B),
    secondary = Color(0xFF5D5F71),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE6E7F2),
    onSecondaryContainer = Color(0xFF1A1B29),
    tertiary = Color(0xFF8A5A00),
    tertiaryContainer = Color(0xFFFFE6A8),
    onTertiaryContainer = Color(0xFF2B1B00),
    background = Color(0xFFF9F9FB),
    onBackground = Color(0xFF1A1B20),
    surface = Color(0xFFF9F9FB),
    onSurface = Color(0xFF1A1B20),
    surfaceVariant = Color(0xFFE7E7EE),
    onSurfaceVariant = Color(0xFF52535E),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF4F4F7),
    surfaceContainer = Color(0xFFEFEFF3),
    surfaceContainerHigh = Color(0xFFE9E9EE),
    surfaceContainerHighest = Color(0xFFE2E2E9),
    outline = Color(0xFF80818D),
    outlineVariant = Color(0xFFD5D5DE),
    inverseSurface = Color(0xFF2F3036),
    inverseOnSurface = Color(0xFFF1F0F6),
    inversePrimary = Color(0xFFBCC2FF),
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDC),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFB4BBFF),
    onPrimary = Color(0xFF1B2690),
    primaryContainer = Color(0xFF333FC4),
    onPrimaryContainer = Color(0xFFE2E4FF),
    secondary = Color(0xFFC6C6D8),
    onSecondary = Color(0xFF2F3040),
    secondaryContainer = Color(0xFF34353F),
    onSecondaryContainer = Color(0xFFE3E3F0),
    tertiary = Color(0xFFFFD36B),
    tertiaryContainer = Color(0xFF5C3F00),
    onTertiaryContainer = Color(0xFFFFE6A8),
    background = Color(0xFF121216),
    onBackground = Color(0xFFE5E5EC),
    surface = Color(0xFF121216),
    onSurface = Color(0xFFE5E5EC),
    surfaceVariant = Color(0xFF45464F),
    onSurfaceVariant = Color(0xFFC6C6D2),
    surfaceContainerLowest = Color(0xFF0C0C10),
    surfaceContainerLow = Color(0xFF19191E),
    surfaceContainer = Color(0xFF1E1E24),
    surfaceContainerHigh = Color(0xFF28282F),
    surfaceContainerHighest = Color(0xFF33333A),
    outline = Color(0xFF8F909C),
    outlineVariant = Color(0xFF45464F),
    inverseSurface = Color(0xFFE5E5EC),
    inverseOnSurface = Color(0xFF2F3036),
    inversePrimary = Color(0xFF4A55E0),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
)

/** Colors for the editor workspace around the canvas (never the canvas content). */
@Immutable
data class WorkspaceColors(val backdrop: Color, val canvasShadow: Color, val checkerA: Color, val checkerB: Color, val selection: Color, val guide: Color)

val LocalWorkspace = staticCompositionLocalOf {
    WorkspaceColors(Color(0xFFE3E2EA), Color(0x33000000), Color.White, Color(0xFFDADADF), Color(0xFF5B4BDB), Color(0xFFFF3D7F))
}

val LocalIsDark = staticCompositionLocalOf { false }

/** Corner radii used across the app: 8 (small controls), 12 (chips, wells), 16 (cards, tiles), 24 (sheets). */
val AppShapes = androidx.compose.material3.Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(26.dp),
)

/** App UI type: the bundled Inter (variable font; weight set via the 'wght' axis). */
object AppType {
    private var assets: android.content.res.AssetManager? = null
    fun init(a: android.content.res.AssetManager) { assets = a }

    @OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
    val inter: androidx.compose.ui.text.font.FontFamily by lazy {
        val a = assets ?: return@lazy androidx.compose.ui.text.font.FontFamily.Default
        androidx.compose.ui.text.font.FontFamily(
            listOf(400, 500, 600, 700).map { w ->
                androidx.compose.ui.text.font.Font(
                    "inter/inter-variable.ttf", a, FontWeight(w),
                    variationSettings = androidx.compose.ui.text.font.FontVariation.Settings(androidx.compose.ui.text.font.FontVariation.weight(w)),
                )
            },
        )
    }

    val typography: Typography by lazy {
        val b = Typography()
        fun TextStyle.i(w: FontWeight? = null, ls: Float? = null) =
            copy(fontFamily = inter, fontWeight = w ?: fontWeight, letterSpacing = ls?.sp ?: letterSpacing)
        b.copy(
            displayLarge = b.displayLarge.i(), displayMedium = b.displayMedium.i(), displaySmall = b.displaySmall.i(),
            headlineLarge = b.headlineLarge.i(FontWeight.SemiBold, -0.4f), headlineMedium = b.headlineMedium.i(FontWeight.SemiBold, -0.3f),
            headlineSmall = b.headlineSmall.i(FontWeight.SemiBold, -0.2f),
            titleLarge = b.titleLarge.i(FontWeight.SemiBold, -0.2f), titleMedium = b.titleMedium.i(FontWeight.SemiBold, -0.1f),
            titleSmall = b.titleSmall.i(FontWeight.SemiBold, 0f),
            bodyLarge = b.bodyLarge.i(ls = 0f), bodyMedium = b.bodyMedium.i(ls = 0f), bodySmall = b.bodySmall.i(ls = 0.1f),
            labelLarge = b.labelLarge.i(FontWeight.Medium, 0f), labelMedium = b.labelMedium.i(FontWeight.Medium, 0.1f),
            labelSmall = TextStyle(fontFamily = inter, fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.2.sp),
        )
    }
}

@Composable
fun BasicArtTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val scheme: ColorScheme = if (dark) Dark else Light
    val ws = if (dark) WorkspaceColors(
        backdrop = Color(0xFF0A0A0D), canvasShadow = Color(0x80000000),
        checkerA = Color(0xFFFFFFFF), checkerB = Color(0xFFDADADF),
        selection = Color(0xFF8E98FF), guide = Color(0xFFFF5FA2),
    ) else WorkspaceColors(
        backdrop = Color(0xFFE4E4EA), canvasShadow = Color(0x2E20202A),
        checkerA = Color(0xFFFFFFFF), checkerB = Color(0xFFDADADF),
        selection = Color(0xFF4A55E0), guide = Color(0xFFE5267A),
    )
    CompositionLocalProvider(LocalWorkspace provides ws, LocalIsDark provides dark) {
        MaterialTheme(colorScheme = scheme, typography = AppType.typography, shapes = AppShapes, content = content)
    }
}
