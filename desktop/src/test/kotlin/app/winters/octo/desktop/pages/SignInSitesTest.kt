package app.winters.octo.desktop.pages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

// The desktop sign-in sources read as text, for what each site must say that
// no behavior row reaches on its own. CRLF is read as LF; every anchor is
// asserted found.
class SignInSitesTest {
    private fun read(path: String): String = File(path).readText().replace("\r\n", "\n")

    private fun main(path: String) = read("src/main/kotlin/app/winters/octo/desktop/$path")

    private val page get() = main("pages/SignInPage.kt")
    private val join get() = main("pages/JoinFlow.kt")
    private val servers get() = main("pages/ServersSection.kt")
    private val form get() = main("pages/SignInForm.kt")
    private val groups get() = main("pages/FormGroups.kt")

    // The text from `start` up to `end`, both asserted found.
    private fun slice(text: String, start: String, end: String): String {
        val from = text.indexOf(start)
        assertTrue("found: $start", from >= 0)
        val to = text.indexOf(end, from + start.length)
        assertTrue("found: $end", to >= 0)
        return text.substring(from, to)
    }

    private val sheet get() = slice(servers, "private fun ColumnScope.ServerSheet(", "fun changePassword(")

    private fun count(text: String, of: String): Int = Regex(Regex.escape(of)).findAll(text).count()

    // Every call starting with `start` (which ends in its opening bracket),
    // each to its closing bracket.
    private fun calls(text: String, start: String): List<String> {
        val found = mutableListOf<String>()
        var at = text.indexOf(start)
        while (at >= 0) {
            var depth = 0
            var end = text.length
            for (i in at + start.length - 1 until text.length) {
                if (text[i] == '(') depth++
                if (text[i] == ')') {
                    depth--
                    if (depth == 0) {
                        end = i + 1
                        break
                    }
                }
            }
            found += text.substring(at, end)
            at = text.indexOf(start, at + 1)
        }
        return found
    }

    @Test
    fun theHolderPassesTheCalloutStateBothWays() {
        assertTrue(form.contains("GatewayFormState(advancedOpen, gatewayKind, detected, detectedFor, callout, calloutPending, keyPending)"))
        val follow = slice(form, "private fun follow(state: GatewayFormState) {", "\n    }\n")
        listOf("callout = state.callout", "calloutPending = state.calloutPending", "keyPending = state.keyPending").forEach { assertTrue(it, follow.contains(it)) }
        // The result line's setter calms on every write.
        assertTrue(form.contains("follow(calmRule(gatewayState))"))
    }

    // The nine desktop failure sites write the words first, then react.
    @Test
    fun everyGatewayFailureWritesItsWordsBeforeTheFormReacts() {
        var total = 0
        for ((text, expected) in listOf(page to 2, join to 5, servers to 2)) {
            val lines = text.lines()
            val sites = lines.indices.filter { lines[it].contains(".failed(tried, ") }
            assertEquals(expected, sites.size)
            sites.forEach { i -> assertTrue(lines[i - 1], lines[i - 1].contains(".result = false to ")) }
            total += sites.size
        }
        assertEquals(9, total)
    }

    // Every attempt says it is sending before its first write of the result.
    @Test
    fun everyAttemptStarterSaysItIsSending() {
        val starters = listOf(
            slice(page, "    fun test() {", "\n    }\n"),
            slice(page, "    fun signIn() {", "\n    }\n"),
            slice(page, "    fun go() {", "\n    }\n"),
            slice(join, "suspend fun signUpFromForm(", "\n}\n"),
            slice(join, "suspend fun receiveFromForm(", "\n}\n"),
            slice(slice(join, "private fun ColumnScope.LinkSheet(", "\n}\n"), "    fun go() {", "\n    }\n"),
            slice(sheet, "    fun test() {", "\n    }\n"),
            slice(sheet, "    fun save() {", "\n    }\n"),
        )
        starters.forEach { body ->
            val sending = body.indexOf("form.sending()")
            val written = body.indexOf("form.result = ")
            assertTrue(body.lineSequence().first(), sending >= 0 && written > sending)
        }
    }

