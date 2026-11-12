package app.winters.octo.desktop.library

import app.winters.octo.covers.coverBucket
import app.winters.octo.desktop.FakeServer
import app.winters.octo.desktop.SignInPages
import app.winters.octo.desktop.appCoverLoader
import app.winters.octo.desktop.server.Accounts
import app.winters.octo.desktop.server.FakeSecrets
import app.winters.octo.desktop.server.ServerSecurity
import app.winters.octo.desktop.server.SignInOutcome
import app.winters.octo.desktop.settings.SettingsStore
import app.winters.octo.subsonic.Credentials
import app.winters.octo.subsonic.SubsonicClient
import app.winters.octo.subsonic.gateway.Gateway
import app.winters.octo.subsonic.gateway.SignInPageAnswer
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.request.ErrorResult
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jetbrains.skia.Color
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Surface
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

// Covers when a sign-in page answers in their place: never kept, a cover
// moved elsewhere still loads, and a page an older version kept as a cover
// is dropped and the cover fetched again.
class CoverGatewayTest {
    @get:Rule val folder = TemporaryFolder()
    private val server = FakeServer()
    private val elsewhere = FakeServer()
    private val png: ByteArray = Surface.makeRasterN32Premul(8, 8).run {
        canvas.clear(Color.RED)
        makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)!!.bytes
    }

    @After
    fun stop() {
        server.close()
        elsewhere.close()
    }

    // The covers client as the app makes it: the shared client set up for
    // the server, refusing a sign-in page.
    private fun covers(): Pair<ImageLoader, SubsonicClient> {
        val security = ServerSecurity(SettingsStore(File(folder.root, "settings.json")))
        val main = server.address.toHttpUrl()
        security.configure(main, null, emptyMap())
        val http = security.install(OkHttpClient.Builder()).build()
        val loader = coverLoader(PlatformContext.INSTANCE, http, File(folder.root, "cache"), security.refuseHtml())
        return loader to SubsonicClient(main, Credentials("winters", "pw"), http)
    }

    // Red when the covers client goes without RefuseHtml.
    @Test
    fun aSignInPageInPlaceOfACoverIsNeverKept() = runBlocking {
        server.respond("getCoverArt") { SignInPages.redirect("/if/flow/login") }
        server.respond("login") { SignInPages.page() }
        val (loader, client) = covers()
        assertNull(coverBytes(loader, client, "c1", 100))
        assertNull(loader.diskCache!!.openSnapshot(coverKey(client.primaryUrl.host, "c1", coverBucket(100))))
    }

    // Authentik's 401 page in place of a cover is asked once, never
    // kept, and named as a sign-in page by the covers client's refusal. Red
    // (the naming) when RefuseHtml judges 2xx answers only.
    @Test
    fun aSignInPagesRefusalInPlaceOfACoverIsNamedAndNeverKept() = runBlocking {
        server.respond("getCoverArt") { SignInPages.authentik() }
        val (loader, client) = covers()
        assertNull(coverBytes(loader, client, "c1", 100))
        assertNull(loader.diskCache!!.openSnapshot(coverKey(client.primaryUrl.host, "c1", coverBucket(100))))
        assertEquals(1, server.calls.count { it.url.pathSegments.last() == "getCoverArt" })
        val security = ServerSecurity(SettingsStore(File(folder.root, "settings.json")))
        security.configure(server.address.toHttpUrl(), null, emptyMap())
        val http = security.install(OkHttpClient.Builder()).addInterceptor(security.refuseHtml()).build()
        val refused = runCatching { http.newCall(Request.Builder().url(client.coverArtUrl("c1", 100)).build()).execute().close() }.exceptionOrNull()
        assertTrue("got $refused", refused is SignInPageAnswer)
        assertEquals(Gateway.Authentik, (refused as SignInPageAnswer).gateway)
        assertEquals(401, refused.status)
    }

    // Red when an origin rule is added to the refusal.
    @Test
    fun aCoverMovedElsewhereStillLoads() = runBlocking {
        elsewhere.file("art.png", png)
        server.respond("getCoverArt") { SignInPages.redirect(elsewhere.address + "art.png") }
        val (loader, client) = covers()
        assertArrayEquals(png, coverBytes(loader, client, "c1", 100))
    }

    // Red when kept covers are not looked at before use.
    @Test
    fun aSignInPageKeptAsACoverBeforeIsDroppedAndFetchedAgain() = runBlocking {
        server.file("getCoverArt", png)
        val (loader, client) = covers()
        val disk = loader.diskCache!!
        val editor = disk.openEditor(coverKey(client.primaryUrl.host, "c1", coverBucket(100)))!!
        disk.fileSystem.write(editor.metadata) {}
        disk.fileSystem.write(editor.data) { writeUtf8("﻿ <!DOCTYPE html><html></html>") }
        editor.commit()
        assertArrayEquals(png, coverBytes(loader, client, "c1", 100))
        assertEquals(1, server.calls.count { it.url.pathSegments.last() == "getCoverArt" })
    }

    // The app's own loader, over the accounts as the app makes them, turns
    // a sign-in page away at its client: the cover fails as a sign-in page,
    // and nothing is written to the disk for a later read to catch. Red when
    // coverLoader leaves `refuse` out, or the app's loader is given none.
    @Test
    fun theAppsLoaderRefusesASignInPageAtItsClient() = runBlocking {
        server.answer("ping")
        server.respond("getCoverArt") { SignInPages.redirect("/if/flow/login") }
        server.respond("login") { SignInPages.page() }
        val settings = SettingsStore(File(folder.root, "settings.json"))
        val security = ServerSecurity(settings)
        val http = security.install(OkHttpClient.Builder()).build()
        val accounts = Accounts(settings, FakeSecrets(), http, security)
        val client = (accounts.signIn(server.address, "winters", "pw") as SignInOutcome.Done).connection.client
        val loader = appCoverLoader(PlatformContext.INSTANCE, http, File(folder.root, "cache"), accounts)
        val bucket = coverBucket(100)
        val result = loader.execute(coverRequest(PlatformContext.INSTANCE, client, "c1", bucket).size(bucket).build())
        val refused = (result as? ErrorResult)?.throwable
        assertTrue("The cover came back as $result", generateSequence(refused) { it.cause }.any { it is SignInPageAnswer })
        assertNull(loader.diskCache!!.openSnapshot(coverKey(client.primaryUrl.host, "c1", bucket)))
    }
}
