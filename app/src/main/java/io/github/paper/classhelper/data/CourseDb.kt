package io.github.paper.classhelper.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.UUID

data class DocumentRow(
    val id: String,
    val sourceUri: String,
    val workingPath: String,
    val title: String,
    val dirty: Boolean,
    val kind: String = "pdf",
    val indexedAt: Long = 0L
)

data class ChunkRow(val documentId: String, val page: Int, val title: String, val text: String)
data class TranscriptRow(val id: Long, val ts: Long, val text: String, val sessionId: String? = null)
data class QuestionRow(val id: Long, val ts: Long, val question: String, val answer: String, val sessionId: String? = null, val evidence: String = "")
data class NoteRow(val id: Long, val ts: Long, val text: String, val sessionId: String? = null)
data class SessionRow(val id: String, val startedAt: Long, val endedAt: Long?, val title: String, val primaryDocumentId: String?)
data class BookmarkRow(val documentId: String, val page: Int, val label: String, val createdAt: Long)
data class AnnotationHistoryRow(val annotationId: String, val documentId: String, val page: Int, val kind: String, val payload: String)
data class LibraryMetaRow(
    val itemType: String,
    val itemId: String,
    val primaryCategory: String = "",
    val subCategory: String = "",
    val course: String = "",
    val topic: String = "",
    val tags: String = "",
    val note: String = "",
    val status: String = "收件箱",
    val starred: Boolean = false,
    val managedBy: String = "manual",
    val confidence: Int = 0,
    val reason: String = "",
    val updatedAt: Long = 0L
)

data class SessionContentStats(val transcripts: Int, val questions: Int, val notes: Int)
data class DocumentContentStats(val chunks: Int, val bookmarks: Int, val annotations: Int, val pdfNotes: Int)
data class StorageContentCounts(
    val documents: Int, val sessions: Int, val transcripts: Int, val questions: Int,
    val notes: Int, val bookmarks: Int, val annotations: Int
)

data class JournalRow(
    val id: Long,
    val documentId: String,
    val page: Int,
    val kind: String,
    val payload: String,
    val annotationId: String
)

