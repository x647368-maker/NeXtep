package io.github.x647368maker.atomicworkbench.config

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import java.util.Base64

/**
 * Exact QR data matrices extracted from the TWO USER-SUPPLIED receipt QR images.
 * Original and rendered matrices were independently decoded to identical payloads.
 * The original center photos are restored with offline 64px WebP thumbnails.\n * No payment SDK, URL lookup, connection, permission or timer.
 */
internal object StageDonationQr {
    data class Code(
        val name: String,
        val size: Int,
        val packedBits: String,
        val filename: String,
    )
    val codes = listOf(
        Code(
            "微信收款码", 37,
            "/oKQo/wQFm2QbqXUZLt1Eb5126IVFa7BY6AhB/qqqq/gG1eRAFegGa9t4barhFXCB8fN6pOtzVxtAAS3mggAjjN/gC0PewgBrSBiwvVCXsYxKzQKpAAD39Jsnsq15HdwR1ypwCTPWcBuyCmSfvDRuqADovlipITZ4JfNMZrCbTD0br2F6v+AZN70T/p5aisQVpqfF7pSc4/N1Pr6ci6earR7BauI+j/h1+fbgA==",
            "台前调度-微信收款码.png",
        ),
        Code(
            "支付宝收款码", 41,
            "/tv04b/BT/I5EG6jErALt0XklWXbolGsGuwUrNqRB/qqqqr+AapCYwA67XDk8/rSWXb9gsHsjbRPM1CnMalgccjUcknOWITNjwT85VFzWgXnW3d6YR3XJCi+pp7EgDuFCoY1WV7VqnsX15Cyyz/6H79r1QbaiKl7AcesD//kdxC4mZ7Xln6gNXaoVST7Jn+HLtlNemThGk7i1jtxIIuVeNRR1OGCJ4qs+wBXIDVEf57eLOuQS8/wMYuqPl6vvdeILyKO6mU48/UE+ywlUv4Bl1QcAA==",
            "台前调度-支付宝收款码.png",
        ),
    )

    /** Builds a small, square, nearest-neighbour bitmap with a four-module quiet zone. */
    fun bitmap(code: Code, pxPerModule: Int = 10): Bitmap {
        require(pxPerModule in 4..16)
        val bits = Base64.getDecoder().decode(code.packedBits)
        val n = code.size
        require(n in 21..177 && bits.size == (n * n + 7) / 8)
        val pixelsWide = (n + 8) * pxPerModule
        val pixels = IntArray(pixelsWide * pixelsWide) { Color.WHITE }
        for (y in 0 until n) for (x in 0 until n) {
            val position = y * n + x
            val dark = ((bits[position / 8].toInt() and 255) ushr (7 - (position % 8))) and 1
            if (dark == 0) continue
            val left = (x + 4) * pxPerModule
            val top = (y + 4) * pxPerModule
            for (yy in top until top + pxPerModule) {
                java.util.Arrays.fill(pixels, yy * pixelsWide + left,
                    yy * pixelsWide + left + pxPerModule, Color.BLACK)
            }
        }
        val output=Bitmap.createBitmap(pixels, pixelsWide, pixelsWide, Bitmap.Config.ARGB_8888)
        // Original dog/portrait from the user's own QR images, rather than
        // an impersonal stripped black-white QR. The source QR payload bits
        // remain exactly unchanged; pictures are small, offline, on demand.
        val photoBits=Base64.getDecoder().decode(
            if(code.name=="微信收款码") StageDonationAvatar.WECHAT else StageDonationAvatar.ALIPAY
        )
        val avatar=BitmapFactory.decodeByteArray(photoBits,0,photoBits.size)
            ?: return output
        try {
            val canvas=Canvas(output)
            val edge=pixelsWide*0.26f
            val cx=pixelsWide/2f
            val cy=pixelsWide/2f
            val photo=RectF(cx-edge/2f,cy-edge/2f,cx+edge/2f,cy+edge/2f)
            val border=RectF(photo.left-6f,photo.top-6f,photo.right+6f,photo.bottom+6f)
            val painter=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            painter.color=Color.WHITE
            canvas.drawRoundRect(border,13f,13f,painter)
            val rounded=Path().apply { addRoundRect(photo,9f,9f,Path.Direction.CW) }
            canvas.save()
            canvas.clipPath(rounded)
            canvas.drawBitmap(avatar,null,photo,painter)
            canvas.restore()
        } finally { avatar.recycle() }
        return output
    }
}
