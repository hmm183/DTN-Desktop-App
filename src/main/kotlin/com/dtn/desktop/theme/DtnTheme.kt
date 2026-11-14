package com.dtn.desktop.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Colours from docs/ui-redesign/visual-design.md §1. */
object DtnColors {
    val Background = Color(0xFFF4F7FC)
    val Card = Color(0xFFFFFFFF)
    val CardBorder = Color(0xFFE6ECF5)
    val Primary = Color(0xFF1769FF)
    val PrimarySoft = Color(0xFFE8F0FF)
    val TextTitle = Color(0xFF0F1B33)
    val TextSubtitle = Color(0xFF5B6B82)
    val TextHint = Color(0xFF94A3B8)
    val Green = Color(0xFF22A447)
    val GreenSoft = Color(0xFFE7F6EC)
    val Orange = Color(0xFFF59E0B)
    val OrangeSoft = Color(0xFFFFF4E0)
    val Red = Color(0xFFE5484D)
    val RedSoft = Color(0xFFFDECEC)
    val Purple = Color(0xFF7C5CFF)
    val PurpleSoft = Color(0xFFEFEAFF)
    val Grey = Color(0xFF9AA4B2)
    val GreySoft = Color(0xFFEEF1F5)
    val Track = Color(0xFFEAF0F8)
    val Terminal = Color(0xFF0F172A)
    val BubbleIn = Color(0xFFE6EBF2)
    val BubbleOut = Color(0xFF1769FF)

    val GreenText = Color(0xFF15803D)
    val OrangeText = Color(0xFF9A5B00)
    val RedText = Color(0xFFC0262D)

    // Algorithm colours
    val AlgoProphet = Color(0xFF1769FF)
    val AlgoStable = Color(0xFF22A447)
    val AlgoMaxProp = Color(0xFFF59E0B)
    val AlgoDiMaxProp = Color(0xFF7C5CFF)
    val AlgoEpidemic = Color(0xFFE5484D)
    val AlgoQLearning = Color(0xFF0EA5A4)

    fun algorithm(name: String): Color = when (name.uppercase().replace("-", "").replace("_", "")) {
        "PROPHET" -> AlgoProphet
        "STABLE", "STABLEPROPHET" -> AlgoStable
        "MAXPROP" -> AlgoMaxProp
        "DIMAXPROP" -> AlgoDiMaxProp
        "EPIDEMIC" -> AlgoEpidemic
        "QLEARNING" -> AlgoQLearning
        else -> Grey
    }

    private val avatars = listOf(
        Color(0xFF1769FF), Color(0xFF22A447), Color(0xFFE5539A),
        Color(0xFF7C5CFF), Color(0xFF38A3F1), Color(0xFF0EA5A4),
    )
    val GroupAvatar = Color(0xFFB9A8FF)

    fun avatarFor(nodeId: String): Color = avatars[Math.floorMod(nodeId.hashCode(), avatars.size)]
}

object DtnType {
    val AppTitle = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold, color = DtnColors.TextTitle)
    val PageTitle = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = DtnColors.TextTitle)
    val PageSubtitle = TextStyle(fontSize = 13.sp, color = DtnColors.TextSubtitle)
    val SectionHeader = TextStyle(
        fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = DtnColors.Primary, letterSpacing = 1.1.sp,
    )
    val RowTitle = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = DtnColors.TextTitle)
    val RowSubtitle = TextStyle(fontSize = 13.sp, color = DtnColors.TextSubtitle)
    val Meta = TextStyle(fontSize = 12.sp, color = DtnColors.TextSubtitle)
    val Mono = TextStyle(fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = DtnColors.TextTitle)
}

object DtnShapes {
    val Card = RoundedCornerShape(16.dp)
    val Button = RoundedCornerShape(12.dp)
    val Field = RoundedCornerShape(12.dp)
    val Pill = CircleShape
    val Bubble = RoundedCornerShape(16.dp)
}

val DtnLightColorScheme = lightColorScheme(
    primary = DtnColors.Primary,
    onPrimary = Color.White,
    secondary = DtnColors.Green,
    onSecondary = Color.White,
    background = DtnColors.Background,
    onBackground = DtnColors.TextTitle,
    surface = DtnColors.Card,
    onSurface = DtnColors.TextTitle,
    surfaceVariant = DtnColors.Track,
    onSurfaceVariant = DtnColors.TextSubtitle,
    outline = DtnColors.CardBorder,
)
