package io.github.mjohnson7cd.chronicleauto.features.car

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.MutableLiveData
import io.github.mjohnson7cd.chronicleauto.data.local.LibrarySyncRepository
import io.github.mjohnson7cd.chronicleauto.data.model.PlexLibrary
import io.github.mjohnson7cd.chronicleauto.data.model.ServerModel
import io.github.mjohnson7cd.chronicleauto.data.sources.plex.FakePlexPrefsRepo
import io.github.mjohnson7cd.chronicleauto.data.sources.plex.IPlexLoginRepo
import io.github.mjohnson7cd.chronicleauto.data.sources.plex.IPlexLoginRepo.LoginState
import io.github.mjohnson7cd.chronicleauto.data.sources.plex.PlexConfig
import io.github.mjohnson7cd.chronicleauto.data.sources.plex.PlexConfig.ConnectionState
import io.github.mjohnson7cd.chronicleauto.data.sources.plex.PlexLoginService
import io.github.mjohnson7cd.chronicleauto.data.sources.plex.PlexMediaService
import io.github.mjohnson7cd.chronicleauto.data.sources.plex.model.MediaType.Companion.ARTIST
import io.github.mjohnson7cd.chronicleauto.data.sources.plex.model.OAuthResponse
import io.github.mjohnson7cd.chronicleauto.data.sources.plex.model.PlexServer
import io.github.mjohnson7cd.chronicleauto.data.sources.plex.model.UsersResponse
import io.github.mjohnson7cd.chronicleauto.data.sources.plex.model.PlexUser
import io.github.mjohnson7cd.chronicleauto.features.car.CarSignInViewModel.UiState
import io.github.mjohnson7cd.chronicleauto.util.Event
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Regression tests for the "stuck on Couldn't reach Plex: <server>" switch-server bug. */
@OptIn(ExperimentalCoroutinesApi::class, InternalCoroutinesApi::class)
class CarSignInViewModelTest {
    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private val home = ServerModel(name = "Home", connections = emptyList(), serverId = "home")
    private val artemis = ServerModel(name = "Artemis", connections = emptyList(), serverId = "artemis")
    private val audiobooks = PlexLibrary(name = "Audiobooks", type = ARTIST, id = "1")

    private lateinit var prefs: FakePlexPrefsRepo
    private lateinit var loginEvent: MutableLiveData<Event<LoginState>>
    private lateinit var connectionState: MutableLiveData<ConnectionState>
    private lateinit var loginRepo: IPlexLoginRepo
    private lateinit var loginService: PlexLoginService
    private lateinit var plexConfig: PlexConfig

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        prefs =
            FakePlexPrefsRepo().apply {
                accountAuthToken = "token"
                user = PlexUser(uuid = "u1", title = "Mara")
                server = home
                library = audiobooks
            }
        loginEvent = MutableLiveData(Event(LoginState.LOGGED_IN_FULLY))
        connectionState = MutableLiveData(ConnectionState.CONNECTED)
        loginRepo = mockk(relaxed = true)
        every { loginRepo.loginEvent } returns loginEvent
        every { loginRepo.chooseServer(any()) } answers {
            prefs.server = firstArg()
            loginEvent.value = Event(LoginState.LOGGED_IN_NO_LIBRARY_CHOSEN)
        }
        every { loginRepo.determineLoginState() } answers {
            loginEvent.value =
                Event(
                    when {
                        prefs.accountAuthToken.isEmpty() -> LoginState.NOT_LOGGED_IN
                        prefs.server == null -> LoginState.LOGGED_IN_NO_SERVER_CHOSEN
                        prefs.library == null -> LoginState.LOGGED_IN_NO_LIBRARY_CHOSEN
                        else -> LoginState.LOGGED_IN_FULLY
                    },
                )
        }
        loginService = mockk()
        coEvery { loginService.resources() } returns
            listOf(
                PlexServer(name = "Home", provides = "server", clientIdentifier = "home"),
                PlexServer(name = "Artemis", provides = "server", clientIdentifier = "artemis"),
            )
        plexConfig = mockk(relaxed = true)
        every { plexConfig.connectionState } returns connectionState
        // Artemis is unreachable.
        every { plexConfig.connectToServerWithRetryAndState(any()) } answers {
            connectionState.value = ConnectionState.CONNECTION_FAILED
        }
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun vm(switchServer: Boolean) =
        CarSignInViewModel(
            loginRepo,
            loginService,
            mockk<PlexMediaService>(relaxed = true),
            plexConfig,
            mockk<LibrarySyncRepository>(relaxed = true),
            prefs,
            switchServer,
        )

