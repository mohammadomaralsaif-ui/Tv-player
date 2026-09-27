package com.tvplayer.app.ui

import android.app.Activity
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.tvplayer.app.data.ProfileStore

/** PIN prompts, plus which categories count as adult content. */
object Pin {
    /** Unlocked for this app session (until the app is closed). */
    var appUnlocked = false
    var adultUnlocked = false

    private val ADULT = Regex(
        """(adult|xxx|porn|\bsex\b|18\s*\+|\+\s*18|\bfor adults\b|للكبار|كبار فقط|\+١٨|١٨\+)""",
        RegexOption.IGNORE_CASE,
    )

    fun isAdult(name: String?): Boolean = name != null && ADULT.containsMatchIn(name)

    /** Whether an item belongs to an adult category (uses the categories already loaded). */
    fun isAdultItem(c: com.tvplayer.app.data.Channel, xtream: Boolean): Boolean {
        if (isAdult(c.name)) return true
        if (!xtream) return isAdult(c.group)
        val sec = when (c.kind) {
            com.tvplayer.app.data.ItemKind.LIVE -> com.tvplayer.app.data.XtreamApi.Section.LIVE
            com.tvplayer.app.data.ItemKind.MOVIE -> com.tvplayer.app.data.XtreamApi.Section.MOVIES
            com.tvplayer.app.data.ItemKind.SERIES -> com.tvplayer.app.data.XtreamApi.Section.SERIES
            com.tvplayer.app.data.ItemKind.EPISODE -> return false
        }
        return com.tvplayer.app.data.Library.categories[sec]?.firstOrNull { it.id == c.group }?.let { isAdult(it.name) } ?: false
    }

    /** Shows a PIN box; calls [onOk] when the right PIN is entered. */
    fun ask(
        activity: Activity,
        title: String,
        cancelable: Boolean = true,
        onCancel: (() -> Unit)? = null,
        onOk: () -> Unit,
    ) {
        val store = ProfileStore(activity)
        val input = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            gravity = Gravity.CENTER
            textSize = 26f
            hint = "••••"
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle(title)
            .setView(input)
            .setCancelable(cancelable)
            .setPositiveButton("فتح", null)
            .setNegativeButton(if (cancelable) "إلغاء" else "خروج") { _, _ -> onCancel?.invoke() }
            .create()
        dialog.setOnShowListener {
            input.requestFocus()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (store.checkPin(input.text.toString())) {
                    dialog.dismiss()
                    onOk()
                } else {
                    input.text.clear()
                    input.error = "الرمز غلط"
                }
            }
        }
        if (cancelable) dialog.setOnCancelListener { onCancel?.invoke() }
        dialog.show()
    }

    /** Asks for a new PIN twice; calls [onSet] with it. */
    fun create(activity: Activity, onSet: (String) -> Unit) {
        val first = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            gravity = Gravity.CENTER
            textSize = 26f
            hint = "4 أرقام أو أكثر"
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle("رمز PIN جديد")
            .setView(first)
            .setPositiveButton("التالي", null)
            .setNegativeButton("إلغاء", null)
            .create()
        dialog.setOnShowListener {
            first.requestFocus()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val pin = first.text.toString()
                if (pin.length < 4) { first.error = "لازم 4 أرقام على الأقل"; return@setOnClickListener }
                dialog.dismiss()
                val again = EditText(activity).apply {
                    inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
                    gravity = Gravity.CENTER
                    textSize = 26f
                }
                AlertDialog.Builder(activity)
                    .setTitle("أعد كتابة الرمز")
                    .setView(again)
                    .setPositiveButton("حفظ") { _, _ ->
                        if (again.text.toString() == pin) onSet(pin)
                        else Toast.makeText(activity, "الرمزين مش متطابقين، جرّب مرة ثانية", Toast.LENGTH_LONG).show()
                    }
                    .setNegativeButton("إلغاء", null)
                    .show()
            }
        }
        dialog.show()
    }
}
