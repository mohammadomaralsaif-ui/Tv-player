package com.tvplayer.app.ui

import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.tvplayer.app.R
import com.tvplayer.app.data.Http
import com.tvplayer.app.data.ProfileStore
import com.tvplayer.app.data.ServerDetector
import com.tvplayer.app.data.ServerProfile
import com.tvplayer.app.data.ServerType
import com.tvplayer.app.data.XtreamApi
import com.tvplayer.app.databinding.ActivityAddServerBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.UUID

class AddServerActivity : AppCompatActivity() {
    companion object { const val EXTRA_ID = "id" }

    private lateinit var b: ActivityAddServerBinding
    private lateinit var store: ProfileStore
    private var existing: ServerProfile? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityAddServerBinding.inflate(layoutInflater)
        setContentView(b.root)
        store = ProfileStore(this)
        existing = store.get(intent.getStringExtra(EXTRA_ID))

        b.typeGroup.setOnCheckedChangeListener { _, _ -> updateFields() }
        existing?.let { p ->
            b.heading.text = "تعديل السيرفر"
            b.typeGroup.check(
                when (p.type) {
                    ServerType.XTREAM -> R.id.rbXtream
                    ServerType.M3U -> R.id.rbM3u
                    ServerType.DIRECT -> R.id.rbDirect
                }
            )
            b.name.setText(p.name)
            b.url.setText(p.url)
            b.username.setText(p.username)
            b.password.setText(p.password)
            b.userAgent.setText(p.userAgent)
            b.formatGroup.check(if (p.liveFormat == "ts") R.id.rbTs else R.id.rbHls)
        }
        updateFields()
        b.showPassword.setOnCheckedChangeListener { _, show ->
            b.password.inputType = android.text.InputType.TYPE_CLASS_TEXT or
                (if (show) android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD else android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD)
            b.password.setSelection(b.password.text.length)
        }
        b.btnSave.setOnClickListener { save() }
        b.btnCancel.setOnClickListener { finish() }
    }

    /** null = detect automatically. */
    private fun selectedType(): ServerType? = when (b.typeGroup.checkedRadioButtonId) {
        R.id.rbXtream -> ServerType.XTREAM
        R.id.rbM3u -> ServerType.M3U
        R.id.rbDirect -> ServerType.DIRECT
        else -> null
    }

    private fun updateFields() {
        val t = selectedType()
        b.xtreamFields.isVisible = t == null || t == ServerType.XTREAM
        b.autoHint.isVisible = t == null
        b.username.hint = if (t == null) "اسم المستخدم (إذا في)" else getString(R.string.username)
        b.password.hint = if (t == null) "كلمة المرور (إذا في)" else getString(R.string.password)
        b.url.hint = when (t) {
            null -> "الصق أي رابط: سيرفر، قائمة M3U، أو رابط بث"
            ServerType.XTREAM -> "رابط السيرفر  (مثال: http://example.com:8080)"
            ServerType.M3U -> "رابط قائمة M3U"
            ServerType.DIRECT -> "رابط البث (m3u8 / ts / mp4 / rtsp …)"
        }
    }

    private fun save() {
        val url = b.url.text.toString().trim()
        val user = b.username.text.toString().trim()
        val pass = b.password.text.toString().trim()
        if (url.isEmpty()) { b.url.error = "مطلوب"; b.url.requestFocus(); return }
        val type = selectedType()
        if (type != null) { saveAs(type, url, user, pass); return }

        // Automatic: find out what the link is first.
        b.progress.isVisible = true
        b.btnSave.isEnabled = false
        b.detectStatus.isVisible = true
        b.detectStatus.text = "جاري فحص الرابط…"
        lifecycleScope.launch {
            try {
                val r = ServerDetector.detect(url, user, pass, b.userAgent.text.toString().trim())
                b.detectStatus.text = "اكتشفت: ${r.label} ✓"
                b.typeGroup.check(
                    when (r.type) {
                        ServerType.XTREAM -> R.id.rbXtream
                        ServerType.M3U -> R.id.rbM3u
                        ServerType.DIRECT -> R.id.rbDirect
                    }
                )
                b.progress.isVisible = false
                b.btnSave.isEnabled = true
                saveAs(r.type, r.url, r.username, r.password, alreadyChecked = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                b.progress.isVisible = false
                b.btnSave.isEnabled = true
                b.detectStatus.text = e.message ?: "ما قدرت أفحص الرابط"
            }
        }
    }

    private fun saveAs(type: ServerType, rawUrl: String, rawUser: String, rawPass: String, alreadyChecked: Boolean = false) {
        var url = rawUrl
        var user = rawUser
        var pass = rawPass

        if (type == ServerType.XTREAM) {
            // If a full get.php?username=..&password=.. link was pasted, pull the credentials out of it.
            val uri = Uri.parse(if (url.contains("://")) url else "http://$url")
            if (user.isEmpty()) user = uri.getQueryParameter("username").orEmpty()
            if (pass.isEmpty()) pass = uri.getQueryParameter("password").orEmpty()
            if (user.isEmpty() || pass.isEmpty()) {
                Toast.makeText(this, "أدخل اسم المستخدم وكلمة المرور", Toast.LENGTH_LONG).show()
                return
            }
            url = XtreamApi.normalizeBase(url)
        } else {
            url = Http.fixUrl(url)
        }

        val name = b.name.text.toString().trim().ifEmpty {
            Uri.parse(url).host ?: "سيرفر"
        }
        val profile = ServerProfile(
            id = existing?.id ?: UUID.randomUUID().toString(),
            name = name,
            type = type,
            url = url,
            username = user,
            password = pass,
            userAgent = b.userAgent.text.toString().trim(),
            liveFormat = if (b.formatGroup.checkedRadioButtonId == R.id.rbTs) "ts" else "m3u8",
        )

        if (type != ServerType.XTREAM || alreadyChecked) {
            store.save(profile)
            if (alreadyChecked) Toast.makeText(this, "انحفظ ✓", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        b.progress.isVisible = true
        b.btnSave.isEnabled = false
        lifecycleScope.launch {
            try {
                val info = XtreamApi(profile).login()
                store.save(profile)
                Toast.makeText(this@AddServerActivity, "تم الاتصال ✓  $info", Toast.LENGTH_LONG).show()
                finish()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                b.progress.isVisible = false
                b.btnSave.isEnabled = true
                AlertDialog.Builder(this@AddServerActivity)
                    .setTitle("فشل الاتصال")
                    .setMessage(e.message ?: e.toString())
                    .setPositiveButton("حفظ على أي حال") { _, _ -> store.save(profile); finish() }
                    .setNegativeButton("رجوع", null)
                    .show()
            }
        }
    }
}
