package com.videoplayer.presentation.adapter

import androidx.recyclerview.widget.DiffUtil

/**
 * DiffUtil callback for EPG items
 */
sealed class EpgItem {
    data class DateHeader(val date: String) : EpgItem()
    data class ProgramItem(
        val id: String,
        val startTime: String,
        val stopTime: String,
        val title: String,
        val description: String,
        val isCurrent: Boolean,
        val isEnded: Boolean
    ) : EpgItem()
}

class EpgItemDiffCallback : DiffUtil.ItemCallback<EpgItem>() {

    override fun areItemsTheSame(oldItem: EpgItem, newItem: EpgItem): Boolean {
        return when {
            oldItem is EpgItem.DateHeader && newItem is EpgItem.DateHeader ->
                oldItem.date == newItem.date
            oldItem is EpgItem.ProgramItem && newItem is EpgItem.ProgramItem ->
                oldItem.id == newItem.id
            else -> false
        }
    }

    override fun areContentsTheSame(oldItem: EpgItem, newItem: EpgItem): Boolean {
        return oldItem == newItem
    }
}
