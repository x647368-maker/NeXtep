package io.github.x647368maker.atomicworkbench.config

import org.junit.Assert.*
import org.junit.Test
import java.util.Base64
import java.security.MessageDigest

class StageDonationQrTest {
    @Test fun twoDistinctScannableMatrixPayloadsFromUserPicturesAreBundled() {
        val codes = StageDonationQr.codes
        assertEquals(listOf("微信收款码","支付宝收款码"),codes.map{it.name})
        assertEquals(listOf(37,41),codes.map{it.size})
        val expected=listOf(
            "751a0b6f53b853c348108bbfd07fe25de2c6a05f1e01c7e6e1de90cbea23a039",
            "28e2795e2b5a9d392cfd97242bf6b052f1d6867bde2afee0fdd748c5fc05156e"
        )
        codes.forEachIndexed { index, code ->
            val bits=Base64.getDecoder().decode(code.packedBits)
            assertEquals((code.size*code.size+7)/8,bits.size)
            val hash=MessageDigest.getInstance("SHA-256").digest(bits)
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            assertEquals(expected[index],hash)
            assertTrue(code.filename.endsWith(".png"))
        }
    }
}
