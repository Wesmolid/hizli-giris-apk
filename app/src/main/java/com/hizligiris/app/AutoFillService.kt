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
    private var lastAutoFilledWindowId = -1
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val root = rootInActiveWindow ?: return

        if (root.packageName == packageName) {
            removeFloatingBar()
            return
        }

        val editNodes = mutableListOf<AccessibilityNodeInfo>()
        collectEditTexts(root, editNodes)

        val passIndex = editNodes.indexOfFirst { it.isPassword }

        if (passIndex != -1) {
            val userNode = if (passIndex > 0) editNodes[passIndex - 1] else null

            showFloatingBar()

            if (root.windowId != lastAutoFilledWindowId) {
                val prefs = getSharedPreferences("HizliGirisPrefs", Context.MODE_PRIVATE)
                val autoId = prefs.getString("id1", "") ?: ""
                val usePaste = prefs.getBoolean("use_paste_mode", false)
                if (autoId.isNotEmpty() && userNode != null) {
                    val currentText = userNode.text?.toString() ?: ""
                    if (currentText.isEmpty() || currentText == userNode.hintText?.toString()) {
                        inputTextToNode(userNode, autoId, usePaste)
                        lastAutoFilledWindowId = root.windowId
                    }
                }
            }
        } else {
            removeFloatingBar()
        }
    }

    private fun collectEditTexts(node: AccessibilityNodeInfo?, list: MutableList<AccessibilityNodeInfo>) {
        if (node == null) return
        val cls = node.className?.toString() ?: ""
        if ((node.isEditable || cls.contains("EditText")) && node.isVisibleToUser) {
            list.add(node)
        }
        for (i in 0 until node.childCount) {
            collectEditTexts(node.getChild(i), list)
        }
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("autofill", text)
        clipboard.setPrimaryClip(clip)
    }

    private fun inputTextToNode(node: AccessibilityNodeInfo, text: String, usePasteMode: Boolean) {
        // Her durumda panoya da kopyala ki kullanıcı gerekirse elle de yapıştırabilsin
        copyToClipboard(text)

        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)

        if (usePasteMode) {
            // Önce varsa içini temizle, sonra gerçek Yapıştır (ACTION_PASTE) gönder
            val emptyArgs = Bundle()
            emptyArgs.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, emptyArgs)

            val pasted = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            if (!pasted) {
                val args = Bundle()
                args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            }
        } else {
            // Doğrudan yazdır (Hızlı Mod)
            val args = Bundle()
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            val success = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            if (!success) {
                node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            }
        }
    }

    private fun fillAction(isPasswordBtn: Boolean, value: String, defaultId: String) {
        val root = rootInActiveWindow ?: return
        val editNodes = mutableListOf<AccessibilityNodeInfo>()
        collectEditTexts(root, editNodes)

        val passIndex = editNodes.indexOfFirst { it.isPassword }
        if (passIndex == -1) return

        val passNode = editNodes[passIndex]
        val userNode = if (passIndex > 0) editNodes[passIndex - 1] else null

        val prefs = getSharedPreferences("HizliGirisPrefs", Context.MODE_PRIVATE)
        val usePaste = prefs.getBoolean("use_paste_mode", false)

        if (!isPasswordBtn) {
            if (userNode != null) inputTextToNode(userNode, value, usePaste)
        } else {
            val needUserFill = userNode != null && defaultId.isNotEmpty() && (userNode.text?.toString() ?: "").isEmpty()
            if (needUserFill && userNode != null) {
                inputTextToNode(userNode, defaultId, usePaste)
                // Yapıştır modunda pano çakışmaması için şifreyi 120ms sonra yapıştır
                mainHandler.postDelayed({
                    inputTextToNode(passNode, value, usePaste)
                }, 120)
            } else {
                inputTextToNode(passNode, value, usePaste)
            }
        }
    }

    private fun showFloatingBar() {
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

        var hasButton = false

        // Sürükleme Tutacağı
        val dragHandle = Button(this).apply {
            text = "✥"
            textSize = 12f
            setTextColor(Color.LTGRAY)
            setBackgroundColor(Color.TRANSPARENT)
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            setPadding(14, 10, 14, 10)
        }
        floatingBar?.addView(dragHandle)

        fun createSmallButton(label: String, bgColor: String, onClick: (Button) -> Unit): Button {
            return Button(this).apply {
                text = label
                textSize = 11f
                setTextColor(Color.WHITE)
                minWidth = 0
                minimumWidth = 0
                minHeight = 0
                minimumHeight = 0
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
                hasButton = true
                floatingBar?.addView(createSmallButton(label, "#555555") {
                    fillAction(false, value, defaultId)
                }, btnParams)
            }
        }

        sList.forEach { (label, value, color) ->
            if (value.isNotEmpty()) {
                hasButton = true
                floatingBar?.addView(createSmallButton(label, color) {
                    fillAction(true, value, defaultId)
                }, btnParams)
            }
        }

        // Mod Değiştirici Ufak Buton (⚡ Normal Mod vs 📋 Kopyala+Yapıştır Modu)
        val initialPaste = prefs.getBoolean("use_paste_mode", false)
        val modeBtn = createSmallButton(if (initialPaste) "📋" else "⚡", "#37474f") { btn ->
            val current = prefs.getBoolean("use_paste_mode", false)
            val next = !current
            prefs.edit().putBoolean("use_paste_mode", next).apply()
            btn.text = if (next) "📋" else "⚡"
            val msg = if (next) "📋 Kopyala+Yapıştır Modu Aktif" else "⚡ Hızlı Yazma Modu Aktif"
            Toast.makeText(this@AutoFillService, msg, Toast.LENGTH_SHORT).show()
        }
        floatingBar?.addView(modeBtn, btnParams)

        if (!hasButton) return

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
            y = 200
        }

        dragHandle.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f

            override fun onTouch(v: View?, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = initialX + (event.rawX - initialTouchX).toInt()
                        params.y = initialY + (event.rawY - initialTouchY).toInt()
                        windowManager?.updateViewLayout(floatingBar, params)
                        return true
                    }
                }
                return false
            }
        })

        try {
            windowManager?.addView(floatingBar, params)
            isBarShown = true
        } catch (e: Exception) {}
    }

    private fun removeFloatingBar() {
        if (isBarShown && floatingBar != null) {
            try {
                windowManager?.removeView(floatingBar)
            } catch (e: Exception) {}
            floatingBar = null
            isBarShown = false
        }
    }

    override fun onInterrupt() {
        removeFloatingBar()
    }
}
