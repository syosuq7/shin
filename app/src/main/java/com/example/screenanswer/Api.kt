package com.example.screenanswer

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class Cfg(val type: String, val url: String, val key: String, val model: String, val prompt: String)

object Api {
    fun ask(cfg: Cfg, jpegB64: String): String {
        val raw = if (cfg.type == "gemini") gemini(cfg, jpegB64) else openai(cfg, jpegB64)
        return raw.replace(Regex("(?s)<think>.*?</think>"), "").trim()
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
        if (code !in 200..299) throw RuntimeException("HTTP $code: ${text.take(300)}")
        return text
    }

    // OpenAI-совместимый формат (как streamOpenAI в index.html), без стрима
    private fun openai(cfg: Cfg, b64: String): String {
        val content = JSONArray()
            .put(JSONObject().put("type", "text").put("text", cfg.prompt))
            .put(
                JSONObject().put("type", "image_url")
                    .put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$b64"))
            )
        val body = JSONObject().put("model", cfg.model)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
        val r = JSONObject(post(cfg.url, mapOf("Authorization" to "Bearer ${cfg.key}"), body.toString()))
        val msg = r.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
        val t = if (msg.isNull("content")) "" else msg.getString("content")
        return t
    }

    // Gemini (как streamGemini в index.html), без стрима
    private fun gemini(cfg: Cfg, b64: String): String {
        val parts = JSONArray()
            .put(JSONObject().put("text", cfg.prompt))
            .put(JSONObject().put("inline_data", JSONObject().put("mime_type", "image/jpeg").put("data", b64)))
        val body = JSONObject().put(
            "contents",
            JSONArray().put(JSONObject().put("role", "user").put("parts", parts))
        )
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
