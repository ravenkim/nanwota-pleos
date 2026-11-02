package ai.nanwota.car.ui

import ai.nanwota.car.R
import ai.nanwota.car.SeatPos
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 동승자 웹(server/public/app.css)과 같은 토큰. 스타일: pastel-consumer, 좌석마다 한 색. */
object T {
    val ground = Color(0xFFF3F5F7)
    val surface = Color(0xFFFFFFFF)
    val raised = Color(0xFFF2F8FD)
    val active = Color(0xFFE9ECEF)
    val border = Color(0xFFE6E6E6)
    val rule = Color(0xFFF0F1F2)
    val control = Color(0xFFD6D9DD)
    val text = Color(0xFF1B1D1F)
    val muted = Color(0xFF5C6064)
    val primary = Color(0xFF1D1F20)
    val accent = Color(0xFF1D5FD6)
    val positiveBg = Color(0xFFDCFAE5)
    val positiveInk = Color(0xFF04250E)
    val warnBg = Color(0xFFFFFBEB)
    val warnInk = Color(0xFF92400E)

    val radiusCard = 20.dp
    val radiusInner = 12.dp
    val gap = 24.dp
    val pad = 24.dp

    data class Hue(val bg: Color, val ink: Color, val mark: Color)

    fun hue(seat: SeatPos?) = when (seat) {
        SeatPos.FRONT_RIGHT -> Hue(Color(0xFFDCF1FF), Color(0xFF0A3A80), Color(0xFF1D5FD6))
        SeatPos.REAR_LEFT -> Hue(Color(0xFFFDEBD3), Color(0xFF7A3306), Color(0xFFB45309))
        SeatPos.REAR_RIGHT -> Hue(Color(0xFFFBE1EF), Color(0xFF7A1846), Color(0xFFB8306F))
        SeatPos.REAR_CENTER -> Hue(Color(0xFFDFF3E2), Color(0xFF1D5A2C), Color(0xFF2F7A45))
        else -> Hue(Color(0xFFEEF1F4), Color(0xFF3A3D40), Color(0xFF6B7075))
    }

    val font = FontFamily(
        Font(R.font.pretendard_regular, FontWeight.Normal),
        Font(R.font.pretendard_medium, FontWeight.Medium),
        Font(R.font.pretendard_semibold, FontWeight.SemiBold),
    )

    // 차량 화면은 팔 길이 거리에서 읽으므로 웹보다 한 단계 크다. 크기는 다섯 개만 쓴다.
    private val tabular = "tnum"
    val title = TextStyle(fontFamily = font, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 36.sp, color = text)
    val section = TextStyle(fontFamily = font, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 30.sp, color = text)
    val figure = TextStyle(fontFamily = font, fontWeight = FontWeight.SemiBold, fontSize = 40.sp, lineHeight = 46.sp, color = text, fontFeatureSettings = tabular)
    val body = TextStyle(fontFamily = font, fontWeight = FontWeight.Normal, fontSize = 20.sp, lineHeight = 28.sp, color = text, fontFeatureSettings = tabular)
    val small = TextStyle(fontFamily = font, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 22.sp, color = muted, fontFeatureSettings = tabular)
    val center = TextAlign.Center
}
