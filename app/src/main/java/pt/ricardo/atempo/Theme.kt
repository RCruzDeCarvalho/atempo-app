package pt.ricardo.atempo

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Papel de aguarela, tinta ameixa e uma caixa de guaches para as cadeiras. */
object Palette {
    val Plum = Color(0xFF3B2A5C)
    val PlumSoft = Color(0xFFE6DDF3)
    val Coral = Color(0xFFF2846B)
    val CoralSoft = Color(0xFFFDE3DB)
    val Ochre = Color(0xFFF2C14E)
    val Paper = Color(0xFFFBF6EE)
    val PaperDeep = Color(0xFFF2EBDF)
    val Ink = Color(0xFF231F2E)

    val Ok = Color(0xFF4F9D7A)
    val Late = Color(0xFFD1495B)
    val LateSoft = Color(0xFFFBE0E3)

    val hero = Brush.linearGradient(listOf(Color(0xFFF2A65A), Color(0xFFE98AA7), Color(0xFFA98BDB), Color(0xFF8FA3F0)))
    val friday = Brush.linearGradient(listOf(Color(0xFFF2C14E), Color(0xFFF2846B), Color(0xFFD96C9A)))

    /** Uma cor de guache por cadeira. */
    val gouache = listOf(
        Color(0xFFE07A5F), // terracota
        Color(0xFF3D8FB0), // azul cerúleo
        Color(0xFF81B29A), // verde salva
        Color(0xFFD9A400), // ocre
        Color(0xFF9B6FC2), // violeta
        Color(0xFFE5989B), // rosa velho
        Color(0xFF2A9D8F), // turquesa
        Color(0xFFC1666B), // carmim
        Color(0xFF6D8B3A), // verde oliva
        Color(0xFF5E60CE), // ultramarino
        Color(0xFFDD8A3E), // laranja queimado
        Color(0xFF8D6E63)  // sépia
    )

    // As cadeiras da Maria, por ordem, para nunca trocarem de cor a meio do ano
    private val known = listOf(
        "Projeto em Design I", "Concept Art I", "Teoria da Imagem e Cultura Visual", "História da Arte I",
        "Desenho I", "Oficina de Pintura", "Oficina de Escultura", "Oficina de Arte Multimédia",
        "Técnicas de Escrita", "Comunicação por Imagem", "Desenho II", "Projeto em Design II"
    )

    fun knownCourses(): List<String> = known

    fun course(name: String): Color {
        val n = name.trim()
        if (n.isEmpty()) return Color(0xFF9A93A8)
        // Os nomes do horário têm extras, como "(Gráfico)": procura a cadeira pelo princípio
        val i = known.indexOfFirst { n.equals(it, true) }.takeIf { it >= 0 }
            ?: known.indexOfFirst { n.startsWith(it, true) }.takeIf { it >= 0 }
        return gouache[(i ?: abs(n.lowercase().hashCode())) % gouache.size]
    }

    val confetti = gouache + listOf(Ochre, Coral)
}

private val LightScheme = lightColorScheme(
    primary = Palette.Plum,
    onPrimary = Color.White,
    primaryContainer = Palette.PlumSoft,
    onPrimaryContainer = Color(0xFF22163A),
    secondary = Palette.Coral,
    onSecondary = Color.White,
    secondaryContainer = Palette.CoralSoft,
    onSecondaryContainer = Color(0xFF4A1A0E),
    tertiary = Palette.Ok,
    background = Palette.Paper,
    onBackground = Palette.Ink,
    surface = Palette.Paper,
    onSurface = Palette.Ink,
    surfaceVariant = Palette.PaperDeep,
    onSurfaceVariant = Color(0xFF65607A),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFAF4EA),
    surfaceContainer = Color(0xFFF5EEE3),
    surfaceContainerHigh = Color(0xFFEFE7DA),
    surfaceContainerHighest = Color(0xFFE8DFD1),
    outline = Color(0xFFCFC6D8),
    outlineVariant = Color(0xFFE6DED3),
    error = Palette.Late
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFCDB8F2),
    onPrimary = Color(0xFF2A1B45),
    primaryContainer = Color(0xFF45336A),
    onPrimaryContainer = Color(0xFFEDE3FF),
    secondary = Color(0xFFFFA48E),
    onSecondary = Color(0xFF4A1A0E),
    secondaryContainer = Color(0xFF6A3427),
    onSecondaryContainer = Palette.CoralSoft,
    tertiary = Color(0xFF8FD1B1),
    background = Color(0xFF16131D),
    onBackground = Color(0xFFEDE8F2),
    surface = Color(0xFF16131D),
    onSurface = Color(0xFFEDE8F2),
    surfaceVariant = Color(0xFF2A2533),
    onSurfaceVariant = Color(0xFFC3BCCF),
    surfaceContainerLowest = Color(0xFF1B1823),
    surfaceContainerLow = Color(0xFF1F1B27),
    surfaceContainer = Color(0xFF241F2D),
    surfaceContainerHigh = Color(0xFF2A2533),
    surfaceContainerHighest = Color(0xFF312B3B),
    outline = Color(0xFF5E5670),
    outlineVariant = Color(0xFF3A3445),
    error = Color(0xFFF2899A)
)

private val AppTypography = Typography().let { t ->
    t.copy(
        displaySmall = t.displaySmall.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold, fontSize = 34.sp),
        headlineMedium = t.headlineMedium.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold),
        headlineSmall = t.headlineSmall.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold),
        titleLarge = t.titleLarge.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 17.sp),
        labelLarge = t.labelLarge.copy(fontWeight = FontWeight.SemiBold)
    )
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkScheme else LightScheme,
        typography = AppTypography,
        content = content
    )
}

private class Piece(val x: Float, val vx: Float, val vy: Float, val color: Color, val spin: Float, val w: Float, val h: Float)

/** Uma chuva de pinceladas que cai uma vez quando [key] muda. */
@Composable
fun Confetti(key: Int, tint: Color? = null, modifier: Modifier = Modifier) {
    if (key == 0) return
    val progress = remember(key) { Animatable(0f) }
    val pieces = remember(key) {
        List(80) {
            val a = Random.nextFloat() * Math.PI.toFloat()
            Piece(
                x = 0.5f + (Random.nextFloat() - 0.5f) * 0.3f,
                vx = cos(a) * (0.25f + Random.nextFloat() * 0.45f),
                vy = -(0.6f + Random.nextFloat() * 0.7f) * sin(a).coerceAtLeast(0.4f),
                color = if (tint != null && Random.nextFloat() < 0.5f) tint else Palette.confetti.random(),
                spin = Random.nextFloat() * 720f - 360f,
                w = 10f + Random.nextFloat() * 14f,
                h = 4f + Random.nextFloat() * 5f
            )
        }
    }
    LaunchedEffect(key) { progress.animateTo(1f, tween(2200, easing = LinearEasing)) }
    val p = progress.value
    if (p >= 1f) return
    Canvas(modifier.fillMaxSize()) {
        pieces.forEach { pc ->
            val t = p * 2.2f
            val x = (pc.x + pc.vx * t) * size.width
            val y = (0.45f + pc.vy * t + 0.9f * t * t) * size.height
            if (y > size.height) return@forEach
            rotate(pc.spin * p, Offset(x, y)) {
                drawRect(
                    pc.color.copy(alpha = (1f - p * 0.6f)),
                    topLeft = Offset(x - pc.w / 2, y - pc.h / 2),
                    size = Size(pc.w, pc.h)
                )
            }
        }
    }
}
