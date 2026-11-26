package com.hizligiris.app

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast

class AutoFillService : AccessibilityService() {

    private var windowManager: WindowManager? = null
    private var floatingBar: LinearLayout? = null
    private var isBarShown = false
    private var isMinimized = false
    private var lastFocusedNode: AccessibilityNodeInfo? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        try {
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            mainHandler.postDelayed({ showFloatingBar() }, 300)
        } catch (e: Throwable) {}
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        try {
            if (event == null) return

            if (event.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED ||
                event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
                val src = event.source
                if (src != null) {
                    lastFocusedNode = src
                }
            }

            if (!isBarShown) {
                showFloatingBar()
            }
        } catch (e: Throwable) {}
    }

    private fun isPasswordNode(node: AccessibilityNodeInfo): Boolean {
        if (node.isPassword) return true
        val it = node.inputType
        val variation = it and InputType.TYPE_MASK_VARIATION
        if (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD) {
            return true
        }
        val hint = (node.hintText?.toString() ?: "").lowercase()
        val text = (node.text?.toString() ?: "").lowercase()
        val desc = (node.contentDescription?.toString() ?: "").lowercase()
        val id = (node.viewIdResourceName ?: "").lowercase()
        return listOf(hint, text, desc, id).any {
            it.contains("şifre") || it.contains("sifre") || it.contains("parola") || it.contains("pass")
        }
    }

    private fun isInputNode(node: AccessibilityNodeInfo): Boolean {
        if (node.isEditable || node.isPassword) return true
        val cls = (node.className?.toString() ?: "").lowercase()
        if (cls.contains("edittext")) return true
        val actions = node.actionList.map { it.id }
        return actions.contains(AccessibilityNodeInfo.ACTION_SET_TEXT)
    }

    private fun collectEditTexts(node: AccessibilityNodeInfo?, list: MutableList<AccessibilityNodeInfo>) {
        if (node == null) return
        try {
            if (isInputNode(node) && node.isVisibleToUser) {
                // URL çubuğunu (adres satırını) hariç tut
                val id = (node.viewIdResourceName ?: "").lowercase()
                if (!id.contains("url_bar") && !id.contains("search")) {
                    list.add(node)
                }
            }
            for (i in 0 until node.childCount) {
                collectEditTexts(node.getChild(i), list)
            }
        } catch (e: Throwable) {}
    }

    private fun copyToClipboard(text: String) {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("autofill", text))
        } catch (e: Throwable) {}
    }

    private fun inputTextToNode(node: AccessibilityNodeInfo, text: String, usePasteMode: Boolean) {
        try {
            copyToClipboard(text)
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)

            val args = Bundle()
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)

            if (usePasteMode) {
                val pasted = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                if (!pasted) {
                    node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                }
            } else {
                val setOk = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                if (!setOk) {
                    node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                }
            }
        } catch (e: Throwable) {}
    }

    private fun findFocusedNode(): AccessibilityNodeInfo? {
        try {
            for (w in windows) {
                val r = w.root ?: continue
                val f = r.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                if (f != null) return f
            }
        } catch (e: Throwable) {}
        val root = rootInActiveWindow
        return root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: lastFocusedNode
    }

    private fun getAllEditNodes(): List<AccessibilityNodeInfo> {
        val editNodes = mutableListOf<AccessibilityNodeInfo>()
        try {
            for (w in windows) {
                val r = w.root ?: continue
                if (r.packageName != packageName) {
                    collectEditTexts(r, editNodes)
                }
            }
        } catch (e: Throwable) {}

        if (editNodes.isEmpty()) {
            collectEditTexts(rootInActiveWindow, editNodes)
        }
        return editNodes
    }

    private fun fillAction(isPasswordBtn: Boolean, value: String, defaultId: String) {
        try {
            copyToClipboard(value)
            val prefs = getSharedPreferences("HizliGirisPrefs", Context.MODE_PRIVATE)
            val usePaste = prefs.getBoolean("use_paste_mode", false)

            val editNodes = getAllEditNodes()
            val passIndex = editNodes.indexOfFirst { isPasswordNode(it) }

            if (passIndex != -1) {
                val passNode = editNodes[passIndex]
                val userNode = if (passIndex > 0) editNodes[passIndex - 1] else null

                if (!isPasswordBtn) {
                    val target = userNode ?: findFocusedNode() ?: passNode
                    inputTextToNode(target, value, usePaste)
                } else {
                    if (userNode != null && defaultId.isNotEmpty()) {
                        inputTextToNode(userNode, defaultId, usePaste)
                        mainHandler.postDelayed({
                            inputTextToNode(passNode, value, usePaste)
                        }, 150)
                        return
                    }
                    inputTextToNode(passNode, value, usePaste)
                }
            } else if (editNodes.size >= 2) {
                val userNode = editNodes[0]
                val passNode = editNodes[1]
                if (!isPasswordBtn) {
                    inputTextToNode(userNode, value, usePaste)
                } else {
                    if (defaultId.isNotEmpty()) {
                        inputTextToNode(userNode, defaultId, usePaste)
                        mainHandler.postDelayed({
                            inputTextToNode(passNode, value, usePaste)
                        }, 150)
                    } else {
                        inputTextToNode(passNode, value, usePaste)
                    }
                }
            } else {
                val target = findFocusedNode() ?: editNodes.firstOrNull()
                if (target != null) {
                    inputTextToNode(target, value, usePaste)
                } else {
                    Toast.makeText(this, "Kopyalandı! Kutuya dokunup tekrar bas.", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Throwable) {}
    }

    private fun showFloatingBar() {
        try {
            if (isBarShown || !Settings.canDrawOverlays(this)) return

            val prefs = getSharedPreferences("HizliGirisPrefs", Context.MODE_PRIVATE)
            val idList = listOf(
                "👤1" to (prefs.getString("id1", "") ?: ""),
                "👤2" to (prefs.getString("id2", "") ?: ""),
                "👤3" to (prefs.getString("id3", "") ?: ""),
                "👤4" to (prefs.getString("id4", "") ?: "")
            )
            val sList = listOf(
                Triple("🔑S1", prefs.getString("s1", "") ?: "", "#ff5722"),
                Triple("🔑S2", prefs.getString("s2", "") ?: "", "#2196f3"),
                Triple("🔑S3", prefs.getString("s3", "") ?: "", "#4caf50"),
                Triple("🔑S4", prefs.getString("s4", "") ?: "", "#9c27b0")
            )
            val defaultId = prefs.getString("id1", "") ?: ""

            floatingBar = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(10, 10, 10, 10)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#E61f232a"))
                    cornerRadius = 22f
                }
            }

            val btnContainer = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }

            val dragHandle = Button(this).apply {
                text = "✥"
                textSize = 13f
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.TRANSPARENT)
                minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
                setPadding(16, 10, 16, 10)
                setOnClickListener {
                    isMinimized = !isMinimized
                    btnContainer.visibility = if (isMinimized) View.GONE else View.VISIBLE
                }
            }
            floatingBar?.addView(dragHandle)
            floatingBar?.addView(btnContainer)

            fun createSmallButton(label: String, bgColor: String, onClick: (Button) -> Unit): Button {
                return Button(this).apply {
                    text = label
                    textSize = 11f
                    setTextColor(Color.WHITE)
                    minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
                    setPadding(20, 14, 20, 14)
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor(bgColor))
                        cornerRadius = 14f
                    }
                    setOnClickListener { onClick(this) }
                }
            }

            val btnParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(5, 0, 5, 0) }

            idList.forEach { (label, value) ->
                if (value.isNotEmpty()) {
                    btnContainer.addView(createSmallButton(label, "#555555") {
                        fillAction(false, value, defaultId)
                    }, btnParams)
                }
            }

            sList.forEach { (label, value, color) ->
                if (value.isNotEmpty()) {
                    btnContainer.addView(createSmallButton(label, color) {
                        fillAction(true, value, defaultId)
                    }, btnParams)
                }
            }

            val refreshBtn = createSmallButton("🔄", "#455a64") {
                removeFloatingBar()
                showFloatingBar()
            }
            btnContainer.addView(refreshBtn, btnParams)

            val initialPaste = prefs.getBoolean("use_paste_mode", false)
            val modeBtn = createSmallButton(if (initialPaste) "📋" else "⚡", "#37474f") { btn ->
                val next = !prefs.getBoolean("use_paste_mode", false)
                prefs.edit().putBoolean("use_paste_mode", next).apply()
                btn.text = if (next) "📋" else "⚡"
                Toast.makeText(this@AutoFillService, if (next) "📋 Yapıştır Modu" else "⚡ Hızlı Mod", Toast.LENGTH_SHORT).show()
            }
            btnContainer.addView(modeBtn, btnParams)

            val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutFlag,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = 180
            }

            dragHandle.setOnTouchListener(object : View.OnTouchListener {
                private var initialX = 0
                private var initialY = 0
                private var initialTouchX = 0f
                private var initialTouchY = 0f
                private var isMoved = false

                override fun onTouch(v: View?, event: MotionEvent): Boolean {
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            initialX = params.x
                            initialY = params.y
                            initialTouchX = event.rawX
                            initialTouchY = event.rawY
                            isMoved = false
                            return false
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val dx = (event.rawX - initialTouchX).toInt()
                            val dy = (event.rawY - initialTouchY).toInt()
                            if (kotlin.math.abs(dx) > 10 || kotlin.math.abs(dy) > 10) {
                                isMoved = true
                                params.x = initialX + dx
                                params.y = initialY + dy
                                windowManager?.updateViewLayout(floatingBar, params)
                            }
                            return isMoved
                        }
                    }
                    return false
                }
            })

            windowManager?.addView(floatingBar, params)
            isBarShown = true
        } catch (e: Throwable) {}
    }

    private fun removeFloatingBar() {
        if (isBarShown && floatingBar != null) {
            try {
                windowManager?.removeView(floatingBar)
            } catch (e: Throwable) {}
            floatingBar = null
            isBarShown = false
        }
    }

    override fun onInterrupt() {
        removeFloatingBar()
    }
}