    // Every header write goes through the reducer, with ids, never a number.
    @Test
    fun noHeaderWriteOutsideTheReducer() {
        listOf("headers.add", "headers.remove", "headers.clear", "headers[", "mutableStateListOf").forEach { assertFalse(it, form.contains(it)) }
        val ops = Regex("""headerList = headerList\.(\w+)\s*[({]""").findAll(form).map { it.groupValues[1] }.toSet()
        assertEquals(setOf("replaced", "kept", "remove", "renamed", "valued"), ops)
        assertTrue(form.contains("val (next, id) = headerList.added()"))
        val literal = Regex("""(setHeaderName|setHeaderValue|removeHeader)\(\s*\d""")
        val sources = File("src").walkTopDown().filter { it.extension == "kt" }.toList()
        assertTrue("found the sources", sources.size > 100)
        sources.forEach { file -> assertFalse(file.path, literal.containsMatchIn(file.readText())) }
    }

    @Test
    fun eachHostProvidesACalloutHostAndRunsTheCalloutsOutsideAdvanced() {
        listOf("SignInPage.kt" to page, "LinkSheet" to slice(join, "private fun ColumnScope.LinkSheet(", "\n}\n"), "ServerSheet" to sheet).forEach { (name, text) ->
            assertTrue(name, text.contains("CompositionLocalProvider(LocalCalloutHost provides host)"))
            assertTrue(name, text.contains("CalloutEffect(form.calloutPending, host, { places.gateway }) { form.calloutShown() }"))
            assertTrue(name, text.contains("CalloutEffect(form.keyPending, host, { places.key }) { form.keyShown() }"))
        }
        assertTrue(groups.contains("LaunchedEffect(pending) {"))
        assertFalse(slice(page, "internal fun ColumnScope.Advanced(", "\n}\n").contains("CalloutEffect("))
    }

    // The hosts draw only the form's own entries; FormGroups is where the
    // real field is. The pop-ups that ask for a password (Change password and
    // the certificate's) draw them too, in a group under a sheet's title.
    @Test
    fun noRawEntryInTheSignInHosts() {
        val certificate = main("pages/CertificateRow.kt")
        val password = slice(servers, "private fun ColumnScope.PasswordSheet(", "fun askToRemove(")
        val raw = listOf("GlassField(", "SecretField(", "BasicTextField(", "TextField(")
        listOf("SignInPage.kt" to page, "JoinFlow.kt" to join, "ServerSheet" to sheet, "ServersSection.kt" to servers, "CertificateRow.kt" to certificate).forEach { (name, text) ->
            raw.forEach { assertFalse("$name holds $it", text.contains(it)) }
        }
        assertTrue(sheet.contains("FormFieldRow("))
        listOf("PasswordSheet" to password, "CertificatePassword" to certificate.substringAfter("private fun ColumnScope.CertificatePassword(")).forEach { (name, text) ->
            assertTrue(name, text.contains("FormSheetTitle("))
            assertEquals(name, 0, count(text, "MenuTitle("))
            assertTrue(name, text.contains("FormFieldRow("))
        }
        assertTrue(groups.contains("GlassField("))
    }

    // Only the address row passes a leading part (the scheme toggle).
    @Test
    fun onlyTheAddressRowPassesLeading() {
        listOf("SignInPage.kt" to page, "ServerSheet" to sheet).forEach { (name, text) ->
            val leading = calls(text, "FormFieldRow(").filter { it.contains("leading =") }
            assertEquals(name, 1, leading.size)
            assertTrue(name, leading.single().contains("SignInWords.SERVER_ADDRESS"))
            assertTrue(name, leading.single().contains("SchemeToggle("))
        }
        assertTrue(calls(join, "FormFieldRow(").isNotEmpty())
        assertTrue(calls(join, "FormFieldRow(").none { it.contains("leading =") })
    }

