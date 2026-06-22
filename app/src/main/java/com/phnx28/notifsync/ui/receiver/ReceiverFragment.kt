package com.phnx28.notifsync.ui.receiver

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.tabs.TabLayoutMediator
import com.phnx28.notifsync.R
import com.phnx28.notifsync.ServiceLocator
import com.phnx28.notifsync.databinding.FragmentReceiverBinding
import com.phnx28.notifsync.service.ConnectionState
import com.phnx28.notifsync.service.ReceiverForegroundService
import com.phnx28.notifsync.service.SenderForegroundService
import com.phnx28.notifsync.util.AppLog
import com.phnx28.notifsync.util.showErrorSnackbar
import com.phnx28.notifsync.util.showNeutralSnackbar
import com.phnx28.notifsync.util.showSuccessSnackbar
import com.phnx28.notifsync.util.showWarningSnackbar
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class ReceiverFragment : Fragment() {

    private var _binding: FragmentReceiverBinding? = null
    private val binding get() = _binding!!
    private var lastState: ConnectionState? = null
    private var logSheet: BottomSheetBehavior<View>? = null

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

        setupLogSheet()

        val senderIp = ServiceLocator.connectionRepository.connectedSenderIp.value ?: "—"
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
        observeLogs()
    }

    private fun setupLogSheet() {
        logSheet = BottomSheetBehavior.from(binding.logSheet.root).apply {
            peekHeight = (32 * resources.displayMetrics.density).toInt() // 32dp peek — narrow strip
            state = BottomSheetBehavior.STATE_COLLAPSED
            isHideable = false
        }

        binding.logSheet.btnCloseLog.setOnClickListener {
            logSheet?.state = BottomSheetBehavior.STATE_COLLAPSED
        }
        binding.logSheet.btnClearLog.setOnClickListener {
            AppLog.clear()
        }
    }

    private fun observeConnectionState() {
        viewLifecycleOwner.lifecycleScope.launch {
            ServiceLocator.connectionRepository.receiverState.collectLatest { state ->
                if (lastState != null && lastState != state) {
                    when (state) {
                        ConnectionState.CONNECTED -> {
                            showSuccessSnackbar("Connected to sender")
                        }
                        ConnectionState.CONNECTING -> { /* no snackbar */ }
                        ConnectionState.RECONNECTING -> {
                            showWarningSnackbar("Connection lost — reconnecting…")
                        }
                        ConnectionState.FAILED -> {
                            showErrorSnackbar("Connection failed — check PIN, IP, and salt. Open the log window for details.")
                        }
                        ConnectionState.DISCONNECTED -> { /* user-initiated */ }
                        ConnectionState.IDLE -> { /* no-op */ }
                    }
                }
                lastState = state

                val senderIp = ServiceLocator.connectionRepository.connectedSenderIp.value ?: "—"
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

    private fun observeLogs() {
        viewLifecycleOwner.lifecycleScope.launch {
            AppLog.entriesFlow.collectLatest { entries ->
                val sb = StringBuilder()
                for (entry in entries) {
                    sb.append(AppLog.format(entry)).append('\n')
                }
                binding.logSheet.tvLog.text = sb.toString()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
