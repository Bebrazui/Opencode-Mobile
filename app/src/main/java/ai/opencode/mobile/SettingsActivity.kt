package ai.opencode.mobile

import android.os.Bundle
import android.os.Environment
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.text.DecimalFormat

class SettingsActivity : AppCompatActivity() {

    private val dbDir by lazy {
        File(filesDir, ".opencode/data/opencode")
    }
    private val dbFile by lazy {
        File(dbDir, "opencode-dev.db")
    }
    private val exportFile by lazy {
        File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "opencode-export.db"
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        supportActionBar?.apply {
            title = getString(R.string.settings_title)
            setDisplayHomeAsUpEnabled(true)
        }

        updateServerStatus()
        updateDbInfo()

        findViewById<TextView>(R.id.restart_btn).setOnClickListener {
            restartServer()
        }

        findViewById<TextView>(R.id.export_btn).setOnClickListener {
            exportChats()
        }

        findViewById<TextView>(R.id.import_btn).setOnClickListener {
            confirmImport()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onResume() {
        super.onResume()
        updateServerStatus()
        updateDbInfo()
    }

    private fun updateServerStatus() {
        val statusView = findViewById<TextView>(R.id.server_status)
        val termuxManager = TermuxManager(this)
        if (termuxManager.isServerReady()) {
            statusView.text = getString(R.string.settings_server_status_running, termuxManager.getServerPort())
            statusView.setTextColor(0xFF7EE787.toInt())
        } else {
            statusView.text = getString(R.string.settings_server_status_stopped)
            statusView.setTextColor(0xFFF85149.toInt())
        }
    }

    private fun updateDbInfo() {
        val sizeView = findViewById<TextView>(R.id.db_size)
        val versionView = findViewById<TextView>(R.id.about_version)
        val pathView = findViewById<TextView>(R.id.about_db_path)
        val storageView = findViewById<TextView>(R.id.about_storage)

        if (dbFile.exists()) {
            sizeView.text = getString(R.string.settings_db_size, formatSize(dbFile.length()))
        } else {
            sizeView.text = getString(R.string.settings_db_size, "not found")
        }

        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "0.1.0"
        } catch (_: Exception) { "0.1.0" }
        versionView.text = getString(R.string.settings_version, version)
        pathView.text = getString(R.string.settings_db_path, dbFile.absolutePath)

        val totalSize = dbDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        storageView.text = getString(R.string.settings_storage, formatSize(totalSize))
    }

    private fun exportChats() {
        if (!dbFile.exists()) {
            Toast.makeText(this, getString(R.string.settings_export_fail), Toast.LENGTH_SHORT).show()
            return
        }
        try {
            exportFile.parentFile?.mkdirs()
            dbFile.copyTo(exportFile, overwrite = true)
            Toast.makeText(
                this,
                getString(R.string.settings_export_done, exportFile.absolutePath),
                Toast.LENGTH_LONG
            ).show()
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.settings_export_fail), Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmImport() {
        if (!exportFile.exists()) {
            Toast.makeText(this, getString(R.string.settings_import_not_found), Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.settings_import_confirm))
            .setPositiveButton("Import") { _, _ -> importChats() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun importChats() {
        val statusView = findViewById<TextView>(R.id.import_status)
        statusView.visibility = android.view.View.VISIBLE
        statusView.text = "Importing..."

        Thread {
            try {
                val imported = mergeDb(exportFile, dbFile)
                runOnUiThread {
                    statusView.text = getString(R.string.settings_import_done, imported)
                    updateDbInfo()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    statusView.text = getString(R.string.settings_import_fail)
                }
            }
        }.start()
    }

    /**
     * Merge source DB into target DB. Copies sessions, messages, and parts
     * that don't already exist (by ID). This ensures chats are ADDED, not replaced.
     */
    private fun mergeDb(source: File, target: File): Int {
        var count = 0
        val srcConn = android.database.sqlite.SQLiteDatabase.openDatabase(
            source.absolutePath, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY
        )
        val dstConn = android.database.sqlite.SQLiteDatabase.openDatabase(
            target.absolutePath, null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE
        )

        try {
            dstConn.beginTransaction()
            try {
                // Copy sessions that don't exist in target
                val sessions = srcConn.rawQuery("SELECT * FROM session", null)
                while (sessions.moveToNext()) {
                    val id = sessions.getString(sessions.getColumnIndexOrThrow("id"))
                    val exists = dstConn.rawQuery("SELECT 1 FROM session WHERE id = ?", arrayOf(id)).use {
                        it.moveToFirst()
                    }
                    if (!exists) {
                        val values = android.content.ContentValues()
                        for (i in 0 until sessions.columnCount) {
                            val colName = sessions.getColumnName(i)
                            when (sessions.getType(i)) {
                                android.database.Cursor.FIELD_TYPE_NULL -> values.putNull(colName)
                                android.database.Cursor.FIELD_TYPE_INTEGER -> values.put(colName, sessions.getLong(i))
                                android.database.Cursor.FIELD_TYPE_FLOAT -> values.put(colName, sessions.getDouble(i))
                                android.database.Cursor.FIELD_TYPE_STRING -> values.put(colName, sessions.getString(i))
                                android.database.Cursor.FIELD_TYPE_BLOB -> values.put(colName, sessions.getBlob(i))
                            }
                        }
                        dstConn.insert("session", null, values)
                        count++
                    }
                }
                sessions.close()

                // Copy messages that don't exist in target
                val messages = srcConn.rawQuery("SELECT * FROM message", null)
                while (messages.moveToNext()) {
                    val id = messages.getString(messages.getColumnIndexOrThrow("id"))
                    val exists = dstConn.rawQuery("SELECT 1 FROM message WHERE id = ?", arrayOf(id)).use {
                        it.moveToFirst()
                    }
                    if (!exists) {
                        val values = android.content.ContentValues()
                        for (i in 0 until messages.columnCount) {
                            val colName = messages.getColumnName(i)
                            when (messages.getType(i)) {
                                android.database.Cursor.FIELD_TYPE_NULL -> values.putNull(colName)
                                android.database.Cursor.FIELD_TYPE_INTEGER -> values.put(colName, messages.getLong(i))
                                android.database.Cursor.FIELD_TYPE_FLOAT -> values.put(colName, messages.getDouble(i))
                                android.database.Cursor.FIELD_TYPE_STRING -> values.put(colName, messages.getString(i))
                                android.database.Cursor.FIELD_TYPE_BLOB -> values.put(colName, messages.getBlob(i))
                            }
                        }
                        dstConn.insert("message", null, values)
                    }
                }
                messages.close()

                // Copy parts that don't exist in target
                val parts = srcConn.rawQuery("SELECT * FROM part", null)
                while (parts.moveToNext()) {
                    val id = parts.getString(parts.getColumnIndexOrThrow("id"))
                    val exists = dstConn.rawQuery("SELECT 1 FROM part WHERE id = ?", arrayOf(id)).use {
                        it.moveToFirst()
                    }
                    if (!exists) {
                        val values = android.content.ContentValues()
                        for (i in 0 until parts.columnCount) {
                            val colName = parts.getColumnName(i)
                            when (parts.getType(i)) {
                                android.database.Cursor.FIELD_TYPE_NULL -> values.putNull(colName)
                                android.database.Cursor.FIELD_TYPE_INTEGER -> values.put(colName, parts.getLong(i))
                                android.database.Cursor.FIELD_TYPE_FLOAT -> values.put(colName, parts.getDouble(i))
                                android.database.Cursor.FIELD_TYPE_STRING -> values.put(colName, parts.getString(i))
                                android.database.Cursor.FIELD_TYPE_BLOB -> values.put(colName, parts.getBlob(i))
                            }
                        }
                        dstConn.insert("part", null, values)
                    }
                }
                parts.close()

                // Copy session_message entries
                val sessionMsgs = srcConn.rawQuery("SELECT * FROM session_message", null)
                while (sessionMsgs.moveToNext()) {
                    val id = sessionMsgs.getString(sessionMsgs.getColumnIndexOrThrow("id"))
                    val exists = dstConn.rawQuery("SELECT 1 FROM session_message WHERE id = ?", arrayOf(id)).use {
                        it.moveToFirst()
                    }
                    if (!exists) {
                        val values = android.content.ContentValues()
                        for (i in 0 until sessionMsgs.columnCount) {
                            val colName = sessionMsgs.getColumnName(i)
                            when (sessionMsgs.getType(i)) {
                                android.database.Cursor.FIELD_TYPE_NULL -> values.putNull(colName)
                                android.database.Cursor.FIELD_TYPE_INTEGER -> values.put(colName, sessionMsgs.getLong(i))
                                android.database.Cursor.FIELD_TYPE_FLOAT -> values.put(colName, sessionMsgs.getDouble(i))
                                android.database.Cursor.FIELD_TYPE_STRING -> values.put(colName, sessionMsgs.getString(i))
                                android.database.Cursor.FIELD_TYPE_BLOB -> values.put(colName, sessionMsgs.getBlob(i))
                            }
                        }
                        dstConn.insert("session_message", null, values)
                    }
                }
                sessionMsgs.close()

                // Copy session_input entries
                val sessionInputs = srcConn.rawQuery("SELECT * FROM session_input", null)
                while (sessionInputs.moveToNext()) {
                    val id = sessionInputs.getString(sessionInputs.getColumnIndexOrThrow("id"))
                    val exists = dstConn.rawQuery("SELECT 1 FROM session_input WHERE id = ?", arrayOf(id)).use {
                        it.moveToFirst()
                    }
                    if (!exists) {
                        val values = android.content.ContentValues()
                        for (i in 0 until sessionInputs.columnCount) {
                            val colName = sessionInputs.getColumnName(i)
                            when (sessionInputs.getType(i)) {
                                android.database.Cursor.FIELD_TYPE_NULL -> values.putNull(colName)
                                android.database.Cursor.FIELD_TYPE_INTEGER -> values.put(colName, sessionInputs.getLong(i))
                                android.database.Cursor.FIELD_TYPE_FLOAT -> values.put(colName, sessionInputs.getDouble(i))
                                android.database.Cursor.FIELD_TYPE_STRING -> values.put(colName, sessionInputs.getString(i))
                                android.database.Cursor.FIELD_TYPE_BLOB -> values.put(colName, sessionInputs.getBlob(i))
                            }
                        }
                        dstConn.insert("session_input", null, values)
                    }
                }
                sessionInputs.close()

                dstConn.setTransactionSuccessful()
            } finally {
                dstConn.endTransaction()
            }
        } finally {
            srcConn.close()
            dstConn.close()
        }
        return count
    }

    private fun restartServer() {
        val termuxManager = TermuxManager(this)
        val projectPath = getExternalFilesDir(null)?.absolutePath ?: filesDir.absolutePath
        termuxManager.start(projectPath)
        Toast.makeText(this, "Server restarting...", Toast.LENGTH_SHORT).show()
        updateServerStatus()
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${DecimalFormat("#.#").format(bytes / 1024.0)} KB"
            else -> "${DecimalFormat("#.#").format(bytes / (1024.0 * 1024.0))} MB"
        }
    }
}
