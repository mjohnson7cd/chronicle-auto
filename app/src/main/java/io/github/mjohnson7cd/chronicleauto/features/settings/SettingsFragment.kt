package io.github.mjohnson7cd.chronicleauto.features.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import com.google.android.gms.oss.licenses.OssLicensesMenuActivity
import io.github.mjohnson7cd.chronicleauto.application.ChronicleBillingManager
import io.github.mjohnson7cd.chronicleauto.application.MainActivity
import io.github.mjohnson7cd.chronicleauto.data.local.IBookRepository
import io.github.mjohnson7cd.chronicleauto.data.local.ITrackRepository
import io.github.mjohnson7cd.chronicleauto.data.local.PrefsRepo
import io.github.mjohnson7cd.chronicleauto.data.sources.plex.ICachedFileManager
import io.github.mjohnson7cd.chronicleauto.data.sources.plex.IPlexLoginRepo
import io.github.mjohnson7cd.chronicleauto.data.sources.plex.PlexConfig
import io.github.mjohnson7cd.chronicleauto.data.sources.plex.PlexPrefsRepo
import io.github.mjohnson7cd.chronicleauto.databinding.FragmentSettingsBinding
import io.github.mjohnson7cd.chronicleauto.features.player.MediaServiceConnection
import io.github.mjohnson7cd.chronicleauto.navigation.Navigator
import io.github.mjohnson7cd.chronicleauto.util.observeEvent
import io.github.mjohnson7cd.chronicleauto.views.getString
import javax.inject.Inject

class SettingsFragment : Fragment() {
    @Inject
    lateinit var viewModelFactory: SettingsViewModel.Factory

    @Inject
    lateinit var mediaServiceConnection: MediaServiceConnection

    @Inject
    lateinit var navigator: Navigator

    @Inject
    lateinit var plexLoginRepo: IPlexLoginRepo

    @Inject
    lateinit var chronicleBillingManager: ChronicleBillingManager

    @Inject
    lateinit var cachedFileManager: ICachedFileManager

    @Inject
    lateinit var trackRepository: ITrackRepository

    @Inject
    lateinit var bookRepository: IBookRepository

    @Inject
    lateinit var prefsRepo: PrefsRepo

    @Inject
    lateinit var plexPrefsRepo: PlexPrefsRepo

    @Inject
    lateinit var plexConfig: PlexConfig

    companion object {
        @JvmStatic
        fun newInstance() = SettingsFragment()
    }

    override fun onAttach(context: Context) {
        (requireActivity() as MainActivity).activityComponent!!.inject(this)
        super.onAttach(context)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? {
        val binding = FragmentSettingsBinding.inflate(inflater, container, false)
        val viewModel = ViewModelProvider(this, viewModelFactory).get(SettingsViewModel::class.java)

        binding.viewModel = viewModel
        binding.lifecycleOwner = viewLifecycleOwner

        viewModel.messageForUser.observe(
            viewLifecycleOwner,
            Observer { message ->
                if (!message.hasBeenHandled) {
                    val formattableString = message.getContentIfNotHandled()
                    Toast.makeText(
                        context,
                        resources.getString(formattableString),
                        Toast.LENGTH_SHORT,
                    )
                        .show()
                }
            },
        )

        viewModel.upgradeToPremium.observeEvent(viewLifecycleOwner) {
            chronicleBillingManager.launchBillingFlow(requireActivity())
        }

        viewModel.webLink.observe(
            viewLifecycleOwner,
            Observer {
                if (!it.hasBeenHandled) {
                    startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(it.getContentIfNotHandled())),
                    )
                }
            },
        )

        viewModel.showLicenseActivity.observe(
            viewLifecycleOwner,
            Observer {
                if (it) {
                    startActivity(Intent(context, OssLicensesMenuActivity::class.java))
                    viewModel.setShowLicenseActivity(false)
                }
            },
        )

        viewModel.showDebugDialog.observeEvent(viewLifecycleOwner) {
            DebugInfoDialogFragment.newInstance()
                .show(parentFragmentManager, DebugInfoDialogFragment.TAG)
        }

        return binding.root
    }
}
