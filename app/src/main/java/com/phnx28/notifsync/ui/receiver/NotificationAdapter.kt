package com.phnx28.notifsync.ui.receiver

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.phnx28.notifsync.data.local.NotificationEntity
import com.phnx28.notifsync.databinding.ItemNotificationBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class NotificationAdapter(
    private val onDismiss: (NotificationEntity) -> Unit,
    private val isArchiveMode: Boolean = false,
    private val onRestore: ((NotificationEntity) -> Unit)? = null
) : ListAdapter<NotificationEntity, NotificationAdapter.ViewHolder>(DiffCallback()) {

    inner class ViewHolder(private val binding: ItemNotificationBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: NotificationEntity) {
            binding.tvAppName.text = item.appName
            binding.tvSender.text = item.sender ?: item.title
            binding.tvBody.text = item.body
            binding.tvTime.text = formatTime(item.timestamp)

            if (isArchiveMode) {
                binding.btnDismiss.visibility = android.view.View.GONE
                binding.btnRestore.visibility = android.view.View.VISIBLE
                binding.tvDaysLeft.visibility = android.view.View.VISIBLE

                item.archivedAt?.let { archivedAt ->
                    val daysLeft = 30 - TimeUnit.MILLISECONDS.toDays(
                        System.currentTimeMillis() - archivedAt
                    ).toInt()
                    binding.tvDaysLeft.text = "Auto-delete in ${daysLeft.coerceAtLeast(0)}d"
                }

                binding.btnRestore.setOnClickListener {
                    onRestore?.invoke(item)
                }
            } else {
                binding.btnDismiss.visibility = android.view.View.VISIBLE
                binding.btnRestore.visibility = android.view.View.GONE
                binding.tvDaysLeft.visibility = android.view.View.GONE

                binding.btnDismiss.setOnClickListener {
                    onDismiss(item)
                }
            }
        }

        private fun formatTime(timestamp: Long): String {
            val now = System.currentTimeMillis()
            val diff = now - timestamp

            return when {
                diff < TimeUnit.MINUTES.toMillis(1) -> "just now"
                diff < TimeUnit.HOURS.toMillis(1) -> "${TimeUnit.MILLISECONDS.toMinutes(diff)}m ago"
                diff < TimeUnit.DAYS.toMillis(1) -> "${TimeUnit.MILLISECONDS.toHours(diff)}h ago"
                else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(timestamp))
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemNotificationBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class DiffCallback : DiffUtil.ItemCallback<NotificationEntity>() {
        override fun areItemsTheSame(
            oldItem: NotificationEntity,
            newItem: NotificationEntity
        ) = oldItem.id == newItem.id

        override fun areContentsTheSame(
            oldItem: NotificationEntity,
            newItem: NotificationEntity
        ) = oldItem == newItem
    }
}
