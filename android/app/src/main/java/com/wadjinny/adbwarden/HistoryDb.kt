package com.wadjinny.adbwarden

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

data class SessionRow(
    val id: String,
    val computerId: String,
    val computerName: String,
    val agent: String,
    val reason: String,
    val status: String,      // active | ended | denied | timeout | cancelled
    val started: Long,
    val ended: Long?,
    val endReason: String?,
    val summary: String?,
    val actions: Int,
    val denied: Int,
)

data class ActionRow(
    val sessionId: String,
    val seq: Int,
    val ts: Long,
    val tool: String,
    val summary: String,
    val status: String,      // ok | denied | error
    val detail: String?,
    val durationMs: Long?,
)

data class LogRow(val id: Long, val ts: Long, val level: String, val message: String)

class HistoryDb(context: Context) : SQLiteOpenHelper(context, "history.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE sessions(id TEXT PRIMARY KEY, computer_id TEXT, computer_name TEXT, agent TEXT,
               reason TEXT, status TEXT, started INTEGER, ended INTEGER, end_reason TEXT, summary TEXT,
               actions INTEGER DEFAULT 0, denied INTEGER DEFAULT 0)"""
        )
        db.execSQL(
            """CREATE TABLE actions(id INTEGER PRIMARY KEY AUTOINCREMENT, session_id TEXT, seq INTEGER, ts INTEGER,
               tool TEXT, summary TEXT, status TEXT, detail TEXT, duration_ms INTEGER)"""
        )
        db.execSQL("CREATE INDEX actions_session ON actions(session_id)")
        db.execSQL("CREATE TABLE logs(id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER, level TEXT, message TEXT)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    /** Startup housekeeping: prune old rows and close sessions left open by a killed process. */
    fun housekeeping() {
        val db = writableDatabase
        val cutoff = System.currentTimeMillis() - 30L * 24 * 3600 * 1000
        db.execSQL("DELETE FROM actions WHERE session_id IN (SELECT id FROM sessions WHERE started < ?)", arrayOf(cutoff))
        db.execSQL("DELETE FROM sessions WHERE started < ?", arrayOf(cutoff))
        db.execSQL("DELETE FROM logs WHERE id NOT IN (SELECT id FROM logs ORDER BY id DESC LIMIT $MAX_LOGS)")
        db.execSQL(
            "UPDATE sessions SET status='ended', end_reason='app_restarted', ended=? WHERE status='active'",
            arrayOf(System.currentTimeMillis())
        )
    }

    fun insertSession(s: SessionRow) {
        writableDatabase.insert("sessions", null, ContentValues().apply {
            put("id", s.id); put("computer_id", s.computerId); put("computer_name", s.computerName)
            put("agent", s.agent); put("reason", s.reason); put("status", s.status); put("started", s.started)
            s.ended?.let { put("ended", it) }; put("end_reason", s.endReason); put("summary", s.summary)
            put("actions", s.actions); put("denied", s.denied)
        })
    }

    fun endSession(id: String, endReason: String, summary: String?, actions: Int, denied: Int) {
        writableDatabase.update("sessions", ContentValues().apply {
            put("status", "ended"); put("ended", System.currentTimeMillis()); put("end_reason", endReason)
            if (summary != null) put("summary", summary)
            put("actions", actions); put("denied", denied)
        }, "id=?", arrayOf(id))
    }

    fun sessionExists(id: String): Boolean =
        readableDatabase.rawQuery("SELECT 1 FROM sessions WHERE id=?", arrayOf(id)).use { it.moveToFirst() }

    fun insertAction(a: ActionRow) {
        writableDatabase.insert("actions", null, ContentValues().apply {
            put("session_id", a.sessionId); put("seq", a.seq); put("ts", a.ts); put("tool", a.tool)
            put("summary", a.summary); put("status", a.status); put("detail", a.detail)
            a.durationMs?.let { put("duration_ms", it) }
        })
    }

    fun sessions(limit: Int = 500): List<SessionRow> =
        readableDatabase.rawQuery("SELECT * FROM sessions ORDER BY started DESC LIMIT $limit", null).use { c ->
            val out = mutableListOf<SessionRow>()
            while (c.moveToNext()) out += c.toSession()
            out
        }

    fun session(id: String): SessionRow? =
        readableDatabase.rawQuery("SELECT * FROM sessions WHERE id=?", arrayOf(id)).use { c ->
            if (c.moveToFirst()) c.toSession() else null
        }

    /** Most recent session that was actually approved (for the live feed). */
    fun lastRunSession(): SessionRow? =
        readableDatabase.rawQuery(
            "SELECT * FROM sessions WHERE status IN ('active','ended') ORDER BY started DESC LIMIT 1", null
        ).use { c -> if (c.moveToFirst()) c.toSession() else null }

    fun actions(sessionId: String): List<ActionRow> =
        readableDatabase.rawQuery("SELECT * FROM actions WHERE session_id=? ORDER BY seq, id", arrayOf(sessionId)).use { c ->
            val out = mutableListOf<ActionRow>()
            while (c.moveToNext()) out += ActionRow(
                c.str("session_id"), c.getInt(c.getColumnIndexOrThrow("seq")), c.long("ts"), c.str("tool"),
                c.str("summary"), c.str("status"), c.strOrNull("detail"), c.longOrNull("duration_ms"),
            )
            out
        }

    fun insertLog(level: String, message: String) {
        writableDatabase.insert("logs", null, ContentValues().apply {
            put("ts", System.currentTimeMillis()); put("level", level); put("message", message)
        })
    }

    fun logs(limit: Int = MAX_LOGS): List<LogRow> =
        readableDatabase.rawQuery("SELECT * FROM logs ORDER BY id DESC LIMIT $limit", null).use { c ->
            val out = mutableListOf<LogRow>()
            while (c.moveToNext()) out += LogRow(c.long("id"), c.long("ts"), c.str("level"), c.str("message"))
            out
        }

    fun clearHistory() {
        writableDatabase.execSQL("DELETE FROM actions WHERE session_id IN (SELECT id FROM sessions WHERE status != 'active')")
        writableDatabase.execSQL("DELETE FROM sessions WHERE status != 'active'")
    }

    fun clearLogs() = writableDatabase.execSQL("DELETE FROM logs")

    fun exportHistory(): JSONObject {
        val arr = JSONArray()
        for (s in sessions(100000)) {
            val acts = JSONArray()
            for (a in actions(s.id)) acts.put(
                JSONObject().put("seq", a.seq).put("ts", a.ts).put("tool", a.tool).put("summary", a.summary)
                    .put("status", a.status).put("detail", a.detail ?: JSONObject.NULL)
                    .put("durationMs", a.durationMs ?: JSONObject.NULL)
            )
            arr.put(
                JSONObject().put("id", s.id).put("computerId", s.computerId).put("computer", s.computerName)
                    .put("agent", s.agent).put("reason", s.reason).put("status", s.status).put("started", s.started)
                    .put("ended", s.ended ?: JSONObject.NULL).put("endReason", s.endReason ?: JSONObject.NULL)
                    .put("summary", s.summary ?: JSONObject.NULL).put("actionCount", s.actions)
                    .put("deniedCount", s.denied).put("actions", acts)
            )
        }
        return JSONObject().put("app", "adb-warden").put("exportedAt", System.currentTimeMillis()).put("sessions", arr)
    }

    fun exportLogs(): JSONObject {
        val arr = JSONArray()
        for (l in logs(100000).reversed()) arr.put(JSONObject().put("ts", l.ts).put("level", l.level).put("message", l.message))
        return JSONObject().put("app", "adb-warden").put("exportedAt", System.currentTimeMillis()).put("logs", arr)
    }

    private fun android.database.Cursor.str(col: String) = getString(getColumnIndexOrThrow(col)) ?: ""
    private fun android.database.Cursor.strOrNull(col: String) = getColumnIndexOrThrow(col).let { if (isNull(it)) null else getString(it) }
    private fun android.database.Cursor.long(col: String) = getLong(getColumnIndexOrThrow(col))
    private fun android.database.Cursor.longOrNull(col: String) = getColumnIndexOrThrow(col).let { if (isNull(it)) null else getLong(it) }
    private fun android.database.Cursor.toSession() = SessionRow(
        str("id"), str("computer_id"), str("computer_name"), str("agent"), str("reason"), str("status"),
        long("started"), longOrNull("ended"), strOrNull("end_reason"), strOrNull("summary"),
        getInt(getColumnIndexOrThrow("actions")), getInt(getColumnIndexOrThrow("denied")),
    )

    companion object {
        const val MAX_LOGS = 2000
    }
}
