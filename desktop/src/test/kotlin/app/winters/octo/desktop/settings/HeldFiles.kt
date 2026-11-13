package app.winters.octo.desktop.settings

import java.io.File
import java.io.IOException
import java.nio.file.AccessDeniedException

// The disk with files held the way another program holds them: reads
// refused, reads denied to this account, replaces refused (the file still
// readable), deletes or moves refused, by file name; `once` hands back
// other bytes for a read, once each. `replaced` names every file written,
// in order.
internal class HeldDisk : SettingsDisk {
    val refuseReads = mutableSetOf<String>()
    val denyReads = mutableSetOf<String>()
    val refuseReplace = mutableSetOf<String>()
    val refuseDelete = mutableSetOf<String>()
    val refuseMove = mutableSetOf<String>()
    val once = mutableMapOf<String, ArrayDeque<ByteArray>>()
    val replaced = mutableListOf<String>()

    override fun exists(file: File) = RealSettingsDisk.exists(file)

    override fun read(file: File): ByteArray {
        if (file.name in denyReads) throw AccessDeniedException(file.path)
        if (file.name in refuseReads) throw IOException("${file.name} is held")
        once[file.name]?.removeFirstOrNull()?.let { return it }
        return RealSettingsDisk.read(file)
    }

    override fun replace(file: File, bytes: ByteArray) {
        if (file.name in refuseReplace) throw IOException("${file.name} is held")
        RealSettingsDisk.replace(file, bytes)
        replaced += file.name
    }

    override fun delete(file: File) = file.name !in refuseDelete && RealSettingsDisk.delete(file)

    override fun move(file: File, to: File) = file.name !in refuseMove && RealSettingsDisk.move(file, to)
}

// A clock whose sleeps only move it on, so a wait of seconds takes none.
internal class StepClock(var at: Long = 1_700_000_000_000) : SettingsClock {
    var sleeps = 0

    override fun now() = at

    override fun sleep(ms: Long) {
        sleeps++
        at += ms
    }
}
