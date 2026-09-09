package io.github.paper.classhelper.course

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.security.MessageDigest
import java.util.UUID

/**
 * First-class course catalog. A course is not a PDF: it can exist with zero or many resources.
 * Chaoxing courses and local courses share the same model; PDFs are only child resources.
 */
data class CourseRow(
    val id: String,
    val name: String,
    val source: String,
    val sourceKey: String,
    val knowledgeDocumentId: String,
    val updatedAt: Long,
)

data class CourseResourceRow(
    val id: String,
    val courseId: String,
    val title: String,
    val kind: String,
    val documentId: String?,
    val localPath: String,
    val remoteUrl: String,
    val sourceKey: String,
    val updatedAt: Long,
)

class CourseCatalog(context: Context) : SQLiteOpenHelper(context, "course_catalog.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE courses(
              id TEXT PRIMARY KEY,
              name TEXT NOT NULL,
              source TEXT NOT NULL,
              source_key TEXT NOT NULL,
              knowledge_document_id TEXT NOT NULL,
              updated_at INTEGER NOT NULL,
              UNIQUE(source, source_key)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE resources(
              id TEXT PRIMARY KEY,
              course_id TEXT NOT NULL,
              title TEXT NOT NULL,
              kind TEXT NOT NULL,
              document_id TEXT,
              local_path TEXT NOT NULL DEFAULT '',
              remote_url TEXT NOT NULL DEFAULT '',
              source_key TEXT NOT NULL,
              updated_at INTEGER NOT NULL,
              UNIQUE(course_id, source_key)
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_course_updated ON courses(updated_at DESC)")
        db.execSQL("CREATE INDEX idx_resource_course ON resources(course_id,updated_at DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun createLocal(name: String): CourseRow {
        val clean = name.trim().ifBlank { "未命名课程" }
        val id = "local-${UUID.randomUUID()}"
        val row = CourseRow(
            id = id,
            name = clean,
            source = "local",
            sourceKey = id,
            knowledgeDocumentId = "course-${stableHash(id).take(28)}",
            updatedAt = System.currentTimeMillis(),
        )
        upsertCourse(row)
        return row
    }

    fun upsertChaoxing(
        courseId: String,
        classId: String,
        cpi: String,
        name: String,
        knowledgeDocumentId: String,
    ): CourseRow {
        val key = "$courseId:$classId:$cpi"
        val row = CourseRow(
            id = "cx-course-${stableHash(key).take(24)}",
            name = name.trim().ifBlank { "未命名课程" },
            source = "chaoxing",
            sourceKey = key,
            knowledgeDocumentId = knowledgeDocumentId,
            updatedAt = System.currentTimeMillis(),
        )
        upsertCourse(row)
        return row
    }

    fun upsertCourse(row: CourseRow) {
        writableDatabase.insertWithOnConflict(
            "courses",
            null,
            ContentValues().apply {
                put("id", row.id)
                put("name", row.name)
                put("source", row.source)
                put("source_key", row.sourceKey)
                put("knowledge_document_id", row.knowledgeDocumentId)
                put("updated_at", System.currentTimeMillis())
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun listCourses(): List<CourseRow> = readableDatabase.rawQuery(
        "SELECT id,name,source,source_key,knowledge_document_id,updated_at FROM courses ORDER BY updated_at DESC,name COLLATE NOCASE",
        null,
    ).use { c ->
        buildList {
            while (c.moveToNext()) {
                add(
                    CourseRow(
                        id = c.getString(0),
                        name = c.getString(1),
                        source = c.getString(2),
                        sourceKey = c.getString(3),
                        knowledgeDocumentId = c.getString(4),
                        updatedAt = c.getLong(5),
                    ),
                )
            }
        }
    }

    fun getCourse(id: String): CourseRow? = readableDatabase.rawQuery(
        "SELECT id,name,source,source_key,knowledge_document_id,updated_at FROM courses WHERE id=?",
        arrayOf(id),
    ).use { c ->
        if (!c.moveToFirst()) null else CourseRow(
            id = c.getString(0),
            name = c.getString(1),
            source = c.getString(2),
            sourceKey = c.getString(3),
            knowledgeDocumentId = c.getString(4),
            updatedAt = c.getLong(5),
        )
    }

    fun upsertResource(
        courseId: String,
        title: String,
        kind: String,
        documentId: String?,
        localPath: String = "",
        remoteUrl: String = "",
        sourceKey: String,
    ): CourseResourceRow {
        val id = "res-${stableHash("$courseId:$sourceKey").take(28)}"
        val row = CourseResourceRow(
            id = id,
            courseId = courseId,
            title = title.trim().ifBlank { "未命名资料" },
            kind = kind,
            documentId = documentId,
            localPath = localPath,
            remoteUrl = remoteUrl,
            sourceKey = sourceKey,
            updatedAt = System.currentTimeMillis(),
        )
        writableDatabase.insertWithOnConflict(
            "resources",
            null,
            ContentValues().apply {
                put("id", row.id)
                put("course_id", row.courseId)
                put("title", row.title)
                put("kind", row.kind)
                put("document_id", row.documentId)
                put("local_path", row.localPath)
                put("remote_url", row.remoteUrl)
                put("source_key", row.sourceKey)
                put("updated_at", row.updatedAt)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
        touch(courseId)
        return row
    }

    fun listResources(courseId: String): List<CourseResourceRow> = readableDatabase.rawQuery(
        "SELECT id,course_id,title,kind,document_id,local_path,remote_url,source_key,updated_at FROM resources WHERE course_id=? ORDER BY updated_at DESC,title COLLATE NOCASE",
        arrayOf(courseId),
    ).use { c ->
        buildList {
            while (c.moveToNext()) {
                add(
                    CourseResourceRow(
                        id = c.getString(0),
                        courseId = c.getString(1),
                        title = c.getString(2),
                        kind = c.getString(3),
                        documentId = if (c.isNull(4)) null else c.getString(4),
                        localPath = c.getString(5),
                        remoteUrl = c.getString(6),
                        sourceKey = c.getString(7),
                        updatedAt = c.getLong(8),
                    ),
                )
            }
        }
    }

    fun deleteCourse(id: String) {
        writableDatabase.beginTransaction()
        try {
            writableDatabase.delete("resources", "course_id=?", arrayOf(id))
            writableDatabase.delete("courses", "id=?", arrayOf(id))
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    private fun touch(courseId: String) {
        writableDatabase.update(
            "courses",
            ContentValues().apply { put("updated_at", System.currentTimeMillis()) },
            "id=?",
            arrayOf(courseId),
        )
    }

    private fun stableHash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }
}
