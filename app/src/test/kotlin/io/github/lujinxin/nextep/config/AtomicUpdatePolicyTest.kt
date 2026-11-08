package io.github.x647368maker.atomicworkbench.config

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class AtomicUpdatePolicyTest {
    private fun metadata(code:Long=17,dev:Boolean=true)=UpdatePolicy.Metadata(if(dev)"0.9.3-dev" else "1.0.0",code,
        if(dev)UpdatePolicy.Channel.DEV else UpdatePolicy.Channel.STABLE,"a".repeat(64),100,"StageManager.apk")
    private fun rejects(action:()->Unit) { try { action(); fail("Expected rejection") } catch(_:IllegalArgumentException) {} }
    @Test fun understandsDevelopmentStableAndLegacyTagFormats() {
        assertTrue(UpdatePolicy.version("0.9.2-dev")!!.development)
        assertFalse(UpdatePolicy.version("1.0.0")!!.development)
        assertTrue(UpdatePolicy.version("atomic-v0.1.0-dev-abcdef12")!!.development)
        assertNull(UpdatePolicy.version("NeXtep latest"))
    }
    @Test fun versionCodeIsAuthoritativeAndNeverDowngrades() {
        assertFalse(UpdatePolicy.eligible(metadata(16),16,UpdatePolicy.Channel.DEV))
        assertFalse(UpdatePolicy.eligible(metadata(15,false),16,UpdatePolicy.Channel.DEV))
        assertTrue(UpdatePolicy.eligible(metadata(17),16,UpdatePolicy.Channel.DEV))
    }
    @Test fun stableChannelExcludesDevelopmentAndDevIncludesStable() {
        assertFalse(UpdatePolicy.eligible(metadata(),16,UpdatePolicy.Channel.STABLE))
        assertTrue(UpdatePolicy.eligible(metadata(dev=false),16,UpdatePolicy.Channel.STABLE))
        assertTrue(UpdatePolicy.eligible(metadata(dev=false),16,UpdatePolicy.Channel.DEV))
    }
    @Test fun metadataRequiresCorrectPackageCertificateAndDigest() {
        rejects { metadata().copy(applicationId="io.github.lujinxin.nextep").validate() }
        rejects { metadata().copy(certificate="b".repeat(64)).validate() }
        rejects { metadata().copy(sha256="").validate() }
        rejects { metadata().copy(channel=UpdatePolicy.Channel.STABLE).validate() }
    }
    @Test fun metadataRejectsInvalidSizeAndPathNames() {
        rejects { metadata().copy(size=0).validate() }
        rejects { metadata().copy(size=UpdatePolicy.MAX_APK+1).validate() }
        rejects { metadata().copy(apkName="../evil.apk").validate() }
    }
    @Test fun updateSourceRejectsOtherRepositoriesAndCleartext() {
        val url=UpdatePolicy.REPO+"/releases/download/v0.9.3/StageManager.apk"
        assertEquals(url,UpdatePolicy.asset(url))
        rejects { UpdatePolicy.asset("https://github.com/lujinxin/NeXtep/releases/download/v2.1.0/app.apk") }
        rejects { UpdatePolicy.secure("http://github.com/x647368-maker/NeXtep") }
        rejects { UpdatePolicy.secure("https://github.com.evil.test/file.apk") }
        rejects { UpdatePolicy.secure("https://user:password@github.com/file.apk") }
        rejects { UpdatePolicy.asset(UpdatePolicy.REPO+"/releases/download/../evil.apk") }
    }
    @Test fun secureRedirectsPermitOnlyGithubReleaseHosts() {
        UpdatePolicy.secure("https://release-assets.githubusercontent.com/asset?token=value")
        rejects { UpdatePolicy.secure("https://untrusted.example/update.apk") }
        rejects { UpdatePolicy.secure("https://github.com:8080/update.apk") }
    }
    @Test fun archiveIdentityChecksPackageVersionAndCompatibleSigner() {
        val m=metadata();val sign=setOf(UpdatePolicy.CERT)
        UpdatePolicy.requireArchive(m,UpdatePolicy.APP,17,"0.9.3-dev",sign,sign,16)
        rejects { UpdatePolicy.requireArchive(m,"other",17,m.versionName,sign,sign,16) }
        rejects { UpdatePolicy.requireArchive(m,UpdatePolicy.APP,18,m.versionName,sign,sign,16) }
        rejects { UpdatePolicy.requireArchive(m,UpdatePolicy.APP,17,"1.0.0",sign,sign,16) }
        rejects { UpdatePolicy.requireArchive(m,UpdatePolicy.APP,17,m.versionName,setOf("evil"),sign,16) }
        rejects { UpdatePolicy.requireArchive(m,UpdatePolicy.APP,17,m.versionName,sign,sign,17) }
    }
    @Test fun digestDetectsCorruptionBeforeInstaller() {
        val f=File.createTempFile("atomic-update-test",".apk")
        try {
            f.writeText("abc");assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",UpdatePolicy.digest(f))
            f.appendText("x");assertNotEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",UpdatePolicy.digest(f))
        } finally { f.delete() }
    }
}
