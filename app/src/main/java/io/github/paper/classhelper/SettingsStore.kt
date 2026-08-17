package io.github.paper.classhelper

import android.content.Context

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val secrets = SecretStore(context)

    var hotwords: String
        get() = prefs.getString("hotwords", "")!!
        set(value) = prefs.edit().putString("hotwords", value).apply()

    var llmBaseUrl: String
        get() = prefs.getString("llm_base_url", "https://api.openai.com/v1")!!
        set(value) = prefs.edit().putString("llm_base_url", value.trim().trimEnd('/')).apply()

    var llmApiKey: String
        get() = secrets.get("llm_api_key")
        set(value) = secrets.put("llm_api_key", value.trim())

    var llmModel: String
        get() = prefs.getString("llm_model", "gpt-4.1-mini")!!
        set(value) = prefs.edit().putString("llm_model", value.trim()).apply()

    var autoNotes: Boolean
        get() = prefs.getBoolean("auto_notes", true)
        set(value) = prefs.edit().putBoolean("auto_notes", value).apply()

    var autoOcr: Boolean
        get() = prefs.getBoolean("auto_ocr", true)
        set(value) = prefs.edit().putBoolean("auto_ocr", value).apply()

    var ocrHighAccuracy: Boolean
        get() = prefs.getBoolean("ocr_high_accuracy", false)
        set(value) = prefs.edit().putBoolean("ocr_high_accuracy", value).apply()

    var showAnswerNotification: Boolean
        get() = prefs.getBoolean("answer_notification", true)
        set(value) = prefs.edit().putBoolean("answer_notification", value).apply()

    var currentDocumentId: String?
        get() = prefs.getString("current_document_id", null)
        set(value) = prefs.edit().putString("current_document_id", value).apply()

    var currentPage: Int
        get() = prefs.getInt("current_page", 0)
        set(value) = prefs.edit().putInt("current_page", value).apply()

    var activeSessionId: String?
        get() = prefs.getString("active_session_id", null)
        set(value) = prefs.edit().putString("active_session_id", value).apply()

    var penColor: Int
        get() = prefs.getInt("pen_color", 0xff111111.toInt())
        set(value) = prefs.edit().putInt("pen_color", value).apply()

    var penWidthDp: Float
        get() = prefs.getFloat("pen_width_dp", 2.2f)
        set(value) = prefs.edit().putFloat("pen_width_dp", value.coerceIn(0.8f, 8f)).apply()
}
