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
 * Walks through the player like a viewer: controls, subtitles & audio, settings,
 * episodes, switching episode and the up-next card — a screenshot after each step.
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

    private fun tapCenter() {
        device.click(device.displayWidth / 2, device.displayHeight / 3)
        Thread.sleep(1_200)
    }

    private fun clickRes(id: String): Boolean {
        val o = device.wait(Until.findObject(By.res(pkg, id)), 4_000)
        log("$id found=${o != null}")
        o?.click()
        Thread.sleep(1_500)
        return o != null
    }

    /** Logs what the player overlay looks like right now (for diagnosing). */
    private fun dumpControls(tag: String) {
        inst.runOnMainSync {
            val act = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).firstOrNull() ?: return@runOnMainSync
            val pv = act.findViewById<androidx.media3.ui.PlayerView>(R.id.playerView) ?: return@runOnMainSync
            val c = pv.findViewById<android.view.View>(androidx.media3.ui.R.id.exo_controller)
            val bg = pv.findViewById<android.view.View>(androidx.media3.ui.R.id.exo_controls_background)
            log("$tag fully=${pv.isControllerFullyVisible} controller=${c?.visibility}/${c?.width}x${c?.height} bg=${bg?.visibility}/a=${bg?.alpha}/${bg?.width}x${bg?.height}")
        }
    }

    private fun closePanels() {
        device.findObject(By.res(pkg, "panelClose"))?.click() ?: run {
            device.findObject(By.res(pkg, "btnCloseEpisodes"))?.click()
        }
        Thread.sleep(800)
    }

    private fun showControls() {
        if (device.findObject(By.res(pkg, "btnList")) == null) tapCenter()
    }

    private fun start(eps: List<Channel>, index: Int, position: Long) {
        val profile = ServerProfile(id = "uitest", name = "test", type = ServerType.M3U, url = "http://localhost")
        PlayerQueue.set(eps, index, profile, position)
        val intent = Intent(inst.targetContext, PlayerActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        inst.targetContext.startActivity(intent)
        device.wait(Until.hasObject(By.res(pkg, "playerView")), 10_000)
    }

    @Test
    fun openEpisodesAndSwitch() {
        // Public test streams: the first has subtitles, several audio tracks and qualities.
        val streams = listOf(
            "Apple BipBop" to "https://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_fmp4/master.m3u8",
            "Big Buck Bunny" to "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8",
            "Tears of Steel" to "https://demo.unified-streaming.com/k8s/features/stable/video/tears-of-steel/tears-of-steel.ism/.m3u8",
            "Sintel" to "https://bitdash-a.akamaihd.net/content/sintel/hls/playlist.m3u8",
        )
        val eps = streams.mapIndexed { i, (n, url) ->
            Channel(
                id = "e$i", name = "S1 E${i + 1} • $n", logo = if (i == 0) "https://example.invalid/broken.jpg" else null,
                group = "1", url = url, kind = ItemKind.EPISODE,
                seriesId = "s1", seriesName = "مسلسل تجريبي", duration = "10 د",
            )
        }

        start(eps, 0, 0)
        Thread.sleep(12_000)
        shot("01_playing")

        dumpControls("before-tap")
        tapCenter()
        dumpControls("after-tap")
        shot("02_controls")

        clickRes("btnSubs")
        shot("03_subs_audio_panel")
        closePanels()

        showControls()
        clickRes("btnSettings")
        shot("04_settings_panel")
        device.findObject(By.text("الجودة"))?.click()
        Thread.sleep(1_200)
        shot("05_quality_page")
        closePanels()

        showControls()
        clickRes("btnAspect")
        shot("06_aspect_panel")
        closePanels()

        showControls()
        clickRes("btnList")
        shot("07_episodes")
        val strip = device.findObject(By.res(pkg, "episodesStrip"))
        log("strip children=${strip?.childCount}")
        strip?.children?.getOrNull(1)?.click()
        Thread.sleep(10_000)
        shot("08_episode2_playing")
        tapCenter()
        shot("09_episode2_controls")

        // Last seconds of episode 2 (Big Buck Bunny is ~10 min): the up-next card.
        start(eps, 1, 612_000)
        Thread.sleep(14_000)
        shot("10_up_next")
        log("app still in front=${device.currentPackageName == pkg}")
    }
}
