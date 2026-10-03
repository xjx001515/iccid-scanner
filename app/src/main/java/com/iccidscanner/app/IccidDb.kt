package com.iccidscanner.app

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class Record(
    val id: Long,
    val iccid: String,
    val source: String,
    val createdAt: Long,
)

class IccidDb(context: Context) : SQLiteOpenHelper(context, "iccid.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE records (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                iccid TEXT NOT NULL UNIQUE,
                source TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    /** 已存在相同 ICCID 时返回 false。 */
    fun insert(iccid: String, source: String): Boolean {
        val values = ContentValues().apply {
            put("iccid", iccid)
            put("source", source)
            put("created_at", System.currentTimeMillis())
        }
        return writableDatabase.insertWithOnConflict(
            "records", null, values, SQLiteDatabase.CONFLICT_IGNORE
        ) != -1L
    }

    /**
     * 找同一张卡的记录：完全相同，或只差末位（条码读出的不含卡板上印的末位字母）。
     * 如 "8986…493" 与 "8986…493M" 视为同一张卡。
     */
    fun findSameCard(iccid: String): Record? =
        readableDatabase.query(
            "records", COLUMNS,
            "iccid = ? OR iccid = ? OR iccid GLOB ?",
            arrayOf(iccid, iccid.dropLast(1), "$iccid?"),
            null, null, "LENGTH(iccid) DESC", "1"
        ).use { c -> if (c.moveToFirst()) c.toRecord() else null }

    /** 改成的 ICCID 与其他记录重复时返回 false。[source] 为 null 时不改来源。 */
    fun update(id: Long, iccid: String, source: String? = null): Boolean = try {
        val values = ContentValues().apply {
            put("iccid", iccid)
            if (source != null) put("source", source)
        }
        writableDatabase.update("records", values, "id = ?", arrayOf(id.toString())) > 0
    } catch (e: SQLiteConstraintException) {
        false
    }

    fun delete(id: Long) {
        writableDatabase.delete("records", "id = ?", arrayOf(id.toString()))
    }

    fun clear() {
        writableDatabase.delete("records", null, null)
    }

    /** 最新的在前。 */
    fun all(): List<Record> =
        readableDatabase.query("records", COLUMNS, null, null, null, null, "id DESC").use { c ->
            buildList {
                while (c.moveToNext()) add(c.toRecord())
            }
        }

    private fun Cursor.toRecord() = Record(getLong(0), getString(1), getString(2), getLong(3))

    companion object {
        private val COLUMNS = arrayOf("id", "iccid", "source", "created_at")
    }
}
