package com.phnx28.notifsync.ui.receiver

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.phnx28.notifsync.ServiceLocator
import com.phnx28.notifsync.databinding.FragmentActiveTabBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class ActiveTabFragment : Fragment() {

    private var _binding: FragmentActiveTabBinding? = null
    private val binding get() = _binding!!
    private lateinit var adapter: NotificationAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentActiveTabBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = NotificationAdapter(
            onDismiss = { notification ->
                lifecycleScope.launch {
                    ServiceLocator.notificationRepository.archive(notification.id)
                }
            },
            isArchiveMode = false
        )

        binding.recyclerView.layoutManager = LinearLayoutManager(context)
        binding.recyclerView.adapter = adapter

        val swipeCallback = object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ) = false

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.adapterPosition
                val notification = adapter.currentList[position]
                lifecycleScope.launch {
                    ServiceLocator.notificationRepository.archive(notification.id)
                }
            }
        }

        ItemTouchHelper(swipeCallback).attachToRecyclerView(binding.recyclerView)

        observeNotifications()
    }

    private fun observeNotifications() {
        viewLifecycleOwner.lifecycleScope.launch {
            ServiceLocator.notificationRepository.getActiveNotifications().collectLatest { notifications ->
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
