package com.phnx28.notifsync.ui.home

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.phnx28.notifsync.BuildConfig
import com.phnx28.notifsync.R
import com.phnx28.notifsync.databinding.FragmentHomeBinding
import com.phnx28.notifsync.service.SenderForegroundService
import com.phnx28.notifsync.util.PermissionsHelper
import com.phnx28.notifsync.util.showErrorSnackbar
import com.phnx28.notifsync.util.showSuccessSnackbar
import com.phnx28.notifsync.util.showWarningSnackbar

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    // ─── Permission flow state ───────────────────────────────────────────
    //
    // We request permissions one at a time so the user sees a clear
    // explanation before each system dialog, and we can show a summary
    // of what was granted/denied at the end.

    private data class PermissionStep(
        val key: String,
        val title: String,
        val message: String,
        val required: Boolean,
        val request: (() -> Unit)? = null  // null = special (opens Settings)
    )

    private val permissionResults = mutableMapOf<String, Boolean>()
    private val permissionQueue = mutableListOf<PermissionStep>()
    private var currentStep: PermissionStep? = null
    private var onPermissionsComplete: (() -> Unit)? = null

    private val singlePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val step = currentStep ?: return@registerForActivityResult
        permissionResults[step.key] = granted
        if (!granted && step.required) {
            showWarningSnackbar("${step.title} was denied — some features won't work")
        }
        processNextPermission()
    }

    // ─── Lifecycle ───────────────────────────────────────────────────────

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
            startActivity(Intent(Intent.ACTION_VIEW,
                Uri.parse("https://github.com/Phnx28/NotifSync/releases")))
            true
        }

        binding.cardSender.setOnClickListener {
            startSenderPermissionFlow {
                findNavController().navigate(R.id.action_home_to_sender)
            }
        }

        binding.cardReceiver.setOnClickListener {
            // If the sender is running on this device, stop it before entering
            // receiver mode — running both on the same device doesn't make
            // sense and causes self-discovery.
            if (SenderForegroundService.isRunning()) {
                SenderForegroundService.stop(requireContext())
                showWarningSnackbar("Stopped sender — entering receiver mode")
            }
            startReceiverPermissionFlow {
                findNavController().navigate(R.id.action_home_to_pairing)
            }
        }
    }

    // ─── Sender permission flow ──────────────────────────────────────────

    private fun startSenderPermissionFlow(onReady: () -> Unit) {
        val steps = mutableListOf<PermissionStep>()

        // 1. Battery optimization (special — opens Settings)
        val pm = requireContext().getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(requireContext().packageName)) {
            steps += PermissionStep(
                key = "battery",
                title = "Battery Optimization",
                message = "NotifSync needs to run in the background to capture and broadcast notifications.\n\n" +
                    "On the next screen, find NotifSync and set it to 'Don't optimize'.",
                required = false,
                request = { openBatteryOptimizationSettings() }
            )
        }

        // 2. Notification Listener (special — opens Settings)
        if (!PermissionsHelper.hasNotificationListenerPermission(requireContext())) {
            steps += PermissionStep(
                key = "notif_listener",
                title = "Notification Access",
                message = "To capture notifications from other apps, NotifSync needs Notification Listener permission.\n\n" +
                    "On the next screen, find NotifSync and enable it.",
                required = true,
                request = { PermissionsHelper.openNotificationListenerSettings(requireContext()) }
            )
        }

        // 3. SMS permissions (runtime)
        if (!PermissionsHelper.hasSmsPermission(requireContext())) {
            steps += PermissionStep(
                key = "sms",
                title = "SMS Permissions",
                message = "To capture and relay incoming SMS messages (including 2FA codes), " +
                    "NotifSync needs to read your text messages.",
                required = false,
                request = { singlePermissionLauncher.launch(Manifest.permission.RECEIVE_SMS) }
            )
        }

        // 4. NSD/mDNS permissions (runtime, OS-dependent)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
                steps += PermissionStep(
                    key = "nearby_wifi",
                    title = "Nearby Wi-Fi Devices",
                    message = "To discover sender devices on your local network via mDNS, " +
                        "NotifSync needs the Nearby Wi-Fi Devices permission.",
                    required = false,
                    request = { singlePermissionLauncher.launch(Manifest.permission.NEARBY_WIFI_DEVICES) }
                )
            }
        } else {
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                steps += PermissionStep(
                    key = "location",
                    title = "Location Access",
                    message = "To discover sender devices on your local network via mDNS, " +
                        "Android requires location access on this version.\n\n" +
                        "NotifSync does not track your location.",
                    required = false,
                    request = { singlePermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION) }
                )
            }
        }

        if (steps.isEmpty()) {
            onReady()
            return
        }

        permissionQueue.clear()
        permissionQueue += steps
        permissionResults.clear()
        onPermissionsComplete = {
            showPermissionSummary(steps, onReady)
        }
        processNextPermission()
    }

    // ─── Receiver permission flow (lighter) ──────────────────────────────

    private fun startReceiverPermissionFlow(onReady: () -> Unit) {
        val steps = mutableListOf<PermissionStep>()

        // Battery optimization
        val pm = requireContext().getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(requireContext().packageName)) {
            steps += PermissionStep(
                key = "battery",
                title = "Battery Optimization",
                message = "For reliable notification receiving, it's recommended to disable battery optimization.\n\n" +
                    "You can skip this if you prefer.",
                required = false,
                request = { openBatteryOptimizationSettings() }
            )
        }

        // POST_NOTIFICATIONS (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                steps += PermissionStep(
                    key = "post_notifications",
                    title = "Notifications",
                    message = "To display mirrored notifications from the sender device, " +
                        "NotifSync needs permission to post notifications.",
                    required = true,
                    request = { singlePermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
                )
            }
        }

        // NSD permissions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
                steps += PermissionStep(
                    key = "nearby_wifi",
                    title = "Nearby Wi-Fi Devices",
                    message = "To discover sender devices on your local network, " +
                        "NotifSync needs the Nearby Wi-Fi Devices permission.",
                    required = false,
                    request = { singlePermissionLauncher.launch(Manifest.permission.NEARBY_WIFI_DEVICES) }
                )
            }
        } else {
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                steps += PermissionStep(
                    key = "location",
                    title = "Location Access",
                    message = "To discover sender devices on your local network, " +
                        "Android requires location access on this version.\n\n" +
                        "NotifSync does not track your location.",
                    required = false,
                    request = { singlePermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION) }
                )
            }
        }

        if (steps.isEmpty()) {
            onReady()
            return
        }

        permissionQueue.clear()
        permissionQueue += steps
        permissionResults.clear()
        onPermissionsComplete = {
            showPermissionSummary(steps, onReady)
        }
        processNextPermission()
    }

    // ─── Permission sequence engine ──────────────────────────────────────

    private fun processNextPermission() {
        if (permissionQueue.isEmpty()) {
            onPermissionsComplete?.invoke()
            onPermissionsComplete = null
            return
        }

        val step = permissionQueue.removeFirst()
        currentStep = step

        // Already granted? Skip.
        if (isStepGranted(step)) {
            permissionResults[step.key] = true
            processNextPermission()
            return
        }

        // Show explanation, then request
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(step.title)
            .setMessage(step.message)
            .setPositiveButton("Grant") { _, _ ->
                step.request?.invoke()
                // For special permissions (opens Settings), we can't wait
                // for a result — mark as "opened" and move on. The user
                // will return to the app after granting.
                if (step.request == null || step.key == "battery" || step.key == "notif_listener") {
                    // Special permission — user navigated to Settings.
                    // Mark as not-granted (we'll verify on return) and continue.
                    permissionResults[step.key] = false
                    // Process next after a short delay to let the Settings open
                    processNextPermission()
                }
                // For runtime permissions, the launcher callback will call
                // processNextPermission() when the result comes back.
            }
            .setNegativeButton(if (step.required) "Skip (required)" else "Skip") { _, _ ->
                permissionResults[step.key] = false
                processNextPermission()
            }
            .setCancelable(false)
            .show()
    }

    private fun isStepGranted(step: PermissionStep): Boolean {
        return when (step.key) {
            "battery" -> {
                val pm = requireContext().getSystemService(Context.POWER_SERVICE) as PowerManager
                pm.isIgnoringBatteryOptimizations(requireContext().packageName)
            }
            "notif_listener" -> PermissionsHelper.hasNotificationListenerPermission(requireContext())
            "sms" -> PermissionsHelper.hasSmsPermission(requireContext())
            "post_notifications" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                } else true
            }
            "nearby_wifi" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED
                } else true
            }
            "location" ->
                ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            else -> true
        }
    }

    private fun showPermissionSummary(steps: List<PermissionStep>, onReady: () -> Unit) {
        // Re-check actual permission states (the user may have granted
        // special permissions via Settings while we were showing dialogs).
        val statusLines = steps.map { step ->
            val granted = isStepGranted(step)
            val icon = if (granted) "✓" else "✗"
            val suffix = if (granted) "" else if (step.required) " (REQUIRED — won't work without it)" else " (optional)"
            "$icon  ${step.title}$suffix"
        }

        val allRequiredGranted = steps.filter { it.required }.all { isStepGranted(it) }

        val message = statusLines.joinToString("\n")

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Permission Summary")
            .setMessage(message)
            .setPositiveButton(if (allRequiredGranted) "Continue" else "Continue anyway") { _, _ ->
                onReady()
            }
            .setNegativeButton("Open App Settings") { _, _ ->
                openAppSettings()
            }
            .setCancelable(false)
            .show()
    }

    // ─── Settings helpers ────────────────────────────────────────────────

    private fun openBatteryOptimizationSettings() {
        startActivity(Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${requireContext().packageName}")
        ))
    }

    private fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${requireContext().packageName}")
        })
    }

    // ─── Cleanup ─────────────────────────────────────────────────────────

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
