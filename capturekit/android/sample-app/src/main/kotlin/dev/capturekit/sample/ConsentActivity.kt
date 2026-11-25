package dev.capturekit.sample

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import dev.capturekit.consent.ConsentStore

/**
 * Consent screen. The text (res/raw/consent_v1.txt, a placeholder pending a legal owner) scrolls; the two
 * optional choices (upload, anonymous telemetry) sit at the end of it and both start off. Accept stays
 * disabled until the user has scrolled to the end. Decline just leaves: no consent, no scanning.
 */
class ConsentActivity : Activity() {
    private lateinit var scroll: ScrollView
    private lateinit var accept: Button
    private lateinit var hint: TextView
    private lateinit var upload: Switch
    private lateinit var telemetry: Switch
    private var reachedEnd = false
    private var accepting = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val p = UiKit.c(this)
        val text = resources.openRawResource(
            resources.getIdentifier(ConsentStore.TEXT_RESOURCE, "raw", packageName).also { check(it != 0) { "missing raw/${ConsentStore.TEXT_RESOURCE}" } }).bufferedReader().use { it.readText() }
        val (head, rest) = text.lines().let { it.first() to it.drop(1).joinToString("\n").trim() }

        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(UiKit.largeTitle(this, "Before you scan").apply { isFocusable = true }, lp(Tokens.S16))
        content.addView(InlineAlert(this).apply { set(head.lowercase().replaceFirstChar { it.uppercase() }, UiKit.Kind.WARN) }, lp(Tokens.S24))
        content.addView(UiKit.body(this, rest), lp(Tokens.S24))
        upload = toggle("Allow uploading my finished scans (optional)")
        telemetry = toggle("Allow anonymous crash and usage reports (optional)")
        content.addView(UiKit.group(this).apply { addView(upload); addView(telemetry) }, lp(Tokens.S24))

        scroll = ScrollView(this).apply {
            isFillViewport = false
            isVerticalScrollBarEnabled = false
            contentDescription = "Consent text. Scroll to the end to enable the Accept button."
            addView(content)
            setOnScrollChangeListener { _, _, _, _, _ -> checkEnd() }
            viewTreeObserver.addOnGlobalLayoutListener { checkEnd() }   // text shorter than the screen: nothing to scroll
            UiKit.applyInsets(this, Tokens.SCREEN_MARGIN, bottom = false)
        }

        hint = UiKit.footnote(this, "").apply { gravity = android.view.Gravity.CENTER }
        accept = UiKit.primaryButton(this, "Accept and continue").apply {
            contentDescription = "Accept and continue. Available after you scroll to the end of the consent text."
            setOnClickListener { onAccept() }
        }
        val decline = UiKit.textButton(this, "Decline").apply {
            contentDescription = "Decline. You will not be able to scan without consent."
            setOnClickListener { setResult(RESULT_CANCELED); finish() }
        }
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(hint, lp(Tokens.S8))
            addView(UiKit.sheetActions(this@ConsentActivity, accept, decline), lp(0))
        }
        val bar = UiKit.bottomActionBar(this, actions)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(p.bg)
            addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(bar, lp(0))
        }
        setContentView(root)
        paint()
    }

    private fun checkEnd() {
        if (reachedEnd) return
        if (!scroll.canScrollVertically(1) && scroll.getChildAt(0).height > 0) {
            reachedEnd = true
            paint()
            accept.announceForAccessibility("Accept is now available")
        }
    }

    private fun paint() {
        accept.isEnabled = reachedEnd
        UiKit.paintButton(accept, UiKit.ButtonStyle.PRIMARY)
        hint.text = if (reachedEnd) "You can accept now. Both optional choices are off unless you turned them on."
            else "Scroll down and read to the end to enable Accept."
    }

    private fun onAccept() {
        if (!reachedEnd || accepting) return
        accepting = true
        accept.isEnabled = false
        ConsentStore.get(this).accept(System.currentTimeMillis(), upload.isChecked, telemetry.isChecked)
        setResult(RESULT_OK)
        if (intent.getBooleanExtra(EXTRA_START_SCAN, false)) startActivity(Intent(this, CaptureActivity::class.java))
        finish()
    }

    private fun toggle(label: String) = UiKit.toggle(this, false).apply {
        text = label; UiKit.type(this, Tokens.BODY); setTextColor(UiKit.c(context).label); isChecked = false
        minHeight = dp(Tokens.ROW_MIN); setPadding(dp(Tokens.S16), dp(Tokens.S8), dp(Tokens.S16), dp(Tokens.S8))
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun lp(bottom: Int) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        .apply { bottomMargin = dp(bottom) }

    companion object {
        const val EXTRA_START_SCAN = "start_scan"
    }
}
