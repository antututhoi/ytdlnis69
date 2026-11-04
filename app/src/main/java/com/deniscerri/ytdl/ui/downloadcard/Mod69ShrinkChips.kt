package com.deniscerri.ytdl.ui.downloadcard

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.deniscerri.ytdl.R
import com.deniscerri.ytdl.mod69.Mod69Shrink
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup

/**
 * 69 mod: "Shrink after download: Off · H.265 MP4 · WebM" chips under the container choice.
 * The video is sent to 69-Media Helper after it is downloaded.
 */
object Mod69ShrinkChips {
    private const val TAG = "mod69_shrink_row"

    fun attach(fragment: Fragment, root: View, url: String) {
        val ctx = fragment.requireContext()
        val container = root.findViewById<View>(R.id.downloadContainer) ?: return
        val row = container.parent as? ViewGroup ?: return
        val parent = row.parent as? ViewGroup ?: return
        if (parent.findViewWithTag<View>(TAG) != null) return

        val d = ctx.resources.displayMetrics.density
        val box = LinearLayout(ctx).apply {
            tag = TAG
            orientation = LinearLayout.VERTICAL
            setPadding((10 * d).toInt(), (4 * d).toInt(), (10 * d).toInt(), (4 * d).toInt())
        }
        val installed = Mod69Shrink.isInstalled(ctx)
        box.addView(TextView(ctx).apply {
            text = if (installed) "Shrink after download (69-Media Helper)" else "Shrink after download – install 69-Media Helper to use"
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelLarge)
        })
        val group = ChipGroup(ctx).apply { isSingleSelection = true; isSelectionRequired = true }
        val options = listOf(
            Mod69Shrink.OFF to "Off",
            Mod69Shrink.H265 to "H.265 MP4 (fast)",
            Mod69Shrink.WEBM to "WebM (smallest, slow)",
        )
        val current = Mod69Shrink.lastChoice(ctx).takeIf { installed } ?: Mod69Shrink.OFF
        options.forEach { (value, label) ->
            group.addView(Chip(ctx).apply {
                text = label
                isCheckable = true
                isEnabled = installed || value == Mod69Shrink.OFF
                isChecked = value == current
                setOnCheckedChangeListener { _, checked ->
                    if (checked) Mod69Shrink.setChoice(ctx, url, value)
                }
            })
        }
        Mod69Shrink.setChoice(ctx, url, current)
        box.addView(group)
        parent.addView(box, parent.indexOfChild(row) + 1)
    }
}
