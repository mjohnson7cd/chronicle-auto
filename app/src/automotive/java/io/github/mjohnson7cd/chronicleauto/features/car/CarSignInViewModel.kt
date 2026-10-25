package io.github.mjohnson7cd.chronicleauto.features.car

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.mjohnson7cd.chronicleauto.application.Injector
import io.github.mjohnson7cd.chronicleauto.data.local.LibrarySyncRepository
import io.github.mjohnson7cd.chronicleauto.data.model.PlexLibrary
import io.github.mjohnson7cd.chronicleauto.data.model.ServerModel
import io.github.mjohnson7cd.chronicleauto.data.model.asServer
import io.github.mjohnson7cd.chronicleauto.data.sources.plex.IPlexLoginRepo
import io.github.mjohnson7cd.chronicleauto.data.sources.plex.IPlexLoginRepo.LoginState
import io.github.mjohnson7cd.chronicleauto.data.sources.plex.PlexPrefsRepo
import io.github.mjohnson7cd.chronicleauto.data.sources.plex.PlexConfig
import io.github.mjohnson7cd.chronicleauto.data.sources.plex.PlexLoginService
import io.github.mjohnson7cd.chronicleauto.data.sources.plex.PlexMediaService
import io.github.mjohnson7cd.chronicleauto.data.sources.plex.model.MediaType.Companion.ARTIST
import io.github.mjohnson7cd.chronicleauto.data.sources.plex.model.PlexUser
import io.github.mjohnson7cd.chronicleauto.data.sources.plex.model.asLibrary
import io.github.mjohnson7cd.chronicleauto.features.auth.PlexAuthCoordinator
import io.github.mjohnson7cd.chronicleauto.features.auth.PlexAuthState
import io.github.mjohnson7cd.chronicleauto.util.Event
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import retrofit2.HttpException
import timber.log.Timber

/**
 * Drives the in-car sign-in: plex.tv/link code → user → server → library.
 *
 * The step shown is derived from [IPlexLoginRepo.loginEvent], the same source of truth the phone
 * onboarding uses, so a partially completed sign-in resumes at the right step and the
 * MediaPlayerService sees [LoginState.LOGGED_IN_FULLY] as soon as we finish.
 * Single users / servers / libraries are chosen automatically to minimise taps in the car.
 */
