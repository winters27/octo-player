package app.winters.octo.ui.signin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

// The wiring of links and asks that unit rows cannot reach (MainShell cannot
// be composed in a unit test, and Hilt's bindings are only built in the
// app), read from the sources. Each check names the exact text.
class LinkSitesTest {
    private fun source(path: String) = File("src/main/java/app/winters/octo/$path").readText().replace("\r\n", "\n")

    // Code only: comment lines dropped and every run of white space one space.
    private fun code(text: String) = text.lines().filterNot { it.trim().startsWith("//") }.joinToString(" ").replace(Regex("\\s+"), " ")

    private fun between(text: String, from: String, to: String): String {
        val start = text.indexOf(from)
        assertTrue("\"$from\" is missing", start >= 0)
        val end = text.indexOf(to, start)
        assertTrue("\"$to\" is missing after \"$from\"", end >= 0)
        return text.substring(start, end)
    }

    private val shell = source("ui/nav/MainShell.kt")

    // Red when the shell routes a link or an ask itself instead of through
    // the tested functions, reads a collected copy of the sessions, or gives
    // them other counters and entries.
    @Test
    fun theShellRoutesLinksAndAsksThroughTheTestedFunctions() {
        val all = code(shell)
        assertTrue(all.contains("val running = hiltViewModel<SignInShellViewModel>().running"))
        val family = code(between(shell, "LaunchedEffect(familyOpen) {", "LaunchedEffect(shared)"))
        listOf(
            "routeFamilyOpen(",
            "reopen = { familyHub.open(it) }",
            "nextOpening = { ++joinOpens }",
            "stack = stacks.last()",
            "askAgain = { sessions.ask(it) }",
            "nameOf = { askName(it, sessions.servers.value, sessions.state.value) }",
        ).forEach { assertTrue(it, family.contains(it)) }
        assertTrue(all.contains("routeSignInAsk(ask, sessions.servers.value, sessions.state.value, running, "))
        assertTrue(all.contains("sessions.signInAsked(ask)"))
        val select = code(between(shell, "onSelect = { index ->", "onRoundButton"))
        assertTrue(select.contains("stack.reselect(running.open.value.isNotEmpty())"))
        assertFalse(select.contains("while (stack.size > 1)"))
        assertEquals(4, Regex(Regex.escape("onDone = { stack.removeOwn(route) }")).findAll(shell).count())
        assertTrue(all.contains("stack.openJoin(FamilyJoinRoute(familyAppLink(what.link), opened = nextOpening()))"))
        assertTrue(all.contains("feedback.show(SignInWords.LINK_WHILE_SIGN_IN_OPEN, \"Use it\", { reopen(what) })"))
    }

    // Red when the open set is made more than once (each form would mark its
    // own), or the shell cannot reach it.
    @Test
    fun oneOpenSetServesTheWholeApp() {
        assertTrue(source("ui/signin/SignInRunning.kt").contains("@Singleton\nclass SignInRunning @Inject constructor()"))
        assertTrue(source("ui/signin/SignInAsks.kt").contains("val running: SignInRunning"))
    }

    // Red when the switch's ask can be cancelled by the page (it runs outside
    // the switch's own scope), or the list of servers pushes a form itself.
    @Test
    fun theSwitchsAskIsRaisedWhereNothingOnScreenCancelsIt() {
        val switcher = source("playback/ServerSwitch.kt")
        val switchTo = between(switcher, "suspend fun switchTo(id: String): SwitchOutcome = scope.async {", "}.await()")
        assertTrue(switchTo.contains("sessions.switchTo(id)"))
        assertTrue(switcher.contains(") : SignInSwitch {"))
        val sessions = code(between(source("data/SessionRepository.kt"), "suspend fun switchTo(id: String)", "suspend fun check(id: String)"))
        assertEquals(5, Regex(Regex.escape("return@withLock asked(SwitchResult.NeedsPassword(")).findAll(sessions).count())
        assertFalse(sessions.contains("return@withLock SwitchResult.NeedsPassword("))
        val list = code(between(source("ui/settings/ServersSection.kt"), "fun switch(server: StoredServer)", "fun menu("))
        assertTrue(list.contains("is SwitchOutcome.NeedsPassword -> Unit"))
        assertFalse(list.contains("outcome.server.id"))
    }

