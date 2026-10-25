package io.github.mjohnson7cd.chronicleauto.features.car

import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import io.github.mjohnson7cd.chronicleauto.R
import io.github.mjohnson7cd.chronicleauto.features.car.CarSignInViewModel.Message
import io.github.mjohnson7cd.chronicleauto.features.car.CarSignInViewModel.UiState

/**
 * In-car Plex sign-in for Android Automotive OS (Google built-in).
 *
 * The car has no browser, so instead of OAuth in Chrome Custom Tabs we show a short code and
 * ask the user to enter it at plex.tv/link on their phone or computer, then poll until Plex
 * links it. Afterwards the user/server/library are picked here (auto-picked when only one).
 *
 * Parked-only: this activity is deliberately NOT distraction-optimized, so the system blocks
 * it while driving. It is launched from the media center via the MediaSession error
 * resolution intent ("Sign in") and from [CarSettingsActivity].
 */
class CarSignInActivity : AppCompatActivity() {
    private val viewModel: CarSignInViewModel by lazy {
        val switchServer = intent?.getBooleanExtra(EXTRA_SWITCH_SERVER, false) ?: false
        ViewModelProvider(this, CarSignInViewModel.Factory(switchServer))[CarSignInViewModel::class.java]
    }

    private lateinit var title: TextView
    private lateinit var message: TextView
    private lateinit var link: TextView
    private lateinit var codeLabel: TextView
    private lateinit var code: TextView
    private lateinit var progress: ProgressBar
    private lateinit var pin: EditText
    private lateinit var list: ListView
    private lateinit var spacer: View
    private lateinit var primary: Button
    private lateinit var secondary: Button
    private lateinit var chooseServer: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_car_sign_in)
        title = findViewById(R.id.car_sign_in_title)
        message = findViewById(R.id.car_sign_in_message)
        link = findViewById(R.id.car_sign_in_link)
        codeLabel = findViewById(R.id.car_sign_in_code_label)
        code = findViewById(R.id.car_sign_in_code)
        progress = findViewById(R.id.car_sign_in_progress)
        pin = findViewById(R.id.car_sign_in_pin)
        list = findViewById(R.id.car_sign_in_list)
        spacer = findViewById(R.id.car_sign_in_spacer)
        primary = findViewById(R.id.car_sign_in_primary)
        secondary = findViewById(R.id.car_sign_in_secondary)
        chooseServer = findViewById(R.id.car_sign_in_choose_server)

        // System back behaves like Cancel (aborts auth, restores an abandoned server switch).
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = cancelAndFinish()
            },
        )

        viewModel.state.observe(this) { render(it) }
    }

    private fun render(state: UiState) {
        // Reset to a neutral layout, then enable what this step needs.
        title.setText(R.string.car_sign_in_title)
        message.text = ""
        listOf(link, codeLabel, code, progress, pin, list, primary, chooseServer).forEach { it.visibility = View.GONE }
        spacer.visibility = View.VISIBLE
        secondary.setText(R.string.car_cancel)
        secondary.setOnClickListener { cancelAndFinish() }
        list.onItemClickListener = null

        when (state) {
            is UiState.Loading -> {
                message.text = messageText(state.message)
                progress.visibility = View.VISIBLE
            }
            is UiState.ShowCode -> {
                message.setText(R.string.car_sign_in_instructions)
                link.visibility = View.VISIBLE
                codeLabel.visibility = View.VISIBLE
                code.visibility = View.VISIBLE
                code.text = state.code
                code.contentDescription = state.code.toCharArray().joinToString(" ")
                progress.visibility = View.VISIBLE
                showPrimary(R.string.car_sign_in_new_code) { viewModel.requestNewCode() }
            }
            is UiState.CodeExpired -> {
                message.setText(R.string.car_sign_in_timeout)
                showPrimary(R.string.car_sign_in_new_code) { viewModel.requestNewCode() }
            }
            is UiState.ChooseUser ->
                showChoices(
                    R.string.car_sign_in_choose_user,
                    state.users.map { it.title.ifEmpty { it.username.orEmpty() } },
                ) { viewModel.pickUser(state.users[it]) }
            is UiState.EnterUserPin -> {
                title.text = getString(R.string.car_sign_in_enter_user_pin, state.user.title)
                if (state.wrongPin) message.setText(R.string.car_sign_in_pin_wrong)
                pin.visibility = View.VISIBLE
                pin.text.clear()
                pin.requestFocus()
                val submit = { viewModel.submitUserPin(state.user, pin.text.toString()) }
                pin.setOnEditorActionListener { _, actionId, _ ->
                    if (actionId == EditorInfo.IME_ACTION_DONE) {
                        submit()
                        true
                    } else {
                        false
                    }
                }
                showPrimary(R.string.car_ok) { submit() }
            }
            is UiState.ChooseServer ->
                showChoices(R.string.car_sign_in_choose_server, state.servers.map { it.name }) {
                    viewModel.chooseServer(state.servers[it])
                }
            is UiState.ChooseLibrary ->
                showChoices(R.string.car_sign_in_choose_library, state.libraries.map { it.name }) {
                    viewModel.chooseLibrary(state.libraries[it])
                }
            is UiState.Done -> {
                message.setText(R.string.car_sign_in_done)
                secondary.setText(R.string.car_close)
                secondary.setOnClickListener { finish() }
            }
            is UiState.Error -> {
                message.text = messageText(state.message, state.detail)
                showPrimary(R.string.car_sign_in_retry) { viewModel.retry() }
                if (state.canChooseServer) {
                    chooseServer.visibility = View.VISIBLE
                    chooseServer.setOnClickListener { viewModel.chooseDifferentServer() }
                }
            }
        }
    }

    private fun showPrimary(
        textRes: Int,
        onClick: () -> Unit,
    ) {
        primary.setText(textRes)
        primary.visibility = View.VISIBLE
        primary.setOnClickListener { onClick() }
    }

    private fun showChoices(
        titleRes: Int,
        labels: List<String>,
        onPick: (Int) -> Unit,
    ) {
        title.setText(titleRes)
        spacer.visibility = View.GONE
        list.visibility = View.VISIBLE
        list.adapter = ArrayAdapter(this, R.layout.car_list_item, android.R.id.text1, labels)
        list.setOnItemClickListener { _, _, position, _ -> onPick(position) }
    }

    private fun messageText(
        msg: Message,
        detail: String? = null,
    ): String =
        when (msg) {
            Message.REQUESTING_CODE -> getString(R.string.car_sign_in_requesting_code)
            Message.LOADING -> getString(R.string.car_sign_in_loading)
            Message.CONNECTING -> getString(R.string.car_sign_in_connecting, viewModel.connectingServerName)
            Message.NO_SERVERS -> getString(R.string.car_sign_in_no_servers)
            Message.NO_LIBRARIES -> getString(R.string.car_sign_in_no_libraries)
            Message.NETWORK -> getString(R.string.car_sign_in_error, detail ?: "")
        }

    private fun cancelAndFinish() {
        viewModel.cancel()
        finish()
    }

    companion object {
        /** Open straight at the server list, keeping the current server until a new one works. */
        const val EXTRA_SWITCH_SERVER = "io.github.mjohnson7cd.chronicleauto.car.SWITCH_SERVER"
    }
}
