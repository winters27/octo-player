buildscript {
    dependencies {
        // Pins the Kotlin compiler used by AGP's built-in Kotlin.
        classpath(libs.kotlin.gradle.plugin)
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ksp) apply false
}

// Test JVMs keep their temp files in one folder per checkout, module and test
// task, emptied before every run and after a passing one, so a folder a test
// does not delete cannot pile up on the disk. A run fails if anything in it
// cannot be removed: a file still held open by a process the tests left
// running. The folder sits directly in the system temp folder because
// Robolectric names its folders after the test, and a deeper base pushes a
// test's database past the 260-character path SQLite can open on Windows.
subprojects {
    tasks.withType<Test>().configureEach {
        val scratch = TestTemp.folderFor("${project.projectDir.absolutePath}|$name")
        jvmArgumentProviders.add(TestTempDir(scratch))
        // Native code and child processes read the temp folder from these.
        environment("TMP", scratch.absolutePath)
        environment("TEMP", scratch.absolutePath)
        environment("TMPDIR", scratch.absolutePath)
        doFirst {
            TestTemp.pruneStale(scratch)
            TestTemp.empty(scratch, "from an earlier run")
        }
        doLast { TestTemp.empty(scratch, "by this run") }
    }
}

/** Points a test JVM's java.io.tmpdir at [dir], which stays out of the build cache key. */
class TestTempDir(@get:Internal val dir: File) : CommandLineArgumentProvider {
    override fun asArguments() = listOf("-Djava.io.tmpdir=${dir.absolutePath}")
}

object TestTemp {
    private val root = File(System.getProperty("java.io.tmpdir"))
    private val name = Regex("octo-t[0-9a-f]{1,8}")
    private const val STALE_MS = 24L * 60 * 60 * 1000

    /** A short folder name that stays the same for one test task of one module in one checkout. */
    fun folderFor(key: String) = File(root, "octo-t" + Integer.toHexString(key.hashCode()))

    /**
     * Removes other test tasks' folders untouched for a day: what the last,
     * failed run of a checkout deleted since then left behind. No test run
     * lasts that long.
     */
    fun pruneStale(keep: File) {
        val now = System.currentTimeMillis()
        root.listFiles { f -> f.isDirectory && f != keep && name.matches(f.name) && now - f.lastModified() > STALE_MS }
            ?.forEach { it.deleteRecursively() }
    }

    /**
     * Deletes everything in [dir] and recreates it empty. A virus scanner can
     * hold a just-closed file for a moment, so a few passes run before failing.
     */
    fun empty(dir: File, left: String) {
        var stuck = emptyList<File>()
        for (pass in 1..10) {
            dir.deleteRecursively()
            stuck = dir.walkBottomUp().filter { it != dir }.toList()
            if (stuck.isEmpty()) break
            Thread.sleep(100)
        }
        if (stuck.isNotEmpty()) {
            throw GradleException(
                "${stuck.size} test temp files left $left could not be removed, still open by a test process? " +
                    stuck.take(5).joinToString { it.path },
            )
        }
        dir.mkdirs()
    }
}
