@file:Suppress("unused")

package com.videoplayer.di

import android.content.Context
import androidx.room.Room
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter.Builder
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter.Companion
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter.EventListener
import androidx.media3.datasource.DefaultHttpDataSource
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.videoplayer.data.local.AppDatabase
import com.videoplayer.data.local.dao.ChannelDao
import com.videoplayer.util.Constants
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Main application module for dependency injection
 */
@Module
@InstallIn(SingletonComponent::class)
@Suppress("unused")
object AppModule {

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            Constants.DATABASE_NAME
        )
            .fallbackToDestructiveMigration()
            .build()
    }

    @Provides
    @Singleton
    fun provideChannelDao(database: AppDatabase): ChannelDao {
        return database.channelDao()
    }

    @Provides
    @Singleton
    fun provideGson(): Gson {
        return GsonBuilder()
            .create()
    }

    @Provides
    @Singleton
    fun provideBandwidthMeter(@ApplicationContext context: Context): DefaultBandwidthMeter {
        return DefaultBandwidthMeter.Builder(context.applicationContext)
            .setInitialBitrateEstimate(2_800_000L)
            .build()
    }

    @Provides
    @Singleton
    fun provideDefaultHttpDataSourceFactory(
        @ApplicationContext context: Context,
        bandwidthMeter: DefaultBandwidthMeter
    ): DefaultHttpDataSource.Factory {
        return DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(Constants.HTTP_CONNECT_TIMEOUT_MS)
            .setReadTimeoutMs(Constants.HTTP_READ_TIMEOUT_MS)
            .setAllowCrossProtocolRedirects(true)
            .setKeepPostFor302Redirects(true)
            .setUserAgent(Constants.DEFAULT_USER_AGENT)
            .setTransferListener(bandwidthMeter)
            .setDefaultRequestProperties(
                mapOf(
                    "Accept" to "*/*",
                    "Accept-Encoding" to "gzip, deflate",
                    "Connection" to "keep-alive"
                )
            )
    }
}
