package io.github.x647368maker.atomicworkbench.config

import org.junit.Assert.*
import org.junit.Test

class StageUpdateDeviceLabelTest {
    @Test fun knownRealModelsHaveFriendlyNames() {
        assertEquals("一加 Ace 7 Pro",StageUpdateDeviceLabel.fromProperties("OnePlus","PMS110"))
        assertEquals("OPPO Find N5",StageUpdateDeviceLabel.fromProperties("OPPO","PKH110"))
        assertEquals("OPPO Find N5 卫星通信版",StageUpdateDeviceLabel.fromProperties("OPPO","PKH120"))
        assertEquals("联想拯救者 Y700 2025",StageUpdateDeviceLabel.fromProperties("Lenovo","TB321FU"))
        assertEquals("联想拯救者 Y700 四代",StageUpdateDeviceLabel.fromProperties("Lenovo","TB322FU"))
    }
    @Test fun modifiedRomMustNotInventAnIdentity() {
        assertEquals("OPPO N5-ported-ROM",StageUpdateDeviceLabel.fromProperties("OPPO","N5-ported-ROM"))
        assertEquals("设备型号未识别",StageUpdateDeviceLabel.fromProperties("OPPO","unknown"))
        assertEquals("设备型号未识别",StageUpdateDeviceLabel.fromProperties(null,null))
        assertEquals("联想 Y700Custom",StageUpdateDeviceLabel.fromProperties("Lenovo","Y700Custom"))
    }
    @Test fun longOrControlCharactersCannotBreakLayout() {
        assertTrue(StageUpdateDeviceLabel.fromProperties("OPPO","Z".repeat(999)).length<=69)
        assertEquals("一加 ATest",StageUpdateDeviceLabel.fromProperties("OnePlus","A\nTest"))
    }
}
