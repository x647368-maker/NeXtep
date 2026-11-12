package io.github.x647368maker.atomicworkbench.config

import android.graphics.Bitmap
import android.graphics.Color
import java.util.Base64

/**
 * Exact scan matrices extracted from the TWO USER-SUPPLIED receipt QR images.
 * Original and rendered matrices were independently decoded to identical payloads.
 * No QR generator, payment SDK, URL lookup, connection, permission or timer.
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
        return Bitmap.createBitmap(pixels, pixelsWide, pixelsWide, Bitmap.Config.ARGB_8888)
    }
}
