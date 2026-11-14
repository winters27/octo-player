package app.winters.octo.ui.signin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

// The sign-in page's wiring that its composed rows cannot see whole, read
// from the sources: every field's keyboard rule, the camera as a modal with
// a home for focus on every way out, the camera let go where it closes,
// header rows drawn keyed with their focus homes, the callout at the host's
// level, and the view model's form rules. Each check names the exact text,
// so a change that drops it fails here.
class SignInSitesTest {
    private fun source(path: String) = File("src/main/java/app/winters/octo/$path").readText().replace("\r\n", "\n")

    // Code only: comment lines dropped and every run of white space one space.
    private fun code(text: String) = text.lines().filterNot { it.trim().startsWith("//") }.joinToString(" ").replace(Regex("\\s+"), " ")

    // The call starting at `at`, to its closing parenthesis.
    private fun callAt(text: String, at: Int): String {
        var depth = 0
        var i = text.indexOf('(', at)
        while (i < text.length) {
            when (text[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return text.substring(at, i + 1)
                }
            }
            i++
        }
        error("No end to the call at $at")
    }

    private fun calls(text: String, name: String): List<String> =
        Regex("(?<![A-Za-z])" + Regex.escape(name) + "\\(").findAll(text).map { code(callAt(text, it.range.first)) }.toList()

    // The text from `from` to `to`, both asserted found.
    private fun between(text: String, from: String, to: String): String {
        val start = text.indexOf(from)
        assertTrue("\"$from\" is missing", start >= 0)
        val end = text.indexOf(to, start)
        assertTrue("\"$to\" is missing after \"$from\"", end >= 0)
        return text.substring(start, end)
    }

    private val screen = source("ui/signin/SignInScreen.kt")
    private val groups = source("ui/common/FormGroups.kt")
    private val model = source("ui/signin/SignInViewModel.kt")
    private val scanner = source("ui/family/FamilyQrViews.kt")

    // Red when the page types into a raw field of its own (its focus would
    // not reach the callout, and its look would not be the form's), or the
    // primitives stop using the field the check names.
    @Test
    fun everyEntryIsAFormRow() {
        for (raw in listOf("GlassInput(", "BasicTextField(", "TextField(")) assertFalse(raw, screen.contains(raw))
        assertTrue(groups.contains("GlassInput("))
    }

    // Red when a field's keyboard action leaves a Next with nothing drawn
    // after it, a Next field submits, a submit field loses its action, or the
    // address loses its scheme or its focus home.
    @Test
    fun everyFieldKeepsItsKeyboardRule() {
        val rows = calls(screen, "FormFieldRow")
        fun row(title: String) = rows.single { "title = $title," in it }
        val address = row("SignInWords.SERVER_ADDRESS")
        assertTrue(address.contains("imeAction = if (vm.joining && vm.invite == null && !showsName) ImeAction.Done else ImeAction.Next"))
        assertTrue(address.contains("leading = { SchemeButton("))
        assertTrue(address.contains("focus = addressFocus"))
        assertTrue(row("SignInWords.SERVER_NAME").contains("imeAction = if (vm.joining && vm.invite == null) ImeAction.Done else ImeAction.Next"))
        assertTrue(row("SignInWords.HOME_ADDRESS").contains("imeAction = if (vm.headers.isEmpty()) ImeAction.Done else ImeAction.Next"))
        assertTrue(code(screen).contains("valueImeAction = if (index == vm.headers.lastIndex) ImeAction.Done else ImeAction.Next"))
        assertEquals(listOf(address), rows.filter { "leading =" in it })
        assertEquals(listOf(address), rows.filter { "focus =" in it })
        for (submits in rows.filter { "onSubmit =" in it }) assertTrue(submits, submits.contains("imeAction = ImeAction.Done"))
        assertTrue(rows.single { "value = vm.password," in it }.contains("onSubmit = vm::submit"))
        assertTrue(row("SignInWords.API_KEY").contains("onSubmit = vm::submit"))
        assertTrue(row("SignInWords.PASSWORD_AGAIN").contains("onSubmit = vm::join"))
        assertTrue(row("labels.second").contains("onSubmit = gatewaySubmit(vm)"))
        val submit = code(between(screen, "private fun gatewaySubmit(", "@Composable"))
        assertTrue(submit.contains("vm.invite != null -> vm::join"))
        assertTrue(submit.contains("vm.handOver != null -> vm::receive"))
    }

