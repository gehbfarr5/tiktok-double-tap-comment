package dev.tiktok.doubletapcomment.ui

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import dev.tiktok.doubletapcomment.R

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val density = resources.displayMetrics.density
        val pad = (24 * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(getColor(R.color.bg))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        root.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 24f
            setTextColor(getColor(R.color.text_primary))
        })

        root.addView(TextView(this).apply {
            text = "Enable this module in LSPosed, select TikTok in scope, then force-stop and relaunch TikTok."
            textSize = 16f
            setTextColor(getColor(R.color.text_secondary))
            setPadding(0, (12 * density).toInt(), 0, 0)
        })

        root.addView(TextView(this).apply {
            text = "Behavior: feed double-tap opens comments and no longer triggers like."
            textSize = 16f
            setTextColor(getColor(R.color.accent))
            setPadding(0, (18 * density).toInt(), 0, 0)
        })

        setContentView(root)
    }
}
