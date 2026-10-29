package com.beefsoup.ai.glass

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

// All custom colors live at the theme boundary; components use paired Material roles.
private val DayColors = lightColorScheme(
    primary = Color(0xFF006B5E), onPrimary = Color.White,
    primaryContainer = Color(0xFF9CF3D9), onPrimaryContainer = Color(0xFF002E25),
    secondary = Color(0xFF924831), onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFDBCC), onSecondaryContainer = Color(0xFF351005),
    tertiary = Color(0xFF506600), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD9EF97), onTertiaryContainer = Color(0xFF182000),
    background = Color(0xFFF9FAF4), onBackground = Color(0xFF18211E),
    surface = Color(0xFFF9FAF4), onSurface = Color(0xFF18211E),
    surfaceVariant = Color(0xFFDDE5DC), onSurfaceVariant = Color(0xFF414A44),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF0F3EB),
    surfaceContainer = Color(0xFFEAEDE5), surfaceContainerHigh = Color(0xFFE3E8DE),
    surfaceContainerHighest = Color(0xFFDDE2D8),
    outline = Color(0xFF717A73), outlineVariant = Color(0xFFC1CAC1),
)
private val NightColors = darkColorScheme(
    primary = Color(0xFF80D6BD), onPrimary = Color(0xFF00382D),
    primaryContainer = Color(0xFF005143), onPrimaryContainer = Color(0xFF9CF3D9),
    secondary = Color(0xFFFFB599), onSecondary = Color(0xFF57200D),
    secondaryContainer = Color(0xFF743520), onSecondaryContainer = Color(0xFFFFDBCC),
    tertiary = Color(0xFFBCD27D), onTertiary = Color(0xFF283500),
    tertiaryContainer = Color(0xFF3C4D00), onTertiaryContainer = Color(0xFFD9EF97),
    background = Color(0xFF101511), onBackground = Color(0xFFE0E5DC),
    surface = Color(0xFF101511), onSurface = Color(0xFFE0E5DC),
    surfaceVariant = Color(0xFF414A44), onSurfaceVariant = Color(0xFFC1CAC1),
    surfaceContainerLowest = Color(0xFF0B100D), surfaceContainerLow = Color(0xFF18201A),
    surfaceContainer = Color(0xFF1D251F), surfaceContainerHigh = Color(0xFF283029),
    surfaceContainerHighest = Color(0xFF333B34),
    outline = Color(0xFF8B958D), outlineVariant = Color(0xFF414A44),
)
private val BaseType = Typography()
private val MeetingType = Typography(
    displaySmall = BaseType.displaySmall.copy(fontWeight = FontWeight.Bold),
    headlineLarge = BaseType.headlineLarge.copy(fontWeight = FontWeight.Bold),
    headlineSmall = BaseType.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = BaseType.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = BaseType.titleMedium.copy(fontWeight = FontWeight.SemiBold),
)

@Composable fun MeetingTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val view = LocalView.current
    SideEffect {
        view.context.activity()?.window?.let { window ->
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    MaterialTheme(colorScheme = if (dark) NightColors else DayColors,
        typography = MeetingType, content = content)
}

@Composable internal fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
}

@Composable internal fun PageIntro(title: String, description: String, color: Color, onColor: Color) {
    Surface(color = color, contentColor = onColor, shape = MaterialTheme.shapes.extraLarge) {
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(title, style = MaterialTheme.typography.displaySmall, modifier = Modifier.semantics { heading() })
            Text(description, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable internal fun SessionHero(state: MeetingUiState, translating: Boolean, active: Boolean, elapsed: Long) {
    if (!active) {
        PageIntro(if (translating) "邊聽，\n邊理解。" else "專心開會，\n隨時回看重點。",
            if (translating) "持續收音，逐句顯示原文與譯文，並自動播報完成的譯句。"
            else "音訊先保存在手機。連接電腦後，可取得逐字稿、翻譯與會後摘要。",
            if (translating) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.primaryContainer,
            if (translating) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onPrimaryContainer)
    } else {
        Surface(color = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary,
            shape = MaterialTheme.shapes.extraLarge) {
            Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(when (state.session.phase) {
                    SessionPhase.STARTING -> "正在準備記錄"
                    SessionPhase.STOPPING -> "正在保存會議"
                    else -> if (state.continuousTranslation) "連續翻譯 · 記錄中" else "會議記錄中"
                }, style = MaterialTheme.typography.titleLarge)
                Text(mediaTime(elapsed * 1_000_000), style = MaterialTheme.typography.displayMedium)
                Text(if (state.demo) "目前是模擬來源，並非眼鏡實錄" else "請持續配戴眼鏡；摘下可能會結束連線。",
                    style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
