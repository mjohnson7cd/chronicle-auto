package io.github.mjohnson7cd.chronicleauto.features.login

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import io.github.mjohnson7cd.chronicleauto.data.model.PlexLibrary
import io.github.mjohnson7cd.chronicleauto.databinding.ListItemLibraryBinding

class LibraryListAdapter(val clickListener: LibraryClickListener) :
    ListAdapter<PlexLibrary, LibraryListAdapter.LibraryViewHolder>(
        LibraryDiffCallback(),
    ) {
    override fun onBindViewHolder(
        holder: LibraryViewHolder,
        position: Int,
    ) {
        holder.bind(getItem(position), clickListener)
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): LibraryViewHolder {
        return LibraryViewHolder.from(
            parent,
        )
    }

    class LibraryViewHolder private constructor(val binding: ListItemLibraryBinding) :
        RecyclerView.ViewHolder(binding.root) {
            fun bind(
                plexLibrary: PlexLibrary,
                clickListener: LibraryClickListener,
            ) {
                binding.library = plexLibrary
                binding.clickListener = clickListener
                binding.executePendingBindings()
            }

            companion object {
                fun from(parent: ViewGroup): LibraryViewHolder {
                    val layoutInflater = LayoutInflater.from(parent.context)
                    val binding = ListItemLibraryBinding.inflate(layoutInflater, parent, false)
                    return LibraryViewHolder(
                        binding,
                    )
                }
            }
        }
}

class LibraryDiffCallback : DiffUtil.ItemCallback<PlexLibrary>() {
    override fun areItemsTheSame(
        oldItem: PlexLibrary,
        newItem: PlexLibrary,
    ): Boolean {
        return oldItem.name == newItem.name
    }

    override fun areContentsTheSame(
        oldItem: PlexLibrary,
        newItem: PlexLibrary,
    ): Boolean {
        return oldItem == newItem
    }
}
