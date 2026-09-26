package app.winters.octo.folders

// One folder in a tree of songs: the folders inside it, then its own songs,
// both already in the order they are shown.
class FolderNode<T>(
    val name: String,
    val folders: List<FolderNode<T>>,
    val songs: List<T>,
) {
    // Every song in this folder and the folders inside it.
    val songCount: Int = songs.size + folders.sumOf { it.songCount }

    // Every song under this folder, in the order the folder shows them:
    // each inside folder in turn, then the folder's own songs.
    fun allSongs(): List<T> = ArrayList<T>(songCount).also(::collectInto)

    private fun collectInto(out: MutableList<T>) {
        folders.forEach { it.collectInto(out) }
        out += songs
    }

    // The folder at this path of names below this one, or null when there
    // is none by now.
    fun at(path: List<String>): FolderNode<T>? =
        path.fold(this as FolderNode<T>?) { node, name -> node?.folders?.firstOrNull { it.name == name } }
}

// The names in a folder path, like "Music/Kavinsky/Nightcall/".
fun pathNames(path: String?): List<String> = path.orEmpty().split('/').filter(String::isNotBlank)

// Builds a folder tree from where each song sits. Folders sort by name the
// way people count (2 before 10); songs within a folder by `songOrder`.
fun <T> buildFolderTree(items: List<T>, pathOf: (T) -> List<String>, songOrder: Comparator<in T>): FolderNode<T> {
    class Building {
        val folders = LinkedHashMap<String, Building>()
        val songs = mutableListOf<T>()
    }
    val root = Building()
    for (item in items) {
        var node = root
        for (name in pathOf(item)) node = node.folders.getOrPut(name) { Building() }
        node.songs += item
    }
    fun finish(name: String, node: Building): FolderNode<T> = FolderNode(
        name,
        node.folders.entries.sortedWith(compareBy(NaturalOrder) { it.key }).map { (child, built) -> finish(child, built) },
        node.songs.sortedWith(songOrder),
    )
    return finish("", root)
}

// A tree whose top was skipped to where the music actually is, and the
// names skipped on the way.
class Collapsed<T>(val skipped: List<String>, val root: FolderNode<T>) {
    // The name to call the top by: the last folder skipped into, or empty
    // when nothing was skipped.
    val name: String get() = skipped.lastOrNull().orEmpty()
}

// Skips down through folders that hold nothing but one other folder, so a
// library kept wholly inside "Music" starts at "Music".
fun <T> FolderNode<T>.collapsed(): Collapsed<T> {
    var node = this
    val skipped = mutableListOf<String>()
    while (node.songs.isEmpty() && node.folders.size == 1) {
        node = node.folders.single()
        skipped += node.name
    }
    return Collapsed(skipped, node)
}

// Compares names the way people count: "Disc 2" before "Disc 10", and case
// only breaks ties.
object NaturalOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                val endA = a.digitsEnd(i)
                val endB = b.digitsEnd(j)
                val numA = a.substring(i, endA).trimStart('0')
                val numB = b.substring(j, endB).trimStart('0')
                // A longer number is bigger; equal lengths compare digit by digit.
                val byNumber = if (numA.length != numB.length) numA.length - numB.length else numA.compareTo(numB)
                if (byNumber != 0) return byNumber
                i = endA
                j = endB
            } else {
                val byLetter = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (byLetter != 0) return byLetter
                i++
                j++
            }
        }
        val byLength = (a.length - i).compareTo(b.length - j)
        return if (byLength != 0) byLength else a.compareTo(b)
    }

    private fun String.digitsEnd(from: Int): Int {
        var end = from
        while (end < length && this[end].isDigit()) end++
        return end
    }
}
