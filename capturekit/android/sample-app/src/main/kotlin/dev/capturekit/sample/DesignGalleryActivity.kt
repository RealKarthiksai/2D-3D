package dev.capturekit.sample

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView

/**
 * Debug design gallery (reachable only from Settings > Developer in debuggable builds): every component and state in
 * the light palette, the dark palette and over the camera, for side-by-side screenshots.
 */
class DesignGalleryActivity : Activity() {
    /** Camera-screen context: always dark, like the capture HUD. */
    private class CameraContext(base: Context) : ContextThemeWrapper(base, R.style.AppTheme_Camera), AlwaysDark

    private fun modeContext(night: Boolean): Context {
        val cfg = Configuration(resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                (if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
        }
        // The override must be applied before the wrapper's resources are first read.
        return ContextThemeWrapper(this, R.style.AppTheme).apply { applyOverrideConfiguration(cfg) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(UiKit.largeTitle(this, "Design gallery"), lp(this, Tokens.S24))
        col.addView(panel(modeContext(night = false), "Light"), lp(this, Tokens.S24))
        col.addView(panel(modeContext(night = true), "Dark"), lp(this, Tokens.S24))
        col.addView(cameraPanel(CameraContext(this)), lp(this, Tokens.S24))
        setContentView(UiKit.page(this, ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(col)
            UiKit.applyInsets(this, Tokens.SCREEN_MARGIN)
        }))
    }

    private fun Context.px(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun lp(ctx: Context, bottom: Int) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        .apply { bottomMargin = ctx.px(bottom) }

    private fun panel(ctx: Context, name: String): View = LinearLayout(ctx).apply {
        val p = UiKit.c(ctx)
        orientation = LinearLayout.VERTICAL
        background = UiKit.surface(ctx, p.bg, Tokens.R_SHEET)
        val pad = ctx.px(Tokens.S16)
        setPadding(pad, pad, pad, pad)
        fun add(v: View, bottom: Int = Tokens.S16) = addView(v, lp(ctx, bottom))

        add(UiKit.sectionHeader(ctx, name), Tokens.S8)
        add(UiKit.largeTitle(ctx, "Large title"), Tokens.S4)
        add(UiKit.title(ctx, "Title"), Tokens.S4)
        add(UiKit.headline(ctx, "Headline"), Tokens.S4)
        add(UiKit.body(ctx, "Body text in the secondary label colour."), Tokens.S4)
        add(UiKit.callout(ctx, "Callout"), Tokens.S4)
        add(UiKit.footnote(ctx, "Footnote"))

        add(UiKit.primaryButton(ctx, "Primary"), Tokens.S8)
        add(UiKit.secondaryButton(ctx, "Secondary"), Tokens.S8)
        add(UiKit.textButton(ctx, "Text button"), Tokens.S8)
        add(UiKit.primaryButton(ctx, "Disabled").apply { isEnabled = false; UiKit.paintButton(this, UiKit.ButtonStyle.PRIMARY) })

        add(UiKit.group(ctx).apply {
            addView(UiKit.listRow(ctx, R.drawable.ic_voice, "Row with switch", "Subtitle", trailing = UiKit.toggle(ctx, true), chevron = false) {})
            addView(UiKit.listRow(ctx, R.drawable.ic_settings, "Tappable row", "Opens something") {})
            addView(UiKit.listRow(ctx, null, "Destructive", titleColor = p.danger, chevron = false) {})
        })

        for ((t, k) in listOf("Passed" to UiKit.Kind.OK, "Usable, with notes" to UiKit.Kind.WARN, "Failed" to UiKit.Kind.ERROR, "Not checked" to UiKit.Kind.INFO))
            add(StatusBadge(ctx).apply { set(t, k, hollow = k == UiKit.Kind.INFO) }, Tokens.S8)

        add(InlineAlert(ctx).apply { set("CaptureKit uses the camera to see the room.", UiKit.Kind.ERROR, "Allow camera", {}, title = "Camera access needed") }, Tokens.S8)
        add(InlineAlert(ctx).apply { set("Uploading is off.", UiKit.Kind.WARN) }, Tokens.S8)
        add(InlineAlert(ctx).apply { set("Your last scan was recovered and saved.", UiKit.Kind.OK, "Dismiss", {}) })

        add(LinearProgress(ctx).apply { fraction = 0.4f })
        add(UiKit.group(ctx).apply { addView(UiKit.skeletonRow(ctx)) })
        add(UiKit.emptyState(ctx, R.drawable.ic_cube, "No scans yet", "Scan a room and it shows up here with its check result."))

        add(ChecklistView(ctx).apply { set(RoomProgress.from(floor = false, ceiling = false, wallPcts = emptyList(), shellClosed = false)) })
        add(ChecklistView(ctx).apply { set(RoomProgress.from(floor = true, ceiling = false, wallPcts = listOf(90, 45, 5), shellClosed = false)) })
        add(ChecklistView(ctx).apply { set(RoomProgress.from(floor = true, ceiling = true, wallPcts = listOf(90, 85, 80, 60), shellClosed = true)) })

        for (s in ScanUi.ScanStage.values()) add(StageRow(ctx).apply { set(s, fraction = if (s == ScanUi.ScanStage.UPLOADING) 0.6f else -1f) }, Tokens.S8)
        add(RoomSummaryCard(ctx).apply { set(14.2, 4, 2.6) })
        add(UiKit.secondaryButton(ctx, "Open a sheet").apply {
            setOnClickListener {
                UiKit.sheet(this@DesignGalleryActivity) { content, dlg ->
                    content.addView(UiKit.title(this@DesignGalleryActivity, "Sheet title"))
                    content.addView(UiKit.body(this@DesignGalleryActivity, "Sheets replace dialogs. They follow the screen's palette."))
                    content.addView(UiKit.sheetActions(this@DesignGalleryActivity,
                        UiKit.primaryButton(this@DesignGalleryActivity, "Primary").apply { setOnClickListener { dlg.dismiss() } },
                        UiKit.secondaryButton(this@DesignGalleryActivity, "Cancel").apply { setOnClickListener { dlg.dismiss() } }),
                        lp(this@DesignGalleryActivity, 0).apply { topMargin = px(Tokens.S24) })
                }
            }
        }, 0)
    }

    /** Camera-only components on a black stand-in for the preview. */
    private fun cameraPanel(ctx: Context): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = UiKit.surface(ctx, Tokens.BLACK, Tokens.R_SHEET)
        val pad = ctx.px(Tokens.S16)
        setPadding(pad, pad, pad, pad)
        fun add(v: View, bottom: Int = Tokens.S12) = addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = ctx.px(bottom) })
        add(UiKit.sectionHeader(ctx, "Camera"), Tokens.S8)
        add(StatusBadge(ctx, onGlass = true).apply { set("Good", UiKit.Kind.OK) })
        add(StatusBadge(ctx, onGlass = true).apply { set("Move slower", UiKit.Kind.WARN) })
        add(StatusBadge(ctx, onGlass = true).apply { set("Tracking lost", UiKit.Kind.ERROR) })
        addView(InlineAlert(ctx, onGlass = true).apply { set("Sensor check failed: hold the phone still and try again.", UiKit.Kind.ERROR, "Re-check", {}) },
            lp(ctx, Tokens.S12))
        add(UiKit.headline(ctx, "Turn slowly to the left").apply {
            background = UiKit.glass(ctx, Tokens.GLASS_REGULAR, Tokens.R_PILL)
            setPadding(ctx.px(Tokens.S24), ctx.px(Tokens.S12), ctx.px(Tokens.S24), ctx.px(Tokens.S12))
        })
        add(UiKit.iconButton(ctx, R.drawable.ic_more_horiz, "More controls", onGlass = true), 0)
    }
}
