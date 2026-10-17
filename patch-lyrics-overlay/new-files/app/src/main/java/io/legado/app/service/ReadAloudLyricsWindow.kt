package io.legado.app.service

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import io.legado.app.BuildConfig
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.help.LifecycleHelp
import io.legado.app.utils.LogUtils
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.putPrefInt
import io.legado.app.utils.toastOnUi
import kotlin.math.abs

/**
 * 朗读「桌面歌词」悬浮窗。
 *
 * 把朗读服务当前的句子投影到系统窗口层(TYPE_APPLICATION_OVERLAY)，
 * 效果对齐音乐类 App 的桌面歌词：当前句高亮 + 逐字着色 + 可拖动 + 控制按钮。
 *
 * 透明度约定：用户设置的「背景透明度」只作用于窗口背景，**前景文字与图标一律完全不透明**，
 * 保证任何壁纸/任意透明度下文字都清晰可读。
 *
 * 所有对外方法都必须在主线程调用。
 */
class ReadAloudLyricsWindow(private val service: BaseReadAloudService) {

    companion object {
        private const val TAG = "ReadAloudLyricsWindow"

        /** 判定为拖动而非点击的位移阈值(px) */
        private const val DRAG_SLOP_PX = 14

        /** 长按判定时长(ms) */
        private const val LONG_PRESS_MS = 550L

        /** 双击判定的最大间隔(ms), 取系统标准值, 与用户日常手感一致 */
        private val DOUBLE_TAP_TIMEOUT_MS: Long = ViewConfiguration.getDoubleTapTimeout().toLong()

        /** 双击判定的最大偏移(px), 两次点击位置相差过大不算双击 */
        private const val DOUBLE_TAP_SLOP_PX = 80

        /** 应用前后台状态轮询间隔(ms) */
        private const val FOREGROUND_POLL_MS = 1500L

        private const val DEFAULT_TEXT_SIZE_SP = 20
        private const val DEFAULT_BG_TRANSPARENCY = 45
        private const val CONTEXT_TEXT_SIZE_RATIO = 0.62f
        private const val DEFAULT_Y_RATIO = 0.08f

        /** 未设置过位置时的标记 */
        private const val POSITION_UNSET = -1

        private val DEFAULT_TEXT_COLOR = Color.WHITE

        /**
         * 默认高亮色与正文同色。
         * 这样默认观感是「同色系由暗到亮」的推进, 不会出现一段文字突然变蓝的突兀感;
         * 想要彩色扫读的用户可在「歌词外观」里另行指定高亮色。
         */
        private val DEFAULT_HIGHLIGHT_COLOR = Color.WHITE
    }

    private val context: Context = service.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val windowManager: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val powerManager: PowerManager =
        context.getSystemService(Context.POWER_SERVICE) as PowerManager

    /** 是否已注册熄屏/亮屏监听 */
    private var receiverRegistered = false

    /** 上一次的可见性判定结果, 仅在变化时打日志 */
    private var lastCanShow: Boolean? = null

    private var rootView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    private var tvPrev: TextView? = null
    private var tvCurrent: TextView? = null
    private var tvNext: TextView? = null
    private var llControls: LinearLayout? = null
    private var ivPrev: ImageButton? = null
    private var ivPlay: ImageButton? = null
    private var ivNext: ImageButton? = null
    private var ivClose: ImageButton? = null

    var isShowing: Boolean = false
        private set

    /** 当前句原文，逐字高亮时复用，避免每次重新切分 */
    private var currentSentence: String = ""
    private var currentSentenceIndex: Int = -1

    /** 前后句文本缓存: 窗口尚未创建时先存着, 视图创建后一次性补齐 */
    private var pendingPrev: String? = null
    private var pendingNext: String? = null

    /** 最近一次句内已读字符数, 供改配色后重绘复用 */
    private var lastCharProgress = 0

    /** 长按临时隐藏按钮 */
    private var controlsTempHidden = false

    private var dragging = false
    private var downRawY = 0f
    private var dragStartY = 0
    private val longPressRunnable = Runnable { onLongPress() }

    /**
     * 朗读是否处于暂停态。
     * 暂停时整个窗口隐藏(由双击悬浮窗触发), 恢复播放后再自动显示。
     * 暂停可能来自音频焦点/来电等非主线程回调, 故用 @Volatile 保证可见性。
     * 初始值取窗口创建瞬间的真实状态: 暂停中新建的窗口不该直接弹出来。
     */
    @Volatile
    private var paused: Boolean = !BaseReadAloudService.isPlay()

