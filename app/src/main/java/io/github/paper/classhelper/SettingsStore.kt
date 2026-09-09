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

    var chaoxingUsername: String
        get() = prefs.getString("chaoxing_username", "")!!
        set(value) = prefs.edit().putString("chaoxing_username", value.trim()).apply()

    /** Encrypted at rest with AndroidKeyStore AES-GCM; never stored in plain SharedPreferences. */
    var chaoxingPassword: String
        get() = secrets.get("chaoxing_password")
        set(value) = secrets.put("chaoxing_password", value)

    /** Session cookie is encrypted at rest as well. */
    var chaoxingCookie: String
        get() = secrets.get("chaoxing_cookie")
        set(value) = secrets.put("chaoxing_cookie", value.trim())

    var chaoxingCourseId: String
        get() = prefs.getString("chaoxing_course_id", "")!!
        set(value) = prefs.edit().putString("chaoxing_course_id", value).apply()

    var chaoxingClassId: String
        get() = prefs.getString("chaoxing_class_id", "")!!
        set(value) = prefs.edit().putString("chaoxing_class_id", value).apply()

    var chaoxingCpi: String
        get() = prefs.getString("chaoxing_cpi", "")!!
        set(value) = prefs.edit().putString("chaoxing_cpi", value).apply()

    var chaoxingCourseName: String
        get() = prefs.getString("chaoxing_course_name", "")!!
        set(value) = prefs.edit().putString("chaoxing_course_name", value).apply()

    var chaoxingCourseDocumentId: String?
        get() = prefs.getString("chaoxing_course_document_id", null)
        set(value) = prefs.edit().putString("chaoxing_course_document_id", value).apply()

    var chaoxingLastSync: Long
        get() = prefs.getLong("chaoxing_last_sync", 0L)
        set(value) = prefs.edit().putLong("chaoxing_last_sync", value).apply()

    /** First-class active course. It may exist even when no PDF is open. */
    var currentCourseId: String?
        get() = prefs.getString("current_course_id", null)
        set(value) = prefs.edit().putString("current_course_id", value).apply()

    var currentCourseName: String
        get() = prefs.getString("current_course_name", "")!!
        set(value) = prefs.edit().putString("current_course_name", value.trim()).apply()

    /** Aggregated/local or Chaoxing knowledge document used by classroom retrieval. */
    var currentCourseKnowledgeDocumentId: String?
        get() = prefs.getString("current_course_knowledge_document_id", null)
        set(value) = prefs.edit().putString("current_course_knowledge_document_id", value).apply()

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
