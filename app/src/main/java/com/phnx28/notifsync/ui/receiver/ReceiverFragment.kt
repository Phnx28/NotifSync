package com.phnx28.notifsync.ui.receiver

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.google.android.material.tabs.TabLayoutMediator
import com.phnx28.notifsync.R
import com.phnx28.notifsync.databinding.FragmentReceiverBinding
import com.phnx28.notifsync.service.ConnectionState
import com.phnx28.notifsync.service.ReceiverForegroundService
import com.phnx28.notifsync.service.SenderForegroundService
import com.phnx28.notifsync.util.showErrorSnackbar
import com.phnx28.notifsync.util.showNeutralSnackbar
import com.phnx28.notifsync.util.showSuccessSnackbar
import com.phnx28.notifsync.util.showWarningSnackbar
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope

class ReceiverFragment : Fragment() {

    private var _binding: FragmentReceiverBinding? = null
    private val binding get() = _binding!!
    private var lastState: ConnectionState? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentReceiverBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Show the sender IP in the header.
        val senderIp = ReceiverForegroundService.connectedSenderIp ?: "—"
        binding.tvFromIp.text = getString(R.string.from_ip, senderIp)

        val tabTitles = arrayOf("Active", "Archive")

        binding.viewPager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount() = 2
            override fun createFragment(position: Int): Fragment {
                return if (position == 0) ActiveTabFragment() else ArchiveTabFragment()
            }
        }

        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
            tab.text = tabTitles[position]
        }.attach()

        binding.btnDisconnect.setOnClickListener {
            ReceiverForegroundService.stop(requireContext())
            showNeutralSnackbar("Disconnected from sender")
            findNavController().navigate(R.id.action_receiver_to_home)
        }

        observeConnectionState()
    }

    /**
     * Observe the receiver's connection state and show a snackbar on
     * transitions. Also updates the header text with the current status.
     */
    private fun observeConnectionState() {
        viewLifecycleOwner.lifecycleScope.launch {
            ReceiverForegroundService.connectionStateFlow.collectLatest { state ->
                // Suppress the initial IDLE → CONNECTING transition if
                // we've never been connected (avoids a redundant snackbar
                // on fragment load).
                if (lastState != null && lastState != state) {
                    when (state) {
                        ConnectionState.CONNECTED -> {
                            showSuccessSnackbar("Connected to sender")
                        }
                        ConnectionState.CONNECTING -> {
                            // Don't snackbar on connecting — the pairing
                            // fragment already showed "Connecting to…"
                        }
                        ConnectionState.RECONNECTING -> {
                            showWarningSnackbar("Connection lost — reconnecting…")
                        }
                        ConnectionState.FAILED -> {
                            showErrorSnackbar("Connection failed — check PIN, IP, and salt")
                        }
                        ConnectionState.DISCONNECTED -> {
                            // User-initiated, snackbar already shown by the button
                        }
                        ConnectionState.IDLE -> { /* no-op */ }
                    }
                }
                lastState = state

                // Update the header subtitle with the current state.
                val senderIp = ReceiverForegroundService.connectedSenderIp ?: "—"
                val statusText = when (state) {
                    ConnectionState.CONNECTED -> "Connected"
                    ConnectionState.CONNECTING -> "Connecting…"
                    ConnectionState.RECONNECTING -> "Reconnecting…"
                    ConnectionState.FAILED -> "Failed"
                    ConnectionState.DISCONNECTED -> "Disconnected"
                    ConnectionState.IDLE -> "Idle"
                }
                binding.tvFromIp.text = getString(R.string.from_ip, "$senderIp · $statusText")
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