    /** 上一次抬手的时间与位置, 用于双击判定 */
    private var lastTapUptime = 0L
    private var lastTapRawX = 0f
    private var lastTapRawY = 0f

    /** 本次按下是否已触发长按; 长按过的手势不再计入双击 */
    private var longPressFired = false

    private val foregroundPoller = object : Runnable {
        override fun run() {
            syncVisibility()
            mainHandler.postDelayed(this, FOREGROUND_POLL_MS)
        }
    }

    /**
     * 熄屏后系统不再合成悬浮窗, 继续轮询与逐字刷新纯属浪费。
     * 这里在熄屏时停掉轮询并隐藏窗口, 亮屏时再恢复。
     */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(receiverContext: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    log("ACTION_SCREEN_OFF: 停止轮询并隐藏窗口")
                    mainHandler.removeCallbacks(foregroundPoller)
                    hide()
                }

                Intent.ACTION_SCREEN_ON -> {
                    log("ACTION_SCREEN_ON: 恢复轮询")
                    attach()
                }
            }
        }
    }

    private fun isScreenOn(): Boolean = powerManager.isInteractive

    private fun registerScreenReceiver() {
        if (receiverRegistered) return
        runCatching {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(screenReceiver, filter)
            }
            receiverRegistered = true
        }.onFailure { log("注册熄屏监听失败: ${it.message}") }
    }

    private fun unregisterScreenReceiver() {
        if (!receiverRegistered) return
        runCatching { context.unregisterReceiver(screenReceiver) }
        receiverRegistered = false
    }

    // ------------------------------------------------------------------ 开关判定

    /** 用户是否开启了桌面歌词总开关 */
    fun isEnabled(): Boolean =
        context.getPrefBoolean(PreferKey.readAloudLyrics, false)

    /** 是否已获得悬浮窗权限 */
    fun hasPermission(): Boolean =
        Settings.canDrawOverlays(context)

    /**
     * 是否可以真正显示。
     * 只在结果发生变化时打一条日志, 避免前后台轮询把日志刷满。
     */
    private fun canShow(): Boolean {
        val enabled = isEnabled()
        val permitted = enabled && hasPermission()
        val playing = permitted && !paused
        val screenOn = playing && isScreenOn()
        var reason: String? = null
        var result: Boolean
        when {
            !enabled -> {
                reason = "开关未开启"
                result = false
            }

            !permitted -> {
                reason = "无悬浮窗权限"
                result = false
            }

            !playing -> {
                // 暂停时不显示: 此时没有任何逐字进度, 留在屏幕上只会挡住别的应用
                reason = "朗读已暂停"
                result = false
            }

            !screenOn -> {
                // 熄屏时既不显示也不刷新, 省掉所有无用的重排与 surface
                reason = "屏幕已熄灭"
                result = false
            }

            else -> {
                val onlyBackground =
                    context.getPrefBoolean(PreferKey.readAloudLyricsOnlyBackground, true)
                val top = if (onlyBackground) LifecycleHelp.getTopActivity() else null
                if (top != null) {
                    reason = "应用在前台 ${top::class.java.simpleName}"
                    result = false
                } else {
                    result = true
                }
            }
        }
        if (lastCanShow != result) {
            lastCanShow = result
            log(if (result) "canShow -> 显示" else "canShow -> 隐藏 ($reason)")
        }
        return result
    }

    // ---------------------------------------------------------------- 生命周期

    /** 朗读开始时调用, 启动前后台轮询并尝试显示 */
    fun attach() {
        registerScreenReceiver()
        mainHandler.removeCallbacks(foregroundPoller)
        mainHandler.post(foregroundPoller)
        log("attach: 启动前后台轮询")
        syncVisibility()
    }

    /** 朗读结束时调用, 彻底释放窗口 */
    fun release() {
        unregisterScreenReceiver()
        mainHandler.removeCallbacks(foregroundPoller)
        mainHandler.removeCallbacks(longPressRunnable)
        hide()
        rootView = null
        layoutParams = null
        tvPrev = null
        tvCurrent = null
        tvNext = null
        llControls = null
        ivPrev = null
        ivPlay = null
        ivNext = null
        ivClose = null
        currentSentence = ""
        currentSentenceIndex = -1
        pendingPrev = null
        pendingNext = null
        lastCharProgress = 0
        lastCanShow = null
        paused = false
        lastTapUptime = 0L
        longPressFired = false
    }

    /** 依据开关与前后台状态同步窗口可见性 */
    fun syncVisibility() {
        if (canShow()) show() else hide()
    }

    /** 重新读取外观配置并刷新 */
    fun refreshAppearance() {
        val view = rootView ?: return
        view.setBackground(createBackground())
        applyTextAppearance()
        applyControlsVisibility()
        applyPositionPref()
    }

    // ------------------------------------------------------------------ 显示/隐藏

    fun show() {
        if (isShowing) return
        if (!hasPermission()) return
        runCatching {
            val view = rootView ?: createView().also { rootView = it }
            windowManager.addView(view, layoutParams)
            isShowing = true
            refreshAppearance()
            log("show: 悬浮窗已添加")
        }.onFailure {
            // 权限被系统回收或窗口服务异常, 静默失败, 下次轮询会再次尝试
            log("show: 添加失败 -> ${it::class.java.simpleName}: ${it.message}")
            isShowing = false
        }
    }

    fun hide() {
        if (!isShowing) return
        val view = rootView ?: return
        runCatching {
            windowManager.removeViewImmediate(view)
        }
        isShowing = false
    }

    // ------------------------------------------------------------------ 内容更新

    /**
     * 句子切换。
     * @param index 当前句在朗读列表中的下标, 用于判断是否真的换句
     */
    fun updateSentence(index: Int, prev: String?, current: String?, next: String?) {
        if (index == currentSentenceIndex && currentSentence == current.orEmpty()) {
            return
        }
        currentSentenceIndex = index
        currentSentence = current.orEmpty()
        pendingPrev = prev
        pendingNext = next
        lastCharProgress = 0
        applySentenceTexts()
        // 换句时顺带校正一次可见性(切后台后最多等一句)
        syncVisibility()
    }

    /**
     * 逐字进度更新(卡拉OK效果)。
     * @param readChars 当前句内已朗读的字符数, <=0 表示未开始
     */
    fun updateCharProgress(readChars: Int) {
        lastCharProgress = readChars.coerceAtLeast(0)
        if (!isShowing) return
        applyCurrentSentence(lastCharProgress)
    }

    /**
     * 同步播放/暂停状态, 并据此收敛窗口可见性。
     *
     * 暂停 → 隐藏悬浮窗; 恢复 → 重新显示。
     * 暂停可能由音频焦点丢失、来电等非主线程路径触发, 因此 UI 部分统一切回主线程。
     */
    fun updatePlayState(isPlaying: Boolean) {
        paused = !isPlaying
        if (Looper.myLooper() == Looper.getMainLooper()) {
            applyPlayStateToViews(isPlaying)
        } else {
            mainHandler.post { applyPlayStateToViews(isPlaying) }
        }
    }

    private fun applyPlayStateToViews(isPlaying: Boolean) {
        ivPlay?.setImageResource(
            if (isPlaying) R.drawable.ic_pause_24dp else R.drawable.ic_play_24dp
        )
        ivPlay?.contentDescription = context.getString(R.string.read_aloud_lyrics_play)
        // 立刻收敛, 不等下一次前后台轮询
        syncVisibility()
    }

    /** 播放/暂停切换, 供悬浮窗按钮与双击手势共用 */
    private fun togglePlayPause() {
        if (BaseReadAloudService.isPlay()) {
            service.pauseReadAloud()
            // 暂停后窗口会消失, 这里给一次轻提示, 免得用户以为程序挂了
            context.toastOnUi(context.getString(R.string.read_aloud_lyrics_paused_tip))
        } else {
            service.resumeReadAloud()
        }
    }

    // ------------------------------------------------------------------ 内部实现

    /**
     * 把缓存的句子文本刷到视图上。
     * 视图尚未创建时所有 tv* 均为 null, 此处是一次空操作;
     * 待 [createView] 结束后会自动再调用一次, 补齐窗口创建前收到的句子。
     */
    private fun applySentenceTexts() {
        tvPrev?.text = pendingPrev.orEmpty()
        tvNext?.text = pendingNext.orEmpty()
        applyCurrentSentence(lastCharProgress)
    }

    private fun applyCurrentSentence(readChars: Int) {
        val view = tvCurrent ?: return
        val text = currentSentence
        if (text.isEmpty()) {
            view.text = ""
            return
        }
        val normalColor = opaque(context.getPrefInt(
            PreferKey.readAloudLyricsTextColor, DEFAULT_TEXT_COLOR
        ))
        val highlightColor = opaque(context.getPrefInt(
            PreferKey.readAloudLyricsHighlightColor, DEFAULT_HIGHLIGHT_COLOR
        ))
        val progress = readChars.coerceIn(0, text.length)
        // 已读部分 = 已读进度色, 未读部分 = 歌词原色。
        // 两者都是**完全不透明**的: 透明度只用于窗口背景, 不参与前景渲染,
        // 否则未读文字会随壁纸明暗而"看不清"。
        // 默认两色相同, 所以默认观感是一整句均匀清晰的白字;
        // 想看到逐字推进, 把「已读进度颜色」改成另一个颜色即可。
        val spannable = SpannableString(text)
        if (progress > 0) {
            spannable.setSpan(
                ForegroundColorSpan(highlightColor),
                0, progress,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        if (progress < text.length) {
            spannable.setSpan(
                ForegroundColorSpan(normalColor),
                progress, text.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        view.text = spannable
    }

    @SuppressLint("ClickableViewAccessibility", "InflateParams")
    private fun createView(): View {
        val view = LayoutInflater.from(context)
            .inflate(R.layout.view_read_aloud_lyrics, null)
        tvPrev = view.findViewById(R.id.tv_lyrics_prev)
        tvCurrent = view.findViewById(R.id.tv_lyrics_current)
        tvNext = view.findViewById(R.id.tv_lyrics_next)
        llControls = view.findViewById(R.id.ll_lyrics_controls)
        ivPrev = view.findViewById(R.id.iv_lyrics_prev)
        ivPlay = view.findViewById(R.id.iv_lyrics_play)
        ivNext = view.findViewById(R.id.iv_lyrics_next)
        ivClose = view.findViewById(R.id.iv_lyrics_close)

        ivPrev?.setOnClickListener { service.prevParagraph() }
        ivPlay?.setOnClickListener { togglePlayPause() }
        ivNext?.setOnClickListener { service.nextParagraph() }
        ivClose?.setOnClickListener {
            context.putPrefBoolean(PreferKey.readAloudLyrics, false)
            context.toastOnUi(context.getString(R.string.read_aloud_lyrics_closed_tip))
            hide()
        }

        view.setOnTouchListener { _, event -> handleTouch(event) }

        layoutParams = buildLayoutParams()
        view.setBackground(createBackground())
        // 窗口创建前收到的句子在此补齐, 避免首次显示缺行
        applySentenceTexts()
        return view
    }

    private fun buildLayoutParams(): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = savedYOr(defaultY())
        }
    }

    private fun defaultY(): Int =
        (context.resources.displayMetrics.heightPixels * DEFAULT_Y_RATIO).toInt()

    private fun savedYOr(fallback: Int): Int {
        val saved = context.getPrefInt(PreferKey.readAloudLyricsY, POSITION_UNSET)
        return if (saved >= 0) saved else fallback
    }

    /** 位置被重置(或从未拖动)时回落到默认位置; 拖动过的位置保持不动 */
    private fun applyPositionPref() {
        val view = rootView ?: return
        val params = layoutParams ?: return
        val saved = context.getPrefInt(PreferKey.readAloudLyricsY, POSITION_UNSET)
        if (saved >= 0) return
        val targetY = defaultY()
        if (params.y == targetY) return
        params.y = targetY
        runCatching { windowManager.updateViewLayout(view, params) }
    }

    /** 圆角半透明黑底。透明度设置只作用于这里, 即窗口背景 */
    private fun createBackground(): GradientDrawable {
        val transparency =
            context.getPrefInt(PreferKey.readAloudLyricsBgTransparency, DEFAULT_BG_TRANSPARENCY)
                .coerceIn(0, 100)
        val alpha = (255 * (100 - transparency) / 100f).toInt().coerceIn(0, 255)
        return GradientDrawable().apply {
            cornerRadius = dp(20f)
            setColor(Color.argb(alpha, 0, 0, 0))
        }
    }

    private fun applyTextAppearance() {
        val sizeSp = context.getPrefInt(
            PreferKey.readAloudLyricsTextSize, DEFAULT_TEXT_SIZE_SP
        ).coerceIn(12, 48)
        // 前景一律不透明: 上下句只靠字号区分, 不再靠"淡"区分
        val normalColor = opaque(context.getPrefInt(
            PreferKey.readAloudLyricsTextColor, DEFAULT_TEXT_COLOR
        ))

        tvCurrent?.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp.toFloat())
        tvCurrent?.setTextColor(normalColor)
        tvPrev?.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp * CONTEXT_TEXT_SIZE_RATIO)
        tvNext?.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp * CONTEXT_TEXT_SIZE_RATIO)
        tvPrev?.setTextColor(normalColor)
        tvNext?.setTextColor(normalColor)
        applyCurrentSentence(lastCharProgress)

        listOfNotNull(ivPrev, ivPlay, ivNext, ivClose).forEach {
            it.setColorFilter(normalColor)
        }
    }

    private fun applyControlsVisibility() {
        val showConfig = context.getPrefBoolean(PreferKey.readAloudLyricsShowControls, true)
        val visible = showConfig && !controlsTempHidden
        llControls?.visibility = if (visible) View.VISIBLE else View.GONE

        val showContext = context.getPrefBoolean(PreferKey.readAloudLyricsShowContext, true)
        val contextVisibility = if (showContext) View.VISIBLE else View.GONE
        tvPrev?.visibility = contextVisibility
        tvNext?.visibility = contextVisibility
    }

    private fun onLongPress() {
        longPressFired = true
        // 长按与双击互斥, 抬手时不应再被判定成双击
        lastTapUptime = 0L
        // 长按空白处: 在「纯文字」与「带按钮」之间切换
        controlsTempHidden = !controlsTempHidden
        applyControlsVisibility()
        if (controlsTempHidden) {
            // 极简模式下把窗口移到屏幕中部, 更接近歌词观感
            layoutParams?.let { params ->
                val view = rootView ?: return@let
                val screenHeight = context.resources.displayMetrics.heightPixels
                if (params.y < screenHeight / 4) {
                    params.y = (screenHeight * 0.12f).toInt()
                    runCatching { windowManager.updateViewLayout(view, params) }
                }
            }
        }
    }

    private fun handleTouch(event: MotionEvent): Boolean {
        val view = rootView ?: return false
        val params = layoutParams ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawY = event.rawY
                dragStartY = params.y
                dragging = false
                longPressFired = false
                mainHandler.removeCallbacks(longPressRunnable)
                mainHandler.postDelayed(longPressRunnable, LONG_PRESS_MS)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dy = event.rawY - downRawY
                if (!dragging && abs(dy) > DRAG_SLOP_PX) {
                    dragging = true
                    lastTapUptime = 0L
                    mainHandler.removeCallbacks(longPressRunnable)
                }
                if (dragging) {
                    val screenHeight = context.resources.displayMetrics.heightPixels
                    val maxY = (screenHeight - view.height).coerceAtLeast(0)
                    params.y = (dragStartY + dy).toInt().coerceIn(0, maxY)
                    runCatching { windowManager.updateViewLayout(view, params) }
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                mainHandler.removeCallbacks(longPressRunnable)
                val wasDragging = dragging
                if (wasDragging) {
                    context.putPrefInt(PreferKey.readAloudLyricsY, params.y)
                }
                dragging = false
                if (event.actionMasked == MotionEvent.ACTION_UP && !wasDragging && !longPressFired) {
                    handleTap(event.rawX, event.rawY)
                } else {
                    lastTapUptime = 0L
                }
                return true
            }
        }
        return false
    }

    /**
     * 单击/双击判定。
     * 单击留给后续扩展(当前无动作); 双击 = 暂停 / 恢复朗读。
     */
    private fun handleTap(rawX: Float, rawY: Float) {
        val now = SystemClock.uptimeMillis()
        val withinTime = lastTapUptime != 0L && now - lastTapUptime <= DOUBLE_TAP_TIMEOUT_MS
        val withinSlop = abs(rawX - lastTapRawX) <= DOUBLE_TAP_SLOP_PX &&
                abs(rawY - lastTapRawY) <= DOUBLE_TAP_SLOP_PX
        if (withinTime && withinSlop) {
            lastTapUptime = 0L
            log("双击: 切换播放/暂停")
            togglePlayPause()
        } else {
            lastTapUptime = now
            lastTapRawX = rawX
            lastTapRawY = rawY
        }
    }

    // ------------------------------------------------------------------ 工具

    /** 仅在 debug 构建输出诊断日志, release 自动静默 */
    private fun log(msg: String) {
        if (BuildConfig.DEBUG) LogUtils.d(TAG, msg)
    }

    private fun dp(value: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics
    )

    /**
     * 强制前景色完全不透明(alpha=0xFF)。
     * 文字与图标的通透度不参与用户配置: 配置里的「背景透明度」只管背景,
     * 这样任何壁纸下文字都保持清晰, 也避免历史配置里残留的半透明颜色生效。
     */
    private fun opaque(color: Int): Int =
        Color.argb(255, Color.red(color), Color.green(color), Color.blue(color))
}
