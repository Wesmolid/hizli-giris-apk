package com.hizligiris.app

import android.accessibilityservice.AccessibilityService
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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

    private var lastAutoFillTime = 0L
    private var lastWindowId = -1
    private var isFilling = false

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        startForegroundNotification()
    }

    // Uygulamaya girildiğinde (MainActivity) burası tetiklenir ve barı anında geri getirir!
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundNotification()
        if (windowManager == null) {
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        }
        mainHandler.post {
            removeFloatingBar()
            showFloatingBar()
        }
        return START_STICKY
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        startForegroundNotification()
        try {
            if (windowManager == null) {
                windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            }
            mainHandler.postDelayed({ showFloatingBar() }, 150)
        } catch (e: Throwable) {}
    }

    private fun startForegroundNotification() {
        try {
            val channelId = "hizli_giris_channel"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    channelId,
                    "Hızlı Giriş Arka Plan Servisi",
                    NotificationManager.IMPORTANCE_LOW
                )
                val manager = getSystemService(NotificationManager::class.java)
                manager?.createNotificationChannel(channel)

                val notification = Notification.Builder(this, channelId)
                    .setContentTitle("Hızlı Giriş Aktif")
                    .setContentText("Otomatik doldurma hazır")
                    .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                    .setOngoing(true)
                    .build()

                if (Build.VERSION.SDK_INT >= 34) {
                    startForeground(101, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                } else {
                    startForeground(101, notification)
                }
            }
        } catch (e: Throwable) {}
    }

    // Bellek temizlendiğinde 1 saniye sonra servisi kendi kendine diriltir
    private fun scheduleSelfRevive() {
        try {
            val restartIntent = Intent(applicationContext, AutoFillService::class.java).apply {
                action = "ACTION_REVIVE_BAR"
            }
            val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_ONE_SHOT
            }
            val pendingIntent = PendingIntent.getService(applicationContext, 1, restartIntent, pendingFlags)
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.set(
                AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + 800,
                pendingIntent
            )
        } catch (e: Throwable) {}
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        scheduleSelfRevive()
        startForegroundNotification()
        mainHandler.postDelayed({ showFloatingBar() }, 200)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        removeFloatingBar()
        scheduleSelfRevive()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        try {
            if (event == null || isFilling) return

            if (event.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED ||
                event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
                event.source?.let { lastFocusedNode = it }
            }

            if (!isBarShown) {
                showFloatingBar()
            }

            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                event.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED) {
                checkAndAutoFillId1AndS1()
            }
        } catch (e: Throwable) {}
    }

    private fun checkAndAutoFillId1AndS1() {
        val now = System.currentTimeMillis()
        if (now - lastAutoFillTime < 2000) return

        val root = rootInActiveWindow ?: return
        if (root.packageName == packageName) return

        val editNodes = mutableListOf<AccessibilityNodeInfo>()
        collectEditTexts(root, editNodes, 0)

        if (editNodes.isEmpty() || editNodes.size > 3) return

        val passIndex = editNodes.indexOfFirst { isPasswordNode(it) }
        if (passIndex == -1) return

        val passNode = editNodes[passIndex]
        val userNode = if (passIndex > 0) editNodes[passIndex - 1] else null

        val prefs = getSharedPreferences("HizliGirisPrefs", Context.MODE_PRIVATE)
        val autoId1 = prefs.getString("id1", "") ?: ""
        val autoS1 = prefs.getString("s1", "") ?: ""
        val usePaste = prefs.getBoolean("use_paste_mode", false)

        val userEmpty = userNode != null && isNodeEmpty(userNode)
        val passEmpty = isNodeEmpty(passNode)

        if (userEmpty && passEmpty && root.windowId != lastWindowId) {
            lastWindowId = root.windowId
            lastAutoFillTime = now

            if (userNode != null && autoId1.isNotEmpty()) {
                replaceTextInNode(userNode, autoId1, usePaste)
            }
            if (autoS1.isNotEmpty()) {
                mainHandler.postDelayed({
                    replaceTextInNode(passNode, autoS1, usePaste)
                }, 200)
            }
        }
    }

    private fun isNodeEmpty(node: AccessibilityNodeInfo): Boolean {
        val text = node.text?.toString()?.trim() ?: ""
        if (text.isEmpty()) return true
        val hint = node.hintText?.toString()?.trim() ?: ""
        if (hint.isNotEmpty() && text.equals(hint, ignoreCase = true)) return true
        val lower = text.lowercase()
        if (lower.contains("lütfen") || lower.contains("kullanıcı adı") || lower.contains("şifre")) return true
        return false
    }

    private fun isPasswordNode(node: AccessibilityNodeInfo): Boolean {
        if (node.isPassword) return true
        val variation = node.inputType and InputType.TYPE_MASK_VARIATION
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
        return cls.contains("edittext")
    }

    private fun collectEditTexts(node: AccessibilityNodeInfo?, list: MutableList<AccessibilityNodeInfo>, depth: Int) {
        if (node == null || depth > 25 || list.size >= 10) return
        try {
            if (isInputNode(node) && node.isVisibleToUser) {
                val id = (node.viewIdResourceName ?: "").lowercase()
                if (!id.contains("url_bar") && !id.contains("search")) {
                    list.add(node)
                }
            }
            val count = node.childCount
            for (i in 0 until count) {
                if (list.size >= 10) break
                collectEditTexts(node.getChild(i), list, depth + 1)
            }
        } catch (e: Throwable) {}
    }

    private fun copyToClipboard(text: String) {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("autofill", text))
        } catch (e: Throwable) {}
    }

    private fun replaceTextInNode(node: AccessibilityNodeInfo, newText: String, usePasteMode: Boolean) {
        try {
            isFilling = true
            copyToClipboard(newText)
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)

            val clearArgs = Bundle()
            clearArgs.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, clearArgs)

            val setArgs = Bundle()
            setArgs.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)

            if (usePasteMode) {
                if (!node.performAction(AccessibilityNodeInfo.ACTION_PASTE)) {
                    node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, setArgs)
                }
            } else {
                if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, setArgs)) {
                    node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                }
            }
        } catch (e: Throwable) {
        } finally {
            mainHandler.postDelayed({ isFilling = false }, 250)
        }
    }

    private fun fillAction(isPasswordBtn: Boolean, value: String, defaultId: String) {
        try {
            copyToClipboard(value)
            val prefs = getSharedPreferences("HizliGirisPrefs", Context.MODE_PRIVATE)
            val usePaste = prefs.getBoolean("use_paste_mode", false)

            val root = rootInActiveWindow
            val editNodes = mutableListOf<AccessibilityNodeInfo>()
            if (root != null) collectEditTexts(root, editNodes, 0)

            val passIndex = editNodes.indexOfFirst { isPasswordNode(it) }

            if (passIndex != -1) {
                val passNode = editNodes[passIndex]
                val userNode = if (passIndex > 0) editNodes[passIndex - 1] else null

                if (!isPasswordBtn) {
                    val target = userNode ?: lastFocusedNode ?: passNode
                    replaceTextInNode(target, value, usePaste)
                } else {
                    if (userNode != null && defaultId.isNotEmpty() && isNodeEmpty(userNode)) {
                        replaceTextInNode(userNode, defaultId, usePaste)
                        mainHandler.postDelayed({
                            replaceTextInNode(passNode, value, usePaste)
                        }, 180)
                    } else {
                        replaceTextInNode(passNode, value, usePaste)
                    }
                }
            } else if (editNodes.size in 2..3) {
                val userNode = editNodes[0]
                val passNode = editNodes[1]
                if (!isPasswordBtn) {
                    replaceTextInNode(userNode, value, usePaste)
                } else {
                    if (defaultId.isNotEmpty() && isNodeEmpty(userNode)) {
                        replaceTextInNode(userNode, defaultId, usePaste)
                        mainHandler.postDelayed({
                            replaceTextInNode(passNode, value, usePaste)
                        }, 180)
                    } else {
                        replaceTextInNode(passNode, value, usePaste)
                    }
                }
            } else {
                val target = root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: lastFocusedNode
                if (target != null) {
                    replaceTextInNode(target, value, usePaste)
                } else {
                    Toast.makeText(this, "Panoya kopyalandı! Kutuya dokunup yapıştır.", Toast.LENGTH_SHORT).show()
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
