package app.winters.octo.update

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

// The public repository the players' releases are published on, beside
// the server's own.
const val OCTO_REPOSITORY = "winters27/octo"
val GITHUB_API: HttpUrl = "https://api.github.com/".toHttpUrl()

// How the updater's choices are kept, the same on both apps.
@Serializable
data class UpdatePrefs(
    // Look for a new version at start and every few hours.
    val checkAutomatically: Boolean = true,
    // Put a downloaded update in when asked, or by itself when Octo quits.
    val install: InstallWhen = InstallWhen.Ask,
    // Early versions (pre-releases) count as updates too.
    val earlyVersions: Boolean = false,
)

@Serializable
enum class InstallWhen { Ask, OnQuit }

// When the checks run: a while after start, so opening the app stays
// quick, then every six hours. GitHub allows 60 unauthenticated asks an
// hour for each address, and an unchanged list (answered from its ETag)
// does not count against that.
object UpdateTiming {
    const val FIRST_CHECK_DELAY_MS = 90_000L
    const val INTERVAL_MS = 6 * 60 * 60 * 1000L

    // How long to wait before the next check, given when GitHub asked us to
    // come back (a rate limit), if it did.
    fun nextWait(now: Long, retryAt: Long?): Long = maxOf(INTERVAL_MS, (retryAt ?: 0L) - now)
}

// What a check found.
sealed interface UpdateCheck {
    // Nothing newer than the version running.
    data object UpToDate : UpdateCheck

    // A newer version, checked but not downloaded (asked not to download).
    data class Available(val version: PlayerVersion, val tag: String, val notes: String, val asset: ManifestAsset) : UpdateCheck

    // A newer version downloaded and checked, ready to install.
    data class Ready(val version: PlayerVersion, val tag: String, val notes: String, val file: File, val asset: ManifestAsset) : UpdateCheck

    // A newer version with nothing for this system (a Mac with no DMG built for its chip).
    data class NoInstaller(val version: PlayerVersion) : UpdateCheck

    // The release or its file failed a check: the file is gone, nothing runs.
    data class Refused(val reason: String) : UpdateCheck

    // GitHub could not be reached, or asked us to wait until `retryAt`.
    data class Unavailable(val reason: String, val retryAt: Long? = null) : UpdateCheck
}

