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
private val colors = lightColorScheme(primary=Green,onPrimary=Color.White,primaryContainer=Mint,onPrimaryContainer=Brand,
    secondary=Brand,secondaryContainer=Mint,onSecondaryContainer=Brand,background=Canvas,onBackground=Ink,
    surface=Color.White,onSurface=Ink,surfaceVariant=Mint,onSurfaceVariant=Muted,outline=Color(0xFF839B92),
    outlineVariant=Color(0xFFD9E8E0),error=Color(0xFFAA3D32),errorContainer=Color(0xFFFFE8E3))

@Composable fun WorkbookTheme(content:@Composable ()->Unit) {
    MaterialTheme(colorScheme=colors, typography=Typography(
        headlineLarge=TextStyle(fontSize=30.sp,lineHeight=40.sp,fontWeight=FontWeight.Bold),
        headlineMedium=TextStyle(fontSize=26.sp,lineHeight=36.sp,fontWeight=FontWeight.SemiBold),
        titleLarge=TextStyle(fontSize=22.sp,lineHeight=31.sp,fontWeight=FontWeight.SemiBold),
        titleMedium=TextStyle(fontSize=18.sp,lineHeight=27.sp,fontWeight=FontWeight.SemiBold),
        bodyLarge=TextStyle(fontSize=16.sp,lineHeight=25.sp),bodyMedium=TextStyle(fontSize=15.sp,lineHeight=23.sp),
        bodySmall=TextStyle(fontSize=13.sp,lineHeight=20.sp),labelLarge=TextStyle(fontSize=15.sp,lineHeight=22.sp,fontWeight=FontWeight.Medium),
        labelMedium=TextStyle(fontSize=13.sp,lineHeight=19.sp,fontWeight=FontWeight.Medium)),
        shapes=Shapes(small=RoundedCornerShape(10.dp),medium=RoundedCornerShape(14.dp),large=RoundedCornerShape(18.dp)), content=content)
}
