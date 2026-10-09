package io.github.veritasx1.lichat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Michelle 0e6bf108: Android itself blocks http (Robolectric does not) – the test build may reach only this phone
 *  over http (adb reverse), LiChat itself never. Checked on the files, the phone test is Michelle's. */
class NetworkConfigTest {
    private val src = File(System.getProperty("lichat.server")).parentFile.resolve("android/app/src")

    @Test
    fun releaseIsHttpsOnly() {
        val main = src.resolve("main/res/xml/network_security_config.xml").readText()
        assertTrue(main.contains("""<base-config cleartextTrafficPermitted="false" />"""))
        assertFalse(main.contains("""cleartextTrafficPermitted="true""""))
        assertTrue(src.resolve("main/AndroidManifest.xml").readText().contains("@xml/network_security_config"))
    }

    @Test
    fun testBuildOnlyThisPhone() {
        val debug = src.resolve("debug/res/xml/network_security_config.xml").readText()
        val domains = Regex("<domain[^>]*>([^<]+)</domain>").findAll(debug).map { it.groupValues[1] }.toSet()
        assertTrue(domains == setOf("127.0.0.1", "localhost"))
        assertFalse(debug.contains("""includeSubdomains="true""""))
    }
}