class CourseDb(context: Context) : SQLiteOpenHelper(context, "classhelper.db", null, 7) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE documents(
              id TEXT PRIMARY KEY,
              source_uri TEXT NOT NULL,
              working_path TEXT NOT NULL,
              title TEXT NOT NULL,
              dirty INTEGER NOT NULL DEFAULT 0,
              updated_at INTEGER NOT NULL,
              kind TEXT NOT NULL DEFAULT 'pdf',
              indexed_at INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())
        db.execSQL("""
            CREATE VIRTUAL TABLE document_chunks USING fts4(
              document_id TEXT,
              page INTEGER,
              title TEXT,
              text TEXT,
              tokenize=unicode61
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE sessions(
              id TEXT PRIMARY KEY,
              started_at INTEGER NOT NULL,
              ended_at INTEGER,
              title TEXT NOT NULL,
              primary_document_id TEXT
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX idx_sessions_started ON sessions(started_at)")
        db.execSQL("""
            CREATE TABLE transcripts(
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              ts INTEGER NOT NULL,
              text TEXT NOT NULL,
              session_id TEXT,
              document_id TEXT,
              page INTEGER
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX idx_transcript_ts ON transcripts(ts)")
        db.execSQL("CREATE INDEX idx_transcript_session ON transcripts(session_id,id)")
        db.execSQL("""
            CREATE TABLE questions(
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              ts INTEGER NOT NULL,
              question TEXT NOT NULL,
              answer TEXT NOT NULL,
              session_id TEXT,
              evidence TEXT NOT NULL DEFAULT ''
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX idx_question_session ON questions(session_id,id)")
        db.execSQL("""
            CREATE TABLE notes(
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              ts INTEGER NOT NULL,
              text TEXT NOT NULL,
              session_id TEXT
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX idx_note_session ON notes(session_id,id)")
        db.execSQL("""
            CREATE TABLE annotation_journal(
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              document_id TEXT NOT NULL,
              page INTEGER NOT NULL,
              kind TEXT NOT NULL,
              payload TEXT NOT NULL,
              annotation_id TEXT NOT NULL,
              created_at INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX idx_journal_document ON annotation_journal(document_id,id)")
        db.execSQL("CREATE INDEX idx_journal_annotation ON annotation_journal(annotation_id)")
        db.execSQL("""
            CREATE TABLE bookmarks(
              document_id TEXT NOT NULL,
              page INTEGER NOT NULL,
              label TEXT NOT NULL DEFAULT '',
              created_at INTEGER NOT NULL,
              PRIMARY KEY(document_id,page)
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE pdf_note_texts(
              annotation_id TEXT PRIMARY KEY,
              document_id TEXT NOT NULL,
              page INTEGER NOT NULL,
              text TEXT NOT NULL,
              updated_at INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE annotation_history(
              annotation_id TEXT PRIMARY KEY,
              document_id TEXT NOT NULL,
              page INTEGER NOT NULL,
              kind TEXT NOT NULL,
              payload TEXT NOT NULL,
              updated_at INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX idx_annotation_history_document ON annotation_history(document_id,page)")
        createLibraryMetaTable(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 3) {
            listOf("documents", "document_chunks", "sessions", "transcripts", "questions", "notes", "annotation_journal", "bookmarks", "pdf_note_texts", "annotation_history")
                .forEach { db.execSQL("DROP TABLE IF EXISTS $it") }
            onCreate(db)
            return
        }
        if (oldVersion < 4) {
            addColumn(db, "documents", "kind", "TEXT NOT NULL DEFAULT 'pdf'")
            addColumn(db, "documents", "indexed_at", "INTEGER NOT NULL DEFAULT 0")
            db.execSQL("CREATE TABLE IF NOT EXISTS sessions(id TEXT PRIMARY KEY,started_at INTEGER NOT NULL,ended_at INTEGER,title TEXT NOT NULL,primary_document_id TEXT)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_sessions_started ON sessions(started_at)")
            addColumn(db, "transcripts", "session_id", "TEXT")
            addColumn(db, "transcripts", "document_id", "TEXT")
            addColumn(db, "transcripts", "page", "INTEGER")
            addColumn(db, "questions", "session_id", "TEXT")
            addColumn(db, "questions", "evidence", "TEXT NOT NULL DEFAULT ''")
            addColumn(db, "notes", "session_id", "TEXT")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_transcript_session ON transcripts(session_id,id)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_question_session ON questions(session_id,id)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_note_session ON notes(session_id,id)")
            db.execSQL("CREATE TABLE IF NOT EXISTS bookmarks(document_id TEXT NOT NULL,page INTEGER NOT NULL,label TEXT NOT NULL DEFAULT '',created_at INTEGER NOT NULL,PRIMARY KEY(document_id,page))")
            db.execSQL("CREATE TABLE IF NOT EXISTS pdf_note_texts(annotation_id TEXT PRIMARY KEY,document_id TEXT NOT NULL,page INTEGER NOT NULL,text TEXT NOT NULL,updated_at INTEGER NOT NULL)")
        }
        if (oldVersion < 5) {
            addColumn(db, "annotation_journal", "annotation_id", "TEXT NOT NULL DEFAULT ''")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_journal_annotation ON annotation_journal(annotation_id)")
        }
        if (oldVersion < 6) {
            db.execSQL("CREATE TABLE IF NOT EXISTS annotation_history(annotation_id TEXT PRIMARY KEY,document_id TEXT NOT NULL,page INTEGER NOT NULL,kind TEXT NOT NULL,payload TEXT NOT NULL,updated_at INTEGER NOT NULL)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_annotation_history_document ON annotation_history(document_id,page)")
        }
        if (oldVersion < 7) {
            createLibraryMetaTable(db)
        }
    }

    private fun createLibraryMetaTable(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS library_meta(
              item_type TEXT NOT NULL,
              item_id TEXT NOT NULL,
              primary_category TEXT NOT NULL DEFAULT '',
              sub_category TEXT NOT NULL DEFAULT '',
              course TEXT NOT NULL DEFAULT '',
              topic TEXT NOT NULL DEFAULT '',
              tags TEXT NOT NULL DEFAULT '',
              note TEXT NOT NULL DEFAULT '',
              status TEXT NOT NULL DEFAULT '收件箱',
              starred INTEGER NOT NULL DEFAULT 0,
              managed_by TEXT NOT NULL DEFAULT 'manual',
              confidence INTEGER NOT NULL DEFAULT 0,
              reason TEXT NOT NULL DEFAULT '',
              updated_at INTEGER NOT NULL,
              PRIMARY KEY(item_type,item_id)
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_library_meta_category ON library_meta(primary_category,sub_category)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_library_meta_status ON library_meta(status,starred,updated_at)")
    }

    private fun addColumn(db: SQLiteDatabase, table: String, column: String, definition: String) {
        if (!columnExists(db, table, column)) db.execSQL("ALTER TABLE $table ADD COLUMN $column $definition")
    }

    private fun columnExists(db: SQLiteDatabase, table: String, column: String): Boolean =
        db.rawQuery("PRAGMA table_info($table)", null).use { c ->
            val idx = c.getColumnIndex("name")
            while (c.moveToNext()) if (idx >= 0 && c.getString(idx) == column) return@use true
            false
        }

    fun upsertDocument(row: DocumentRow) {
        writableDatabase.insertWithOnConflict("documents", null, ContentValues().apply {
            put("id", row.id); put("source_uri", row.sourceUri); put("working_path", row.workingPath)
            put("title", row.title); put("dirty", if (row.dirty) 1 else 0); put("updated_at", System.currentTimeMillis())
            put("kind", row.kind); put("indexed_at", row.indexedAt)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getDocument(id: String): DocumentRow? = readableDatabase.rawQuery(
        "SELECT id,source_uri,working_path,title,dirty,kind,indexed_at FROM documents WHERE id=?", arrayOf(id)
    ).use { c -> if (c.moveToFirst()) document(c) else null }

    fun findDocumentBySource(uri: String): DocumentRow? = readableDatabase.rawQuery(
        "SELECT id,source_uri,working_path,title,dirty,kind,indexed_at FROM documents WHERE source_uri=?", arrayOf(uri)
    ).use { c -> if (c.moveToFirst()) document(c) else null }

    fun listDocuments(limit: Int = 100): List<DocumentRow> = readableDatabase.rawQuery(
        "SELECT id,source_uri,working_path,title,dirty,kind,indexed_at FROM documents ORDER BY updated_at DESC LIMIT ?", arrayOf(limit.toString())
    ).use { c -> buildList { while (c.moveToNext()) add(document(c)) } }

    fun deleteDocument(id: String) {
        writableDatabase.beginTransaction()
        try {
            writableDatabase.delete("document_chunks", "document_id=?", arrayOf(id))
            writableDatabase.delete("bookmarks", "document_id=?", arrayOf(id))
            writableDatabase.delete("pdf_note_texts", "document_id=?", arrayOf(id))
            writableDatabase.delete("annotation_journal", "document_id=?", arrayOf(id))
            writableDatabase.delete("annotation_history", "document_id=?", arrayOf(id))
            writableDatabase.delete("library_meta", "item_type='document' AND item_id=?", arrayOf(id))
            writableDatabase.delete("documents", "id=?", arrayOf(id))
            writableDatabase.setTransactionSuccessful()
        } finally { writableDatabase.endTransaction() }
    }

    fun setDocumentDirty(id: String, dirty: Boolean) {
        val value = if (dirty) 1 else 0
        writableDatabase.update("documents", ContentValues().apply {
            put("dirty", value); put("updated_at", System.currentTimeMillis())
        }, "id=? AND dirty<>?", arrayOf(id, value.toString()))
    }

    fun setIndexed(id: String, at: Long = System.currentTimeMillis()) {
        writableDatabase.update("documents", ContentValues().apply { put("indexed_at", at); put("updated_at", System.currentTimeMillis()) }, "id=?", arrayOf(id))
    }

    fun replaceChunks(documentId: String, chunks: List<ChunkRow>) {
        writableDatabase.beginTransaction()
        try {
            writableDatabase.delete("document_chunks", "document_id=?", arrayOf(documentId))
            val stmt = writableDatabase.compileStatement("INSERT INTO document_chunks(document_id,page,title,text) VALUES(?,?,?,?)")
            chunks.forEach { ch ->
                stmt.clearBindings(); stmt.bindString(1, ch.documentId); stmt.bindLong(2, ch.page.toLong())
                stmt.bindString(3, ch.title); stmt.bindString(4, ch.text); stmt.executeInsert()
            }
            writableDatabase.setTransactionSuccessful()
        } finally { writableDatabase.endTransaction() }
        setIndexed(documentId)
    }

    fun searchChunks(documentId: String?, query: String, limit: Int = 8): List<ChunkRow> {
        val clean = query.replace(Regex("[^\\p{L}\\p{N}_]+"), " ").trim()
        if (clean.isBlank()) return emptyList()
        val match = clean.split(Regex("\\s+")).filter { it.length >= 2 }.take(8).joinToString(" OR ") { "\"$it\"" }
        if (match.isBlank()) return emptyList()
        val sql = if (documentId != null)
            "SELECT document_id,page,title,text FROM document_chunks WHERE document_chunks MATCH ? AND document_id=? LIMIT ?"
        else "SELECT document_id,page,title,text FROM document_chunks WHERE document_chunks MATCH ? LIMIT ?"
        val args = if (documentId != null) arrayOf(match, documentId, limit.toString()) else arrayOf(match, limit.toString())
        return runCatching { readableDatabase.rawQuery(sql, args).use { c -> chunks(c) } }.getOrDefault(emptyList())
    }

    fun searchCurrentDocumentLike(documentId: String, query: String, limit: Int = 20): List<ChunkRow> {
        val terms = query.trim().split(Regex("\\s+")).filter { it.length >= 2 }.take(4)
        if (terms.isEmpty()) return emptyList()
        val where = terms.joinToString(" OR ") { "text LIKE ? OR title LIKE ?" }
        val args = mutableListOf<String>()
        terms.forEach { t -> args += "%$t%"; args += "%$t%" }
        args += documentId; args += limit.toString()
        return readableDatabase.rawQuery(
            "SELECT document_id,page,title,text FROM document_chunks WHERE ($where) AND document_id=? ORDER BY page LIMIT ?",
            args.toTypedArray()
        ).use { c -> chunks(c) }
    }

    fun chunksNearPage(documentId: String, page: Int, radius: Int = 2): List<ChunkRow> = readableDatabase.rawQuery(
        "SELECT document_id,page,title,text FROM document_chunks WHERE document_id=? AND page BETWEEN ? AND ? ORDER BY page",
        arrayOf(documentId, (page - radius).coerceAtLeast(0).toString(), (page + radius).toString())
    ).use { c -> chunks(c) }

    fun allChunks(documentId: String, limit: Int = 500): List<ChunkRow> = readableDatabase.rawQuery(
        "SELECT document_id,page,title,text FROM document_chunks WHERE document_id=? ORDER BY page LIMIT ?", arrayOf(documentId, limit.toString())
    ).use { c -> chunks(c) }

    fun allChunksAcross(limit: Int = 2000): List<ChunkRow> = readableDatabase.rawQuery(
        "SELECT document_id,page,title,text FROM document_chunks ORDER BY document_id,page LIMIT ?", arrayOf(limit.toString())
    ).use { c -> chunks(c) }

    fun startSession(title: String, primaryDocumentId: String?): String {
        val id = UUID.randomUUID().toString()
        writableDatabase.insertOrThrow("sessions", null, ContentValues().apply {
            put("id", id); put("started_at", System.currentTimeMillis()); put("title", title); put("primary_document_id", primaryDocumentId)
        })
        return id
    }

    fun endSession(id: String) {
        writableDatabase.update("sessions", ContentValues().apply { put("ended_at", System.currentTimeMillis()) }, "id=?", arrayOf(id))
    }

    fun getSession(id: String): SessionRow? = readableDatabase.rawQuery(
        "SELECT id,started_at,ended_at,title,primary_document_id FROM sessions WHERE id=?", arrayOf(id)
    ).use { c -> if (c.moveToFirst()) session(c) else null }

    fun recentSessions(limit: Int = 40): List<SessionRow> = readableDatabase.rawQuery(
        "SELECT id,started_at,ended_at,title,primary_document_id FROM sessions ORDER BY started_at DESC LIMIT ?", arrayOf(limit.toString())
    ).use { c -> buildList { while (c.moveToNext()) add(session(c)) } }

    fun addTranscript(text: String, sessionId: String? = null, documentId: String? = null, page: Int? = null): Long =
        writableDatabase.insert("transcripts", null, ContentValues().apply {
            put("ts", System.currentTimeMillis()); put("text", text); put("session_id", sessionId); put("document_id", documentId)
            if (page != null) put("page", page)
        })

    fun recentTranscripts(limit: Int = 20, sessionId: String? = null): List<TranscriptRow> {
        val (where, args) = if (sessionId != null) "WHERE session_id=?" to arrayOf(sessionId, limit.toString()) else "" to arrayOf(limit.toString())
        return readableDatabase.rawQuery("SELECT id,ts,text,session_id FROM transcripts $where ORDER BY id DESC LIMIT ?", args).use { c ->
            buildList { while (c.moveToNext()) add(TranscriptRow(c.getLong(0), c.getLong(1), c.getString(2), c.getString(3))) }.reversed()
        }
    }

    fun addQuestion(question: String, answer: String, sessionId: String? = null, evidence: String = ""): Long =
        writableDatabase.insert("questions", null, ContentValues().apply {
            put("ts", System.currentTimeMillis()); put("question", question); put("answer", answer); put("session_id", sessionId); put("evidence", evidence)
        })

    fun recentQuestions(limit: Int = 8, sessionId: String? = null): List<QuestionRow> {
        val (where, args) = if (sessionId != null) "WHERE session_id=?" to arrayOf(sessionId, limit.toString()) else "" to arrayOf(limit.toString())
        return readableDatabase.rawQuery("SELECT id,ts,question,answer,session_id,evidence FROM questions $where ORDER BY id DESC LIMIT ?", args).use { c ->
            buildList { while (c.moveToNext()) add(QuestionRow(c.getLong(0), c.getLong(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5) ?: "")) }
        }
    }

    fun addNote(text: String, sessionId: String? = null): Long = writableDatabase.insert("notes", null, ContentValues().apply {
        put("ts", System.currentTimeMillis()); put("text", text); put("session_id", sessionId)
    })

    fun recentNotes(limit: Int = 12, sessionId: String? = null): List<NoteRow> {
        val (where, args) = if (sessionId != null) "WHERE session_id=?" to arrayOf(sessionId, limit.toString()) else "" to arrayOf(limit.toString())
        return readableDatabase.rawQuery("SELECT id,ts,text,session_id FROM notes $where ORDER BY id DESC LIMIT ?", args).use { c ->
            buildList { while (c.moveToNext()) add(NoteRow(c.getLong(0), c.getLong(1), c.getString(2), c.getString(3))) }
        }
    }

    fun addJournal(documentId: String, page: Int, kind: String, payload: String, annotationId: String): Long =
        writableDatabase.insert("annotation_journal", null, ContentValues().apply {
            put("document_id", documentId); put("page", page); put("kind", kind); put("payload", payload); put("annotation_id", annotationId)
            put("created_at", System.currentTimeMillis())
        })

    /**
     * Persist one newly-created annotation using a single SQLite transaction.
     *
     * Pen input used to perform two independent autocommit writes (history + journal)
     * for every stroke. On flash storage that unnecessary fsync traffic is visible as
     * small pauses while writing quickly. Keep the crash-safe journal, but commit both
     * rows together.
     */
    fun addAnnotationCommand(annotationId: String, documentId: String, page: Int, kind: String, payload: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.insertWithOnConflict("annotation_history", null, ContentValues().apply {
                put("annotation_id", annotationId); put("document_id", documentId); put("page", page)
                put("kind", kind); put("payload", payload); put("updated_at", System.currentTimeMillis())
            }, SQLiteDatabase.CONFLICT_REPLACE)
            db.insertOrThrow("annotation_journal", null, ContentValues().apply {
                put("document_id", documentId); put("page", page); put("kind", kind); put("payload", payload)
                put("annotation_id", annotationId); put("created_at", System.currentTimeMillis())
            })
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun pendingJournal(documentId: String): List<JournalRow> = readableDatabase.rawQuery(
        "SELECT id,document_id,page,kind,payload,annotation_id FROM annotation_journal WHERE document_id=? ORDER BY id", arrayOf(documentId)
    ).use { c -> buildList { while (c.moveToNext()) add(JournalRow(c.getLong(0), c.getString(1), c.getInt(2), c.getString(3), c.getString(4), c.getString(5))) } }

    fun deleteJournal(ids: List<Long>) {
        if (ids.isEmpty()) return
        writableDatabase.delete("annotation_journal", "id IN (${ids.joinToString(",") { "?" }})", ids.map { it.toString() }.toTypedArray())
    }

    fun deleteJournalByAnnotationId(annotationId: String): Int = writableDatabase.delete("annotation_journal", "annotation_id=?", arrayOf(annotationId))

    /** Queue/cancel one eraser hit with one SQLite commit instead of several autocommits. */
    fun applyAnnotationErase(documentId: String, page: Int, annotationId: String, persistedInPdf: Boolean) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (persistedInPdf) {
                db.insertOrThrow("annotation_journal", null, ContentValues().apply {
                    put("document_id", documentId); put("page", page); put("kind", "delete_id")
                    put("payload", org.json.JSONObject().put("target", annotationId).toString())
                    put("annotation_id", "erase:${UUID.randomUUID()}"); put("created_at", System.currentTimeMillis())
                })
            } else {
                db.delete("annotation_journal", "annotation_id=?", arrayOf(annotationId))
            }
            db.delete("pdf_note_texts", "annotation_id=?", arrayOf(annotationId))
            db.delete("annotation_history", "annotation_id=?", arrayOf(annotationId))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Queue a whole-page clear in one transaction. */
    fun applyAnnotationEraseBatch(documentId: String, page: Int, persistedIds: List<String>, pendingIds: List<String>) {
        if (persistedIds.isEmpty() && pendingIds.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            persistedIds.forEach { annotationId ->
                db.insertOrThrow("annotation_journal", null, ContentValues().apply {
                    put("document_id", documentId); put("page", page); put("kind", "delete_id")
                    put("payload", org.json.JSONObject().put("target", annotationId).toString())
                    put("annotation_id", "erase:${UUID.randomUUID()}"); put("created_at", System.currentTimeMillis())
                })
                db.delete("pdf_note_texts", "annotation_id=?", arrayOf(annotationId))
                db.delete("annotation_history", "annotation_id=?", arrayOf(annotationId))
            }
            pendingIds.forEach { annotationId ->
                db.delete("annotation_journal", "annotation_id=?", arrayOf(annotationId))
                db.delete("pdf_note_texts", "annotation_id=?", arrayOf(annotationId))
                db.delete("annotation_history", "annotation_id=?", arrayOf(annotationId))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun upsertAnnotationHistory(annotationId: String, documentId: String, page: Int, kind: String, payload: String) {
        writableDatabase.insertWithOnConflict("annotation_history", null, ContentValues().apply {
            put("annotation_id", annotationId); put("document_id", documentId); put("page", page)
            put("kind", kind); put("payload", payload); put("updated_at", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getAnnotationHistory(annotationId: String): AnnotationHistoryRow? = readableDatabase.rawQuery(
        "SELECT annotation_id,document_id,page,kind,payload FROM annotation_history WHERE annotation_id=?", arrayOf(annotationId)
    ).use { c -> if (c.moveToFirst()) AnnotationHistoryRow(c.getString(0), c.getString(1), c.getInt(2), c.getString(3), c.getString(4)) else null }

    fun upsertPdfNote(annotationId: String, documentId: String, page: Int, text: String) {
        writableDatabase.insertWithOnConflict("pdf_note_texts", null, ContentValues().apply {
            put("annotation_id", annotationId); put("document_id", documentId); put("page", page); put("text", text); put("updated_at", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun deletePdfNote(annotationId: String) { writableDatabase.delete("pdf_note_texts", "annotation_id=?", arrayOf(annotationId)) }

    fun pdfNotes(documentId: String, page: Int? = null): List<ChunkRow> {
        val sql = if (page == null) "SELECT page,text FROM pdf_note_texts WHERE document_id=? ORDER BY page" else "SELECT page,text FROM pdf_note_texts WHERE document_id=? AND page=?"
        val args = if (page == null) arrayOf(documentId) else arrayOf(documentId, page.toString())
        return readableDatabase.rawQuery(sql, args).use { c -> buildList { while (c.moveToNext()) add(ChunkRow(documentId, c.getInt(0), "PDF 批注", c.getString(1))) } }
    }

    fun toggleBookmark(documentId: String, page: Int, label: String): Boolean {
        val exists = isBookmarked(documentId, page)
        if (exists) writableDatabase.delete("bookmarks", "document_id=? AND page=?", arrayOf(documentId, page.toString()))
        else writableDatabase.insert("bookmarks", null, ContentValues().apply {
            put("document_id", documentId); put("page", page); put("label", label); put("created_at", System.currentTimeMillis())
        })
        return !exists
    }

    fun isBookmarked(documentId: String, page: Int): Boolean = readableDatabase.rawQuery(
        "SELECT 1 FROM bookmarks WHERE document_id=? AND page=? LIMIT 1", arrayOf(documentId, page.toString())
    ).use { it.moveToFirst() }

    fun bookmarks(documentId: String): List<BookmarkRow> = readableDatabase.rawQuery(
        "SELECT document_id,page,label,created_at FROM bookmarks WHERE document_id=? ORDER BY page", arrayOf(documentId)
    ).use { c -> buildList { while (c.moveToNext()) add(BookmarkRow(c.getString(0), c.getInt(1), c.getString(2), c.getLong(3))) } }


    fun getLibraryMeta(itemType: String, itemId: String): LibraryMetaRow? = readableDatabase.rawQuery(
        "SELECT item_type,item_id,primary_category,sub_category,course,topic,tags,note,status,starred,managed_by,confidence,reason,updated_at FROM library_meta WHERE item_type=? AND item_id=?",
        arrayOf(itemType, itemId)
    ).use { c -> if (c.moveToFirst()) libraryMeta(c) else null }

    fun upsertLibraryMeta(row: LibraryMetaRow) {
        writableDatabase.insertWithOnConflict("library_meta", null, ContentValues().apply {
            put("item_type", row.itemType); put("item_id", row.itemId)
            put("primary_category", row.primaryCategory.trim()); put("sub_category", row.subCategory.trim())
            put("course", row.course.trim()); put("topic", row.topic.trim()); put("tags", row.tags.trim())
            put("note", row.note.trim()); put("status", row.status.ifBlank { "收件箱" })
            put("starred", if (row.starred) 1 else 0); put("managed_by", row.managedBy.ifBlank { "manual" })
            put("confidence", row.confidence.coerceIn(0, 100)); put("reason", row.reason.trim())
            put("updated_at", if (row.updatedAt > 0L) row.updatedAt else System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun toggleLibraryStar(itemType: String, itemId: String, fallback: LibraryMetaRow): Boolean {
        val current = getLibraryMeta(itemType, itemId) ?: fallback
        val next = !current.starred
        upsertLibraryMeta(current.copy(starred = next, updatedAt = System.currentTimeMillis()))
        return next
    }

    fun libraryMetaRows(): List<LibraryMetaRow> = readableDatabase.rawQuery(
        "SELECT item_type,item_id,primary_category,sub_category,course,topic,tags,note,status,starred,managed_by,confidence,reason,updated_at FROM library_meta ORDER BY starred DESC,updated_at DESC", null
    ).use { c -> buildList { while (c.moveToNext()) add(libraryMeta(c)) } }

    fun documentContentStats(documentId: String): DocumentContentStats {
        fun count(sql: String, args: Array<String>): Int = readableDatabase.rawQuery(sql, args).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
        return DocumentContentStats(
            chunks = count("SELECT COUNT(*) FROM document_chunks WHERE document_id=?", arrayOf(documentId)),
            bookmarks = count("SELECT COUNT(*) FROM bookmarks WHERE document_id=?", arrayOf(documentId)),
            annotations = count("SELECT COUNT(*) FROM annotation_history WHERE document_id=?", arrayOf(documentId)),
            pdfNotes = count("SELECT COUNT(*) FROM pdf_note_texts WHERE document_id=?", arrayOf(documentId))
        )
    }

    fun sessionContentStats(sessionId: String): SessionContentStats {
        fun count(table: String): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM $table WHERE session_id=?", arrayOf(sessionId)).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
        return SessionContentStats(count("transcripts"), count("questions"), count("notes"))
    }

    fun storageContentCounts(): StorageContentCounts {
        fun count(table: String): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM $table", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
        return StorageContentCounts(
            documents = count("documents"), sessions = count("sessions"), transcripts = count("transcripts"),
            questions = count("questions"), notes = count("notes"), bookmarks = count("bookmarks"),
            annotations = count("annotation_history")
        )
    }

    fun deleteSession(id: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("transcripts", "session_id=?", arrayOf(id))
            db.delete("questions", "session_id=?", arrayOf(id))
            db.delete("notes", "session_id=?", arrayOf(id))
            db.delete("library_meta", "item_type='session' AND item_id=?", arrayOf(id))
            db.delete("sessions", "id=?", arrayOf(id))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun deleteAllLibraryMetadata() { writableDatabase.delete("library_meta", null, null) }

    private fun chunks(c: Cursor) = buildList { while (c.moveToNext()) add(ChunkRow(c.getString(0), c.getInt(1), c.getString(2), c.getString(3))) }
    private fun document(c: Cursor) = DocumentRow(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getInt(4) != 0, c.getString(5), c.getLong(6))
    private fun session(c: Cursor) = SessionRow(c.getString(0), c.getLong(1), if (c.isNull(2)) null else c.getLong(2), c.getString(3), if (c.isNull(4)) null else c.getString(4))
    private fun libraryMeta(c: Cursor) = LibraryMetaRow(
        itemType = c.getString(0), itemId = c.getString(1), primaryCategory = c.getString(2), subCategory = c.getString(3),
        course = c.getString(4), topic = c.getString(5), tags = c.getString(6), note = c.getString(7), status = c.getString(8),
        starred = c.getInt(9) != 0, managedBy = c.getString(10), confidence = c.getInt(11), reason = c.getString(12), updatedAt = c.getLong(13)
    )
}
