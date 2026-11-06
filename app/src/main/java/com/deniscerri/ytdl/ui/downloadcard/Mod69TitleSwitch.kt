package com.deniscerri.ytdl.ui.downloadcard

import android.widget.Toast
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.widget.PopupMenu
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.deniscerri.ytdl.R
import com.deniscerri.ytdl.database.DBManager
import com.deniscerri.ytdl.util.extractors.ytdlp.YTDLPUtil
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 69 mod: a translate button on the title field of the download card.
 * Tap it to choose YouTube's own English title (its translation) or the original title.
 */
object Mod69TitleSwitch {
    private val englishCache = mutableMapOf<String, String>()

    /** Letters that are not Latin (Korean, Japanese, Chinese, Thai, Cyrillic, Arabic, ...). */
    private val NON_LATIN = Regex("[\\p{IsHangul}\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}\\p{IsThai}\\p{IsCyrillic}\\p{IsArabic}\\p{IsHebrew}\\p{IsDevanagari}\\p{IsGreek}]")

    fun attach(fragment: Fragment, title: TextInputLayout, url: String, originalTitle: () -> String?) {
        val first = originalTitle()
        val original = { first }
        if (url.isBlank() || !(url.contains("youtube.com") || url.contains("youtu.be"))) return
        val ctx = fragment.requireContext()
        title.startIconDrawable = AppCompatResources.getDrawable(ctx, R.drawable.ic_translate)
        title.startIconContentDescription = "Title language"
        // 69 mod: non-English title -> use YouTube's English title right away (icon switches back)
        if (first != null && NON_LATIN.containsMatchIn(first)) {
            fragment.viewLifecycleOwner.lifecycleScope.launch {
                val en = englishCache[url] ?: withContext(Dispatchers.IO) {
                    val app = ctx.applicationContext
                    runCatching {
                        YTDLPUtil(app, DBManager.getInstance(app).commandTemplateDao).getTranslatedTitle(url)
                    }.getOrNull()
                }
                if (!fragment.isAdded || en.isNullOrBlank() || en == first) return@launch
                englishCache[url] = en
                // only if the user did not change the title meanwhile
                if (title.editText?.text?.toString() == first) title.editText?.setText(en)
            }
        }
        title.setStartIconOnClickListener { anchor ->
            val menu = PopupMenu(ctx, anchor)
            menu.menu.add(0, 1, 0, "Original title")
            menu.menu.add(0, 2, 1, "English title (YouTube translation)")
            menu.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> original()?.let { title.editText?.setText(it) }
                    2 -> {
                        val cached = englishCache[url]
                        if (cached != null) {
                            title.editText?.setText(cached)
                        } else {
                            Toast.makeText(ctx, "Getting the English title…", Toast.LENGTH_SHORT).show()
                            fragment.viewLifecycleOwner.lifecycleScope.launch {
                                val en = withContext(Dispatchers.IO) {
                                    val app = ctx.applicationContext
                                    YTDLPUtil(app, DBManager.getInstance(app).commandTemplateDao).getTranslatedTitle(url)
                                }
                                if (!fragment.isAdded) return@launch
                                if (en.isNullOrBlank()) {
                                    Toast.makeText(ctx, "No English title found", Toast.LENGTH_SHORT).show()
                                } else {
                                    englishCache[url] = en
                                    title.editText?.setText(en)
                                    if (en == original()) {
                                        Toast.makeText(ctx, "This video has no separate English title", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        }
                    }
                }
                true
            }
            menu.show()
        }
    }
}
