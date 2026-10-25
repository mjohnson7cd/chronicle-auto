package io.github.mjohnson7cd.chronicleauto.features.auth

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.MutableLiveData
import io.github.mjohnson7cd.chronicleauto.data.sources.plex.IPlexLoginRepo
import io.github.mjohnson7cd.chronicleauto.data.sources.plex.model.OAuthResponse
import io.github.mjohnson7cd.chronicleauto.util.Event
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * The in-car (Android Automotive OS) sign-in has no browser, so it asks Plex for a short
 * plex.tv/link PIN instead of the strong OAuth PIN the phone flow uses.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlexAuthCoordinatorLinkCodeTest {
    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private lateinit var plexLoginRepo: IPlexLoginRepo
    private val loginEvent = MutableLiveData<Event<IPlexLoginRepo.LoginState>>()

    @Before
    fun setUp() {
        plexLoginRepo = mockk(relaxed = true)
        loginEvent.value = Event(IPlexLoginRepo.LoginState.AWAITING_LOGIN_RESULTS)
        every { plexLoginRepo.loginEvent } returns loginEvent
        coEvery { plexLoginRepo.checkForOAuthAccessToken() } returns Unit
        coEvery { plexLoginRepo.postLinkPin() } returns
            OAuthResponse(id = 9L, clientIdentifier = "client-car", code = "K7QZ", authToken = null)
    }

    @Test
    fun `link code mode requests a short plex-tv-link pin, not a strong oauth pin`() =
        runTest {
            val coordinator =
                PlexAuthCoordinator(
                    plexLoginRepo,
                    this,
                    PlexAuthCoordinator.LINK_CODE_TIMEOUT_MS,
                    useLinkCode = true,
                )
            val flow = coordinator.startAuth()
            advanceTimeBy(PlexAuthCoordinator.POLLING_INTERVAL_MS + 100)

            val state = flow.value
            assertTrue("expected Polling, was $state", state is PlexAuthState.Polling)
            assertEquals("K7QZ", (state as PlexAuthState.Polling).pinCode)
            coVerify(exactly = 1) { plexLoginRepo.postLinkPin() }
            coVerify(exactly = 0) { plexLoginRepo.postOAuthPin() }
            coordinator.dispose()
        }

    @Test
    fun `link code mode succeeds once the code is entered at plex-tv-link`() =
        runTest {
            val coordinator =
                PlexAuthCoordinator(
                    plexLoginRepo,
                    this,
                    PlexAuthCoordinator.LINK_CODE_TIMEOUT_MS,
                    useLinkCode = true,
                )
            val flow = coordinator.startAuth()
            advanceTimeBy(PlexAuthCoordinator.POLLING_INTERVAL_MS + 100)

            // User typed the code on their phone: the next poll obtains a token
            loginEvent.value = Event(IPlexLoginRepo.LoginState.LOGGED_IN_NO_SERVER_CHOSEN)
            advanceTimeBy(PlexAuthCoordinator.POLLING_INTERVAL_MS + 100)

            assertTrue("expected Success, was ${flow.value}", flow.value is PlexAuthState.Success)
            coordinator.dispose()
        }
}
