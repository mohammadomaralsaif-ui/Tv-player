package com.tvplayer.app.ui

import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.tvplayer.app.BuildConfig
import com.tvplayer.app.data.Http
import com.tvplayer.app.data.Library
import com.tvplayer.app.data.ProfileStore
import com.tvplayer.app.databinding.ActivitySettingsBinding
import java.io.File

class SettingsActivity : AppCompatActivity() {
    private lateinit var b: ActivitySettingsBinding
    private lateinit var store: ProfileStore

    private val qualityCaps = intArrayOf(0, 2160, 1080, 720, 480, 360)
    private val qualityNames = arrayOf("تلقائي (أفضل جودة)", "4K كحد أقصى", "Full HD 1080p كحد أقصى", "HD 720p كحد أقصى", "480p (توفير نت)", "360p")
    private val subScales = floatArrayOf(0.75f, 1f, 1.3f, 1.6f, 2f)
    private val subNames = arrayOf("صغير", "عادي", "كبير", "كبير جداً", "ضخم")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(b.root)
        store = ProfileStore(this)
        b.btnBack.setOnClickListener { finish() }
        b.pin.setOnClickListener { onPin() }
        b.lockApp.setOnClickListener { toggleLock(app = true) }
        b.lockAdult.setOnClickListener { toggleLock(app = false) }
        b.quality.setOnClickListener {
            AlertDialog.Builder(this).setTitle("الجودة الافتراضية")
                .setSingleChoiceItems(qualityNames, qualityCaps.indexOf(store.maxQuality).coerceAtLeast(0)) { d, w ->
                    store.maxQuality = qualityCaps[w]; d.dismiss(); refresh()
                }.show()
        }
        b.subSize.setOnClickListener {
            AlertDialog.Builder(this).setTitle("حجم الترجمة")
                .setSingleChoiceItems(subNames, subScales.indexOfFirst { it == store.subtitleScale }.coerceAtLeast(1)) { d, w ->
                    store.subtitleScale = subScales[w]; d.dismiss(); refresh()
                }.show()
        }
        b.clearCache.setOnClickListener {
            Http.clearCache()
            cacheDir.listFiles()?.filter { it.name.startsWith("playlist_") }?.forEach(File::delete)
            Library.clear()
            Toast.makeText(this, "انمسحت الذاكرة المؤقتة. القوائم رح تتحمّل من جديد", Toast.LENGTH_SHORT).show()
        }
        b.about.text = "TV Player ${BuildConfig.VERSION_NAME}  •  هذا الجهاز: ${if (Device.isTv(this)) "تلفزيون / TV Box" else "موبايل / تابلت"}"
        refresh()
        b.pin.requestFocus()
    }

    private fun refresh() {
        b.pin.text = if (store.hasPin) "🔑  رمز PIN: مضبوط  (تغيير / إزالة)" else "🔑  رمز PIN: غير مضبوط  (اضغط للتعيين)"
        setToggle(b.lockApp, "🔒  قفل التطبيق عند الفتح", store.lockApp)
        setToggle(b.lockAdult, "🔞  قفل تصنيفات الكبار", store.lockAdult)
        b.quality.text = "🎞  الجودة الافتراضية: ${qualityNames[qualityCaps.indexOf(store.maxQuality).coerceAtLeast(0)]}"
        b.subSize.text = "🔠  حجم الترجمة: ${subNames[subScales.indexOfFirst { it == store.subtitleScale }.coerceAtLeast(1)]}"
    }

    private fun setToggle(btn: Button, label: String, on: Boolean) {
        btn.text = "$label:  ${if (on) "مفعّل ✓" else "مطفي"}"
        btn.isActivated = on
    }

    private fun onPin() {
        if (!store.hasPin) {
            Pin.create(this) { pin -> store.setPin(pin); toast("انحفظ الرمز ✓"); refresh() }
            return
        }
        Pin.ask(this, "أدخل الرمز الحالي") {
            AlertDialog.Builder(this)
                .setItems(arrayOf("تغيير الرمز", "إزالة الرمز (وإلغاء كل الأقفال)")) { _, w ->
                    if (w == 0) Pin.create(this) { pin -> store.setPin(pin); toast("انحفظ الرمز الجديد ✓"); refresh() }
                    else { store.setPin(null); Pin.adultUnlocked = false; toast("انشال الرمز"); refresh() }
                }.show()
        }
    }

    private fun toggleLock(app: Boolean) {
        val current = if (app) store.lockApp else store.lockAdult
        val apply = {
            if (app) store.lockApp = !current else store.lockAdult = !current
            if (!app) Pin.adultUnlocked = false
            refresh()
        }
        when {
            !store.hasPin -> Pin.create(this) { pin -> store.setPin(pin); apply() }
            current -> Pin.ask(this, "أدخل الرمز لإلغاء القفل") { apply() }
            else -> apply()
        }
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()

}
