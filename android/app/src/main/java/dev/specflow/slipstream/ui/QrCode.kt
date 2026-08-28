package dev.specflow.slipstream.ui

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Turns a link into a QR bitmap. There is no decoding side in this app —
 * scanning one is left to whatever camera app is already on the device,
 * which hands the link to this app through the deep link registered in the
 * manifest, so nothing here needs the camera permission or a CameraX
 * dependency to earn its keep.
 */
fun encodeQrCode(text: String, size: Int = 768): Bitmap {
    val hints = mapOf(
        // A profile link can carry a pinned certificate, which pushes the
        // payload well past what a short URL would need; a lower error
        // correction level keeps the module count — and so the code's
        // density — from growing along with it.
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L,
        EncodeHintType.MARGIN to 1,
    )
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
    val bitmap = Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.RGB_565)
    for (y in 0 until matrix.height) {
        for (x in 0 until matrix.width) {
            bitmap.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
        }
    }
    return bitmap
}
