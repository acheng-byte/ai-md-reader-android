package com.mdreader.app

import android.graphics.Paint
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.mdreader.app.databinding.HistoryItemBinding

/**
 * 历史记录列表适配器。可访问状态由外部异步检测后回填：
 * - 已删除（DELETED）：物理文件不存在 -> 加删除线 + 红色「已删除」
 * - 授权过期（EXPIRED）：文件可能还在但无访问权限（如微信临时授权失效）-> 橙色「授权过期」
 * 已收藏的条目名称前加 ★。
 */
class HistoryAdapter(
    private val items: MutableList<History.Entry>,
    private val favorites: Set<String>,
    private val onClick: (History.Entry) -> Unit,
    private val onDelete: ((History.Entry, Int) -> Unit)? = null
) : RecyclerView.Adapter<HistoryAdapter.VH>() {

    private val statuses = HashMap<String, DocStatus>()

    fun setStatuses(map: Map<String, DocStatus>) {
        statuses.clear()
        statuses.putAll(map)
        notifyDataSetChanged()
    }

    fun statusOf(uri: String): DocStatus = statuses[uri] ?: DocStatus.AVAILABLE

    /** 删除指定位置的条目（外部调用后需自行更新数据源） */
    fun removeAt(position: Int) {
        if (position in items.indices) {
            items.removeAt(position)
            // 使用 notifyDataSetChanged 避免 notifyItemRemoved + notifyItemRangeChanged
            // 在 BottomSheetDialog 的 RecyclerView 中引发 IndexOutOfBoundsException 闪退
            notifyDataSetChanged()
        }
    }

    class VH(val binding: HistoryItemBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(HistoryItemBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val e = items[position]
        val ctx = holder.binding.root.context
        val status = statusOf(e.uri)
        val starred = e.uri in favorites

        holder.binding.itemName.text = if (starred) "★ " + e.name else e.name
        val strike = status == DocStatus.DELETED
        holder.binding.itemName.paintFlags =
            if (strike) holder.binding.itemName.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            else holder.binding.itemName.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()

        val rel = DateUtils.getRelativeTimeSpanString(
            e.time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS
        )
        when (status) {
            DocStatus.AVAILABLE -> {
                holder.binding.itemSub.text = rel
                holder.binding.itemSub.setTextColor(COLOR_NORMAL)
            }
            DocStatus.EXPIRED -> {
                holder.binding.itemSub.text = ctx.getString(R.string.history_expired) + " · " + rel
                holder.binding.itemSub.setTextColor(COLOR_EXPIRED)
            }
            DocStatus.DELETED -> {
                holder.binding.itemSub.text = ctx.getString(R.string.history_deleted) + " · " + rel
                holder.binding.itemSub.setTextColor(COLOR_DELETED)
            }
        }

        holder.binding.root.setOnClickListener { onClick(e) }

        // 删除按钮
        holder.binding.btnDelete.setOnClickListener {
            val pos = holder.adapterPosition
            if (pos != RecyclerView.NO_POSITION) {
                onDelete?.invoke(e, pos)
            }
        }
    }

    companion object {
        private const val COLOR_DELETED = 0xFFD32F2F.toInt()  // 红
        private const val COLOR_EXPIRED = 0xFFE8833A.toInt()  // 橙
        private const val COLOR_NORMAL = 0xFF8A8F98.toInt()   // 灰
    }
}