class CarSignInViewModel(
    private val plexLoginRepo: IPlexLoginRepo,
    private val plexLoginService: PlexLoginService,
    private val plexMediaService: PlexMediaService,
    private val plexConfig: PlexConfig,
    private val librarySyncRepository: LibrarySyncRepository,
    private val plexPrefs: PlexPrefsRepo,
    switchServer: Boolean = false,
) : ViewModel() {
    /**
     * @param switchServer true when opened from "Switch server / library" in car settings: the
     * current server/library stay saved until a new library is actually chosen, and are restored
     * if the user cancels or backs out.
     */
    class Factory(private val switchServer: Boolean = false) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val component = Injector.get()
            return CarSignInViewModel(
                component.plexLoginRepo(),
                component.plexLoginService(),
                component.plexMediaService(),
                component.plexConfig(),
                component.librarySyncRepo(),
                component.plexPrefs(),
                switchServer,
            ) as T
        }
    }

    sealed class UiState {
        data class Loading(val message: Message) : UiState()

        data class ShowCode(val code: String) : UiState()

        data class ChooseUser(val users: List<PlexUser>) : UiState()

        data class EnterUserPin(val user: PlexUser, val wrongPin: Boolean = false) : UiState()

        data class ChooseServer(val servers: List<ServerModel>) : UiState()

        data class ChooseLibrary(val libraries: List<PlexLibrary>) : UiState()

        object Done : UiState()

        object CodeExpired : UiState()

        /** [canChooseServer]: offer "Choose another server" (failure was server-specific). */
        data class Error(
            val message: Message,
            val detail: String? = null,
            val canChooseServer: Boolean = false,
        ) : UiState()
    }

    /** Resource-free message keys; the activity maps them to strings. */
    enum class Message {
        REQUESTING_CODE,
        LOADING,
        CONNECTING,
        NO_SERVERS,
        NO_LIBRARIES,
        NETWORK,
    }

    private val _state = MutableLiveData<UiState>(UiState.Loading(Message.LOADING))
    val state: LiveData<UiState> get() = _state

    /** Name of the server being connected to, for the CONNECTING message. */
    var connectingServerName: String = ""
        private set

    private var authCoordinator: PlexAuthCoordinator? = null
    private var authJob: Job? = null
    private var lastRoutedLoginState: LoginState? = null
    private var awaitingLibraries = false

    /** True only while the link code is being shown/polled; gates auth-flow UI updates. */
    private var codeFlowActive = false

    /** Server/library that were active before a switch; restored if the switch is abandoned. */
    private data class Snapshot(val server: ServerModel, val library: PlexLibrary)

    private var switchSnapshot: Snapshot? = null
    private var finished = false
    private var libraryChosen = false

    private val loginObserver =
        Observer<Event<LoginState>> { event -> route(event.peekContent()) }

    private val connectionObserver =
        Observer<PlexConfig.ConnectionState> { connection ->
            if (!awaitingLibraries) return@Observer
            when (connection) {
                PlexConfig.ConnectionState.CONNECTED -> loadLibraries()
                PlexConfig.ConnectionState.CONNECTION_FAILED -> {
                    awaitingLibraries = false
                    _state.postValue(UiState.Error(Message.NETWORK, connectingServerName, canChooseServer = true))
                }
                else -> Unit
            }
        }

    init {
        val server = plexPrefs.server
        val library = plexPrefs.library
        val switching = switchServer && plexPrefs.accountAuthToken.isNotEmpty() && plexPrefs.user != null
        if (switching) {
            // Don't touch saved prefs yet: just show the server list. Mark the current (sticky)
            // login event as already routed *before* observing so it doesn't trigger a connect.
            if (server != null && library != null) switchSnapshot = Snapshot(server, library)
            lastRoutedLoginState = plexLoginRepo.loginEvent.value?.peekContent()
        }
        plexLoginRepo.loginEvent.observeForever(loginObserver)
        plexConfig.connectionState.observeForever(connectionObserver)
        if (switching) loadServers() else plexLoginRepo.determineLoginState()
    }

    private fun route(loginState: LoginState) {
        if (loginState == lastRoutedLoginState) return
        Timber.i("[CarSignIn] login state -> $loginState")
        if (loginState != LoginState.NOT_LOGGED_IN && loginState != LoginState.AWAITING_LOGIN_RESULTS) {
            // Linked: the polling loop must not emit again, or its Polling state would
            // re-post ShowCode over the user/server picker (stuck-on-code-screen bug).
            stopCodeFlow()
        }
        when (loginState) {
            LoginState.NOT_LOGGED_IN -> startCodeFlow()
            LoginState.AWAITING_LOGIN_RESULTS -> return // transient: keep current UI, don't latch
            LoginState.FAILED_TO_LOG_IN -> _state.postValue(UiState.Error(Message.NETWORK))
            LoginState.LOGGED_IN_FULLY ->
                if (switchSnapshot != null && !libraryChosen) {
                    return // stale/sticky event mid-switch; wait for the new library
                } else {
                    finishSignIn()
                }
            LoginState.LOGGED_IN_NO_USER_CHOSEN -> loadUsers()
            LoginState.LOGGED_IN_NO_SERVER_CHOSEN -> loadServers()
            LoginState.LOGGED_IN_NO_LIBRARY_CHOSEN -> connectForLibraries()
        }
        lastRoutedLoginState = loginState
    }

    /** Retry whatever step failed (or request a fresh code). */
    fun retry() {
        lastRoutedLoginState = null
        authCoordinator?.reset()
        plexLoginRepo.determineLoginState()
    }

    /**
     * Abandon the current server (e.g. unreachable) and pick another one. Keeps the Plex
     * account and user; only the half-chosen server/library are dropped.
     */
    fun chooseDifferentServer() {
        awaitingLibraries = false
        lastRoutedLoginState = null
        // Prefs are left alone until a new server is actually picked (chooseServer), so
        // cancelling from the list loses nothing.
        loadServers()
    }

    fun requestNewCode() {
        lastRoutedLoginState = null
        startCodeFlow()
    }

    private fun stopCodeFlow() {
        codeFlowActive = false
        authJob?.cancel()
        authJob = null
        authCoordinator?.dispose()
    }

    private fun startCodeFlow() {
        authJob?.cancel()
        authCoordinator?.dispose()
        codeFlowActive = true
        _state.postValue(UiState.Loading(Message.REQUESTING_CODE))
        val coordinator =
            PlexAuthCoordinator(
                plexLoginRepo,
                viewModelScope,
                PlexAuthCoordinator.LINK_CODE_TIMEOUT_MS,
                useLinkCode = true,
            )
        authCoordinator = coordinator
        authJob =
            viewModelScope.launch {
                coordinator.startAuth().collect { authState ->
                    if (!codeFlowActive) return@collect
                    when (authState) {
                        is PlexAuthState.WaitingForUser -> _state.postValue(UiState.ShowCode(authState.pinCode))
                        is PlexAuthState.Polling ->
                            if (_state.value !is UiState.ShowCode && authState.pinCode.isNotEmpty()) {
                                _state.postValue(UiState.ShowCode(authState.pinCode))
                            }
                        // Success: loginEvent advances (user/server step) and route() takes over
                        is PlexAuthState.Success -> Unit
                        is PlexAuthState.Timeout -> _state.postValue(UiState.CodeExpired)
                        is PlexAuthState.Error -> _state.postValue(UiState.Error(Message.NETWORK, authState.message))
                        else -> Unit
                    }
                }
            }
    }

    private fun loadUsers() {
        _state.postValue(UiState.Loading(Message.LOADING))
        viewModelScope.launch {
            try {
                val users = plexLoginService.getUsersForAccount().users
                when {
                    users.isEmpty() -> _state.postValue(UiState.Error(Message.NETWORK, "No Plex users"))
                    users.size == 1 -> pickUser(users.first())
                    else -> _state.postValue(UiState.ChooseUser(users))
                }
            } catch (t: Throwable) {
                Timber.e(t, "[CarSignIn] failed to load users")
                _state.postValue(UiState.Error(Message.NETWORK, t.message))
            }
        }
    }

    fun pickUser(user: PlexUser) {
        if (user.hasPassword) {
            _state.postValue(UiState.EnterUserPin(user))
        } else {
            submitUserPin(user, null)
        }
    }

    fun submitUserPin(
        user: PlexUser,
        pin: String?,
    ) {
        _state.postValue(UiState.Loading(Message.LOADING))
        viewModelScope.launch {
            try {
                val responseUser = plexLoginService.pickUser(user.uuid, pin)
                check(!responseUser.authToken.isNullOrEmpty()) { "No auth token received for user" }
                plexLoginRepo.chooseUser(responseUser)
            } catch (e: HttpException) {
                Timber.w("[CarSignIn] pick user failed: ${e.code()}")
                if (e.code() == 403) {
                    _state.postValue(UiState.EnterUserPin(user, wrongPin = true))
                } else {
                    _state.postValue(UiState.Error(Message.NETWORK, "HTTP ${e.code()}"))
                }
            } catch (t: Throwable) {
                Timber.e(t, "[CarSignIn] pick user failed")
                _state.postValue(UiState.Error(Message.NETWORK, t.message))
            }
        }
    }

    private fun loadServers() {
        _state.postValue(UiState.Loading(Message.LOADING))
        viewModelScope.launch {
            try {
                val servers =
                    plexLoginService.resources()
                        .filter { it.provides.contains("server") }
                        .map { it.asServer() }
                when (servers.size) {
                    0 -> _state.postValue(UiState.Error(Message.NO_SERVERS))
                    // Switching with one server: still show it so the user sees what's happening.
                    1 -> if (switchSnapshot != null) {
                        _state.postValue(UiState.ChooseServer(servers))
                    } else {
                        chooseServer(servers.first())
                    }
                    else -> _state.postValue(UiState.ChooseServer(servers))
                }
            } catch (t: Throwable) {
                Timber.e(t, "[CarSignIn] failed to load servers")
                _state.postValue(UiState.Error(Message.NETWORK, t.message))
            }
        }
    }

    fun chooseServer(server: ServerModel) {
        connectingServerName = server.name
        // Re-picking may post the same NO_LIBRARY_CHOSEN state as before; don't dedupe it away.
        lastRoutedLoginState = null
        // A new server invalidates the old library (chooseServer() only replaces the server).
        plexPrefs.library = null
        plexLoginRepo.chooseServer(server)
    }

    @OptIn(InternalCoroutinesApi::class) // PlexConfig.connectToServerWithRetryAndState, as in ChooseLibraryViewModel
    private fun connectForLibraries() {
        connectingServerName = plexPrefs.server?.name.orEmpty()
        _state.postValue(UiState.Loading(Message.CONNECTING))
        awaitingLibraries = true
        // Retry-enabled connect: the car is almost never on the server's LAN, so WAN/relay
        // connections need the retry path (same as ChooseLibraryViewModel).
        // It always posts CONNECTING then CONNECTED/CONNECTION_FAILED; connectionObserver reacts.
        plexConfig.connectToServerWithRetryAndState(plexMediaService)
    }

    private fun loadLibraries() {
        awaitingLibraries = false
        viewModelScope.launch {
            try {
                val libraries =
                    plexMediaService.retrieveLibraries()
                        .plexMediaContainer.plexDirectories
                        .filter { it.type == ARTIST.typeString }
                        .map { it.asLibrary() }
                when (libraries.size) {
                    0 -> _state.postValue(UiState.Error(Message.NO_LIBRARIES, canChooseServer = true))
                    1 -> chooseLibrary(libraries.first())
                    else -> _state.postValue(UiState.ChooseLibrary(libraries))
                }
            } catch (t: Throwable) {
                Timber.e(t, "[CarSignIn] failed to load libraries")
                _state.postValue(UiState.Error(Message.NETWORK, t.message, canChooseServer = true))
            }
        }
    }

    fun chooseLibrary(library: PlexLibrary) {
        _state.postValue(UiState.Loading(Message.LOADING))
        libraryChosen = true
        plexLoginRepo.chooseLibrary(library)
    }

    private fun finishSignIn() {
        finished = true
        switchSnapshot = null
        authCoordinator?.dispose()
        // There is no home screen in the car to kick off the first sync, so do it here.
        librarySyncRepository.refreshLibrary()
        _state.postValue(UiState.Done)
    }

    /** Abort the flow. Never signs out: an abandoned server switch restores the previous server. */
    fun cancel() {
        authCoordinator?.cancelAuth()
        awaitingLibraries = false
        restoreIfSwitchAbandoned()
    }

    @OptIn(InternalCoroutinesApi::class)
    private fun restoreIfSwitchAbandoned() {
        val snapshot = switchSnapshot ?: return
        switchSnapshot = null
        if (finished) return
        Timber.i("[CarSignIn] server switch abandoned; restoring ${snapshot.server.name}/${snapshot.library.name}")
        plexPrefs.server = snapshot.server
        plexPrefs.library = snapshot.library
        plexConfig.setPotentialConnections(snapshot.server.connections)
        // A failed attempt at another server may have left PlexConfig pointed elsewhere.
        plexConfig.connectToServerWithRetryAndState(plexMediaService)
        plexLoginRepo.determineLoginState()
    }

    override fun onCleared() {
        // Backstop for the activity being finished without cancel() (e.g. system back).
        restoreIfSwitchAbandoned()
        plexLoginRepo.loginEvent.removeObserver(loginObserver)
        plexConfig.connectionState.removeObserver(connectionObserver)
        authCoordinator?.dispose()
        super.onCleared()
    }
}
