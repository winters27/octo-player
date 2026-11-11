package app.winters.octo.catalog

import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.Closeable

// One id the save changes, as a node of its sets: a library song's,
// album's or artist's (RENAME_SONG, RENAME_ALBUM, RENAME_ARTIST).
data class SaveNodeKey(val kind: String, val id: String)

// The node numbers a find hands out, one after another from the highest in
// save_node, so a node's number is its row's key and no counter is written.
class SaveNodeNumbers(private var last: Long) {
    fun next(): Long = ++last
}

internal const val NODE_OF_SQL = "SELECT node, parent, size FROM save_node WHERE kind = ?1 AND id = ?2"
internal const val NODE_AT_SQL = "SELECT parent, size FROM save_node WHERE node = ?1"
internal const val NODE_IN_SQL = "INSERT INTO save_node (node, kind, id, parent, size) VALUES (?1, ?2, ?3, ?1, 1)"
internal const val NODE_LINK_SQL = "UPDATE save_node SET parent = ?2 WHERE node = ?1"
internal const val NODE_SIZE_SQL = "UPDATE save_node SET size = ?2 WHERE node = ?1"
internal const val LAST_NODE_SQL = "SELECT COALESCE(MAX(node), 0) FROM save_node"

// The sets of changed ids that must move together, as a union-find over
// save_node, inside the transaction of the caller's `sql`. Each union puts
// the smaller tree under the larger root, so no tree is deeper than log2 of
// its size and a find reads at most log2(n) + 1 rows: the node by its id,
// then each parent up to the root, one row by key each. Every node's row
// holds its size, so a find ends with its root's size in hand.
class SaveSets(private val sql: SupportSQLiteDatabase, private val numbers: SaveNodeNumbers) : Closeable {
    private val add = sql.compileStatement(NODE_IN_SQL)
    private val link = sql.compileStatement(NODE_LINK_SQL)
    private val size = sql.compileStatement(NODE_SIZE_SQL)

    // A root and the size of its tree.
    private class Root(val node: Long, val size: Long)

    // The two ids are in one set from now on.
    fun union(a: SaveNodeKey, b: SaveNodeKey) {
        val ra = root(a)
        val rb = root(b)
        if (ra.node == rb.node) return
        // The larger tree keeps its root; of two the same size, the older.
        val (big, small) = if (rb.size > ra.size || (rb.size == ra.size && rb.node < ra.node)) rb to ra else ra to rb
        link.bindAll(small.node, big.node)
        link.executeUpdateDelete()
        size.bindAll(big.node, big.size + small.size)
        size.executeUpdateDelete()
    }

    // The id is in a set, one of its own where it was in none: its node
    // read by its id, and made where there is none.
    fun touch(a: SaveNodeKey) {
        val held = sql.query(NODE_OF_SQL, arrayOf<Any?>(a.kind, a.id)).use { it.moveToFirst() }
        if (held) return
        add.bindAll(numbers.next(), a.kind, a.id)
        add.executeInsert()
    }

    // The root of an id's set, its node made (a set of its own) where it
    // has none; null for an id with no node, when `make` is false.
    fun find(a: SaveNodeKey): Long? = rootOf(a, make = false)?.node

    private fun root(a: SaveNodeKey): Root = rootOf(a, make = true)!!

    private fun rootOf(a: SaveNodeKey, make: Boolean): Root? {
        val start = sql.query(NODE_OF_SQL, arrayOf<Any?>(a.kind, a.id)).use { c ->
            if (c.moveToFirst()) Triple(c.getLong(0), c.getLong(1), c.getLong(2)) else null
        }
        if (start == null) {
            if (!make) return null
            val node = numbers.next()
            add.bindAll(node, a.kind, a.id)
            add.executeInsert()
            return Root(node, 1)
        }
        var (node, parent, treeSize) = start
        while (parent != node) {
            node = parent
            val up = sql.query(NODE_AT_SQL, arrayOf<Any?>(node)).use { c -> if (c.moveToFirst()) c.getLong(0) to c.getLong(1) else null }
                ?: error("save_node $node is missing under ${a.kind} ${a.id}")
            parent = up.first
            treeSize = up.second
        }
        return Root(node, treeSize)
    }

    // The root of a node by its number, each parent read up to it.
    fun rootOfNode(start: Long, parent: Long): Long {
        var node = start
        var up = parent
        while (up != node) {
            node = up
            up = sql.query(NODE_AT_SQL, arrayOf<Any?>(node)).use { c -> if (c.moveToFirst()) c.getLong(0) else null }
                ?: error("save_node $node is missing")
        }
        return node
    }

    // A node points straight at its root.
    fun pointAt(node: Long, root: Long) {
        link.bindAll(node, root)
        link.executeUpdateDelete()
    }

    override fun close() {
        add.close()
        link.close()
        size.close()
    }

    companion object {
        // The most reads one find takes over `n` ids at most.
        fun depth(n: Long): Long = 64 - java.lang.Long.numberOfLeadingZeros((n - 1).coerceAtLeast(0)) + 1L
    }
}
