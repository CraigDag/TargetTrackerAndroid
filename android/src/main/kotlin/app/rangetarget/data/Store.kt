package app.rangetarget.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * Everything the app saves, as JSON files in the app's private folder:
 * settings.json, kit.json and sessions/<id>/session.json beside that
 * session's photos. Nothing leaves the phone unless the shooter exports it.
 */
class Store(private val root: File) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val sessionsDir = File(root, "sessions").apply { mkdirs() }

    private val _settings = MutableStateFlow(read("settings.json") ?: Settings())
    val settings: StateFlow<Settings> = _settings
    private val _kit = MutableStateFlow(read("kit.json") ?: Kit())
    val kit: StateFlow<Kit> = _kit
    private val _sessions = MutableStateFlow(loadSessions())
    /** Newest first. */
    val sessions: StateFlow<List<StoredSession>> = _sessions

    val dataRoot: File get() = root

    @Synchronized fun saveSettings(s: Settings) { _settings.value = s; write(File(root, "settings.json"), json.encodeToString(Settings.serializer(), s)) }

    @Synchronized fun saveKit(k: Kit) { _kit.value = k; write(File(root, "kit.json"), json.encodeToString(Kit.serializer(), k)) }

    fun session(id: String): StoredSession? = _sessions.value.firstOrNull { it.id == id }

    fun sessionDir(id: String) = File(sessionsDir, id).apply { mkdirs() }

    @Synchronized fun saveSession(s: StoredSession) {
        write(File(sessionDir(s.id), "session.json"), json.encodeToString(StoredSession.serializer(), s))
        _sessions.value = (_sessions.value.filter { it.id != s.id } + s).sortedByDescending { it.createdAt }
    }

    @Synchronized fun update(id: String, change: (StoredSession) -> StoredSession): StoredSession {
        val s = change(session(id) ?: error("Session not found"))
        saveSession(s)
        return s
    }

    @Synchronized fun deleteSession(id: String) {
        File(sessionsDir, id).deleteRecursively()
        _sessions.value = _sessions.value.filter { it.id != id }
    }

    fun newId(): String = UUID.randomUUID().toString().take(8)

    fun gun(id: String?) = id?.let { g -> _kit.value.guns.firstOrNull { it.id == g } }
    fun ammo(id: String?) = id?.let { a -> _kit.value.ammo.firstOrNull { it.id == a } }

    private fun loadSessions(): List<StoredSession> =
        (sessionsDir.listFiles() ?: emptyArray()).mapNotNull { d ->
            runCatching { json.decodeFromString(StoredSession.serializer(), File(d, "session.json").readText()) }.getOrNull()
        }.sortedByDescending { it.createdAt }

    private inline fun <reified T> read(name: String): T? =
        runCatching { json.decodeFromString<T>(File(root, name).readText()) }.getOrNull()

    /** Write to a temporary file and rename, so a crash never leaves half a file. */
    private fun write(f: File, text: String) {
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }
}
