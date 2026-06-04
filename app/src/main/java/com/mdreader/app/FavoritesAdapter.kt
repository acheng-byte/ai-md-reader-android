package com.mdreader.app

import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.mdreader.app.databinding.FavoriteItemBinding

/**
 * 收藏夹列表适配器。点击行打开（始终可用，因有本地副本）；点击右侧星标取消收藏。
 * 取消收藏时先从内部列表移除并刷新，再回调外部执行物理删除与空状态刷新。
 */
class FavoritesAdapter(
    private val items: MutableList<Favorites.Fav>,
    private val onOpen: (Favorites.Fav) -> Unit,
    private val onRemove: (Favorites.Fav) -> Unit
) : RecyclerView.Adapter<FavoritesAdapter.VH>() {

    class VH(val binding: FavoriteItemBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(FavoriteItemBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val f = items[position]
        holder.binding.itemName.text = f.name
        holder.binding.itemSub.text = DateUtils.getRelativeTimeSpanString(
            f.time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS
        )
        holder.binding.root.setOnClickListener { onOpen(f) }
        holder.binding.btnUnfav.setOnClickListener {
            val pos = holder.adapterPosition
            if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
            val removed = items.removeAt(pos)
            notifyItemRemoved(pos)
            onRemove(removed)
        }
    }
}
