package com.phnx28.notifsync.ui.pairing

import android.net.nsd.NsdServiceInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.phnx28.notifsync.R
import com.phnx28.notifsync.databinding.FragmentPairingBinding
import com.phnx28.notifsync.network.NsdHelper
import com.phnx28.notifsync.service.ReceiverForegroundService
import com.phnx28.notifsync.util.showErrorSnackbar
import com.phnx28.notifsync.util.showSuccessSnackbar
import java.net.InetAddress

class PairingFragment : Fragment() {

    private var _binding: FragmentPairingBinding? = null
    private val binding get() = _binding!!
    private var nsdHelper: NsdHelper? = null
    private val discoveredDevices = mutableListOf<DiscoveredDevice>()
    private lateinit var deviceAdapter: DeviceAdapter
    private val handler = Handler(Looper.getMainLooper())
    private var discoveryTimeoutRunnable: Runnable? = null

    data class DiscoveredDevice(
        val name: String,
        val host: InetAddress,
        val port: Int
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPairingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        deviceAdapter = DeviceAdapter { device ->
            connectToSender(device.host.hostAddress ?: return@DeviceAdapter)
        }

        binding.rvDevices.layoutManager = LinearLayoutManager(context)
        binding.rvDevices.adapter = deviceAdapter

        binding.btnBack.setOnClickListener {
            findNavController().popBackStack()
        }

        binding.btnConnect.setOnClickListener {
            val ip = binding.etIpAddress.text.toString().trim()
            if (ip.isEmpty()) {
                binding.etIpAddress.error = "Enter an IP address"
                return@setOnClickListener
            }
            if (!isValidIpAddress(ip)) {
                binding.etIpAddress.error = "Enter a valid IP address"
                return@setOnClickListener
            }
            connectToSender(ip)
        }

        startDiscovery()

        discoveryTimeoutRunnable = Runnable {
            if (discoveredDevices.isEmpty()) {
                binding.progressScanning.visibility = View.GONE
                binding.tvScanningStatus.text = "No devices found. Enter IP manually below."
                binding.tvNoDevices.visibility = View.VISIBLE
            }
        }
        handler.postDelayed(discoveryTimeoutRunnable!!, 12000)
    }

    private fun startDiscovery() {
        nsdHelper = NsdHelper(requireContext())

        nsdHelper?.discoverServices(object : NsdHelper.DiscoveryCallback {
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                nsdHelper?.resolveService(serviceInfo, this)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                activity?.runOnUiThread {
                    discoveredDevices.removeAll { it.name == serviceInfo.serviceName }
                    deviceAdapter.submitList(discoveredDevices.toList())
                    updateDiscoveryUI()
                }
            }

            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                activity?.runOnUiThread {
                    showErrorSnackbar("Failed to resolve ${serviceInfo.serviceName}")
                }
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                activity?.runOnUiThread {
                    val host = serviceInfo.host ?: return@runOnUiThread
                    val device = DiscoveredDevice(
                        name = serviceInfo.serviceName,
                        host = host,
                        port = serviceInfo.port
                    )
                    if (discoveredDevices.none { it.host == host }) {
                        discoveredDevices.add(device)
                        deviceAdapter.submitList(discoveredDevices.toList())
                        updateDiscoveryUI()
                        showSuccessSnackbar("Found: ${device.name}")
                    }
                }
            }
        })
    }

    private fun updateDiscoveryUI() {
        if (discoveredDevices.isNotEmpty()) {
            binding.progressScanning.visibility = View.GONE
            binding.tvScanningStatus.text = "Found ${discoveredDevices.size} device(s)"
            binding.tvNoDevices.visibility = View.GONE
        }
    }

    private fun isValidIpAddress(ip: String): Boolean {
        return try {
            val parts = ip.split(".")
            parts.size == 4 && parts.all { it.toInt() in 0..255 }
        } catch (e: NumberFormatException) {
            false
        }
    }

    private fun connectToSender(ip: String) {
        showSuccessSnackbar("Connecting to $ip…")
        ReceiverForegroundService.connect(requireContext(), ip)
        findNavController().navigate(R.id.action_pairing_to_receiver)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        discoveryTimeoutRunnable?.let { handler.removeCallbacks(it) }
        nsdHelper?.tearDown()
        nsdHelper = null
        _binding = null
    }

    inner class DeviceAdapter(
        private val onClick: (DiscoveredDevice) -> Unit
    ) : RecyclerView.Adapter<DeviceAdapter.DeviceViewHolder>() {

        private var items = listOf<DiscoveredDevice>()

        fun submitList(newItems: List<DiscoveredDevice>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_device, parent, false)
            return DeviceViewHolder(view)
        }

        override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
            val device = items[position]
            holder.tvName.text = device.name
            holder.tvIp.text = device.host.hostAddress
            holder.itemView.setOnClickListener { onClick(device) }
        }

        override fun getItemCount() = items.size

        inner class DeviceViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvName: TextView = view.findViewById(R.id.tvDeviceName)
            val tvIp: TextView = view.findViewById(R.id.tvDeviceIp)
        }
    }
}