    // Red when any part of the camera's modal wiring or its focus homes
    // changes from the design's exact text.
    @Test
    fun theCameraIsAModalWithAHomeForFocus() {
        val page = code(screen)
        listOf(
            "LaunchedEffect(vm.scanning) { if (vm.scanning) { if (inputModes.inputMode == InputMode.Touch) focusManager.clearFocus() else { scanCancel.requestFocus(); keyboard?.hide() } } }",
            "val keyboard = LocalSoftwareKeyboardController.current",
            "val scanCancel = remember { FocusRequester() }",
            "val closeScanner = { if (inputModes.inputMode != InputMode.Touch) scanAgain.requestFocus(); vm.scanning = false }",
            "val focusAddress = { if (inputModes.inputMode != InputMode.Touch) addressFocus.requestFocus() }",
            "val onFound: (FamilyLink) -> Unit = { if (vm.scanning) { focusAddress(); vm.startJoin(it) } }",
            "BackHandler(enabled = vm.scanning) { closeScanner() }",
            "LocalQrScanner.current(onFound, closeScanner, scanCancel)",
            "LaunchedEffect(join, joinOpened) { if (join != null) vm.open(join, joinOpened) { focusAddress() } }",
            "Box(Modifier.fillMaxSize().then(if (vm.scanning) Modifier.clearAndSetSemantics {} else Modifier)) { SignInForm(vm, scanAgain, addressFocus) BackButton(",
            "modifier = Modifier.padding(top = 12.dp).focusRequester(scanAgain)",
        ).forEach { assertTrue(it, page.contains(it)) }
        val view = code(scanner)
        listOf(
            "fun QrScanner(onFound: (FamilyLink) -> Unit, onClose: () -> Unit, cancelFocus: FocusRequester)",
            "GlazeButton(\"Cancel\", onClose, Modifier.focusRequester(cancelFocus), size = ButtonSize.Medium)",
            ".pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { if (it.changedToDown()) it.consume() } } }",
            ".focusProperties { onExit = { if (requestedFocusDirection != FocusDirection.Enter && requestedFocusDirection != FocusDirection.Exit) cancelFocusChange() } } .focusGroup()",
            "val LocalQrScanner = staticCompositionLocalOf",
        ).forEach { assertTrue(it, view.contains(it)) }
    }

    // Red when the Name row loses the shared placeholder or types one of its
    // own.
    @Test
    fun theNameRowShowsTheSharedPlaceholder() {
        val name = calls(screen, "FormFieldRow").single { "title = SignInWords.SERVER_NAME," in it }
        assertTrue(name, name.contains("placeholder = SignInWords.NAME_PLACEHOLDER,"))
        assertFalse(screen.contains("Home or Work"))
    }

    // Red when the camera is bound or let go anywhere but the one place
    // each belongs, or closing stops letting it go.
    @Test
    fun theCameraIsLetGoWhereItCloses() {
        val view = code(scanner)
        assertEquals(1, Regex(Regex.escape("onDispose { camera.close(); analysis.shutdown() }")).findAll(view).count())
        val listener = view.indexOf("future.addListener({")
        assertTrue(listener >= 0)
        assertTrue(view.indexOf("camera.bind(preview, reader)") > listener)
        val body = between(scanner, "private fun ScannerView", "internal interface CameraBinder")
        for (gone in listOf("bindToLifecycle(", "unbindAll(", "future.get(")) assertFalse(gone, body.contains(gone))
        val binder = scanner.indexOf("internal class ProviderBinder")
        assertTrue(binder >= 0)
        assertEquals(listOf(true), Regex("bindToLifecycle\\(").findAll(scanner).map { it.range.first > binder }.toList())
        assertEquals(1, Regex(Regex.escape(".get().unbindAll()")).findAll(scanner).count())
        assertTrue(code(between(scanner, "override fun unbindAll()", "override fun bind(")).contains(".get().unbindAll()"))
        val unbind = scanner.substringAfter("override fun unbind(preview: Preview, reader: ImageAnalysis) {").substringBefore("}")
        assertEquals("future.get().unbind(preview, reader)", unbind.trim())
    }

    // Red when the header rows are drawn by position, Remove's focus homes
    // change, Add header asks for focus in its own click, or a Remove button
    // stops being named for its row.
    @Test
    fun headerRowsDrawKeyedWithTheirFocusHomes() {
        val page = code(screen)
        assertTrue(page.contains("key(headerIds[index])"))
        assertTrue(page.contains("{ if (inputModes.inputMode == InputMode.Touch) focusManager.clearFocus() else if (focusedRowId == id) addHeaderFocus.requestFocus(); vm.removeHeader(id) }"))
        assertTrue(page.contains("focusNewHeader = true"))
        assertEquals(1, Regex(Regex.escape("LaunchedEffect(focusNewHeader")).findAll(page).count())
        assertTrue(groups.contains("SignInWords.removeHeader("))
        for (file in listOf(screen, groups, model)) assertFalse(file.contains("\"Remove header"))
    }

    // Red when "optional" is typed into the page's words anywhere but the
    // shared tag.
    @Test
    fun noWordsOnThePageSayOptionalButTheTag() {
        for (file in listOf(screen, groups)) {
            val literals = Regex("\"[^\"\\n]*\"").findAll(file).map { it.value }.toList()
            assertTrue(literals.isNotEmpty())
            for (literal in literals) assertFalse(literal, literal.contains("optional", ignoreCase = true))
        }
    }

