package com.tvplayer.app.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import com.tvplayer.app.data.Channel
import com.tvplayer.app.data.M3uParser
import com.tvplayer.app.data.PlayerQueue
import com.tvplayer.app.data.ProfileStore
import com.tvplayer.app.data.ServerProfile
import com.tvplayer.app.data.ServerType
import com.tvplayer.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var b: ActivityMainBinding
    private lateinit var store: ProfileStore
    private val adapter = ProfileAdapter(onClick = { open(it) }, onLongClick = { options(it) })

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        store = ProfileStore(this)
        b.list.layoutManager = LinearLayoutManager(this)
        b.list.adapter = adapter
        b.btnAdd.setOnClickListener { startActivity(Intent(this, AddServerActivity::class.java)) }
        b.btnSettings.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
    }

    private var askingPin = false

    override fun onResume() {
        super.onResume()
        if (store.lockApp && !Pin.appUnlocked) {
            b.root.visibility = android.view.View.INVISIBLE
            if (!askingPin) {
                askingPin = true
                Pin.ask(this, "🔒 أدخل رمز PIN", cancelable = false, onCancel = { finish() }) {
                    askingPin = false
                    Pin.appUnlocked = true
                    b.root.visibility = android.view.View.VISIBLE
        Updater.resumePending(this)
        Updater.check(this, manual = false)
                    onResume()
                }
            }
            return
        }
        b.root.visibility = android.view.View.VISIBLE
        val list = store.all()
        adapter.submit(list)
        b.empty.isVisible = list.isEmpty()
        if (list.isEmpty()) b.btnAdd.requestFocus() else b.list.post { b.list.getChildAt(0)?.requestFocus() }
    }

    private fun open(p: ServerProfile) {
        if (p.type == ServerType.DIRECT) {
            val ch = Channel(
                id = p.id, name = p.name, logo = null, group = "",
                url = p.url, kind = M3uParser.guessKind(p.url),
            )
            Nav.play(this, p, listOf(ch), 0)
        } else {
            startActivity(Intent(this, BrowserActivity::class.java).putExtra(BrowserActivity.EXTRA_PROFILE, p.id))
        }
    }

    private fun options(p: ServerProfile) {
        AlertDialog.Builder(this)
            .setTitle(p.name)
            .setItems(arrayOf("تعديل", "حذف")) { _, which ->
                if (which == 0) {
                    startActivity(Intent(this, AddServerActivity::class.java).putExtra(AddServerActivity.EXTRA_ID, p.id))
                } else {
                    AlertDialog.Builder(this)
                        .setMessage("حذف \"${p.name}\"؟")
                        .setPositiveButton("حذف") { _, _ -> store.delete(p.id); onResume() }
                        .setNegativeButton("إلغاء", null)
                        .show()
                }
            }
            .show()
    }
}
