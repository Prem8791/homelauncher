package com.home.launcher.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Process
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.recyclerview.widget.RecyclerView
import com.home.launcher.R
import com.home.launcher.data.AppEntry
import com.home.launcher.data.AppIndex

class AllAppsAdapter(
    private val activity: Activity,
    private val appIndex: AppIndex
) : RecyclerView.Adapter<AllAppsAdapter.ViewHolder>() {

    private var apps: List<AppEntry> = emptyList()
    private var filteredApps: MutableList<AppEntry> = mutableListOf()

    fun setApps(apps: List<AppEntry>) {
        this.apps = apps
        this.filteredApps = apps.toMutableList()
        notifyDataSetChanged()
    }

    fun filterByLetter(letter: Char?): List<AppEntry> {
        filteredApps = if (letter == null) {
            apps.toMutableList()
        } else {
            appIndex.getAppsForLetter(letter).toMutableList()
        }
        notifyDataSetChanged()
        return filteredApps
    }

    fun getFilteredApps(): List<AppEntry> = filteredApps

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_app_list, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val entry = filteredApps[position]
        holder.bind(entry)
    }

    override fun getItemCount(): Int = filteredApps.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val icon: ImageView = itemView.findViewById(R.id.appItemIcon)
        private val label: TextView = itemView.findViewById(R.id.appItemLabel)
        private val favIndicator: View = itemView.findViewById(R.id.appItemFav)

        fun bind(entry: AppEntry) {
            label.text = entry.label
            if (entry.icon != null) {
                icon.setImageDrawable(entry.icon)
            }
            favIndicator.visibility = if (entry.isFavourite) View.VISIBLE else View.GONE
            itemView.setOnClickListener {
                launchApp(entry.packageName)
            }
            itemView.setOnLongClickListener {
                showAppContextMenu(entry)
                true
            }
        }

        private fun launchApp(packageName: String) {
            try {
                val intent = activity.packageManager.getLaunchIntentForPackage(packageName)
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    activity.startActivity(intent)
                }
            } catch (e: Exception) {
                Toast.makeText(activity, "Failed to launch app", Toast.LENGTH_SHORT).show()
            }
        }

        private fun showAppContextMenu(entry: AppEntry) {
            val menu = PopupMenu(activity, itemView)
            val shortcuts = getPublishedShortcuts(entry.packageName)
            for ((index, shortcut) in shortcuts.withIndex()) {
                val label = shortcut.shortLabel?.toString()
                    ?: shortcut.longLabel?.toString()
                    ?: continue
                menu.menu.add(0, index + 1, index, label)
            }
            menu.menu.add(if (entry.isFavourite) "Remove favourite" else "Add favourite")
            menu.menu.add("Launch")
            menu.menu.add("App info")
            menu.menu.add("Uninstall")
            menu.setOnMenuItemClickListener { item ->
                val shortcutIndex = item.itemId - 1
                if (shortcutIndex in shortcuts.indices) {
                    startShortcut(shortcuts[shortcutIndex])
                    return@setOnMenuItemClickListener true
                }
                when (item.title.toString()) {
                    "Launch" -> {
                        launchApp(entry.packageName)
                        true
                    }
                    "Add favourite", "Remove favourite" -> {
                        appIndex.toggleFavourite(entry.packageName)
                        val position = bindingAdapterPosition
                        if (position != RecyclerView.NO_POSITION) {
                            val newIsFav = appIndex.isFavourite(entry.packageName)
                            filteredApps[position] = filteredApps[position].copy(isFavourite = newIsFav)
                            notifyItemChanged(position)
                        }
                        true
                    }
                    "App info" -> {
                        openAppInfo(entry.packageName)
                        true
                    }
                    "Uninstall" -> {
                        requestUninstall(entry.packageName)
                        true
                    }
                    else -> false
                }
            }
            menu.show()
        }

        private fun getPublishedShortcuts(packageName: String): List<ShortcutInfo> {
            val launcherApps = activity.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
            val query = LauncherApps.ShortcutQuery()
                .setPackage(packageName)
                .setQueryFlags(
                    LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or
                        LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
                        LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED
                )
            return try {
                launcherApps.getShortcuts(query, Process.myUserHandle())
                    ?.filter { it.isEnabled }
                    ?.sortedBy { it.rank }
                    ?.take(4)
                    ?: emptyList()
            } catch (e: SecurityException) {
                emptyList()
            } catch (e: RuntimeException) {
                emptyList()
            }
        }

        private fun startShortcut(shortcut: ShortcutInfo) {
            val launcherApps = activity.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
            try {
                launcherApps.startShortcut(
                    shortcut.`package`,
                    shortcut.id,
                    null,
                    null,
                    shortcut.userHandle
                )
            } catch (e: Exception) {
                Toast.makeText(activity, "Cannot open shortcut", Toast.LENGTH_SHORT).show()
            }
        }

        private fun openAppInfo(packageName: String) {
            try {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                intent.data = Uri.parse("package:$packageName")
                activity.startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(activity, "Cannot open app info", Toast.LENGTH_SHORT).show()
            }
        }

        private fun requestUninstall(packageName: String) {
            try {
                val intent = Intent(Intent.ACTION_DELETE)
                intent.data = Uri.parse("package:$packageName")
                activity.startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(activity, "Cannot uninstall app", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