    // Red when the callout moves inside Advanced (closing Advanced would end
    // it), loses its key, its focus rule or its one focus record, or a host
    // stops providing itself.
    @Test
    fun theCalloutRunsAtTheHostLevel() {
        val effect = code(groups)
        listOf(
            "LaunchedEffect(pending)",
            "if (inputModes.inputMode == InputMode.Touch) focusManager.clearFocus() else keyboard?.hide()",
            "val currentHost by rememberUpdatedState(host)",
            "val currentGroup by rememberUpdatedState(group)",
            "val currentShown by rememberUpdatedState(onShown)",
            "if (it.isFocused && !focused) host?.focus?.taken = true",
            "if (currentCoroutineContext().isActive) currentShown()",
        ).forEach { assertTrue(it, effect.contains(it)) }
        assertTrue(effect.indexOf("currentHost?.focus?.taken = false") < effect.indexOf("focusManager.clearFocus()"))
        val page = code(screen)
        assertTrue(page.contains("CompositionLocalProvider(LocalCalloutHost provides host)"))
        assertEquals(1, Regex(Regex.escape("remember { CalloutFocus() }")).findAll(page).count())
        assertTrue(page.contains("focus = calloutFocus"))
        val form = between(screen, "private fun SignInForm(", "private fun MainCard(")
        assertTrue(form.contains("CalloutEffect(vm.calloutPending, host, { gatewayGroup }, vm::calloutShown)"))
        assertTrue(form.contains("CalloutEffect(vm.keyPending, host?.copy(settled = { true }), { keyRow }, vm::keyShown)"))
        assertTrue(form.indexOf("CalloutEffect(") < form.indexOf("AnimatedVisibility("))
        assertFalse(between(screen, "internal fun Advanced(", "private fun GatewayGroup(").contains("CalloutEffect("))
    }

    // The body of a view model function, from its head to the next function.
    private fun body(head: String): String = code(between(model, head, "\n    fun ").ifBlank { error("no $head") })

    // Red when an attempt starter calls the form out before its guards, or
    // writes an error before ending the last callout.
    @Test
    fun everyAttemptStarterSendsAfterItsGuards() {
        for ((head, guard) in listOf(
            "fun submit() {" to "if (busy || !ready) return",
            "fun join() {" to "if (busy || joinProblem != null || url == null) return",
            "fun receive() {" to "if (busy) return",
            "fun trust() {" to "val asked = question ?: return",
        )) {
            val text = body(head)
            val sent = text.indexOf("useGatewayForm(sending(gatewayForm))")
            assertTrue(head, sent > text.indexOf(guard))
            val firstError = text.indexOf("error =")
            assertTrue(head, firstError < 0 || sent < firstError)
        }
    }

    // Red when a failure that met a sign-in page is reacted to before its
    // words are set (the error's setter would end the callout it starts), or
    // a site is added or lost.
    @Test
    fun everyGatewayFailureWritesItsWordsFirst() {
        val all = code(model)
        assertEquals(5, Regex("(?<!fun )met\\(").findAll(all).count())
        assertTrue(all.contains("error = refused.gateway?.let { GatewayWords.failure(it, refused.loginSent) } ?: refused.message met(attempt, refused.gateway)"))
        assertTrue(all.contains("error = joined.gateway?.let { GatewayWords.failure(it, joined.loginSent) } ?: joined.message met(attempt, joined.gateway)"))
        assertEquals(2, Regex(Regex.escape("error = failed.userMessage() met(attempt, gatewayOf(failed))")).findAll(all).count())
        assertTrue(all.contains("LegacyRetry.None -> error = failed.userMessage() } met(request.address, gatewayOf(failed))"))
    }

    // Red when the form state stops carrying the callout both ways, the
    // error stops calming it, a header write bypasses HeaderList, or the
    // Method is read another way.
    @Test
    fun theFormStateCarriesTheCalloutAndTheRules() {
        val all = code(model)
        listOf(
            "private set(value) { errorWords = value useGatewayForm(calm(gatewayForm)) }",
            "GatewayFormState(advancedOpen, gatewayKind, gateway, gatewayFor, callout, calloutPending, keyPending)",
            "callout = state.callout",
            "calloutPending = state.calloutPending",
            "keyPending = state.keyPending",
            "private var list by mutableStateOf(HeaderList.empty)",
            "val mode: AuthMode get() = authModeOf(legacyPassword, useApiKey)",
            "useGatewayForm(gatewayEdited(gatewayForm))",
            "useGatewayForm(kindPicked(gatewayForm, kind))",
        ).forEach { assertTrue(it, all.contains(it)) }
        for (bypass in listOf("headers.add", "headers.remove", "headers.clear")) assertFalse(bypass, all.contains(bypass))
        assertFalse(Regex("headers\\[[^\\]]*\\] =").containsMatchIn(all))
    }

    // Red when a header setter is called with a row's place instead of its
    // id (an integer literal still compiles against a Long).
    @Test
    fun noHeaderSetterIsCalledWithAPlace() {
        val positional = Regex("(setHeaderName|setHeaderValue|removeHeader)\\(\\s*\\d")
        for (root in listOf(File("src/main/java"), File("src/test/java"))) {
            root.walkTopDown().filter { it.extension == "kt" }.forEach { file ->
                assertFalse(file.path, positional.containsMatchIn(file.readText()))
            }
        }
    }
}
