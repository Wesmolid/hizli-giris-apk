package com.hizligiris.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.*

class MainActivity : Activity() {

    private val inputs = mutableMapOf<String, EditText>()
    private val keys = listOf("id1", "id2", "id3", "id4", "s1", "s2", "s3", "s4")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("HizliGirisPrefs", Context.MODE_PRIVATE)

        val scroll = ScrollView(this).apply { setBackgroundColor(Color.parseColor("#1f232a")) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(45, 45, 45, 45)
        }

        fun addSectionTitle(title: String) {
            root.addView(TextView(this).apply {
                text = title
                textSize = 16f
                setTextColor(Color.parseColor("#ff5722"))
                setPadding(0, 30, 0, 12)
            })
        }

        fun addInput(key: String, label: String, isPassword: Boolean) {
            root.addView(TextView(this).apply {
                text = label
                textSize = 12f
                setTextColor(Color.parseColor("#aaaaaa"))
            })
            val et = EditText(this).apply {
                setText(prefs.getString(key, ""))
                setTextColor(Color.WHITE)
                setHintTextColor(Color.GRAY)
                setBackgroundColor(Color.parseColor("#2c323d"))
                setPadding(25, 22, 25, 22)
                if (isPassword) {
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                } else {
                    inputType = InputType.TYPE_CLASS_TEXT
                }
            }
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 20 }
            root.addView(et, params)
            inputs[key] = et
        }

        addSectionTitle("👤 Kullanıcı Adları (ID)")
        addInput("id1", "ID 1 (Otomatik Doldurulur)", false)
        addInput("id2", "ID 2", false)
        addInput("id3", "ID 3", false)
        addInput("id4", "ID 4", false)

        addSectionTitle("🔑 Şifreler")
        addInput("s1", "Şifre 1 (S1 - Otomatik Doldurulur)", true)
        addInput("s2", "Şifre 2 (S2)", true)
        addInput("s3", "Şifre 3 (S3)", true)
        addInput("s4", "Şifre 4 (S4)", true)

        val pasteSwitch = CheckBox(this).apply {
            text = "Varsayılan olarak Kopyala+Yapıştır (📋) modunu kullan"
            setTextColor(Color.WHITE)
            isChecked = prefs.getBoolean("use_paste_mode", false)
        }
        root.addView(pasteSwitch, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 15 })

        val saveBtn = Button(this).apply {
            text = "KAYDET VE AKTİF ET"
            setBackgroundColor(Color.parseColor("#ff5722"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                val editor = prefs.edit()
                keys.forEach { k -> editor.putString(k, inputs[k]?.text.toString().trim()) }
                editor.putBoolean("use_paste_mode", pasteSwitch.isChecked)
                editor.apply()
                reviveService()
                Toast.makeText(this@MainActivity, "Kaydedildi ve Bar Aktif Edildi!", Toast.LENGTH_SHORT).show()
            }
        }
        root.addView(saveBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 25 })

        addSectionTitle("⚙️ Gerekli İzinler (Tek Seferlik)")

        val overlayBtn = Button(this).apply {
            text = "1. Üstte Gösterme İzni Ver"
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                } else {
                    Toast.makeText(this@MainActivity, "Üstte Gösterme İzni Zaten Açık!", Toast.LENGTH_SHORT).show()
                }
            }
        }
        root.addView(overlayBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 10 })

        val accBtn = Button(this).apply {
            text = "2. Erişilebilirlik Servisini Aç"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
        root.addView(accBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 10 })

        scroll.addView(root)
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        // Uygulamaya her girildiğinde servisi ve yüzen barı otomatik dirilt!
        reviveService()
    }

    private fun reviveService() {
        try {
            val intent = Intent(this, AutoFillService::class.java).apply {
                action = "ACTION_REVIVE_BAR"
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (e: Throwable) {
            try {
                startService(Intent(this, AutoFillService::class.java).apply {
                    action = "ACTION_REVIVE_BAR"
                })
            } catch (ex: Throwable) {}
        }
    }
}
