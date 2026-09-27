package com.tvplayer.app.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.tvplayer.app.BuildConfig
import com.tvplayer.app.data.Http
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * Self-update from the GitHub releases of this app:
 * checks the latest build number, downloads the APK, and opens the Android installer.
 * Updates install over the current app because every build is signed with the same key.
 */
object Updater {
    private val API = "https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest"

    class Release(val build: Int, val name: String, val apkUrl: String)

    /** Checked once per app session automatically. */
    private var checkedThisSession = false
    /** A downloaded update waiting for the "install unknown apps" permission. */
    private var pendingFile: File? = null

    private suspend fun latest(): Release? = withContext(Dispatchers.IO) {
        val json = JSONObject(Http.get(API, "TVPlayer-Updater"))
        val build = json.optString("tag_name").substringAfter("build-").toIntOrNull() ?: return@withContext null
        val assets = json.optJSONArray("assets") ?: return@withContext null
        val apk = (0 until assets.length()).mapNotNull { assets.optJSONObject(it) }
            .let { list -> list.firstOrNull { it.optString("name") == "Alsaif.apk" } ?: list.firstOrNull { it.optString("name").endsWith(".apk") } } ?: return@withContext null
        Release(build, json.optString("name").ifBlank { "1.$build" }, apk.optString("browser_download_url"))
    }

    /** Call from the main screen; [manual] = the user pressed "check for updates". */
    fun check(activity: AppCompatActivity, manual: Boolean) {
        if (!manual) {
            if (checkedThisSession) return
            checkedThisSession = true
        }
        activity.lifecycleScope.launch {
            val r = (try {
                latest()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (manual) toast(activity, "ما قدرت أتحقق من التحديثات. تأكد من النت")
                null
            }) ?: return@launch
            if (r.build <= BuildConfig.VERSION_CODE) {
                if (manual) toast(activity, "عندك آخر نسخة ✓  (${BuildConfig.VERSION_NAME})")
                return@launch
            }
            if (activity.isFinishing) return@launch
            AlertDialog.Builder(activity)
                .setTitle("⬆ في تحديث جديد")
                .setMessage("النسخة الجديدة: ${r.name}\nنسختك الحالية: ${BuildConfig.VERSION_NAME}\n\nسيرفراتك وإعداداتك بتضل مثل ما هي.")
                .setPositiveButton("تحديث الآن") { _, _ -> download(activity, r) }
                .setNegativeButton("لاحقاً", null)
                .show()
        }
    }

    private fun download(activity: AppCompatActivity, r: Release) {
        val d = activity.resources.displayMetrics.density
        val bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            setPadding((24 * d).toInt(), (16 * d).toInt(), (24 * d).toInt(), (8 * d).toInt())
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle("جاري تنزيل التحديث…")
            .setView(bar)
            .setCancelable(false)
            .setNegativeButton("إلغاء", null)
            .create()
        val job = activity.lifecycleScope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    // Android 7+ shares the file through a FileProvider; older versions need a readable location.
                    val base = if (Build.VERSION.SDK_INT >= 24) activity.cacheDir else (activity.externalCacheDir ?: activity.cacheDir)
                    val dir = File(base, "updates").apply { mkdirs() }
                    val f = File(dir, "Alsaif.apk")
                    f.delete()
                    Http.client.newCall(Request.Builder().url(r.apkUrl).header("User-Agent", "TVPlayer-Updater").build()).execute().use { resp ->
                        if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                        val body = resp.body ?: throw IOException("الملف فاضي")
                        val total = body.contentLength()
                        var done = 0L
                        var shown = -1
                        body.byteStream().use { input ->
                            f.outputStream().use { out ->
                                val buf = ByteArray(64 * 1024)
                                while (true) {
                                    if (!isActive) throw CancellationException()
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    out.write(buf, 0, n)
                                    done += n
                                    if (total > 0) {
                                        val p = (done * 100 / total).toInt()
                                        if (p != shown) {
                                            shown = p
                                            withContext(Dispatchers.Main) { bar.progress = p }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    f
                }
                dialog.dismiss()
                install(activity, file)
            } catch (e: CancellationException) {
                dialog.dismiss()
            } catch (e: Exception) {
                dialog.dismiss()
                toast(activity, "فشل تنزيل التحديث: ${e.message}")
            }
        }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                job.cancel()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun install(activity: Activity, file: File) {
        if (Build.VERSION.SDK_INT >= 26 && !activity.packageManager.canRequestPackageInstalls()) {
            // Android asks once for permission to install updates from this app.
            pendingFile = file
            AlertDialog.Builder(activity)
                .setTitle("خطوة وحدة")
                .setMessage("عشان التطبيق يقدر يثبّت التحديث، فعّل \"السماح من هذا المصدر\" بالشاشة الجاية، وبعدين ارجع.")
                .setPositiveButton("فتح الإعدادات") { _, _ ->
                    try {
                        activity.startActivity(
                            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
                        )
                    } catch (e: ActivityNotFoundException) {
                        try {
                            activity.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
                        } catch (e2: ActivityNotFoundException) {
                            toast(activity, "افتح إعدادات الجهاز واسمح لـ ${activity.getString(com.tvplayer.app.R.string.app_name)} بتثبيت التطبيقات")
                        }
                    }
                }
                .setNegativeButton("إلغاء") { _, _ -> pendingFile = null }
                .show()
            return
        }
        val uri = if (Build.VERSION.SDK_INT >= 24) {
            FileProvider.getUriForFile(activity, "${activity.packageName}.files", file)
        } else {
            file.setReadable(true, false)
            Uri.fromFile(file)
        }
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            activity.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            toast(activity, "ما لقيت مثبّت التطبيقات على هذا الجهاز")
        }
    }

    /** After coming back from the permission screen, continue the install. */
    fun resumePending(activity: Activity) {
        val f = pendingFile ?: return
        if (Build.VERSION.SDK_INT < 26 || activity.packageManager.canRequestPackageInstalls()) {
            pendingFile = null
            install(activity, f)
        }
    }

    private fun toast(activity: Activity, text: String) =
        Toast.makeText(activity, text, Toast.LENGTH_LONG).show()
}
