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

        checkBatteryOptimization()
        checkNotificationListenerPermission()

        binding.cardSender.setOnClickListener {
            findNavController().navigate(R.id.action_home_to_sender)
        }

        binding.cardReceiver.setOnClickListener {
            findNavController().navigate(R.id.action_home_to_pairing)
        }
    }

    private fun checkBatteryOptimization() {
        val pm = requireContext().getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(requireContext().packageName)) {
            val intent = Intent(
                android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:${requireContext().packageName}")
            )
            startActivity(intent)
        }
    }

    private fun checkNotificationListenerPermission() {
        if (!PermissionsHelper.hasNotificationListenerPermission(requireContext())) {
            PermissionsHelper.openNotificationListenerSettings(requireContext())
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
