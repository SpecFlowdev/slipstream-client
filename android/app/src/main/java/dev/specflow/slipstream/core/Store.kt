package dev.specflow.slipstream.core

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

/**
 * One JSON file, written whole.
 *
 * The store is small enough that rewriting it on every change is cheaper than
 * anything cleverer, and a whole-file write through a temporary file cannot
 * leave a half-written store behind after a kill.
 */
class Store private constructor(private val file: File) {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val _state = MutableStateFlow(StoreFile())
    val state: StateFlow<StoreFile> = _state.asStateFlow()

    val current: StoreFile get() = _state.value

    private fun load() {
        if (!file.exists()) return
        try {
            _state.value = json.decodeFromString(StoreFile.serializer(), file.readText())
        } catch (e: Exception) {
            // A store that cannot be parsed is kept, not deleted: the user may
            // want it back, and starting from defaults loses nothing else.
            Log.w(TAG, "could not read the store, starting fresh", e)
            runCatching { file.copyTo(File(file.path + ".broken"), overwrite = true) }
        }
    }

    @Synchronized
    fun edit(change: (StoreFile) -> StoreFile) {
        val next = change(_state.value)
        _state.value = next
        try {
            val tmp = File(file.path + ".tmp")
            tmp.writeText(json.encodeToString(StoreFile.serializer(), next))
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText())
                tmp.delete()
            }
        } catch (e: IOException) {
            Log.e(TAG, "could not write the store", e)
        }
    }

    fun activeProfile(): Profile? {
        val s = current
        return s.profiles.firstOrNull { it.name == s.activeProfile } ?: s.profiles.firstOrNull()
    }

    /** Sessions that moved nothing are noise; they are not recorded. */
    fun recordSession(record: SessionRecord) {
        if (record.bytesUp == 0L && record.bytesDown == 0L) return
        edit { it.copy(history = (listOf(record) + it.history).take(HISTORY_CAPACITY)) }
    }

    companion object {
        private const val TAG = "SlipstreamStore"

        @Volatile
        private var instance: Store? = null

        fun of(context: Context): Store = instance ?: synchronized(this) {
            instance ?: Store(File(context.filesDir, "store.json")).also {
                it.load()
                instance = it
            }
        }
    }
}