    // Red when the page's Back handlers are composed in the wrong order (the
    // camera's must win), or a way out stops following the phase.
    @Test
    fun thePagesWaysOutFollowThePhase() {
        val page = between(source("ui/signin/SignInScreen.kt"), "fun SignInScreen(", "private fun SignInForm(")
        val held = page.indexOf("BackHandler(enabled = vm.busy || vm.holdsSignUp)")
        val camera = page.indexOf("BackHandler(enabled = vm.scanning)")
        assertTrue(held >= 0)
        assertTrue(camera > held)
        val all = code(source("ui/signin/SignInScreen.kt"))
        listOf(
            "BackButton(back, enabled = !vm.committing)",
            "vm.committing -> Unit",
            "vm.holdsSignUp -> vm.stopJoin()",
            "onDone()",
            "GlazeButton(\"Sign in with a password\", vm::stopJoin, enabled = !vm.committing,",
            "readOnly = vm.holdsSignUp,",
            "SchemeButton(vm.scheme, enabled = !vm.holdsSignUp, onClick = vm::toggleScheme)",
            "Modifier.bringIntoViewRequester(seen)",
        ).forEach { assertTrue(it, all.contains(it)) }
        // The trust question's own popup window cannot be built in a unit
        // test (no merged resources), so its wiring is read from the sheet
        // itself: a tap outside its card answers nothing, and Back answers
        // No. Red when the outside tap answers No.
        val trust = code(between(source("ui/signin/SignInScreen.kt"), "internal fun TrustSheet(", "PopupQuestion("))
        assertTrue(trust.contains("onDismiss = {},"))
        assertTrue(trust.contains("onBack = { onCancel() true },"))
        assertTrue(code(source("ui/common/Layout.kt")).contains("fun BackButton(onBack: () -> Unit, enabled: Boolean = true)"))
        val button = code(File("../design/src/main/java/app/winters/octo/design/GlazedIconButton.kt").readText())
        assertTrue(button.contains("enabled: Boolean = true,"))
        assertTrue(button.contains("enabled = enabled, role = Role.Button,"))
    }

    // Red when a phase flag is set or cleared in the wrong place, a job's end
    // clears another job's flags, the open marker misses a writer, or the
    // seams are wired other than the design says.
    @Test
    fun theViewModelKeepsItsPhasesAndSeams() {
        val model = code(source("ui/signin/SignInViewModel.kt"))
        listOf(
            "private val switcher: ServerSwitch, private val signIns: SignInSwitch, private val clients: JoinClients, private val devices: DeviceIds, private val running: SignInRunning, ) : ViewModel()",
            "val committing: Boolean get() = busy && !waiting",
            "waiting = received == null",
            "} finally { http.letGo() } waiting = false",
            "fun stopJoin() { if (committing) return linkJob?.cancel() linkJob = null busy = false waiting = false handOverStep = null leaveLink() }",
            "if (linkJob === coroutineContext.job) { busy = false waiting = false linkJob = null }",
            "internal val marking: Job = viewModelScope.launch { snapshotFlow { signInOpen(busy, question != null, received != null, signedUp != null) }.collect { running.mark(this@SignInViewModel, it) } }",
            "override fun onCleared() { linkJob?.cancel() running.mark(this, false) }",
            "signedUp?.let { username = it.account.username }",
            "signIns.signIn(request, signsInAgainTo(form, serverId))",
            "signIns.signIn(request, serverId.takeIf { links.kind == LinkForm.KeptServer })",
            "val check = links.received(receivedFacts(signIn))",
            "links = links.adopted(check) attempt = sentAddress",
        ).forEach { assertTrue(it, model.contains(it)) }
        assertFalse(model.contains("switcher.signIn("))
        val start = between(model, "fun startJoin(link: FamilyLink? = null) {", "fun stopJoin()")
        assertTrue(start.indexOf("links.take(") in 0 until start.indexOf("typeAddress("))
        assertTrue(start.indexOf("links = links.took(action)", start.indexOf("typeAddress(")) > 0)
        val module = source("di/SignInModule.kt")
        assertTrue(module.contains("@InstallIn(SingletonComponent::class)"))
        assertTrue(module.contains("abstract fun signIns(switcher: ServerSwitch): SignInSwitch"))
        assertTrue(module.contains("abstract fun joinClients(clients: ServerClients): JoinClients"))
    }
}
