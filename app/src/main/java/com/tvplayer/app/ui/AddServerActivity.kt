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
        b.btnSave.setOnClickListener { save() }
        b.btnCancel.setOnClickListener { finish() }
    }

    private fun selectedType() = when (b.typeGroup.checkedRadioButtonId) {
        R.id.rbM3u -> ServerType.M3U
        R.id.rbDirect -> ServerType.DIRECT
        else -> ServerType.XTREAM
    }

    private fun updateFields() {
        val t = selectedType()
        b.xtreamFields.isVisible = t == ServerType.XTREAM
        b.url.hint = when (t) {
            ServerType.XTREAM -> "رابط السيرفر  (مثال: http://example.com:8080)"
            ServerType.M3U -> "رابط قائمة M3U"
            ServerType.DIRECT -> "رابط البث (m3u8 / ts / mp4 / rtsp …)"
        }
    }

    private fun save() {
        val type = selectedType()
        var url = b.url.text.toString().trim()
        var user = b.username.text.toString().trim()
        var pass = b.password.text.toString().trim()
        if (url.isEmpty()) { b.url.error = "مطلوب"; b.url.requestFocus(); return }

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

        if (type != ServerType.XTREAM) {
            store.save(profile); finish(); return
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