    @Test
    fun theSubmitSitesPassTheirActions() {
        assertTrue(page.contains("onSubmit = { if (form.invite != null || form.handOver != null) go() else signIn() }"))
        assertTrue(page.contains("submit = submit,"))
        assertTrue(calls(join, "FormFieldRow(").all { it.contains("onSubmit = go") })
        assertTrue(calls(sheet, "FormFieldRow(").all { it.contains("onSubmit = ::save") })
    }

    @Test
    fun headerRowsAreKeyedAndFocusHasANamedHome() {
        assertEquals(1, count(page, "LaunchedEffect(focusNewHeader"))
        assertEquals(1, count(page, "LaunchedEffect(focusAddHeader"))
        assertTrue(page.contains("{ form.removeHeader(id); if (visibility.keyboard) focusAddHeader = true }"))
        assertTrue(page.contains("form.addHeader(); focusNewHeader = true"))
        assertTrue(page.contains("key(form, form.headerIds[index])"))
        assertTrue(groups.contains("SignInWords.removeHeader(header.name)"))
        listOf(page, join, servers, groups).forEach { assertFalse(it.contains("\"Remove header")) }
    }

    @Test
    fun theButtonsRingFollowsTheKeyboardOnTheDesktop() {
        assertTrue(read("../design/src/main/java/app/winters/octo/design/Buttons.kt").contains("if (focused && LocalRingsShown.current) 1f else 0f"))
        assertTrue(read("../desktop-design/src/main/kotlin/app/winters/octo/design/WindowLook.kt").contains("LocalRingsShown provides focus.keyboard"))
    }

    @Test
    fun aTypedValueInASavedRowIsAChangeAndFocusCoversBothSecrets() {
        assertTrue(form.contains("headers.any { !it.saved || it.value.isNotBlank() }"))
        assertTrue(form.contains("val startsAtPassword: Boolean get() = lastServer != null && (useApiKey || username.isNotBlank())"))
    }

    // "Optional" is said only by the tag, never typed into a sign-in word.
    @Test
    fun noSignInWordsSayOptional() {
        val literal = Regex("\"[^\"\\n]*\"")
        listOf("SignInPage.kt" to page, "JoinFlow.kt" to join, "ServerSheet" to sheet, "FormGroups.kt" to groups, "SignInForm.kt" to form).forEach { (name, text) ->
            literal.findAll(text).forEach { assertFalse("$name: ${it.value}", it.value.contains("optional", ignoreCase = true)) }
        }
    }

    private val accounts get() = main("server/Accounts.kt")
    private val app get() = main("AppState.kt")
    private val family get() = main("pages/FamilyPage.kt")
    private val menus get() = read("../desktop-design/src/main/kotlin/app/winters/octo/design/GlassMenu.kt")

    // Every call a sign-in, a sign-up or a hand-over commits through has a
    // total time limit, so nothing that waits for it waits for ever.
    @Test
    fun theCommittingClientsHaveATimeLimit() {
        assertTrue(accounts.contains("internal const val COMMIT_SECONDS = 30L"))
        assertTrue(accounts.contains("checkClient(listOfNotNull(main, home), headers, tried.certificate, COMMIT_SECONDS).use { own ->"))
        assertEquals(2, count(join, "checkClient(addresses, headers, sent.certificate, COMMIT_SECONDS)"))
    }

    // The server sheets never take a family link: their address only types.
    @Test
    fun theServerSheetsAddressOnlyTypes() {
        assertTrue(sheet.contains("onChange = form::typeAddress"))
        assertFalse(sheet.contains("takeJoinLink"))
    }

    // Every way a pop-up closes but its own buttons asks the host, and none
    // moves the page when it refuses: Escape and a press outside close the
    // open one (one kept under it comes back), Settings closes them all.
    @Test
    fun everyCloseButTheSheetsOwnGoesThroughDismiss() {
        assertTrue(menus.contains("Key.Escape -> host.dismiss()"))
        assertTrue(menus.contains("awaitFirstDown(requireUnconsumed = true)\n                    host.dismiss()"))
        assertEquals(2, count(app, "if (!popups.dismissAll()) return"))
        assertTrue(app.contains("popups.open -> popups.dismiss()"))
        assertFalse(app.contains("popups.close()"))
    }

