package com.mdreader.app

import android.graphics.Paint
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.mdreader.app.databinding.HistoryItemBinding

/** 历史记录列表适配器。可用性由外部异步检测后回填；不可用条目加删除线并显示「已删除」。 */
class HistoryAdapter(
    private val items: List<History.Entry>,
    private val onClick: (History.Entry) -> Unit
) : RecyclerView.Adapter<HistoryAdapter.VH>() {

    // uri -> 是否可访问；缺省（未检测）按可用处理
    private val availability = HashMap<String, Boolean>()

    fun setAvailability(map: Map<String, Boolean>) {
        availability.clear()
        availability.putAll(map)
        notifyDataSetChanged()
    }

    fun isUnavailable(uri: String): Boolean = availability[uri] == false

    class VH(val binding: HistoryItemBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = HistoryItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val e = items[position]
        val ctx = holder.binding.root.context
        val unavailable = isUnavailable(e.uri)

        holder.binding.itemName.text = e.name
        holder.binding.itemName.paintFlags =
            if (unavailable) holder.binding.itemName.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            else holder.binding.itemName.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()

        val rel = DateUtils.getRelativeTimeSpanString(
            e.time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS
        )
        if (unavailable) {
            holder.binding.itemSub.text = ctx.getString(R.string.history_deleted) + " · " + rel
            holder.binding.itemSub.setTextColor(COLOR_DELETED)
        } else {
            holder.binding.itemSub.text = rel
            holder.binding.itemSub.setTextColor(COLOR_NORMAL)
        }

        holder.binding.root.setOnClickListener { onClick(e) }
    }

    companion object {
        private const val COLOR_DELETED = 0xFFD32F2F.toInt()
        private const val COLOR_NORMAL = 0xFF8A8F98.toInt()
    }
}
