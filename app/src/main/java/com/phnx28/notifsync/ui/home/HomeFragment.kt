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

        // v0.2.1 — long-press version label opens GitHub releases in the
        // browser. The previous in-app self-updater was removed (AUDIT.md C-03).
        binding.tvVersion.setOnLongClickListener {
            startActivity(Intent(Intent.ACTION_VIEW,
                Uri.parse("https://github.com/Phnx28/NotifSync/releases")))
            true
        }

        binding.cardSender.setOnClickListener {
            ensureSenderPermissions {
                findNavController().navigate(R.id.action_home_to_sender)
            }
        }

        binding.cardReceiver.setOnClickListener {
            ensureBatteryOptimization {
                findNavController().navigate(R.id.action_home_to_pairing)
            }
        }
    }

    /**
     * Step through sender-mode permission prompts one at a time so dialogs
     * don't stack (AUDIT.md L-02). Order:
     *   1. Battery optimization (optional, can skip)
     *   2. Notification Listener (required)
     *   3. SMS + NSD runtime permissions (handled by the fragment that follows)
     */
    private fun ensureSenderPermissions(onReady: () -> Unit) {
        val ctx = requireContext()

        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(ctx.packageName)) {
            MaterialAlertDialogBuilder(ctx)
                .setTitle("Battery Optimization")
                .setMessage("NotifSync needs to run in the background to capture and broadcast notifications. Please disable battery optimization for this app.")
                .setPositiveButton("Open Settings") { _, _ ->
                    startActivity(Intent(
                        android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:${ctx.packageName}")
                    ))
                }
                .setNegativeButton("Skip") { d, _ -> d.dismiss() }
                .setOnDismissListener {
                    // After battery dialog (skipped or returned from settings),
                    // proceed to the notification-listener check.
                    ensureNotificationListenerThenNavigate(onReady)
                }
                .show()
        } else {
            ensureNotificationListenerThenNavigate(onReady)
        }
    }

    private fun ensureNotificationListenerThenNavigate(onReady: () -> Unit) {
        val ctx = requireContext()
        if (!PermissionsHelper.hasNotificationListenerPermission(ctx)) {
            MaterialAlertDialogBuilder(ctx)
                .setTitle("Notification Access Required")
                .setMessage("To capture notifications from other apps, NotifSync needs Notification Listener permission. You'll be taken to system settings to enable it.")
                .setPositiveButton("Open Settings") { _, _ ->
                    PermissionsHelper.openNotificationListenerSettings(ctx)
                }
                .setNegativeButton("Skip") { d, _ -> d.dismiss() }
                .setOnDismissListener { onReady() }
                .show()
        } else {
            onReady()
        }
    }

    private fun ensureBatteryOptimization(onReady: () -> Unit) {
        val ctx = requireContext()
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager

        if (!pm.isIgnoringBatteryOptimizations(ctx.packageName)) {
            MaterialAlertDialogBuilder(ctx)
                .setTitle("Battery Optimization")
                .setMessage("For reliable notification receiving, it's recommended to disable battery optimization. You can skip this if you prefer.")
                .setPositiveButton("Open Settings") { _, _ ->
                    startActivity(Intent(
                        android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:${ctx.packageName}")
                    ))
                }
                .setNegativeButton("Skip") { _, _ -> onReady() }
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
