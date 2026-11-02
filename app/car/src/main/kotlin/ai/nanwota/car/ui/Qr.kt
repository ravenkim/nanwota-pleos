package ai.nanwota.car.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** QR은 정차 중에만 그린다 (호출하는 쪽이 주행 상태를 확인). */
@Composable
fun QrCode(content: String, modifier: Modifier = Modifier, ink: Int = 0xFF1B1D1F.toInt()) {
    val bitmap = remember(content, ink) {
        val hints = mapOf(EncodeHintType.MARGIN to 1, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M)
        val m = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0, hints)
        val px = IntArray(m.width * m.height) { i -> if (m[i % m.width, i / m.width]) ink else 0xFFFFFFFF.toInt() }
        Bitmap.createBitmap(px, m.width, m.height, Bitmap.Config.ARGB_8888).asImageBitmap()
    }
    Image(bitmap, contentDescription = "탑승 등록 QR", modifier = modifier, filterQuality = FilterQuality.None)
}
