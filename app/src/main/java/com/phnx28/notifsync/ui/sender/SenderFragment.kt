package com.phnx28.notifsync.ui.sender

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import com.phnx28.notifsync.network.Crypto
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

        // v0.2.1 — copy buttons (AUDIT.md U-03 / U-04). Lets the user paste
        // the IP + PIN + salt into a chat / messenger instead of typing them.
        binding.btnCopyIp.setOnClickListener {
            val ip = binding.tvServerAddress.text.toString()
            copyToClipboard("NotifSync address", ip)
            showSuccessSnackbar("Copied: $ip")
        }
        binding.btnCopyPin.setOnClickListener {
            val pin = binding.tvPairingPin.text.toString()
            copyToClipboard("NotifSync PIN", pin)
            showSuccessSnackbar("Copied PIN")
        }
        binding.btnCopySalt.setOnClickListener {
            val saltHex = SenderForegroundService.activeSessionSalt
                ?.let(Crypto::toHex) ?: ""
            if (saltHex.isNotEmpty()) {
                copyToClipboard("NotifSync session salt", saltHex)
                showSuccessSnackbar("Copied salt")
            }
        }

        observeStats()
    }

    private fun copyToClipboard(label: String, value: String) {
        val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, value))
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

        // v0.2.1 — fix for AUDIT.md I-08: the "Forwarded" stat now shows the
        // count of NOTIFICATION events (previously it showed the active count).
        viewLifecycleOwner.lifecycleScope.launch {
            app.repository.getNotificationCount().collectLatest { count ->
                binding.tvForwardedCount.text = count.toString()
            }
        }

        // v0.2.1 — fix for AUDIT.md I-08: the "SMS Relayed" stat now shows
        // the count of SMS events (previously it showed the archived count).
        viewLifecycleOwner.lifecycleScope.launch {
            app.repository.getSmsCount().collectLatest { count ->
                binding.tvArchivedCount.text = count.toString()
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            SenderForegroundService.connectionCountFlow.collectLatest { count ->
                binding.tvConnectedReceivers.text = count.toString()
                val ip = SenderForegroundService.getLocalIpAddress() ?: "Unknown IP"
                binding.tvServerAddress.text = "ws://$ip:${com.phnx28.notifsync.Constants.DEFAULT_PORT}"
                binding.tvPairingPin.text = SenderForegroundService.activePin ?: "------"
                SenderForegroundService.activeSessionSalt?.let {
                    binding.tvSessionSalt.text = Crypto.toHex(it)
                } ?: binding.tvSessionSalt.setText(R.string.unknown)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
