package cn.izis.izisbridge

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.hamcrest.Matchers.containsString

@RunWith(AndroidJUnit4::class)
class TrainingDeviceTest {
    @Test fun savedRead19ProfileRestartsWithoutConnecting() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        context.stopService(Intent(context, TrainingService::class.java))
        instrumentation.waitForIdleSync()
        val ui = context.getSharedPreferences("ui", Context.MODE_PRIVATE)
        val credentials = context.getSharedPreferences("credentials", Context.MODE_PRIVATE)
        val oldKey = credentials.getString("cloud", null)
        val oldProfile = ui.getString("profile", null)
        val oldSuccess = ui.getString("successfulProfile", null)
        val synthetic = "kg_live_" + java.util.UUID.randomUUID().toString().replace("-", "")
        try {
            Credentials(context).put("cloud", synthetic)
            ui.edit().putString("profile", "cloud").putString("successfulProfile", "desktop").commit()
            repeat(2) {
                var service: TrainingService? = null
                val connection = object : android.content.ServiceConnection {
                    override fun onServiceConnected(n: android.content.ComponentName, b: android.os.IBinder) { service = (b as TrainingService.LocalBinder).service }
                    override fun onServiceDisconnected(n: android.content.ComponentName) { service = null }
                }
                context.bindService(Intent(context, TrainingService::class.java), connection, Context.BIND_AUTO_CREATE)
                val end = android.os.SystemClock.elapsedRealtime() + 10000
                while (service == null && android.os.SystemClock.elapsedRealtime() < end) Thread.sleep(50)
                assertNotNull(service)
                assertEquals("cloud", service!!.profile)
                assertEquals("Configured", service!!.cloud.status.configuration)
                assertFalse(service!!.cloud.connected); assertFalse(service!!.hardware.active); assertNull(service!!.game)
                assertEquals(synthetic, Credentials(context).get("cloud"))
                assertFalse(credentials.getString("cloud", "")!!.contains(synthetic))
                context.unbindService(connection)
                context.stopService(Intent(context, TrainingService::class.java))
                instrumentation.waitForIdleSync()
            }
        } finally {
            credentials.edit().putString("cloud", oldKey).commit()
            ui.edit().putString("profile", oldProfile).putString("successfulProfile", oldSuccess).commit()
        }
    }
    @Test fun matchNavigationAndSettings() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withText("Start Game")).perform(scrollTo()).check(matches(isDisplayed()))
            onView(withText("Start Game")).perform(scrollTo(), click())
            onView(withText("Local Computer")).perform(click())
            onView(withText("Start Game · Local Computer")).check(matches(isDisplayed()))
            onView(withText("Cancel")).perform(click())
            onView(withText("Settings")).perform(click())
            onView(withText("Submit stable human moves automatically")).perform(scrollTo()).check(matches(isDisplayed()))
            onView(withText("Match")).perform(click())
            onView(withText("Undo")).perform(scrollTo()).check(matches(isDisplayed()))
        }
    }
    @Test fun keystoreRoundtripReplacementAndAutosaveRecovery() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val vault = Credentials(context)
        val secret = "test-only-secret-never-a-provider-key"
        vault.put("instrumentation", secret)
        assertEquals(secret, Credentials(context).get("instrumentation"))
        val stored = context.getSharedPreferences("credentials", Context.MODE_PRIVATE).getString("instrumentation", "")!!
        assertFalse(stored.contains(secret))
        vault.put("instrumentation", "replacement")
        assertEquals("replacement", vault.get("instrumentation"))
        val game = GoGame(9); game.play(10); game.play(null)
        GameStore(context).save(game.json(), "cloud")
        val restored = GoGame.restore(GameStore(context).all().first { it.getString("id") == game.id })
        assertArrayEquals(game.board, restored.board)
        assertEquals(game.sgf(), restored.sgf())
        val path = java.io.File(context.filesDir, "games/cloud-${game.id}.json")
        val interrupted = android.util.AtomicFile(path).startWrite()
        interrupted.write("partial".toByteArray()); interrupted.close()
        val recovered = GoGame.restore(GameStore(context).all().first { it.getString("id") == game.id })
        assertEquals(game.sgf(), recovered.sgf())
        android.util.AtomicFile(path).delete()
        context.getSharedPreferences("credentials", Context.MODE_PRIVATE).edit().remove("instrumentation").commit()
    }
    @Test fun touchscreenTabsAndPersistentHelp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withText("Settings")).perform(click())
            onView(withText("Configure / Replace Read19 credential")).perform(scrollTo()).check(matches(isDisplayed()))
            onView(withText("Configure / Replace desktop pairing")).perform(scrollTo()).check(matches(isDisplayed()))
            onView(withText("Physical board help")).perform(scrollTo(), click())
            onView(withText("Physical board help")).check(matches(isDisplayed()))
            onView(withText("OK")).perform(click())
            onView(withText("Analysis")).perform(click())
            onView(withText("? Help — buttons and LED colors")).check(matches(isDisplayed()))
            // Only this credential-free test screen permits a verification screenshot.
            scenario.onActivity { it.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE) }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            Thread.sleep(500)
            val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            assertNotNull(screenshot)
            java.io.File(context.filesDir, "api25-analysis.png").outputStream().use { output ->
                screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)
            }
            scenario.onActivity { it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE) }
        }
        context.stopService(Intent(context, TrainingService::class.java))
    }
}
