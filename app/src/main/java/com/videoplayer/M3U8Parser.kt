package com.videoplayer

object M3U8Parser {
    
    data class Channel(
        val title: String,
        val url: String,
        val logo: String = "",
        val group: String = "",
        val tvgId: String = ""
    )
    
    fun parse(content: String): List<Channel> {
        val channels = mutableListOf<Channel>()
        val lines = content.lines()
        
        var currentTitle = ""
        var currentLogo = ""
        var currentGroup = ""
        var currentTvgId = ""
        
        for (i in lines.indices) {
            val line = lines[i].trim()
            
            if (line.startsWith("#EXTINF:")) {
                val tvgNameMatch = Regex("""tvg-name="([^"]+)"""").find(line)
                val tvgIdMatch = Regex("""tvg-id="([^"]+)"""").find(line)
                val logoMatch = Regex("""tvg-logo="([^"]+)"""").find(line)
                val groupMatch = Regex("""group-title="([^"]+)"""").find(line)
                val titleMatch = Regex(""",\s*(.+)$""").find(line)
                
                currentTitle = tvgNameMatch?.groupValues?.get(1) 
                    ?: titleMatch?.groupValues?.get(1) 
                    ?: "Unknown"
                currentLogo = logoMatch?.groupValues?.get(1) ?: ""
                currentGroup = groupMatch?.groupValues?.get(1) ?: "General"
                currentTvgId = tvgIdMatch?.groupValues?.get(1) ?: ""
                
            } else if (line.isNotEmpty() && !line.startsWith("#") && currentTitle.isNotEmpty()) {
                channels.add(
                    Channel(
                        title = currentTitle,
                        url = line,
                        logo = currentLogo,
                        group = currentGroup,
                        tvgId = currentTvgId
                    )
                )
                currentTitle = ""
                currentLogo = ""
                currentGroup = ""
                currentTvgId = ""
            }
        }
        
        return channels
    }
}
