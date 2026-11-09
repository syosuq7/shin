package com.example.screenanswer

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    // Провайдеры и модели — из BUILTIN в index.html. Модель должна уметь читать картинки!
    data class Prov(val name: String, val type: String, val url: String, val model: String)

    private val provs = listOf(
        Prov("Gemini", "gemini", "https://generativelanguage.googleapis.com/v1beta", "gemini-3.6-flash"),
        Prov("OpenRouter", "openai", "https://openrouter.ai/api/v1/chat/completions", "openrouter/free"),
        Prov("Ashna AI", "openai", "https://api.ashna.ai/v1/api/chat/completions", "gpt-6-luna"),
        Prov("Odyssey", "openai", "https://odysseyapi.tech/v1/chat/completions", "gpt-6-astra"),
        Prov("AgentRouter", "openai", "https://agentrouter.org/v1/chat/completions", "gpt-6.1-sol"),
        Prov("TokenHarbor", "openai", "https://tokenharbor.ai/v1/chat/completions", "claude-haiku-5.5:free"),
        Prov("Groq", "openai", "https://api.groq.com/openai/v1/chat/completions", "meta-llama/llama-4-scout-17b-16e-instruct"),
        Prov("Свой (OpenAI-совместимый)", "openai", "https://", "")
    )

    private val defaultPrompt =
        "На скриншоте вопрос, тест или задача. Дай правильный ответ максимально коротко: " +
        "номер/буква варианта, слово или число, плюс одно короткое пояснение, только если оно нужно. " +
        "Без вступлений. Отвечай на языке вопроса. Если несколько вопросов — ответь на каждый в строке."

    private lateinit var p: SharedPreferences
    private lateinit var spProv: Spinner
    private lateinit var etUrl: EditText
    private lateinit var etKey: EditText
    private lateinit var etModel: EditText
    private lateinit var etPrompt: EditText
    private var cur = 0

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        p = getSharedPreferences("cfg", Context.MODE_PRIVATE)
        cur = p.getInt("prov", 0).coerceIn(0, provs.size - 1)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(32), dp(20), dp(24))
        }
        fun label(t: String) = TextView(this).apply { text = t; textSize = 13f; setPadding(0, dp(14), 0, dp(2)) }

        col.addView(TextView(this).apply { text = "Screen Answer"; textSize = 24f })
        col.addView(label("Провайдер"))
        spProv = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, provs.map { it.name })
        }
        col.addView(spProv)

        col.addView(label("URL"))
        etUrl = EditText(this).apply { setSingleLine(); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI }
        col.addView(etUrl)
        col.addView(label("API-ключ"))
        etKey = EditText(this).apply { setSingleLine(); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        col.addView(etKey)
        col.addView(label("Модель (с поддержкой изображений)"))
        etModel = EditText(this).apply { setSingleLine() }
        col.addView(etModel)
        col.addView(label("Инструкция для нейросети"))
        etPrompt = EditText(this).apply { setText(p.getString("prompt", defaultPrompt)); minLines = 3 }
        col.addView(etPrompt)

        col.addView(Button(this).apply {
            text = "Запустить кружок"
            setOnClickListener { start() }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(24) })
        col.addView(Button(this).apply {
            text = "Остановить"
            setOnClickListener { stopService(Intent(this@MainActivity, OverlayService::class.java)) }
        })

        loadFields(cur)
        spProv.setSelection(cur, false)
        spProv.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (pos == cur) return
                saveFields(cur)
                cur = pos
                loadFields(cur)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        setContentView(ScrollView(this).apply { addView(col) })
    }

    private fun loadFields(i: Int) {
        etUrl.setText(p.getString("url$i", provs[i].url))
        etKey.setText(p.getString("key$i", ""))
        etModel.setText(p.getString("model$i", provs[i].model))
    }

    private fun saveFields(i: Int) {
        p.edit()
            .putString("url$i", etUrl.text.toString().trim())
            .putString("key$i", etKey.text.toString().trim())
            .putString("model$i", etModel.text.toString().trim())
            .putString("prompt", etPrompt.text.toString().ifBlank { defaultPrompt })
            .putInt("prov", i)
            .putString("type", provs[i].type)
            .apply()
    }

    private fun start() {
        saveFields(cur)
        if (etKey.text.isBlank() || etModel.text.isBlank()) {
            Toast.makeText(this, "Укажите ключ и модель", Toast.LENGTH_SHORT).show(); return
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Разрешите показ поверх других приложений и нажмите «Запустить» снова", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), 100)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 100 && resultCode == RESULT_OK && data != null) {
            val i = Intent(this, OverlayService::class.java).putExtra("code", resultCode).putExtra("data", data)
            startForegroundService(i)
            moveTaskToBack(true)
        }
    }
}
