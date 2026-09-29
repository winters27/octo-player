package app.winters.octo.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// A release on the public Octo repository, as GitHub's API lists it. Only
// the parts the updater reads.
@Serializable
data class GitHubRelease(
    @SerialName("tag_name") val tag: String,
    val name: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val body: String? = null,
    val assets: List<GitHubAsset> = emptyList(),
)

@Serializable
data class GitHubAsset(
    val name: String,
    val size: Long = 0,
    @SerialName("browser_download_url") val url: String,
)

// A release this app could update to, with the version its tag names.
data class ReleaseCandidate(val release: GitHubRelease, val version: PlayerVersion) {
    fun asset(name: String): GitHubAsset? = release.assets.firstOrNull { it.name == name }
}

private val releaseJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
}

// The releases in an answer from GitHub's list of releases.
fun parseReleases(text: String): List<GitHubRelease> = releaseJson.decodeFromString(text)

// The newest release of this app that is newer than the one running, or
// null when there is none. Only this app's own tags count: never the other
// app's, and never the server's dated releases, which share the repository.
// Drafts never count, and early versions only when the listener asked to
// try them. A running early version is still offered the release it leads to.
fun newestUpdate(
    releases: List<GitHubRelease>,
    app: PlayerApp,
    running: PlayerVersion,
    early: Boolean,
): ReleaseCandidate? = releases
    .asSequence()
    .filter { !it.draft }
    .mapNotNull { release -> versionOfTag(release.tag, app)?.let { ReleaseCandidate(release, it) } }
    // GitHub's flag and the tag must agree that a release is final.
    .filter { early || (!it.release.prerelease && !it.version.isEarly) }
    .filter { it.version > running }
    .maxByOrNull { it.version }
