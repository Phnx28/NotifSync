package com.phnx28.notifsync.ui.home

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.phnx28.notifsync.BuildConfig
import com.phnx28.notifsync.R
import com.phnx28.notifsync.databinding.FragmentHomeBinding
import com.phnx28.notifsync.util.PermissionsHelper
import com.phnx28.notifsync.util.UpdateHelper

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.tvVersion.text = "v${BuildConfig.VERSION_NAME}"
        binding.tvVersion.setOnLongClickListener {
            UpdateHelper.checkForUpdate(requireContext(), it, BuildConfig.VERSION_NAME)
            true
        }

        binding.cardSender.setOnClickListener {
            // Check sender-specific permissions before navigating
            ensureSenderPermissions {
                findNavController().navigate(R.id.action_home_to_sender)
            }
        }

        binding.cardReceiver.setOnClickListener {
            // Receiver needs fewer permissions — just battery optimization
            ensureBatteryOptimization {
                findNavController().navigate(R.id.action_home_to_pairing)
            }
        }
    }

    /**
     * Check permissions required for sender mode:
     * 1. Battery optimization exemption (for persistent foreground service)
     * 2. Notification Listener access (to capture notifications)
     * 3. SMS permissions (to capture SMS)
     */
    private fun ensureSenderPermissions(onReady: () -> Unit) {
        val ctx = requireContext()

        // Step 1: Battery optimization
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(ctx.packageName)) {
            MaterialAlertDialogBuilder(ctx)
                .setTitle("Battery Optimization")
                .setMessage("NotifSync needs to run in the background to capture and broadcast notifications. Please disable battery optimization for this app.")
                .setPositiveButton("Open Settings") { _, _ ->
                    val intent = Intent(
                        android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:${ctx.packageName}")
                    )
                    startActivity(intent)
                }
                .setNegativeButton("Skip", null)
                .show()
            // Don't block navigation — user may skip
        }

        // Step 2: Notification Listener
        if (!PermissionsHelper.hasNotificationListenerPermission(ctx)) {
            MaterialAlertDialogBuilder(ctx)
                .setTitle("Notification Access Required")
                .setMessage("To capture notifications from other apps, NotifSync needs Notification Listener permission. You'll be taken to system settings to enable it.")
                .setPositiveButton("Open Settings") { _, _ ->
                    PermissionsHelper.openNotificationListenerSettings(ctx)
                }
                .setNegativeButton("Skip", null)
                .show()
            return // Don't navigate yet — this permission is essential for sender
        }

        // Step 3: SMS permissions
        if (!PermissionsHelper.hasSmsPermission(ctx)) {
            PermissionsHelper.requestPermissions(requireActivity())
            // Non-blocking — SMS forwarding is optional
        }

        onReady()
    }

    /**
     * Battery optimization is helpful for receiver mode too, but not mandatory.
     */
    private fun ensureBatteryOptimization(onReady: () -> Unit) {
        val ctx = requireContext()
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager

        if (!pm.isIgnoringBatteryOptimizations(ctx.packageName)) {
            MaterialAlertDialogBuilder(ctx)
                .setTitle("Battery Optimization")
                .setMessage("For reliable notification receiving, it's recommended to disable battery optimization. You can skip this if you prefer.")
                .setPositiveButton("Open Settings") { _, _ ->
                    val intent = Intent(
                        android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:${ctx.packageName}")
                    )
                    startActivity(intent)
                }
                .setNegativeButton("Skip") { _, _ ->
                    onReady()
                }
                .show()
            return
        }

        onReady()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
