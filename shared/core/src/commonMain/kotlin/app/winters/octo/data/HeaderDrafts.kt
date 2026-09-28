package app.winters.octo.data

import app.winters.octo.connection.ServerHeader
import app.winters.octo.connection.mask

// A header as the form holds it. A saved header's value is not shown; left
// empty it keeps the saved value.
data class HeaderDraft(val name: String, val value: String, val saved: Boolean = false) {
    override fun toString() = "HeaderDraft(name=$name, value=${mask(value)}, saved=$saved)"
}

// The headers to use: what was typed, with saved values filled in where a
// saved header was left empty.
fun resolveHeaders(drafts: List<HeaderDraft>, saved: List<ServerHeader>): List<ServerHeader> {
    val savedByName = saved.associateBy { it.name.lowercase() }
    return drafts.map { draft ->
        val keep = draft.saved && draft.value.isEmpty()
        ServerHeader(draft.name, if (keep) savedByName[draft.name.trim().lowercase()]?.value.orEmpty() else draft.value)
    }
}
