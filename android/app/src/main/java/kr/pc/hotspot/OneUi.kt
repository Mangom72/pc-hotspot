package kr.pc.hotspot

import android.content.Context
import android.content.res.Configuration
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.widget.*

/** Native Views, system font and scalable text; One UI layout principles. */
class OneUi(private val c: Context) {
    val dark = c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    val background = Color.parseColor(if (dark) "#000000" else "#F3F3F3")
    val surface = Color.parseColor(if (dark) "#171717" else "#FFFFFF")
    val text = Color.parseColor(if (dark) "#F4F4F4" else "#171717")
    val secondary = Color.parseColor(if (dark) "#B8B8B8" else "#616161")
    val accent = Color.parseColor(if (dark) "#9BBEFF" else "#245BC2")
    fun dp(n: Int) = (n * c.resources.displayMetrics.density).toInt()
    fun shape(color: Int, radius: Int = 24) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    fun label(value: String, size: Float = 15f, muted: Boolean = false) = TextView(c).apply {
        this.text = value; textSize = size; setTextColor(if (muted) secondary else this@OneUi.text)
        setLineSpacing(dp(3).toFloat(), 1f)
    }
    fun section(parent: LinearLayout, title: String): LinearLayout {
        parent.addView(label(title, 14f, true).apply { setPadding(dp(12), dp(24), dp(12), dp(10)) })
        return LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL; background = shape(surface)
            setPadding(dp(20), dp(18), dp(20), dp(18))
            parent.addView(this, LinearLayout.LayoutParams(-1, -2))
        }
    }
    fun button(parent: LinearLayout, title: String, primary: Boolean = false, action: () -> Unit): Button {
        return Button(c).apply {
            text = title; isAllCaps = false; textSize = 16f; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(if (primary) Color.WHITE else accent)
            background = RippleDrawable(ColorStateList.valueOf(0x223377FF), shape(if (primary) Color.parseColor("#245BC2") else surface, 18), null)
            minHeight = dp(52); minimumHeight = dp(52)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setOnClickListener { action() }
            parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        }
    }
    fun toggle(parent: LinearLayout, title: String, detail: String, checked: Boolean, action: (Boolean) -> Unit): Switch {
        val row = LinearLayout(c).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, dp(8)) }
        val copy = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        copy.addView(label(title, 17f)); copy.addView(label(detail, 13f, true))
        row.addView(copy, LinearLayout.LayoutParams(0, -2, 1f))
        return Switch(c).apply {
            isChecked = checked; contentDescription = title; minHeight = dp(48); minWidth = dp(48)
            thumbTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(accent, secondary))
            setOnCheckedChangeListener { _, value -> action(value) }
            row.addView(this); parent.addView(row)
        }
    }
}
