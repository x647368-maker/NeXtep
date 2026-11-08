package io.github.x647368maker.atomicworkbench.startup

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element

class StartupResourcesTest {
    private val source = File("src/main").takeIf { it.exists() } ?: File("app/src/main")
    private fun xml(path: String) = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        .newDocumentBuilder().parse(File(source,path))
    private fun items(folder: String, name: String): Map<String,String> {
        val styles=xml("res/$folder/themes.xml").getElementsByTagName("style")
        val style=(0 until styles.length).map { styles.item(it) as Element }.single { it.getAttribute("name")==name }
        val children=style.getElementsByTagName("item")
        return (0 until children.length).map { children.item(it) as Element }.associate { it.getAttribute("name") to it.textContent.trim() }
    }
    @Test fun dayAndNightSplashAndApplicationBackgroundsUseTheSameStageIdentity() {
        for (folder in listOf("values","values-night")) {
            for (name in listOf("Theme.NeXtep","Theme.StageLauncherEntry")) {
                val i=items(folder,name)
                assertEquals("@drawable/ic_atomic_workbench",i["android:windowSplashScreenAnimatedIcon"])
                assertEquals("@color/stage_background",i["android:windowSplashScreenBackground"])
                assertEquals("@android:color/transparent",i["android:windowSplashScreenIconBackgroundColor"])
            }
            val i=items(folder,"Theme.NeXtep")
            assertEquals(i["android:windowSplashScreenBackground"],i["android:windowBackground"])
            assertEquals(i["android:windowBackground"],i["android:colorBackground"])
        }
        assertEquals("#F0F1F3",xml("res/values/stage_colors.xml").getElementsByTagName("color").item(0).textContent.trim())
        assertEquals("#111214",xml("res/values-night/stage_colors.xml").getElementsByTagName("color").item(0).textContent.trim())
    }
    @Test fun everyStableLauncherAliasRetainsTheNoDisplayEntryAndStageIcon() {
        val android="http://schemas.android.com/apk/res/android"
        val doc=xml("AndroidManifest.xml")
        val app=doc.getElementsByTagName("application").item(0) as Element
        assertEquals("@drawable/ic_atomic_workbench",app.getAttributeNS(android,"icon"))
        assertEquals("@drawable/ic_atomic_workbench",app.getAttributeNS(android,"roundIcon"))
        val aliases=doc.getElementsByTagName("activity-alias")
        assertEquals(6,aliases.length)
        for(i in 0 until aliases.length) {
            val alias=aliases.item(i) as Element
            assertEquals(".icons.LauncherEntryActivity",alias.getAttributeNS(android,"targetActivity"))
            assertEquals("@drawable/ic_atomic_workbench",alias.getAttributeNS(android,"icon"))
        }
        val activities=doc.getElementsByTagName("activity")
        val entry=(0 until activities.length).map { activities.item(it) as Element }.single { it.getAttributeNS(android,"name")==".icons.LauncherEntryActivity" }
        assertEquals("@style/Theme.StageLauncherEntry",entry.getAttributeNS(android,"theme"))
        assertEquals("true",entry.getAttributeNS(android,"noHistory"))
    }
    @Test fun legacySplashAndLauncherNamesCannotSelectDensitySpecificUpstreamArtwork() {
        val aliases=xml("res/values/stage_launcher_icons.xml").getElementsByTagName("item")
        assertEquals(8,aliases.length)
        for(i in 0 until aliases.length) assertEquals("@drawable/ic_atomic_workbench",aliases.item(i).textContent.trim())
        assertTrue(File(source,"res").walkTopDown().none { it.isFile && it.parentFile.name.startsWith("mipmap-") && it.name.startsWith("ic_launcher") && it.extension=="webp" })
    }
}
