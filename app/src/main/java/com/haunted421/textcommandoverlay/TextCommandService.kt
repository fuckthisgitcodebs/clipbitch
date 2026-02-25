package com.haunted421.textcommandoverlay

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageButton
import android.widget.Toast
import android.animation.ValueAnimator

class TextCommandService : AccessibilityService() {

    private lateinit var windowManager: WindowManager
    private var container: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var isExpanded = false
    private var currentText = ""
    private var currentPackage = ""

    private lateinit var btnMain: ImageButton
    private val actionButtons = mutableListOf<ImageButton>()

    private val handler = Handler(Looper.getMainLooper())
    private val autoHideRunnable = Runnable { hideMenu() }

    private var ignoreDeselectionUntil: Long = 0

    companion object {
        private const val TAG = "TextCommander"
        private const val RADIUS_DP = 180f
        private const val CONTAINER_SIZE_DP = 460f
    }

    override fun onServiceConnected() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        Log.d(TAG, "Universal Text Command Service connected — fully overhauled")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (System.currentTimeMillis() < ignoreDeselectionUntil) return

        val root = rootInActiveWindow ?: return
        val selectedNode = findSelectedNode(root) ?: return

        currentPackage = event.packageName?.toString() ?: ""
        val start = selectedNode.textSelectionStart
        val end = selectedNode.textSelectionEnd

