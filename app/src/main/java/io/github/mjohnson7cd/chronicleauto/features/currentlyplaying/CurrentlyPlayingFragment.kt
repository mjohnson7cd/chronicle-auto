package io.github.mjohnson7cd.chronicleauto.features.currentlyplaying

import android.content.Context
import android.content.IntentFilter
import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import android.widget.Toast.LENGTH_SHORT
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.material.slider.Slider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import io.github.mjohnson7cd.chronicleauto.application.MainActivity
import io.github.mjohnson7cd.chronicleauto.application.MainActivityViewModel.BottomSheetState.COLLAPSED
import io.github.mjohnson7cd.chronicleauto.data.model.Chapter
import io.github.mjohnson7cd.chronicleauto.data.sources.plex.PlexConfig
import io.github.mjohnson7cd.chronicleauto.databinding.FragmentCurrentlyPlayingBinding
import io.github.mjohnson7cd.chronicleauto.features.bookdetails.ChapterListAdapter
import io.github.mjohnson7cd.chronicleauto.features.bookdetails.TrackClickListener
import io.github.mjohnson7cd.chronicleauto.features.player.SleepTimer
import io.github.mjohnson7cd.chronicleauto.util.observeEvent
import io.github.mjohnson7cd.chronicleauto.views.ModalBottomSheetSpeedChooser
import timber.log.Timber
import javax.inject.Inject

/** Responsible for playback controls and displaying the currently playing media */
@ExperimentalCoroutinesApi
class CurrentlyPlayingFragment : Fragment() {
    private lateinit var currentlyPlayingInterface: MainActivity.CurrentlyPlayingInterface

    @Inject
    lateinit var plexConfig: PlexConfig

    @Inject
    lateinit var viewModelFactory: CurrentlyPlayingViewModel.Factory

    @Inject
    lateinit var localBroadcastManager: LocalBroadcastManager

    private val viewModel: CurrentlyPlayingViewModel by lazy {
        ViewModelProvider(this, viewModelFactory).get(CurrentlyPlayingViewModel::class.java)
    }

    companion object {
        fun newInstance() = CurrentlyPlayingFragment()
    }

    override fun onAttach(context: Context) {
        currentlyPlayingInterface = (context as MainActivity).getCurrentlyPlayingInterface()
        context.activityComponent!!.inject(this)
        super.onAttach(context as Context)
    }

    override fun onStart() {
        super.onStart()
        localBroadcastManager.registerReceiver(
            viewModel.onUpdateSleepTimer,
            IntentFilter(SleepTimer.ACTION_SLEEP_TIMER_CHANGE),
        )
    }

    override fun onStop() {
        localBroadcastManager.unregisterReceiver(viewModel.onUpdateSleepTimer)
        super.onStop()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        // Activity and context are non-null on view creation. This informs lint about that
        val binding = FragmentCurrentlyPlayingBinding.inflate(inflater, container, false)

        viewModel.showUserMessage.observeEvent(viewLifecycleOwner) { message ->
            Toast.makeText(context, message, LENGTH_SHORT).show()
        }

        binding.viewModel = viewModel
        binding.plexConfig = plexConfig
        binding.lifecycleOwner = viewLifecycleOwner

        val adapter =
            ChapterListAdapter(
                object : TrackClickListener {
                    override fun onClick(chapter: Chapter) {
                        viewModel.jumpToChapter(chapter.startTimeOffset, chapter.trackId)
                    }
                },
            )

        binding.chapterProgressSeekbar.addOnSliderTouchListener(
            object : Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(slider: Slider) {
                    viewModel.startSliding()
                }

                override fun onStopTrackingTouch(slider: Slider) {
                    viewModel.seekTo(slider.value.toDouble() / slider.valueTo)
                }
            },
        )

        binding.chapterProgressSeekbar.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                binding.chapterProgress.text = DateUtils.formatElapsedTime(
                    StringBuilder(),
                    value.toLong() / 1000,
                )
            }
        }

        binding.chapterProgressSeekbar.setLabelFormatter { value: Float ->
            DateUtils.formatElapsedTime(
                StringBuilder(),
                value.toLong() / 1000,
            )
        }

        viewModel.activeChapter.observe(viewLifecycleOwner) { chapter ->
            Timber.i(
                "Updating current chapter: (${chapter.trackId}, ${chapter.discNumber}, ${chapter.index})",
            )
            adapter.updateCurrentChapter(
                trackId = chapter.trackId,
                discNumber = chapter.discNumber,
                chapterIndex = chapter.index,
            )
        }

        binding.tracks.adapter = adapter

        binding.detailsToolbar.setNavigationOnClickListener {
            currentlyPlayingInterface.setBottomSheetState(COLLAPSED)
        }

        viewModel.showModalBottomSheetSpeedChooser.observe(viewLifecycleOwner) { eventShowChooser ->
            if (!eventShowChooser.hasBeenHandled) {
                ModalBottomSheetSpeedChooser().show(
                    childFragmentManager,
                    ModalBottomSheetSpeedChooser.TAG,
                )
                eventShowChooser.getContentIfNotHandled()
            }
        }

        return binding.root
    }
}
