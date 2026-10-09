package app.galaxypulse.ui.design

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Galaxy Pulse design system. One dark, AMOLED-friendly language shared by the overlay and the
 * settings app: true-black canvas, restrained translucent surfaces, blue/violet identity and
 * green/amber/red used only for state (charging / low / critical).
 */
object PulseColors {
    val Black = Color(0xFF000000)
    val Ink = Color(0xFF05060B)
    val Surface = Color(0xFF0C0E16)
    val SurfaceRaised = Color(0xFF151927)
    val SurfaceOverlay = Color(0xE60C0E16)
    val Stroke = Color(0x1FFFFFFF)
    val StrokeStrong = Color(0x33FFFFFF)

    val OnSurface = Color(0xFFF3F5FB)
    val OnSurfaceMuted = Color(0xB8F3F5FB)
    val OnSurfaceFaint = Color(0x70F3F5FB)

    val Blue = Color(0xFF4C8DFF)
    val Violet = Color(0xFF8B7BFF)
    /** Used sparingly: charging only. */
    val Charging = Color(0xFF3DDC84)
    val Warning = Color(0xFFFFB454)
    val Critical = Color(0xFFFF5D73)
}

object PulseShapes {
    val Card = 36.dp
    val Sheet = 32.dp
    val ArtworkCompact = 8.dp
    val ArtworkExpanded = 18.dp
    val Chip = 14.dp
    val Button = 22.dp
    val Hairline = 1.dp
}

object PulseSpacing {
    val XS = 4.dp
    val S = 8.dp
    val M = 12.dp
    val L = 16.dp
    val XL = 20.dp
    val XXL = 28.dp
}

/** Fixed overlay dimensions, in dp, before the user's size scale. */
object PulseSizes {
    val CompactHeight = 40.dp
    val CompactMediaWidth = 236.dp
    val CompactTimerWidth = 148.dp
    val CompactChargingWidth = 164.dp
    val CompactNotificationWidth = 280.dp
    val CompactBluetoothWidth = 232.dp
    val CompactAlertWidth = 220.dp

    val ExpandedWidth = 388.dp
    val ExpandedMediaHeight = 214.dp
    val ExpandedTimerHeight = 176.dp
    val ExpandedStopwatchHeight = 252.dp
    val ExpandedChargingHeight = 148.dp
    val ExpandedNotificationHeight = 164.dp
    val ExpandedBluetoothHeight = 150.dp
    val ExpandedAlertHeight = 168.dp

    val ArtworkCompact = 26.dp
    val ArtworkExpanded = 92.dp
}

/**
 * Typography. [FontFamily.Default] resolves to the device system font, which is Samsung One on
 * Galaxy phones, so the overlay matches One UI without bundling any font.
 */
object PulseType {
    private val family = FontFamily.Default

    val Title = TextStyle(fontFamily = family, fontSize = 18.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp)
    val Body = TextStyle(fontFamily = family, fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)
    val BodyCompact = TextStyle(fontFamily = family, fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold)
    val Caption = TextStyle(fontFamily = family, fontSize = 12.sp, lineHeight = 15.sp, fontWeight = FontWeight.Normal)
    val Label = TextStyle(fontFamily = family, fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.3.sp)
    /** Tabular figures so a ticking timer never jitters sideways. */
    val Numeric = TextStyle(fontFamily = family, fontSize = 15.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum")
    val NumericCompact = TextStyle(fontFamily = family, fontSize = 14.sp, lineHeight = 17.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum")
    val Display = TextStyle(fontFamily = family, fontSize = 46.sp, lineHeight = 52.sp, fontWeight = FontWeight.Light, fontFeatureSettings = "tnum", letterSpacing = (-1).sp)
}

private val PulseColorScheme = darkColorScheme(
    primary = PulseColors.Blue,
    onPrimary = Color(0xFF00102B),
    primaryContainer = Color(0xFF16346B),
    onPrimaryContainer = Color(0xFFD8E5FF),
    secondary = PulseColors.Violet,
    onSecondary = Color(0xFF1A0F66),
    secondaryContainer = Color(0xFF2E2A6B),
    onSecondaryContainer = Color(0xFFE3DEFF),
    tertiary = PulseColors.Charging,
    background = PulseColors.Black,
    onBackground = PulseColors.OnSurface,
    surface = PulseColors.Surface,
    onSurface = PulseColors.OnSurface,
    surfaceVariant = PulseColors.SurfaceRaised,
    onSurfaceVariant = PulseColors.OnSurfaceMuted,
    surfaceContainer = PulseColors.Surface,
    surfaceContainerHigh = PulseColors.SurfaceRaised,
    outline = PulseColors.StrokeStrong,
    outlineVariant = PulseColors.Stroke,
    error = PulseColors.Critical,
)

/** Theme for the settings app and in-app preview. Pulse is dark-only by design (AMOLED), whatever the system setting. */
@Composable
fun GalaxyPulseTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = PulseColorScheme, content = content)
}
