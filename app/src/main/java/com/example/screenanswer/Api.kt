package com.example.screenanswer

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class Cfg(val type: String, val url: String, val key: String, val model: String, val prompt: String)

object Api {
    class HttpError(val code: Int, msg: String) : RuntimeException(msg)

    // провайдеры/модели, которые не приняли параметры отключения размышлений — для них шлём запрос без них
    private val noThink = HashSet<String>()

    fun ask(cfg: Cfg, jpegB64: String): String {
        val raw = withoutThinking(cfg) { off ->
            if (cfg.type == "gemini") gemini(cfg, jpegB64, off) else openai(cfg, jpegB64, off)
        }
        return raw.replace(Regex("(?s)<think>.*?</think>"), "").trim()
    }

    // Сначала пробуем с отключённым размышлением. Если API ответил 400/422 (параметр не поддерживается),
    // повторяем без него и запоминаем это, чтобы не терять время на следующих запросах.
    private fun withoutThinking(cfg: Cfg, run: (Boolean) -> String): String {
        val key = cfg.url + "|" + cfg.model
        if (key in noThink) return run(false)
        return try {
            run(true)
        } catch (e: HttpError) {
            if (e.code != 400 && e.code != 422) throw e
            val r = run(false)
            noThink.add(key)
            r
        }
    }

    private fun post(url: String, headers: Map<String, String>, body: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 20000
        c.readTimeout = 90000
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
        if (code !in 200..299) throw HttpError(code, "HTTP $code: ${text.take(300)}")
        return text
    }

    // OpenAI-совместимый формат (как streamOpenAI в index.html), без стрима
    private fun openai(cfg: Cfg, b64: String, noThinking: Boolean): String {
        val content = JSONArray()
            .put(JSONObject().put("type", "text").put("text", cfg.prompt))
            .put(
                JSONObject().put("type", "image_url")
                    .put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$b64"))
            )
        val body = JSONObject().put("model", cfg.model)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
        if (noThinking) {
            body.put("reasoning_effort", "none")                                   // OpenAI-стиль
            body.put("reasoning", JSONObject().put("effort", "none"))              // OpenRouter-стиль
        }
        val r = JSONObject(post(cfg.url, mapOf("Authorization" to "Bearer ${cfg.key}"), body.toString()))
        val msg = r.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
        return if (msg.isNull("content")) "" else msg.getString("content")
    }

    // Gemini (как streamGemini в index.html), без стрима
    private fun gemini(cfg: Cfg, b64: String, noThinking: Boolean): String {
        val parts = JSONArray()
            .put(JSONObject().put("text", cfg.prompt))
            .put(JSONObject().put("inline_data", JSONObject().put("mime_type", "image/jpeg").put("data", b64)))
        val body = JSONObject().put(
            "contents",
            JSONArray().put(JSONObject().put("role", "user").put("parts", parts))
        )
        if (noThinking) {
            body.put(
                "generationConfig",
                JSONObject().put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
            )
        }
        val url = "${cfg.url.trimEnd('/')}/models/${cfg.model}:generateContent?key=${URLEncoder.encode(cfg.key, "UTF-8")}"
        val r = JSONObject(post(url, emptyMap(), body.toString()))
        val ps = r.getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts")
        val sb = StringBuilder()
        for (i in 0 until ps.length()) {
            val p = ps.getJSONObject(i)
            if (!p.optBoolean("thought", false) && p.has("text")) sb.append(p.getString("text"))
        }
        return sb.toString()
    }
}
