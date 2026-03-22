package com.blive.tv.ui.login

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.WriterException
import com.google.zxing.common.BitMatrix

class QrCodeBitmapFactory(private val size: Int = 300) {
    @Throws(WriterException::class)
    fun create(url: String): Bitmap {
        val bitMatrix: BitMatrix = MultiFormatWriter().encode(
            url,
            BarcodeFormat.QR_CODE,
            size,
            size,
            null
        )
        val width = bitMatrix.width
        val height = bitMatrix.height
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                pixels[y * width + x] = if (bitMatrix.get(x, y)) Color.BLACK else Color.WHITE
            }
        }
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, width, 0, 0, width, height)
        }
    }
}
