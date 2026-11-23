package uz.olim.dovud.ui

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Oddiy ekran qurish yordamchilari (XML'siz). */
object Ui {
    fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    fun column(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        val p = dp(ctx, 16); setPadding(p, p, p, p)
    }

    fun scroll(ctx: Context, child: View) = ScrollView(ctx).apply { addView(child) }

    fun title(ctx: Context, t: String) = TextView(ctx).apply {
        text = t; textSize = 22f; setTypeface(typeface, Typeface.BOLD); setPadding(0, 0, 0, dp(ctx, 8))
    }

    fun text(ctx: Context, t: String, size: Float = 15f) = TextView(ctx).apply { text = t; textSize = size; setPadding(0, dp(ctx, 4), 0, dp(ctx, 4)) }

    fun button(ctx: Context, t: String, onClick: () -> Unit) = Button(ctx).apply {
        text = t; isAllCaps = false; textSize = 16f
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(ctx, 6) }
    }

    fun row(ctx: Context) = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
}
