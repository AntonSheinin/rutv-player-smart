package com.videoplayer

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.*

class EpgProgramsAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    
    private var items = listOf<EpgItem>()
    
    sealed class EpgItem {
        data class DateHeader(val date: String) : EpgItem()
        data class ProgramItem(val program: EpgProgram, val isCurrent: Boolean) : EpgItem()
    }
    
    class DateViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val dateText: TextView = view.findViewById(R.id.date_text)
    }
    
    class ProgramViewHolder(view: View) : RecyclerView.ViewHolder(view) {
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
                
                if (item.isCurrent) {
                    programHolder.timeIndicator.visibility = View.VISIBLE
                    programHolder.time.setTextColor(android.graphics.Color.parseColor("#4CAF50"))
                } else {
                    programHolder.timeIndicator.visibility = View.GONE
                    programHolder.time.setTextColor(android.graphics.Color.parseColor("#FFFFFF"))
                }
            }
        }
    }
    
    override fun getItemCount(): Int = items.size
    
    fun updatePrograms(programs: List<EpgProgram>) {
        val now = System.currentTimeMillis()
        val newItems = mutableListOf<EpgItem>()
        
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
                newItems.add(EpgItem.ProgramItem(program, isCurrent))
            }
        }
        
        items = newItems
        notifyDataSetChanged()
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
