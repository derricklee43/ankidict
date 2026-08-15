package com.derricklee.ankidict

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.io.FileNotFoundException

/**
 * Pronunciation audio, keyed by character, pulled from Anki Desktop's own collection via
 * scripts/build_audio_db.py (AnkiDroid on the phone has no way to expose this -- see that
 * script's header). audio.db is gitignored, so a fresh checkout won't have it; every lookup
 * here degrades to "no audio" rather than crashing when that's the case.
 */
class AudioRepository(private val context: Context) {

    private val db: SQLiteDatabase? by lazy { openDatabaseOrNull() }

    private fun openDatabaseOrNull(): SQLiteDatabase? {
        val dbFile = context.getDatabasePath(DB_NAME)
        if (!dbFile.exists()) {
            val bundled = try {
                context.assets.open(DB_NAME)
            } catch (e: FileNotFoundException) {
                null
            } ?: return null
            dbFile.parentFile?.mkdirs()
            bundled.use { input -> dbFile.outputStream().use { output -> input.copyTo(output) } }
        }
        return SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY)
    }

    fun hasAudio(character: String): Boolean {
        val db = db ?: return false
        db.rawQuery("SELECT 1 FROM audio WHERE character = ? LIMIT 1", arrayOf(character)).use {
            return it.moveToFirst()
        }
    }

    /** Writes (once, then cached) and returns a local file playable via MediaPlayer, or null. */
    fun audioFileFor(character: String): File? {
        val db = db ?: return null
        val cacheFile = File(context.cacheDir, "audio/$character.mp3")
        if (cacheFile.exists()) return cacheFile

        db.rawQuery("SELECT data FROM audio WHERE character = ?", arrayOf(character)).use { cursor ->
            if (!cursor.moveToFirst()) return null
            cacheFile.parentFile?.mkdirs()
            cacheFile.writeBytes(cursor.getBlob(0))
        }
        return cacheFile
    }

    companion object {
        private const val DB_NAME = "audio.db"
    }
}
