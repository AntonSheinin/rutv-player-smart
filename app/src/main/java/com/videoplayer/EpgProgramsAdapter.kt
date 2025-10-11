package com.videoplayer

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.*

class EpgProgramsAdapter(
    private val onProgramClick: ((EpgProgram) -> Unit)? = null
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    
    private var items = listOf<EpgItem>()
    private var selectedPosition = -1
    
    sealed class EpgItem {
        data class DateHeader(val date: String) : EpgItem()
        data class ProgramItem(val program: EpgProgram, val isCurrent: Boolean, val isEnded: Boolean) : EpgItem()
    }
    
    class DateViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val dateText: TextView = view.findViewById(R.id.date_text)
    }
    
    class ProgramViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val itemLayout: View = view.findViewById(R.id.program_item_layout)
        val timeIndicator: View = view.findViewById(R.id.program_time_indicator)
        val time: TextView = view.findViewById(R.id.program_time)
        val title: TextView = view.findViewById(R.id.program_title)
        val description: TextView = view.findViewById(R.id.program_description)
    }
    
    override fun getItemViewType(position: Int): Int {
        return when (items[position]) {
            is EpgItem.DateHeader -> VIEW_TYPE_DATE
            is EpgItem.ProgramItem -> VIEW_TYPE_PROGRAM
        }
    }
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            VIEW_TYPE_DATE -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.epg_date_delimiter, parent, false)
                DateViewHolder(view)
            }
            else -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.epg_program_item, parent, false)
                ProgramViewHolder(view)
            }
        }
    }
    
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is EpgItem.DateHeader -> {
                (holder as DateViewHolder).dateText.text = item.date
            }
            is EpgItem.ProgramItem -> {
                val programHolder = holder as ProgramViewHolder
                programHolder.time.text = formatTime(item.program.startTime)
                programHolder.title.text = item.program.title
                
                if (!item.program.description.isNullOrBlank()) {
                    programHolder.description.visibility = View.VISIBLE
                    programHolder.description.text = item.program.description
                } else {
                    programHolder.description.visibility = View.GONE
                }
                
                // Apply fading to ended programs
                val alpha = if (item.isEnded) 0.4f else 1.0f
                programHolder.itemLayout.alpha = alpha
                
                // Highlight current program with green indicator
                if (item.isCurrent) {
                    programHolder.timeIndicator.visibility = View.VISIBLE
                    programHolder.time.setTextColor(android.graphics.Color.parseColor("#4CAF50"))
                } else {
                    programHolder.timeIndicator.visibility = View.GONE
                    programHolder.time.setTextColor(android.graphics.Color.parseColor("#E0E0E0"))
                }
                
                // Highlight selected program with gold background
                if (position == selectedPosition) {
                    programHolder.itemLayout.setBackgroundColor(android.graphics.Color.parseColor("#2A2500"))
                    programHolder.title.setTextColor(android.graphics.Color.parseColor("#FFD700"))
                } else {
                    programHolder.itemLayout.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    programHolder.title.setTextColor(android.graphics.Color.parseColor("#F5F5F5"))
                }
                
                // Click handler
                programHolder.itemLayout.setOnClickListener {
                    val previousPosition = selectedPosition
                    selectedPosition = position
                    notifyItemChanged(previousPosition)
                    notifyItemChanged(position)
                    onProgramClick?.invoke(item.program)
                }
            }
        }
    }
    
    override fun getItemCount(): Int = items.size
    
    fun updatePrograms(programs: List<EpgProgram>): Int {
        val now = System.currentTimeMillis()
        val newItems = mutableListOf<EpgItem>()
        var currentProgramPosition = -1
        
        val groupedByDate = programs.groupBy { program ->
            val date = parseTimeString(program.startTime)
            val calendar = Calendar.getInstance()
            calendar.timeInMillis = date
            calendar.get(Calendar.DAY_OF_YEAR)
        }
        
        groupedByDate.entries.sortedBy { it.key }.forEach { (_, programsForDate) ->
            val firstProgram = programsForDate.firstOrNull()
            if (firstProgram != null) {
                val dateHeader = formatDateHeader(parseTimeString(firstProgram.startTime))
                newItems.add(EpgItem.DateHeader(dateHeader))
            }
            
            programsForDate.forEach { program ->
                val startTime = parseTimeString(program.startTime)
                val stopTime = parseTimeString(program.stopTime)
                val isCurrent = now in startTime..stopTime
                val isEnded = now > stopTime
                
                if (isCurrent) {
                    currentProgramPosition = newItems.size
                }
                
                newItems.add(EpgItem.ProgramItem(program, isCurrent, isEnded))
            }
        }
        
        items = newItems
        selectedPosition = currentProgramPosition
        notifyDataSetChanged()
        
        return currentProgramPosition
    }
    
    private fun parseTimeString(timeString: String): Long {
        return try {
            val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            format.timeZone = TimeZone.getTimeZone("UTC")
            format.parse(timeString)?.time ?: 0L
        } catch (e: Exception) {
            try {
                val format = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US)
                format.parse(timeString)?.time ?: 0L
            } catch (e2: Exception) {
                0L
            }
        }
    }
    
    private fun formatTime(timeString: String): String {
        val millis = parseTimeString(timeString)
        if (millis == 0L) return ""
        
        val format = SimpleDateFormat("HH:mm", Locale.US)
        format.timeZone = TimeZone.getDefault()
        return format.format(Date(millis))
    }
    
    private fun formatDateHeader(millis: Long): String {
        if (millis == 0L) return ""
        
        val format = SimpleDateFormat("EEEE, dd/MM", Locale.getDefault())
        format.timeZone = TimeZone.getDefault()
        return format.format(Date(millis))
    }
    
    companion object {
        private const val VIEW_TYPE_DATE = 0
        private const val VIEW_TYPE_PROGRAM = 1
    }
}
