package br.com.tabelapp.ui.comum

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Cupom fiscal com um esboço de QR Code (o Material Icons não tem um pronto): contorno do cupom
 * com a borda de baixo serrilhada; dentro, 3 quadradinhos cheios nos cantos (como no QR), 2 pontos
 * e um traço. Desenhado em 24x24 para ficar legível em 22 dp. A cor vem do `tint` do Icon.
 */
val IconeCupomQr: ImageVector by lazy {
    val tinta = SolidColor(Color.Black)
    ImageVector.Builder(name = "CupomQr", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        // Contorno do cupom, com a borda de baixo serrilhada.
        .path(stroke = tinta, strokeLineWidth = 1.7f, strokeLineJoin = StrokeJoin.Round, strokeLineCap = StrokeCap.Round) {
            moveTo(5f, 2.5f)
            lineTo(19f, 2.5f)
            lineTo(19f, 20f)
            var x = 19f
            var alto = true
            while (x > 5.01f) {
                x -= 1.75f
                lineTo(x, if (alto) 21.6f else 20f)
                alto = !alto
            }
            close()
        }
        // Três quadradinhos cheios nos cantos, como no QR.
        .path(fill = tinta) {
            moveTo(7.5f, 5f); lineTo(11f, 5f); lineTo(11f, 8.5f); lineTo(7.5f, 8.5f); close()
            moveTo(13f, 5f); lineTo(16.5f, 5f); lineTo(16.5f, 8.5f); lineTo(13f, 8.5f); close()
            moveTo(7.5f, 10.5f); lineTo(11f, 10.5f); lineTo(11f, 14f); lineTo(7.5f, 14f); close()
            // Dois pontos.
            moveTo(13f, 10.5f); lineTo(14.6f, 10.5f); lineTo(14.6f, 12.1f); lineTo(13f, 12.1f); close()
            moveTo(14.9f, 12.4f); lineTo(16.5f, 12.4f); lineTo(16.5f, 14f); lineTo(14.9f, 14f); close()
        }
        // Traço embaixo.
        .path(stroke = tinta, strokeLineWidth = 1.7f, strokeLineCap = StrokeCap.Round) {
            moveTo(7.5f, 16.8f)
            lineTo(16.5f, 16.8f)
        }
        .build()
}
