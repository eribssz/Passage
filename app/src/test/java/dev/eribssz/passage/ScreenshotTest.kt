package dev.eribssz.passage

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.eribssz.passage.core.CertInfo
import dev.eribssz.passage.core.Endpoint
import dev.eribssz.passage.ui.PassageApp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class ScreenshotTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Suppress("UNCHECKED_CAST")
    private fun rootViews(): List<View> {
        val cls = Class.forName("android.view.WindowManagerGlobal")
        val wmg = cls.getMethod("getInstance").invoke(null)
        val f = cls.getDeclaredField("mViews").apply { isAccessible = true }
        return (f.get(wmg) as List<View>).toList()
    }

    private fun setup(state: VpnState, traffic: Traffic = Traffic()): Prefs {
        VpnController.set(state)
        VpnController.traffic(traffic)
        val prefs = Prefs(rule.activity)
        val p = Profile(name = "Acme HQ", username = "alex")
        prefs.save(p.copy(servers = listOf(Endpoint("203.0.113.10", 4433), Endpoint("203.0.113.11", 4433),
            Endpoint("198.51.100.7", 10443)), pins = mapOf("203.0.113.10:4433" to "AB12".repeat(16))))
        prefs.save(Profile(name = "Home lab", servers = listOf(Endpoint("vpn.example.com", 443)), username = "alex"))
        prefs.setActive(p.id)
        rule.setContent { PassageApp(prefs, onConnect = {}, onDisconnect = {}) }
        rule.mainClock.autoAdvance = false
        rule.mainClock.advanceTimeBy(1700)
        return prefs
    }

    /** Advances the paused clock frame by frame until [cond] holds (sheets animate in). */
    private fun frames(cond: () -> Boolean) {
        repeat(400) { if (cond()) return; rule.mainClock.advanceTimeByFrame() }
        check(cond()) { "condition not met" }
    }

    private fun capture(name: String) {
        rule.mainClock.advanceTimeBy(1200)
        val dir = File(System.getProperty("shots.dir") ?: "shots").apply { mkdirs() }
        rule.runOnUiThread {
            rootViews().forEachIndexed { i, v ->
                if (v.width == 0 || v.height == 0) return@forEachIndexed
                val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
                v.draw(Canvas(bmp))
                File(dir, if (i == 0) "$name.png" else "$name-$i.png").outputStream()
                    .use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
    }

    @Test fun idle() { setup(VpnState.Idle()); capture("1-idle") }

    @Test fun addressPicker() {
        val prefs = setup(VpnState.Idle())
        rule.onNodeWithText("Auto · 3").performClick()
        frames { rule.onAllNodesWithText("Automatic").fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodesWithText("203.0.113.11:4433")[0].performClick()
        frames { prefs.active().preferred != null }
        assertEquals("203.0.113.11:4433", prefs.active().preferred)
        capture("8-fixed")
    }

    @Test fun welcome() {
        VpnController.set(VpnState.Idle())
        rule.setContent { PassageApp(Prefs(rule.activity), onConnect = {}, onDisconnect = {}) }
        rule.mainClock.autoAdvance = false
        rule.mainClock.advanceTimeBy(1700)
        capture("0-welcome")
    }

    @Test fun exportImportRoundTrip() {
        val a = Prefs(rule.activity)
        a.save(Profile(name = "Acme HQ", username = "alex", servers = listOf(Endpoint("203.0.113.10", 4433), Endpoint("203.0.113.11", 4433)),
            pins = mapOf("203.0.113.10:4433" to "AB".repeat(32)), rememberPassword = true, preferred = "203.0.113.11:4433"))
        val id = a.profiles().single().id
        a.savePassword(id, "secret")
        val json = a.exportJson()
        org.junit.Assert.assertFalse("password must not be exported", json.contains("secret"))
        // Wipe and import into an empty install
        rule.activity.getSharedPreferences("passage", 0).edit().clear().commit()
        val b = Prefs(rule.activity)
        assertEquals(Profile.NONE, b.active())
        assertEquals(1, b.importJson(json))
        val p = b.active()
        assertEquals("Acme HQ", p.name)
        assertEquals(2, p.servers.size)
        assertEquals("203.0.113.11:4433", p.preferred)
        assertEquals("AB".repeat(32), p.pins["203.0.113.10:4433"])
        org.junit.Assert.assertFalse(p.rememberPassword)
        // Re-import updates instead of duplicating
        assertEquals(1, b.importJson(json)); assertEquals(1, b.profiles().size)
    }

    @Test fun busy() { setup(VpnState.Busy("Signing in", "203.0.113.11:4433")); capture("2-connecting") }

    @Test fun connected() {
        setup(VpnState.Connected(
            ip = "10.255.255.3", gateway = "203.0.113.11:4433", profileName = "Acme HQ", addressIndex = 1, addressCount = 3, fixed = false,
            since = System.currentTimeMillis() - 3_725_000, fortiOs = null, routes = listOf("192.168.0.0/21"),
            dns = listOf("192.168.1.2", "192.168.1.8"), mtu = 1354), Traffic(48_200_000, 3_900_000))
        capture("3-connected")
    }

    @Test fun cert() {
        setup(VpnState.NeedsCert(CertInfo("9F3A".repeat(16), "CN=FortiGate,O=Fortinet",
            "CN=FG100FTK20000000,O=Fortinet Ltd.", 1893456000000, false, "198.51.100.7:10443")))
        capture("4-cert")
    }

    @Test fun migration() {
        val sp = rule.activity.getSharedPreferences("passage", 0)
        sp.edit().clear().putString("server", "203.0.113.10").putInt("port", 4433).putString("username", "alex")
            .putString("name", "Acme HQ").putString("pin", "CD34".repeat(16)).commit()
        val p = Prefs(rule.activity).active()
        assertEquals(listOf(Endpoint("203.0.113.10", 4433)), p.servers)
        assertEquals(mapOf("203.0.113.10:4433" to "CD34".repeat(16)), p.pins)
        assertEquals("Acme HQ", p.name)
    }
}
