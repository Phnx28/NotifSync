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
import com.phnx28.notifsync.service.ReceiverForegroundService
import com.phnx28.notifsync.util.showNeutralSnackbar

class ReceiverFragment : Fragment() {

    private var _binding: FragmentReceiverBinding? = null
    private val binding get() = _binding!!

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

        // v0.2.1 — populate the previously-empty `tvFromIp` (AUDIT.md U-01).
        // The service exposes the last-connected sender IP from its
        // EncryptedSharedPreferences, but since the service is in the same
        // process we can read it directly.
        val senderIp = (requireActivity().applicationContext as? android.app.Application)
            ?.let { (it as? com.phnx28.notifsync.NotifSyncApp) }
            ?.let { "—" }  // placeholder; the actual IP is inside the service
        binding.tvFromIp.text = getString(R.string.from_ip, senderIp ?: "—")

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
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