    @Test
    fun `switch opens server list without clearing the current server`() {
        val vm = vm(switchServer = true)
        assertTrue(vm.state.value is UiState.ChooseServer)
        assertEquals(home, prefs.server)
        assertEquals(audiobooks, prefs.library)
    }

    @Test
    fun `unreachable server shows error with choose-another-server option`() {
        val vm = vm(switchServer = true)
        vm.chooseServer(artemis)
        val state = vm.state.value
        assertTrue("was $state", state is UiState.Error && state.canChooseServer)
    }

    @Test
    fun `cancel after failed switch restores the previous server and library`() {
        val vm = vm(switchServer = true)
        vm.chooseServer(artemis)
        vm.cancel()
        assertEquals(home, prefs.server)
        assertEquals(audiobooks, prefs.library)
        assertEquals("token", prefs.accountAuthToken)
        assertEquals(LoginState.LOGGED_IN_FULLY, loginEvent.value?.peekContent())
        verify(exactly = 0) { plexConfig.clear() }
    }

    @Test
    fun `user stuck on unreachable saved server can pick another one`() {
        // The state the user's car was left in by the old flow.
        prefs.server = artemis
        prefs.library = null
        val vm = vm(switchServer = false)
        assertTrue(vm.state.value is UiState.Error)

        vm.chooseDifferentServer()
        assertTrue(vm.state.value is UiState.ChooseServer)

        // Home is reachable; picking it must not be swallowed by the NO_LIBRARY_CHOSEN dedupe.
        every { plexConfig.connectToServerWithRetryAndState(any()) } answers {
            connectionState.value = ConnectionState.CONNECTED
        }
        vm.chooseServer(home)
        assertEquals(home, prefs.server)
        verify(exactly = 2) { plexConfig.connectToServerWithRetryAndState(any()) }
    }

    @Test
    fun `late polling tick after link does not bounce UI back to the code screen`() {
        val dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
        prefs.clear()
        loginEvent.value = Event(LoginState.NOT_LOGGED_IN)
        every { loginRepo.determineLoginState() } answers { loginEvent.value = Event(LoginState.NOT_LOGGED_IN) }
        // Real check is a network call: suspend, so the collector observes each Polling tick.
        coEvery { loginRepo.checkForOAuthAccessToken() } coAnswers { delay(100) }
        coEvery { loginRepo.postLinkPin() } returns OAuthResponse(id = 1, clientIdentifier = "c", code = "ZB2D")
        coEvery { loginService.getUsersForAccount() } returns
            UsersResponse(listOf(PlexUser(uuid = "a", title = "Mara"), PlexUser(uuid = "b", title = "Shane")))

        val vm = vm(switchServer = false)
        dispatcher.scheduler.advanceTimeBy(2_000)
        dispatcher.scheduler.runCurrent()
        assertTrue("was ${vm.state.value}", vm.state.value is UiState.ShowCode)

        // Code entered at plex.tv/link: repo posts the new state asynchronously, so the
        // coordinator's poll doesn't see it until a later tick.
        loginEvent.value = Event(LoginState.LOGGED_IN_NO_USER_CHOSEN)
        dispatcher.scheduler.runCurrent()
        assertTrue("was ${vm.state.value}", vm.state.value is UiState.ChooseUser)

        dispatcher.scheduler.advanceTimeBy(10_000)
        dispatcher.scheduler.runCurrent()
        assertTrue("was ${vm.state.value}", vm.state.value is UiState.ChooseUser)
    }
}