// GitHub's list of releases, remembered with its ETag so an unchanged list
// costs nothing against the rate limit.
class ReleaseFeed(
    private val client: OkHttpClient,
    private val cacheFile: File?,
    private val userAgent: String,
    private val apiBase: HttpUrl = GITHUB_API,
    private val repository: String = OCTO_REPOSITORY,
    private val now: () -> Long = System::currentTimeMillis,
) {
    sealed interface Answer {
        data class Releases(val releases: List<GitHubRelease>) : Answer
        data class Wait(val reason: String, val retryAt: Long?) : Answer
    }

    @Serializable
    private data class Cached(val etag: String, val body: String)

    fun releases(): Answer {
        val cached = readCache()
        val url = apiBase.newBuilder()
            .addPathSegment("repos")
            .addPathSegments(repository)
            .addPathSegment("releases")
            .addQueryParameter("per_page", "100")
            .build()
        val request = Request.Builder().url(url)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", userAgent)
            .apply { cached?.let { header("If-None-Match", it.etag) } }
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                when {
                    response.code == 304 && cached != null -> Answer.Releases(parseReleases(cached.body))
                    response.isSuccessful -> {
                        val body = response.body.string()
                        val releases = parseReleases(body)
                        response.header("ETag")?.let { writeCache(Cached(it, body)) }
                        Answer.Releases(releases)
                    }
                    response.code == 403 || response.code == 429 -> {
                        val retryAfter = response.header("Retry-After")?.toLongOrNull()?.let { now() + it * 1000 }
                        val reset = response.header("X-RateLimit-Reset")?.toLongOrNull()?.takeIf { response.header("X-RateLimit-Remaining") == "0" }?.let { it * 1000 }
                        Answer.Wait("GitHub asked Octo to wait", retryAfter ?: reset)
                    }
                    else -> Answer.Wait("GitHub answered ${response.code}", null)
                }
            }
        } catch (e: IOException) {
            Answer.Wait("GitHub could not be reached", null)
        } catch (e: IllegalArgumentException) {
            // Not a list of releases (a captive portal's page, say).
            Answer.Wait("GitHub's answer was not readable", null)
        }
    }

    private fun readCache(): Cached? = cacheFile?.takeIf(File::isFile)?.let { file ->
        runCatching { cacheJson.decodeFromString(Cached.serializer(), file.readText()) }.getOrNull()
    }

    private fun writeCache(cached: Cached) {
        val file = cacheFile ?: return
        runCatching {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeText(cacheJson.encodeToString(Cached.serializer(), cached))
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private companion object {
        val cacheJson = Json { ignoreUnknownKeys = true }
    }
}

// Finds, downloads and checks an update for one app. Every file is kept
// under `folder`, one folder per release; a file that fails any check is
// deleted there and then, and older releases' files go once a newer one
// is ready.
class PlayerUpdater(
    private val app: PlayerApp,
    private val running: PlayerVersion,
    private val feed: ReleaseFeed,
    private val client: OkHttpClient,
    private val folder: File,
    private val keys: List<ByteArray>,
    // The file for this system among a release's files, or null.
    private val pick: (List<ManifestAsset>) -> ManifestAsset?,
) {
    fun check(early: Boolean, download: Boolean = true): UpdateCheck {
        val releases = when (val answer = feed.releases()) {
            is ReleaseFeed.Answer.Wait -> return UpdateCheck.Unavailable(answer.reason, answer.retryAt)
            is ReleaseFeed.Answer.Releases -> answer.releases
        }
        val candidate = newestUpdate(releases, app, running, early) ?: return UpdateCheck.UpToDate
        val tag = candidate.release.tag
        return try {
            val manifestAsset = candidate.asset(MANIFEST_NAME) ?: throw UpdateRefused("the release has no $MANIFEST_NAME")
            val signatureAsset = candidate.asset(SIGNATURE_NAME) ?: throw UpdateRefused("the release has no $SIGNATURE_NAME")
            val bytes = fetchSmall(manifestAsset.url, MANIFEST_LIMIT)
            val signature = fetchSmall(signatureAsset.url, SIGNATURE_LIMIT).toString(Charsets.US_ASCII)
            val manifest = verifiedManifest(bytes, signature, keys, app, tag)
            val asset = pick(manifest.assets) ?: return UpdateCheck.NoInstaller(candidate.version)
            val listed = candidate.asset(asset.name) ?: throw UpdateRefused("the release is missing ${asset.name}")
            if (listed.size != asset.size) throw UpdateRefused("${asset.name} is not the size the manifest gives")
            if (!download) return UpdateCheck.Available(candidate.version, tag, manifest.notes, asset)
            val place = File(folder, safeName(tag))
            val file = downloadVerified(listed.url, asset, File(place, asset.name))
            forgetOthers(keep = place)
            UpdateCheck.Ready(candidate.version, tag, manifest.notes, file, asset)
        } catch (e: UpdateRefused) {
            File(folder, safeName(tag)).deleteRecursively()
            UpdateCheck.Refused(e.message ?: "refused")
        } catch (e: IOException) {
            UpdateCheck.Unavailable("the download stopped: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    // A small file (the manifest or its signature), refusing one too big.
    private fun fetchSmall(url: String, limit: Int): ByteArray {
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("GitHub answered ${response.code}")
            // Read by hand: InputStream.readNBytes is newer than the phones the app supports.
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            response.body.byteStream().use { input ->
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    if (out.size() > limit) throw UpdateRefused("a release file is far bigger than it should be")
                }
            }
            return out.toByteArray()
        }
    }

    // The file at `url`, kept at `target` only when it has the manifest's
    // size and SHA-256. A copy already there that checks out is used as is.
    fun downloadVerified(url: String, asset: ManifestAsset, target: File): File {
        if (target.isFile && target.length() == asset.size && sha256Of(target) == asset.sha256) return target
        target.parentFile?.mkdirs()
        val part = File(target.parentFile, target.name + ".part")
        try {
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) throw IOException("GitHub answered ${response.code}")
                val body = response.body
                val told = body.contentLength()
                if (told >= 0 && told != asset.size) throw UpdateRefused("${asset.name} is not the size the manifest gives")
                val digest = MessageDigest.getInstance("SHA-256")
                var total = 0L
                body.byteStream().use { input ->
                    part.outputStream().use { out ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            if (total > asset.size) throw UpdateRefused("${asset.name} is bigger than the manifest gives")
                            digest.update(buffer, 0, read)
                            out.write(buffer, 0, read)
                        }
                        out.fd.sync()
                    }
                }
                if (total != asset.size) throw UpdateRefused("${asset.name} is smaller than the manifest gives")
                if (hex(digest.digest()) != asset.sha256) throw UpdateRefused("${asset.name} does not match the manifest")
            }
            Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            return target
        } catch (e: Exception) {
            part.delete()
            target.delete()
            throw e
        }
    }

    private fun forgetOthers(keep: File) {
        folder.listFiles()?.filter { it.isDirectory && it != keep }?.forEach { it.deleteRecursively() }
    }

    private companion object {
        const val MANIFEST_LIMIT = 256 * 1024
        const val SIGNATURE_LIMIT = 4 * 1024
    }
}

// A tag as a folder name: letters, digits, dots and dashes only.
internal fun safeName(tag: String): String = tag.map { if (it.isLetterOrDigit() || it == '.' || it == '-') it else '_' }.joinToString("")

fun sha256Of(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return hex(digest.digest())
}

internal fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
