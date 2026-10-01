package com.tvplayer.app

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.tvplayer.app.data.Channel
import com.tvplayer.app.data.ItemKind
import com.tvplayer.app.data.PlayerQueue
import com.tvplayer.app.data.ServerProfile
import com.tvplayer.app.data.ServerType
import com.tvplayer.app.ui.PlayerActivity
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Plays a small "series", opens the episodes strip and taps another episode —
 * the same steps a viewer does — taking a screenshot after each step.
 * Screenshots land in /data/local/tmp/shots for the CI job to collect.
 */
@RunWith(AndroidJUnit4::class)
class EpisodesUiTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(inst)
    private val pkg = inst.targetContext.packageName

    private fun shot(name: String) {
        device.executeShellCommand("mkdir -p /data/local/tmp/shots")
        device.executeShellCommand("screencap -p /data/local/tmp/shots/$name.png")
    }

    private fun log(msg: String) = android.util.Log.i("UITEST", msg)

    @Test
    fun openEpisodesAndSwitch() {
        val base = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/"
        val names = listOf("ForBiggerBlazes", "ForBiggerEscapes", "ForBiggerFun", "ForBiggerJoyrides", "ForBiggerMeltdowns")
        val eps = names.mapIndexed { i, n ->
            Channel(
                id = "e$i", name = "S1 E${i + 1} • $n", logo = null, group = "1",
                url = "$base$n.mp4", kind = ItemKind.EPISODE,
                seriesId = "s1", seriesName = "مسلسل تجريبي", duration = "1 د",
            )
        }
        val profile = ServerProfile(id = "uitest", name = "test", type = ServerType.M3U, url = "http://localhost")
        PlayerQueue.set(eps, 0, profile, 0)

        val intent = Intent(inst.targetContext, PlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        inst.targetContext.startActivity(intent)
        device.wait(Until.hasObject(By.res(pkg, "playerView")), 10_000)
        Thread.sleep(8_000)
        shot("1_playing")

        // Tap the video to show the controls.
        device.click(device.displayWidth / 2, device.displayHeight / 2)
        Thread.sleep(1_500)
        shot("2_controls")

        // Open the episodes strip.
        val listBtn = device.wait(Until.findObject(By.res(pkg, "btnList")), 5_000)
        log("btnList found=${listBtn != null}")
        listBtn?.click()
        Thread.sleep(1_500)
        shot("3_episodes_open")

        // Tap the 3rd episode in the strip.
        val strip = device.findObject(By.res(pkg, "episodesStrip"))
        log("strip found=${strip != null} children=${strip?.childCount}")
        strip?.children?.getOrNull(2)?.click() ?: strip?.children?.lastOrNull()?.click()
        Thread.sleep(2_000)
        shot("4_after_tap")
        Thread.sleep(6_000)
        shot("5_playing_new")

        // Open the strip again and tap the next one, then wait.
        device.click(device.displayWidth / 2, device.displayHeight / 2)
        Thread.sleep(1_200)
        device.findObject(By.res(pkg, "btnList"))?.click()
        Thread.sleep(1_200)
        shot("6_strip_again")
        device.findObject(By.res(pkg, "episodesStrip"))?.children?.getOrNull(1)?.click()
        Thread.sleep(6_000)
        shot("7_final")
        log("app still in front=${device.currentPackageName == pkg}")
    }
}