    // Every request that sets busy says whether it waits; only a hand-over's
    // redeem does, and each job is kept as the form's own.
    @Test
    fun everyBusyRequestSaysWhetherItWaits() {
        for ((name, text) in listOf("SignInPage.kt" to page, "LinkSheet" to slice(join, "private fun ColumnScope.LinkSheet(", "\n}\n"), "ServerSheet" to sheet)) {
            val lines = text.lines()
            val busy = lines.indices.filter { lines[it].trim() == "form.busy = true" }
            assertTrue(name, busy.isNotEmpty())
            busy.forEach { i -> assertTrue("$name: ${lines[i + 1]}", lines[i + 1].trim().startsWith("form.waiting = ")) }
        }
        val waits = "form.waiting = form.handOver != null && form.received == null"
        assertEquals(1, count(page, waits))
        assertEquals(1, count(join, waits))
        assertTrue(page.contains("form.running = job"))
        assertTrue(join.contains("form.running = job"))
        assertEquals(2, count(page + join, "if (form.running === coroutineContext.job) {"))
        // The redeem's end, whatever it brought, and the header problem end the wait.
        val receive = slice(join, "suspend fun receiveFromForm(", "\n}\n")
        assertTrue(receive.contains("}\n        // The wait for the other device is over, however it ended: what\n        // follows commits.\n        form.waiting = false"))
        assertTrue(receive.contains("is ResolvedHeaders.Problem -> {\n                form.waiting = false"))
        assertTrue(form.contains("fun leaveLink() {\n        if (committing) return"))
    }

    @Test
    fun aPasswordChangeRunsInTheAppsScopeAndOneAtATime() {
        assertEquals(2, count(servers, "enabled = !app.changingPassword"))
        assertEquals(2, count(family, "enabled = !app.changingPassword"))
        assertTrue(servers.contains("fun changePassword(app: AppState) {\n    if (app.changingPassword) return"))
        assertTrue(servers.contains("app.scope.launch {\n            val outcome = app.changePassword("))
        assertTrue(servers.contains("app.popups.request !== mine -> app.notice = words"))
    }

    // The link note sits under the result line wherever one is drawn, and
    // under the certificate question; a form that replaces the page's
    // carries it.
    @Test
    fun theLinkNoteSitsUnderEveryResultLine() {
        assertTrue(page.contains("ResultLine(form)\n                        LinkNote(form)"))
        assertTrue(join.contains("ResultLine(form)\n    LinkNote(form)"))
        assertTrue(sheet.contains("ResultLine(form)\n        LinkNote(form)"))
        assertTrue(page.contains("onCancel = close,\n            )\n            LinkNote(form)"))
        assertTrue(join.contains("asking = null\n                },\n            )\n            LinkNote(form)"))
        assertTrue(sheet.contains("onCancel = form::distrust,\n            )\n            LinkNote(form)"))
        assertTrue(app.contains("carried?.let(form::noteLink)"))
    }

    // A link from outside checks for an open sign-in where it is routed, a
    // switch on its way included, signed in or out; the link's sheet starts
    // nothing while a switch is on its way.
    @Test
    fun theLinkRouteChecksForAnOpenSignIn() {
        val route = slice(join, "fun AppState.openFamilyLink(", "\n}\n")
        assertTrue(route.contains("if (switching != null || signInOpen(form.busy, form.question != null, form.received != null, form.signedUp != null)) {"))
        assertTrue(route.contains("val open = popups.request?.takeIf { it.open() }\n    if (open != null || switching != null) {"))
        val go = slice(slice(join, "private fun ColumnScope.LinkSheet(", "\n}\n"), "    fun go() {", "\n    }\n")
        assertTrue(go.contains("if (form.busy || app.switching != null) return"))
        assertTrue(route.contains("busy = { signInOpen(form.committing, form.question != null, form.received != null, form.signedUp != null) },"))
        assertTrue(route.contains("open = { signInOpen(form.busy, form.question != null, form.received != null, form.signedUp != null) },"))
        assertTrue(route.contains("link = true,"))
    }
}
