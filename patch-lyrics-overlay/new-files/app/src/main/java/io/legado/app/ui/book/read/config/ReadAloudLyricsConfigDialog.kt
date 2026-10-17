package io.legado.app.ui.book.read.config

import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.preference.Preference
import io.legado.app.R
import io.legado.app.base.BasePrefDialogFragment
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.lib.prefs.fragment.PreferenceFragment
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.utils.postEvent
import io.legado.app.utils.putPrefInt
import io.legado.app.utils.setLayout
import io.legado.app.utils.toastOnUi
import splitties.init.appCtx

/**
 * 朗读桌面歌词: 外观配置(字号/颜色/透明度/上下句/按钮/显示时机/位置)
 */
class ReadAloudLyricsConfigDialog : BasePrefDialogFragment() {

    companion object {
        private const val TAG_FRAGMENT = "readAloudLyricsConfig"
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.run {
            setBackgroundDrawableResource(R.color.transparent)
            setLayout(.9f, .8f)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        (activity as? ReadBookActivity)?.let { it.bottomDialog++ }
        return LinearLayout(requireContext()).apply {
            id = R.id.tag1
            setBackgroundColor(context.backgroundColor)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (childFragmentManager.findFragmentByTag(TAG_FRAGMENT) == null) {
            childFragmentManager.beginTransaction()
                .replace(view.id, LyricsPreferenceFragment(), TAG_FRAGMENT)
                .commit()
        }
    }

    class LyricsPreferenceFragment : PreferenceFragment(),
        SharedPreferences.OnSharedPreferenceChangeListener {

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            addPreferencesFromResource(R.xml.pref_config_lyrics)
        }

        override fun onResume() {
            super.onResume()
            preferenceManager.sharedPreferences?.registerOnSharedPreferenceChangeListener(this)
        }

        override fun onPause() {
            preferenceManager.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(this)
            super.onPause()
        }

        /** 任一外观项变化都实时刷新悬浮窗 */
        override fun onSharedPreferenceChanged(
            sharedPreferences: SharedPreferences?,
            key: String?
        ) {
            postEvent(EventBus.READ_ALOUD_LYRICS_CONFIG, true)
        }

        override fun onPreferenceTreeClick(preference: Preference): Boolean {
            if (preference.key == KEY_RESET_POSITION) {
                // -1 表示未设置, 悬浮窗会回落到默认位置
                appCtx.putPrefInt(PreferKey.readAloudLyricsY, -1)
                appCtx.toastOnUi(R.string.read_aloud_lyrics_reset_position_summary)
                postEvent(EventBus.READ_ALOUD_LYRICS_CONFIG, true)
                return true
            }
            return super.onPreferenceTreeClick(preference)
        }

        companion object {
            private const val KEY_RESET_POSITION = "readAloudLyricsResetPosition"
        }
    }
}
