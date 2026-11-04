package app.winters.octo.catalog

import android.content.Context
import android.os.StatFs
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

// Usable bytes on the disk the library lives on. Tests hand in their own.
fun interface FreeSpace {
    fun bytes(): Long
}

class DiskFreeSpace @Inject constructor(@ApplicationContext private val context: Context) : FreeSpace {
    override fun bytes(): Long = StatFs(context.getDatabasePath(CATALOG_FILE).parentFile!!.apply { mkdirs() }.path).availableBytes
}

// A library change that needs more free space than the phone has.
class NotEnoughSpace(val neededBytes: Long) : Exception("needs $neededBytes bytes free")

// Whole megabytes, rounded up, for telling someone how much to free.
fun megabytes(bytes: Long): Long = (bytes + 999_999) / 1_000_000
