package app.winters.octo.playlists

import app.winters.octo.subsonic.PlaylistFolder
import app.winters.octo.subsonic.PlaylistPlace
import java.text.Collator
import java.util.Locale
import kotlin.random.Random

// Why a folder can't move where it was asked to.
enum class FolderMoveRefusal(val words: String) {
    IntoItself(PlaylistFolderWords.INTO_ITSELF),
    TooDeep(PlaylistFolderWords.TOO_DEEP),
}

// A listener's playlist folders as a tree, with where their playlists sit,
// for every app's folder screens: each level A to Z in the listener's
// language, a folder's path and depth, and the rules a change keeps (no
// folder inside itself, at most MAX_DEPTH levels). A folder whose parent is
// missing, or whose parents loop, sits at the top; a placement whose folder
// is missing counts as none. Playlists are named by their placement key:
// the server's playlist id, or "local:<id>" for one only on a phone.
class PlaylistFolderTree(folders: List<PlaylistFolder>, places: List<PlaylistPlace>, locale: Locale = Locale.getDefault()) {
    private val collator: Collator = Collator.getInstance(locale).apply { strength = Collator.SECONDARY }
    private val byId: Map<String, PlaylistFolder> = folders.associateBy { it.id }
    private val placed: Map<String, String> = places.filter { it.folder in byId }.associate { it.playlist to it.folder }
    private val kids: Map<String?, List<PlaylistFolder>>

    init {
        val order = Comparator<PlaylistFolder> { a, b -> collator.compare(a.name, b.name).takeIf { it != 0 } ?: a.id.compareTo(b.id) }
        kids = byId.values.groupBy { parentOf(it.id) }.mapValues { (_, list) -> list.sortedWith(order) }
    }

    val isEmpty: Boolean get() = byId.isEmpty()

    fun folder(id: String): PlaylistFolder? = byId[id]

    // The folder a folder sits in, or null at the top.
    fun parentOf(id: String): String? {
        val parent = byId[id]?.parent ?: return null
        return parent.takeIf { it in byId && it != id && !loops(id) }
    }

    // A level's folders, A to Z: the top with `parent` null.
    fun children(parent: String?): List<PlaylistFolder> = kids[parent].orEmpty()

    // The folder a playlist sits in, or null at the top.
    fun folderOf(playlistKey: String): String? = placed[playlistKey]

    // Which of `keys` sit right in `folderId` (null: in no folder).
    fun playlistsIn(folderId: String?, keys: Collection<String>): List<String> = keys.filter { placed[it] == folderId }

    // From the top folder down to this one.
    fun path(id: String): List<PlaylistFolder> {
        val out = ArrayList<PlaylistFolder>()
        var at: String? = id
        while (at != null) {
            out += byId[at] ?: break
            at = parentOf(at)
        }
        return out.asReversed()
    }

    // "Road trips / Summer".
    fun pathName(id: String, separator: String = " / "): String = path(id).joinToString(separator) { it.name }

    // 1 for a folder at the top.
    fun depth(id: String): Int = path(id).size

    // 1 for a folder with no folders inside it.
    fun height(id: String): Int = 1 + (children(id).maxOfOrNull { height(it.id) } ?: 0)

    // The folder and every folder inside it.
    fun subtree(id: String): Set<String> {
        val out = LinkedHashSet<String>()
        val todo = ArrayDeque(listOf(id))
        while (todo.isNotEmpty()) {
            val at = todo.removeFirst()
            if (out.add(at)) children(at).forEach { todo.addLast(it.id) }
        }
        return out
    }

    // Why folder `id` can't move into `parent` (null: the top), or null when it can.
    fun moveRefusal(id: String, parent: String?): FolderMoveRefusal? {
        if (parent != null && parent in subtree(id)) return FolderMoveRefusal.IntoItself
        val above = if (parent == null) 0 else depth(parent)
        return if (above + height(id) > MAX_DEPTH) FolderMoveRefusal.TooDeep else null
    }

    // Whether a new folder can go in `parent` (null: the top).
    fun canHoldFolder(parent: String?): Boolean = parent == null || depth(parent) < MAX_DEPTH

    // Every folder in tree order, each with its depth (1 at the top), each
    // level A to Z.
    fun inTreeOrder(): List<Pair<PlaylistFolder, Int>> {
        val out = ArrayList<Pair<PlaylistFolder, Int>>()
        fun walk(parent: String?, depth: Int) {
            for (folder in children(parent)) {
                out += folder to depth
                walk(folder.id, depth + 1)
            }
        }
        walk(null, 1)
        return out
    }

    // Whether a folder's parents come back round to it.
    private fun loops(id: String): Boolean {
        val seen = HashSet<String>()
        var at: String? = id
        while (at != null) {
            if (!seen.add(at)) return true
            at = byId[at]?.parent?.takeIf { it in byId }
        }
        return false
    }

    companion object {
        const val MAX_DEPTH = 4
        const val MAX_NAME = 100
        const val ID_PREFIX = "pf"
        const val LOCAL_PREFIX = "local:"
        private const val ID_LENGTH = 22
        private const val DIGITS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"

        val Empty = PlaylistFolderTree(emptyList(), emptyList())

        // A new folder's id, made here: "pf" and 20 base62 characters.
        fun newId(random: Random = Random.Default): String =
            ID_PREFIX + String(CharArray(ID_LENGTH - ID_PREFIX.length) { DIGITS[random.nextInt(DIGITS.length)] })

        fun isId(id: String): Boolean = id.length == ID_LENGTH && id.startsWith(ID_PREFIX) && id.all { it in DIGITS }

        // A folder name as kept: trimmed, 1 to MAX_NAME characters; null otherwise.
        fun cleanName(name: String): String? {
            val trimmed = name.trim()
            val length = trimmed.codePointCount(0, trimmed.length)
            return trimmed.takeIf { length in 1..MAX_NAME }
        }

        // Whether a playlist can be filed: not one of Octo's generated lists
        // ("or" stations and "og" lists, Liked Songs among them).
        fun fileable(playlistKey: String): Boolean =
            playlistKey.isNotBlank() && !(playlistKey.length == ID_LENGTH && (playlistKey.startsWith("or") || playlistKey.startsWith("og")))

        // A playlist only on a phone, by its row id: placed there, never sent.
        fun localKey(rowId: String): String = LOCAL_PREFIX + rowId

        fun isLocalKey(key: String): Boolean = key.startsWith(LOCAL_PREFIX)
    }
}
