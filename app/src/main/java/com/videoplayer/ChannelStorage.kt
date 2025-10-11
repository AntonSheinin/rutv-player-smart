package com.videoplayer

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

object ChannelStorage {
    private const val PREFS_NAME = "ChannelStorage"
    private const val KEY_CHANNELS = "saved_channels"
    private const val KEY_FAVORITES = "favorites"
    private const val KEY_ASPECT_RATIOS = "aspect_ratios"
    private const val KEY_PLAYLIST_HASH = "playlist_hash"
    private const val KEY_LAST_PLAYED_INDEX = "last_played_index"
    
    fun saveChannels(context: Context, channels: List<VideoItem>, playlistHash: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        
        val jsonArray = JSONArray()
        channels.forEach { channel ->
            val jsonObject = JSONObject().apply {
                put("title", channel.title)
                put("url", channel.url)
                put("logo", channel.logo)
                put("group", channel.group)
                put("tvgId", channel.tvgId)
                put("catchupDays", channel.catchupDays)
            }
            jsonArray.put(jsonObject)
        }
        
        editor.putString(KEY_CHANNELS, jsonArray.toString())
        editor.putString(KEY_PLAYLIST_HASH, playlistHash)
        editor.apply()
    }
    
    fun loadChannels(context: Context): List<VideoItem>? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val channelsJson = prefs.getString(KEY_CHANNELS, null) ?: return null
        
        val channels = mutableListOf<VideoItem>()
        val jsonArray = JSONArray(channelsJson)
        
        for (i in 0 until jsonArray.length()) {
            val jsonObject = jsonArray.getJSONObject(i)
            channels.add(
                VideoItem(
                    title = jsonObject.getString("title"),
                    url = jsonObject.getString("url"),
                    logo = jsonObject.optString("logo", ""),
                    group = jsonObject.optString("group", "General"),
                    tvgId = jsonObject.optString("tvgId", ""),
                    catchupDays = jsonObject.optInt("catchupDays", 0),
                    isFavorite = isFavorite(context, jsonObject.getString("url")),
                    aspectRatio = getAspectRatio(context, jsonObject.getString("url"))
                )
            )
        }
        
        return channels
    }
    
    fun getStoredPlaylistHash(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_PLAYLIST_HASH, null)
    }
    
    fun setFavorite(context: Context, channelUrl: String, isFavorite: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val favorites = prefs.getStringSet(KEY_FAVORITES, mutableSetOf())?.toMutableSet() ?: mutableSetOf()
        
        if (isFavorite) {
            favorites.add(channelUrl)
        } else {
            favorites.remove(channelUrl)
        }
        
        prefs.edit().putStringSet(KEY_FAVORITES, favorites).apply()
    }
    
    fun isFavorite(context: Context, channelUrl: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val favorites = prefs.getStringSet(KEY_FAVORITES, mutableSetOf()) ?: return false
        return favorites.contains(channelUrl)
    }
    
    fun setAspectRatio(context: Context, channelUrl: String, aspectRatio: Int) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val aspectRatios = getAspectRatiosMap(prefs).toMutableMap()
        aspectRatios[channelUrl] = aspectRatio
        saveAspectRatiosMap(prefs, aspectRatios)
    }
    
    fun getAspectRatio(context: Context, channelUrl: String): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val aspectRatios = getAspectRatiosMap(prefs)
        return aspectRatios[channelUrl] ?: androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
    }
    
    private fun getAspectRatiosMap(prefs: SharedPreferences): Map<String, Int> {
        val json = prefs.getString(KEY_ASPECT_RATIOS, null) ?: return emptyMap()
        val map = mutableMapOf<String, Int>()
        val jsonObject = JSONObject(json)
        
        jsonObject.keys().forEach { key ->
            map[key] = jsonObject.getInt(key)
        }
        
        return map
    }
    
    private fun saveAspectRatiosMap(prefs: SharedPreferences, map: Map<String, Int>) {
        val jsonObject = JSONObject()
        map.forEach { (key, value) ->
            jsonObject.put(key, value)
        }
        prefs.edit().putString(KEY_ASPECT_RATIOS, jsonObject.toString()).apply()
    }
    
    fun clearAllData(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
    }
    
    fun clearAspectRatios(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(KEY_ASPECT_RATIOS).apply()
    }
    
    fun saveLastPlayedIndex(context: Context, index: Int) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt(KEY_LAST_PLAYED_INDEX, index).apply()
    }
    
    fun getLastPlayedIndex(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_LAST_PLAYED_INDEX, 0)
    }
}
