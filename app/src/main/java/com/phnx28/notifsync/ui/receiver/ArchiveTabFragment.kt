package com.phnx28.notifsync.ui.receiver

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.phnx28.notifsync.NotifSyncApp
import com.phnx28.notifsync.databinding.FragmentArchiveTabBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class ArchiveTabFragment : Fragment() {

    private var _binding: FragmentArchiveTabBinding? = null
    private val binding get() = _binding!!
    private lateinit var adapter: NotificationAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentArchiveTabBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = NotificationAdapter(
            onDismiss = {},
            isArchiveMode = true,
            onRestore = { notification ->
                val app = requireActivity().application as NotifSyncApp
                lifecycleScope.launch {
                    app.repository.restore(notification.id)
                }
            }
        )

        binding.recyclerView.layoutManager = LinearLayoutManager(context)
        binding.recyclerView.adapter = adapter

        observeNotifications()
    }

    private fun observeNotifications() {
        val app = requireActivity().application as NotifSyncApp
        viewLifecycleOwner.lifecycleScope.launch {
            app.repository.getArchivedNotifications().collectLatest { notifications ->
                adapter.submitList(notifications)
                binding.tvEmpty.visibility = if (notifications.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
