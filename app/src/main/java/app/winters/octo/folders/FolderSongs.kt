package app.winters.octo.folders

// What one folder holds, as far as gathering songs needs: the ids of the
// folders inside it and its own songs, in the order shown.
class FolderContents<S>(val folders: List<String>, val songs: List<S>)

// The songs gathered under a folder, and whether there were more than the
// limit allowed.
class Gathered<S>(val songs: List<S>, val limitReached: Boolean)

// Collects every song under a folder, in the order the folders show them:
// each inside folder in turn, then the folder's own songs. Stops at `limit`
// songs, or after opening `maxFolders` folders, so a tap on the top of a
// big library cannot run on for minutes. A folder seen twice is opened once.
suspend fun <S> gatherSongs(
    start: FolderContents<S>,
    limit: Int,
    maxFolders: Int,
    open: suspend (String) -> FolderContents<S>,
): Gathered<S> {
    val out = ArrayList<S>()
    val seen = HashSet<String>()
    var opened = 0
    var cut = false

    suspend fun walk(contents: FolderContents<S>) {
        for (id in contents.folders) {
            if (cut) return
            if (!seen.add(id)) continue
            // Full already, or out of folder reads, with more left to look at.
            if (out.size >= limit || opened >= maxFolders) {
                cut = true
                return
            }
            opened++
            walk(open(id))
        }
        if (cut) return
        val room = limit - out.size
        if (contents.songs.size > room) {
            out += contents.songs.take(room)
            cut = true
        } else {
            out += contents.songs
        }
    }

    walk(start)
    return Gathered(out, cut)
}
