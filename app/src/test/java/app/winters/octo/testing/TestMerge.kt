package app.winters.octo.testing

import app.winters.octo.catalog.CatalogDatabase
import app.winters.octo.catalog.CatalogMerge
import app.winters.octo.catalog.MergeHooks
import app.winters.octo.catalog.WorkDao

// A real merge over this database, with the server in use fixed (or read
// from `inUse` each time), all the space it asks for unless told less, and
// the given test hooks.
internal fun CatalogDatabase.testMerge(
    serverInUse: String?,
    hooks: MergeHooks = MergeHooks(),
    space: Long = Long.MAX_VALUE,
    work: WorkDao = workDao(),
    inUse: (suspend () -> String?)? = null,
): CatalogMerge = CatalogMerge(this, { space }, inUse ?: { serverInUse }, work).also { it.hooks = hooks }
