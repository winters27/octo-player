package app.winters.octo.connection

import app.winters.octo.data.HeaderDraft

// The custom header rows a sign-in form holds, each with an id kept beside
// it (never inside HeaderDraft, whose equality other code uses), so the rows
// draw keyed and an edit always lands on its own row. Every write goes
// through one of these ops, and an id is never used twice.
class HeaderList private constructor(
    val rows: List<HeaderDraft>,
    val ids: List<Long>,
    private val nextId: Long,
) {
    // New rows, each with a fresh id: a form filled from a saved server, or
    // emptied.
    fun replaced(rows: List<HeaderDraft>): HeaderList =
        HeaderList(rows.toList(), List(rows.size) { nextId + it }, nextId + rows.size)

    // The rows `predicate` accepts, each with its id.
    fun kept(predicate: (HeaderDraft) -> Boolean): HeaderList {
        val stay = rows.indices.filter { predicate(rows[it]) }
        return HeaderList(stay.map(rows::get), stay.map(ids::get), nextId)
    }

    // A new name. A saved row stays saved only under its own name, compared
    // trimmed and ignoring case; under another its saved value no longer
    // applies.
    fun renamed(id: Long, name: String): HeaderList =
        changed(id) { it.copy(name = name, saved = it.saved && name.trim().equals(it.name.trim(), ignoreCase = true)) }

    // A new value; a saved row stays saved.
    fun valued(id: Long, value: String): HeaderList = changed(id) { it.copy(value = value) }

    // A blank row at the end, and its id.
    fun added(): Pair<HeaderList, Long> = HeaderList(rows + HeaderDraft("", ""), ids + nextId, nextId + 1) to nextId

    fun remove(id: Long): HeaderList {
        val at = ids.indexOf(id)
        if (at < 0) return this
        return HeaderList(rows.filterIndexed { i, _ -> i != at }, ids.filterIndexed { i, _ -> i != at }, nextId)
    }

    // The row with `id` changed; nothing when no row has it.
    private fun changed(id: Long, change: (HeaderDraft) -> HeaderDraft): HeaderList {
        val at = ids.indexOf(id)
        if (at < 0) return this
        return HeaderList(rows.mapIndexed { i, row -> if (i == at) change(row) else row }, ids, nextId)
    }

    companion object {
        val empty = HeaderList(emptyList(), emptyList(), 0L)
    }
}
