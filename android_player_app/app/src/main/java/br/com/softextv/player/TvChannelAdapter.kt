package br.com.softextv.player

import android.graphics.Color
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import br.com.softextv.player.databinding.ItemTvChannelBinding

class TvChannelAdapter(
    private val onFocusChanged: (Long?) -> Unit,
    private val onMoveUpFromCard: () -> Unit,
    private val onClick: (TvChannel) -> Unit
) : ListAdapter<TvChannel, TvChannelAdapter.ChannelViewHolder>(DiffCallback) {

    private var activeChannelId: Long? = null

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long = (getItem(position).id * 1_000L) + position

    fun channelIdAt(position: Int): Long? =
        if (position in 0 until itemCount) getItem(position).id else null

    fun setActiveChannel(channelId: Long?) {
        if (activeChannelId == channelId) return

        activeChannelId = channelId
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChannelViewHolder {
        val binding = ItemTvChannelBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ChannelViewHolder(binding, onFocusChanged, onMoveUpFromCard, onClick)
    }

    override fun onBindViewHolder(holder: ChannelViewHolder, position: Int) {
        holder.bind(getItem(position), getItem(position).id == activeChannelId)
    }

    class ChannelViewHolder(
        private val binding: ItemTvChannelBinding,
        private val onFocusChanged: (Long?) -> Unit,
        private val onMoveUpFromCard: () -> Unit,
        private val onClick: (TvChannel) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(channel: TvChannel, isActive: Boolean) {
            binding.channelName.text = channel.name
            val statusText = when (channel.status?.lowercase()) {
                "running" -> "No ar"
                "stopped" -> "Parada"
                else -> "Sem status"
            }
            binding.channelStatus.text = statusText
            binding.channelReflectionStatus.text = statusText
            binding.channelReflectionName.text = channel.name
            RemoteImageLoader.loadInto(binding.channelThumbnail, channel.thumbnailUrl)
            RemoteImageLoader.loadInto(binding.channelReflectionThumbnail, channel.thumbnailUrl)
            setActive(isActive)

            binding.root.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    onFocusChanged(channel.id)
                }
            }
            binding.root.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    onMoveUpFromCard()
                    true
                } else {
                    false
                }
            }
            binding.root.setOnClickListener { onClick(channel) }
        }

        fun setActive(isActive: Boolean) {
            binding.channelNeonGlow.visibility = if (isActive) View.VISIBLE else View.INVISIBLE
            binding.focusRing.visibility = View.INVISIBLE
            binding.channelReflectionFocusRing.visibility = if (isActive) View.VISIBLE else View.INVISIBLE
            binding.channelGlassOverlay.alpha = if (isActive) 0f else 0.72f
            binding.channelThumbnail.alpha = if (isActive) 0.86f else 0.64f
            binding.channelCard.alpha = if (isActive) 1f else 0.86f
            binding.channelCard.strokeColor = if (isActive) Color.TRANSPARENT else Color.argb(45, 120, 92, 255)
            binding.channelReflection.alpha = if (isActive) 0.82f else 0.4f
            binding.channelReflectionCard.alpha = if (isActive) 0.72f else 0.36f
            binding.channelReflectionThumbnail.alpha = if (isActive) 0.58f else 0.34f
            binding.channelReflectionGlow.alpha = if (isActive) 0.14f else 0.06f
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<TvChannel>() {
        override fun areItemsTheSame(oldItem: TvChannel, newItem: TvChannel): Boolean = oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: TvChannel, newItem: TvChannel): Boolean = oldItem == newItem
    }
}