        if (start >= 0 && end > start) {
            currentText = selectedNode.text?.substring(start, end)?.toString() ?: ""
            if (currentText.isNotEmpty()) {
                val rect = Rect()
                selectedNode.getBoundsInScreen(rect)
                showRadialMenu(rect)
            }
        } else {
            hideMenu()
        }
        selectedNode.recycle()
    }

    private fun findSelectedNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val start = node.textSelectionStart
            val end = node.textSelectionEnd
            if (start >= 0 && end > start) return node
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    private fun showRadialMenu(selectionRect: Rect) {
        if (container != null) {
            updatePosition(selectionRect)
            return
        }

        container = LayoutInflater.from(this).inflate(R.layout.floating_radial_menu, null)

        btnMain = container!!.findViewById(R.id.btnMain)
        actionButtons.apply {
            add(container!!.findViewById(R.id.btnShare))
            add(container!!.findViewById(R.id.btnCopy))
            add(container!!.findViewById(R.id.btnAppend))
            add(container!!.findViewById(R.id.btnPaste))
            add(container!!.findViewById(R.id.btnTasker))
        }

        setupActionListeners()
        makeDraggable()

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )

        calculatePosition(selectionRect)
        windowManager.addView(container, params)
        handler.postDelayed(autoHideRunnable, 15000)
    }

    private fun calculatePosition(rect: Rect) {
        val density = resources.displayMetrics.density
        val half = (CONTAINER_SIZE_DP * density / 2).toInt()

        var x = rect.right + dpToPx(28) - half
        var y = rect.bottom + dpToPx(48) - half

        val screenW = resources.displayMetrics.widthPixels
        if (x + half * 2 > screenW) x = rect.left - dpToPx(48) - half * 2

        params?.x = x.coerceAtLeast(0)
        params?.y = y.coerceAtLeast(0)
    }

    private fun updatePosition(rect: Rect) {
        if (container == null || params == null) return
        calculatePosition(rect)
        try {
            windowManager.updateViewLayout(container, params)
        } catch (e: Exception) {
            Log.w(TAG, "Update skipped (transient race)")
        }
    }

    private fun dpToPx(dp: Int) = (dp * resources.displayMetrics.density).toInt()

    private fun makeDraggable() {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var dragThresholdPassed = false

        btnMain.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dragThresholdPassed = false
                    initialX = params?.x ?: 0
                    initialY = params?.y ?: 0
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    ignoreDeselectionUntil = System.currentTimeMillis() + 3500L
                    handler.removeCallbacks(autoHideRunnable)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (!dragThresholdPassed && (Math.abs(dx) > 12 || Math.abs(dy) > 12)) dragThresholdPassed = true
                    params?.x = (initialX + dx.toInt())
                    params?.y = (initialY + dy.toInt())
                    container?.let { windowManager.updateViewLayout(it, params) }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    ignoreDeselectionUntil = System.currentTimeMillis() + 1200L
                    handler.postDelayed(autoHideRunnable, 15000)
                    if (!dragThresholdPassed) {
                        toggleExpand()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun setupActionListeners() {
        actionButtons[0].setOnClickListener { shareText(); collapseAndHide() }
        actionButtons[1].setOnClickListener { copyText(); collapseAndHide() }
        actionButtons[2].setOnClickListener { appendCopy(); collapseAndHide() }
        actionButtons[3].setOnClickListener { pasteHere(); collapseAndHide() }
        actionButtons[4].setOnClickListener { sendToTasker(); collapseAndHide() }
    }

    private fun toggleExpand() {
        if (isExpanded) collapseMenu() else expandMenu()
    }

    private fun expandMenu() {
        if (isExpanded) return
        isExpanded = true

        val density = resources.displayMetrics.density
        val radius = RADIUS_DP * density
        val finalAngles = listOf(270f, 198f, 126f, 54f, 342f) // exactly 5 angles for 5 buttons

        actionButtons.forEachIndexed { i, btn ->
            if (i >= finalAngles.size) return@forEachIndexed // safety
            btn.visibility = View.VISIBLE
            btn.alpha = 0f
            btn.scaleX = 0.6f
            btn.scaleY = 0.6f
            btn.translationX = radius
            btn.translationY = 0f

            val targetDeg = finalAngles[i]
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 620
                startDelay = (i * 58L)
                addUpdateListener { anim ->
                    val frac = anim.animatedFraction
                    val angleDeg = targetDeg * frac
                    val rad = Math.toRadians(angleDeg.toDouble())
                    btn.translationX = (radius * Math.cos(rad)).toFloat()
                    btn.translationY = (radius * Math.sin(rad)).toFloat()
                    btn.alpha = (frac * 1.3f).coerceAtMost(1f)
                    btn.scaleX = 0.6f + frac * 0.4f
                    btn.scaleY = btn.scaleX
                }
                start()
            }
        }
        // Main button stays stable size (no conflicting scale)
    }

    private fun collapseMenu() {
        if (!isExpanded) return
        isExpanded = false

        actionButtons.forEach { btn ->
            btn.animate()
                .translationX(0f).translationY(0f)
                .scaleX(0.3f).scaleY(0.3f).alpha(0f)
                .setDuration(240)
                .withEndAction { btn.visibility = View.GONE }
                .start()
        }
    }

    private fun collapseAndHide() {
        collapseMenu()
        handler.postDelayed({ hideMenu() }, 300)
    }

    private fun hideMenu() {
        collapseMenu()
        container?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        container = null
        isExpanded = false
        ignoreDeselectionUntil = 0
        handler.removeCallbacks(autoHideRunnable)
    }

    private fun shareText() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, currentText)
        }
        startActivity(Intent.createChooser(intent, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun copyText() {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("TextCommand", currentText))
        Toast.makeText(this, "Copied ✓", Toast.LENGTH_SHORT).show()
    }

    private fun appendCopy() {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val existing = cm.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
        val newText = if (existing.isEmpty()) currentText else "$existing\n\n$currentText"
        cm.setPrimaryClip(ClipData.newPlainText("TextCommand", newText))
        Toast.makeText(this, "Appended ✓", Toast.LENGTH_SHORT).show()
    }

    private fun pasteHere() {
        val node = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
        if (node?.isEditable == true) node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        else Toast.makeText(this, "No editable field", Toast.LENGTH_SHORT).show()
    }

    private fun sendToTasker() {
        val intent = Intent("com.haunted421.textcommand.TEXT_SELECTED").apply {
            putExtra("text", currentText)
            putExtra("source_package", currentPackage)
            putExtra("timestamp", System.currentTimeMillis())
        }
        sendBroadcast(intent)
        Toast.makeText(this, "Sent to Tasker ✓", Toast.LENGTH_SHORT).show()
    }

    override fun onInterrupt() = hideMenu()
    override fun onDestroy() = hideMenu()
}
