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
        assertEquals("联想拯救者 Y700 无极",StageUpdateDeviceLabel.fromProperties("Lenovo","TB324ZC"))
    }
    @Test fun knownHardwareIdOutranksSpoofedRomModel() {
        assertEquals("联想拯救者 Y700 无极",StageUpdateDeviceLabel.fromSystemProperties(
            "OPPO","OPPO","ColorOS","TB324ZC","TB324ZC"))
        assertEquals("OPPO Find N5",StageUpdateDeviceLabel.fromSystemProperties(
            "OPPO","OPPO","Unknown","PKH110","device_pk"))
        assertEquals("一加 Ace 7 Pro",StageUpdateDeviceLabel.fromSystemProperties(
            "OnePlus","OnePlus","PMS110","OP64F1L1","other"))
        assertEquals("设备型号待核实",StageUpdateDeviceLabel.fromSystemProperties(
            "OPPO","OPPO","PKH110","TB324ZC","PMS110"))
    }
    @Test fun conflictingKnownModelAndHardwareAreNotGuessed() {
        assertEquals("设备型号待核实",StageUpdateDeviceLabel.fromSystemProperties(
            "OPPO","OPPO","PMS110","TB324ZC","TB324ZC"))
        assertEquals("设备型号待核实",StageUpdateDeviceLabel.fromSystemProperties(
            "Lenovo","Lenovo","TB324ZC","PKH110","device_port"))
        assertEquals("联想拯救者 Y700 无极",StageUpdateDeviceLabel.fromSystemProperties(
            "OPPO","OPPO","ColorOS","TB324ZC","TB324ZC"))
    }
    @Test fun genericModelsDoNotInventADevice() {
        assertEquals("设备型号未识别",StageUpdateDeviceLabel.fromSystemProperties(
            "OPPO","OPPO","generic","unknown","unknown"))
        assertEquals("OPPO Find N5",StageUpdateDeviceLabel.fromProperties("OPPO","OPPO Find N5"))
    }
    @Test fun emulatorLabelNeverLooksLikeRetailDevice() {
        assertEquals("Android 模拟器",StageUpdateDeviceLabel.fromSystemProperties(
            "Google","Google","sdk_gphone64_x86_64","emu64x","sdk_gphone64_x86_64"))
        assertEquals("Android 模拟器",StageUpdateDeviceLabel.fromProperties(
            "Google","Android SDK built for x86_64"))
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
