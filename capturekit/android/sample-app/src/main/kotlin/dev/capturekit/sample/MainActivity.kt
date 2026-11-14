package dev.capturekit.sample

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout

/** Launcher: capture session (Phase 6) and Phase 0 tools (raw recorder, clock check). */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun add(label: String, cls: Class<*>) = root.addView(Button(this).apply {
            text = label
            setOnClickListener { startActivity(Intent(this@MainActivity, cls)) }
        })
        add("Capture (core session)", CaptureActivity::class.java)
        add("Raw recorder", RecorderActivity::class.java)
        add("Clock check", ClockCheckActivity::class.java)
        setContentView(root)
    }
}
