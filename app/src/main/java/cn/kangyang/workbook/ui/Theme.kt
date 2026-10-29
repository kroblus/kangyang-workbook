package cn.kangyang.workbook.ui

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val Brand = Color(0xFF0F514F)
val Green = Color(0xFF176C65)
val Mint = Color(0xFFE7F5EE)
val Canvas = Color(0xFFF7FBF8)
val Ink = Color(0xFF213A36)
val Muted = Color(0xFF526761)

/** One set of shapes for app-owned surfaces; Android's file/photo pickers remain system UI. */
object AppShapes {
    val control = RoundedCornerShape(12.dp)
    val card = RoundedCornerShape(16.dp)
    val sheet = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    val dialog = RoundedCornerShape(24.dp)
    val badge = RoundedCornerShape(8.dp)
}

object Spacing {
    val tiny = 4.dp
    val small = 8.dp
    val medium = 12.dp
    val page = 16.dp
    val section = 24.dp
    val large = 32.dp
}

private val colors = lightColorScheme(
    primary = Green, onPrimary = Color.White,
    primaryContainer = Mint, onPrimaryContainer = Brand,
    secondary = Brand, onSecondary = Color.White,
    secondaryContainer = Mint, onSecondaryContainer = Brand,
    tertiary = Color(0xFF356E58), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE4F2E8), onTertiaryContainer = Ink,
    background = Canvas, onBackground = Ink,
    surface = Color.White, onSurface = Ink,
    surfaceVariant = Mint, onSurfaceVariant = Muted,
    surfaceTint = Green,
    surfaceBright = Color.White, surfaceDim = Color(0xFFDCE7E1),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF2F8F4),
    surfaceContainer = Color(0xFFEDF5EF),
    surfaceContainerHigh = Color(0xFFE7F0EA),
    surfaceContainerHighest = Color(0xFFDDEAE2),
    inverseSurface = Ink, inverseOnSurface = Canvas, inversePrimary = Color(0xFFA4D9C7),
    outline = Color(0xFF839B92), outlineVariant = Color(0xFFD9E8E0),
    error = Color(0xFFAA3D32), onError = Color.White,
    errorContainer = Color(0xFFFFE8E3), onErrorContainer = Color(0xFF681D17), scrim = Color.Black,
)

@Composable fun WorkbookTheme(content:@Composable ()->Unit) {
    MaterialTheme(colorScheme=colors, typography=Typography(
        headlineLarge=TextStyle(fontSize=26.sp,lineHeight=36.sp,fontWeight=FontWeight.Bold),
        headlineMedium=TextStyle(fontSize=24.sp,lineHeight=33.sp,fontWeight=FontWeight.SemiBold),
        headlineSmall=TextStyle(fontSize=22.sp,lineHeight=31.sp,fontWeight=FontWeight.SemiBold),
        titleLarge=TextStyle(fontSize=22.sp,lineHeight=31.sp,fontWeight=FontWeight.SemiBold),
        titleMedium=TextStyle(fontSize=18.sp,lineHeight=27.sp,fontWeight=FontWeight.SemiBold),
        titleSmall=TextStyle(fontSize=16.sp,lineHeight=25.sp,fontWeight=FontWeight.Medium),
        bodyLarge=TextStyle(fontSize=17.sp,lineHeight=27.sp),bodyMedium=TextStyle(fontSize=16.sp,lineHeight=25.sp),
        bodySmall=TextStyle(fontSize=14.sp,lineHeight=22.sp),labelLarge=TextStyle(fontSize=16.sp,lineHeight=24.sp,fontWeight=FontWeight.Medium),
        labelMedium=TextStyle(fontSize=14.sp,lineHeight=22.sp,fontWeight=FontWeight.Medium),
        labelSmall=TextStyle(fontSize=12.sp,lineHeight=18.sp,fontWeight=FontWeight.Medium)),
        shapes=Shapes(extraSmall=AppShapes.badge,small=AppShapes.control,medium=AppShapes.card,large=AppShapes.card,extraLarge=AppShapes.dialog), content=content)
}
