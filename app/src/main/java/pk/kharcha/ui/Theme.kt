package pk.kharcha.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp

// Deep jade ground with a marigold accent. Local vernacular rather than
// fintech blue, and dark because this gets opened at a checkout counter.
object Ink {
    val Ground = Color(0xFF0E1618)
    val Sunk = Color(0xFF0B1214)
    val Surface = Color(0xFF16211F)
    val Line = Color(0xFF1B2A2C)
    val LineStrong = Color(0xFF24393C)

    val Chalk = Color(0xFFF0E9DA)
    val Chalk2 = Color(0xFFD6CEBE)
    val Muted = Color(0xFF93A5A6)
    val Faint = Color(0xFF6E8486)
    val Ghost = Color(0xFF4A5E60)

    val Marigold = Color(0xFFE0A230)
    val Jade = Color(0xFF2C8C72)
    val Clay = Color(0xFFC2543C)
    val Slate = Color(0xFF35494C)
}

/** Assigned by hash, so a category you invent never lands colourless. */
val CategoryColors = listOf(Ink.Marigold, Ink.Jade, Ink.Clay, Ink.Faint, Ink.Slate)

fun colorFor(category: String?): Color =
    if (category == null) Ink.Ghost
    else CategoryColors[(category.hashCode().let { if (it < 0) -it else it }) % CategoryColors.size]

// System faces, so the project builds with no font configuration at all.
// For the intended Bricolage Grotesque / IBM Plex pairing, drop the .ttf files
// into res/font/ and swap these for FontFamily(Font(R.font.name)).
private val display = FontFamily.SansSerif
private val body = FontFamily.SansSerif

/** Monospace for every rupee figure, so columns of numbers align. */
val Numeral = FontFamily.Monospace

private val typography = Typography(
    displayLarge = TextStyle(fontFamily = Numeral, fontSize = 38.sp, letterSpacing = (-1.5).sp),
    titleLarge = TextStyle(fontFamily = display, fontSize = 22.sp, letterSpacing = (-0.2).sp),
    titleMedium = TextStyle(fontFamily = display, fontSize = 16.sp),
    bodyLarge = TextStyle(fontFamily = body, fontSize = 15.sp),
    bodyMedium = TextStyle(fontFamily = body, fontSize = 13.sp),
    labelSmall = TextStyle(fontFamily = body, fontSize = 11.sp, letterSpacing = 0.7.sp)
)

private val scheme = darkColorScheme(
    primary = Ink.Marigold,
    onPrimary = Ink.Sunk,
    secondary = Ink.Jade,
    background = Ink.Ground,
    onBackground = Ink.Chalk,
    surface = Ink.Surface,
    onSurface = Ink.Chalk,
    outline = Ink.Line,
    error = Ink.Clay
)

@Composable
fun KharchaTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}

/** 1234567 paisa -> "12,345". Rupees only; paisa are noise at this scale. */
fun Long.asRupees(): String =
    java.text.NumberFormat.getIntegerInstance(java.util.Locale("en", "PK")).format(this / 100)
