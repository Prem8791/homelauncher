package com.home.launcher.adapter

import android.content.Context
import android.graphics.Bitmap
import android.util.DisplayMetrics
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.home.launcher.HiddenApi
import com.home.launcher.R

data class RecentTaskTile(
    val taskId: Int,
    val packageName: String,
    val appLabel: String?,
    val userId: Int
)

class RecentAppsAdapter(
    private val context: Context,
    private val onClose: (RecentTaskTile) -> Unit,
    private val onResume: (RecentTaskTile) -> Unit
) : RecyclerView.Adapter<RecentAppsAdapter.TileViewHolder>() {

    private val tiles = mutableListOf<RecentTaskTile>()
    private val thumbnailCache = mutableMapOf<Int, Bitmap?>()
    private var tileHeight = 0

    fun setTileHeight(height: Int) {
        tileHeight = height
    }

    fun updateTiles(newTiles: List<RecentTaskTile>) {
        val existingIds = tiles.map { it.taskId }.toSet()
        val newIds = newTiles.map { it.taskId }.toSet()

        val added = newTiles.filter { it.taskId !in existingIds }
        val removed = tiles.filter { it.taskId !in newIds }

        for (r in removed) {
            val idx = tiles.indexOfFirst { it.taskId == r.taskId }
            if (idx >= 0) {
                tiles.removeAt(idx)
                thumbnailCache.remove(r.taskId)
            }
        }

        val staleCache = thumbnailCache.keys.filter { it !in newIds }
        staleCache.forEach { thumbnailCache.remove(it) }

        for (a in added.reversed()) {
            tiles.add(0, a)
        }

        if (added.isNotEmpty() || removed.isNotEmpty()) {
            notifyDataSetChanged()
            refreshThumbnails()
        }
    }

    fun removeTile(taskId: Int) {
        val idx = tiles.indexOfFirst { it.taskId == taskId }
        if (idx >= 0) {
            tiles.removeAt(idx)
            thumbnailCache.remove(taskId)
            notifyItemRemoved(idx)
        }
    }

    fun clearAll() {
        tiles.clear()
        thumbnailCache.clear()
        notifyDataSetChanged()
    }

    fun isEmpty(): Boolean = tiles.isEmpty()
    fun getTileAt(position: Int): RecentTaskTile = tiles[position]

    private fun refreshThumbnails() {
        for (i in tiles.indices) {
            val tile = tiles[i]
            val cached = thumbnailCache[tile.taskId]
            if (cached == null && tile.taskId > 0) {
                val bmp = HiddenApi.getTaskSnapshot(tile.taskId, false)
                thumbnailCache[tile.taskId] = bmp
                notifyItemChanged(i)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TileViewHolder {
        val view = LayoutInflater.from(context).inflate(R.layout.item_recent_tile, parent, false)
        if (tileHeight > 0) {
            view.layoutParams = view.layoutParams?.also { it.height = tileHeight }
        }
        return TileViewHolder(view)
    }

    override fun onBindViewHolder(holder: TileViewHolder, position: Int) {
        if (tileHeight > 0) {
            holder.itemView.layoutParams?.height = tileHeight
        }
        holder.bind(tiles[position])
    }

    override fun getItemCount(): Int = tiles.size

    inner class TileViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val thumbnail: ImageView = itemView.findViewById(R.id.tileThumbnail)
        private val appIcon: ImageView = itemView.findViewById(R.id.tileAppIcon)
        private val appLabel: TextView = itemView.findViewById(R.id.tileAppLabel)
        private val closeButton: ImageButton = itemView.findViewById(R.id.tileCloseButton)

        fun bind(tile: RecentTaskTile) {
            val label = tile.appLabel ?: HiddenApi.getAppLabelForTask(context, tile.packageName) ?: tile.packageName
            appLabel.text = label

            val icon = HiddenApi.getAppIconForTask(context, tile.packageName)
            if (icon != null) {
                appIcon.setImageDrawable(icon)
            }

            val snapshot = thumbnailCache[tile.taskId]
            if (snapshot != null) {
                thumbnail.setImageBitmap(snapshot)
                thumbnail.scaleType = ImageView.ScaleType.CENTER_CROP
                appIcon.visibility = View.GONE
            } else {
                thumbnail.setImageDrawable(null)
                appIcon.visibility = View.VISIBLE
            }

            closeButton.setOnClickListener { onClose(tile) }
            itemView.setOnClickListener { onResume(tile) }

            val taskIdView: TextView = itemView.findViewById(R.id.tileTaskId)
            taskIdView.text = "#${tile.taskId}"
        }
    }
}
