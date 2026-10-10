package app.winters.octo.ui.family

import app.winters.octo.subsonic.FamilyLink
import app.winters.octo.subsonic.parseFamilyLink
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder

// A QR code as squares: `size` by `size`, each dark or light. Drawn by
// each app on its own canvas, with a quiet light border around it.
class QrCode(val size: Int, private val dark: BooleanArray) {
    fun isDark(x: Int, y: Int): Boolean = dark[y * size + x]
}

// The QR code for a link, made on the device. Medium error correction, so
// a phone camera reads it from a screen at an angle.
fun qrCode(text: String): QrCode {
    val matrix = Encoder.encode(text, ErrorCorrectionLevel.M).matrix
    val size = matrix.width
    val dark = BooleanArray(size * size) { matrix.get(it % size, it / size).toInt() == 1 }
    return QrCode(size, dark)
}

private val HINTS = mapOf(
    DecodeHintType.TRY_HARDER to true,
    DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
    DecodeHintType.CHARACTER_SET to "UTF-8",
)

// The text of a QR code in a picture, as ARGB pixels row by row, or null
// when there is none. Also tried with light and dark swapped, for a code
// shown light on dark.
fun readQr(argb: IntArray, width: Int, height: Int): String? = read(RGBLuminanceSource(width, height, argb))

// The same from a camera frame's brightness plane (Android's YUV images):
// one byte a pixel, `rowStride` bytes a row.
fun readQrFromLuminance(luminance: ByteArray, rowStride: Int, width: Int, height: Int): String? =
    read(PlanarYUVLuminanceSource(luminance, rowStride, height, 0, 0, width, height, false))

private fun read(source: LuminanceSource): String? =
    decode(source) ?: decode(source.invert())

private fun decode(source: LuminanceSource): String? = try {
    QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source)), HINTS).text
} catch (e: NotFoundException) {
    null
} catch (e: ReaderException) {
    null
}

// The family link in a QR code's text, or null when it holds something else.
fun familyLinkInQr(text: String?): FamilyLink? = text?.let(::parseFamilyLink)
