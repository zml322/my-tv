package com.lizongying.mytv

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.lizongying.mytv.models.TVViewModel

class LiveChannelAdapter(
    private val onChannelSelected: (TVViewModel) -> Unit,
) : RecyclerView.Adapter<LiveChannelAdapter.ChannelViewHolder>() {

    private var channels: List<TVViewModel> = emptyList()
    private var selectedChannel = -1

    fun submit(channels: List<TVViewModel>, selectedChannel: Int) {
        this.channels = channels
        this.selectedChannel = selectedChannel
        notifyDataSetChanged()
    }

    fun setSelectedChannel(channelId: Int) {
        if (selectedChannel == channelId) return

        val oldPosition = selectedChannel
        selectedChannel = channelId
        if (oldPosition in channels.indices) notifyItemChanged(oldPosition)
        if (channelId in channels.indices) notifyItemChanged(channelId)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChannelViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.live_channel_item, parent, false)
        return ChannelViewHolder(view)
    }

    override fun onBindViewHolder(holder: ChannelViewHolder, position: Int) {
        val channel = channels[position]
        val selected = channel.getTV().id == selectedChannel
        holder.bind(channel, selected)
        holder.itemView.setOnClickListener { onChannelSelected(channel) }
    }

    override fun getItemCount(): Int = channels.size

    class ChannelViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val number: TextView = view.findViewById(R.id.live_channel_number)
        private val name: TextView = view.findViewById(R.id.live_channel_name)
        private val group: TextView = view.findViewById(R.id.live_channel_group)

        fun bind(channel: TVViewModel, selected: Boolean) {
            val tv = channel.getTV()
            itemView.isSelected = selected
            number.text = (tv.id + 1).toString().padStart(2, '0')
            name.text = tv.title
            val sources = channel.videoUrl.value.orEmpty()
            val sourceType = when {
                sources.any(OfficialLiveSources::isWeb) -> "官网"
                sources.any(OfficialLiveSources::isOfficialDirect) -> "官方"
                sources.isNotEmpty() -> "备用"
                else -> "暂无源"
            }
            group.text = if (sources.isEmpty()) {
                "${tv.channel} · $sourceType"
            } else {
                "${tv.channel} · $sourceType · ${sources.size}线"
            }
            itemView.contentDescription = "${tv.title}，${tv.channel}"
        }
    }
}
