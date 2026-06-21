package com.phnx28.notifsync.ui.sender

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.phnx28.notifsync.NotifSyncApp
import com.phnx28.notifsync.R
import com.phnx28.notifsync.databinding.FragmentSenderBinding
import com.phnx28.notifsync.service.SenderForegroundService
import com.phnx28.notifsync.util.PermissionsHelper
import com.phnx28.notifsync.util.showNeutralSnackbar
import com.phnx28.notifsync.util.showSuccessSnackbar
import com.phnx28.notifsync.util.showWarningSnackbar
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class SenderFragment : Fragment() {

    private var _binding: FragmentSenderBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSenderBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        checkPermissionsAndStart()

        binding.btnStop.setOnClickListener {
            SenderForegroundService.stop(requireContext())
            showNeutralSnackbar("Broadcasting stopped")
            findNavController().navigate(R.id.action_sender_to_home)
        }

        observeStats()
    }

    private fun checkPermissionsAndStart() {
        if (!PermissionsHelper.hasNotificationListenerPermission(requireContext())) {
            showWarningSnackbar("Notification Listener permission required")
            PermissionsHelper.openNotificationListenerSettings(requireContext())
        }

        if (!PermissionsHelper.hasSmsPermission(requireContext())) {
            PermissionsHelper.requestPermissions(requireActivity())
        }

        SenderForegroundService.start(requireContext())
        showSuccessSnackbar("Broadcasting started")
    }

    private fun observeStats() {
        val app = requireActivity().application as NotifSyncApp

        viewLifecycleOwner.lifecycleScope.launch {
            app.repository.getActiveCount().collectLatest { count ->
                binding.tvForwardedCount.text = count.toString()
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            app.repository.getArchivedCount().collectLatest { count ->
                binding.tvArchivedCount.text = count.toString()
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            SenderForegroundService.connectionCountFlow.collectLatest { count ->
                binding.tvConnectedReceivers.text = count.toString()
                val ip = SenderForegroundService.getLocalIpAddress() ?: "Unknown IP"
                binding.tvServerAddress.text = "ws://$ip:${com.phnx28.notifsync.Constants.DEFAULT_PORT}"
                binding.tvPairingPin.text = SenderForegroundService.activePin ?: "----"
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
