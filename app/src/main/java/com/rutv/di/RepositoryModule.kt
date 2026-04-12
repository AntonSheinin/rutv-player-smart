package com.rutv.di

import com.rutv.data.repository.ChannelRepositoryImpl
import com.rutv.data.repository.EpgRepositoryImpl
import com.rutv.domain.repository.ChannelRepository
import com.rutv.domain.repository.EpgRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindChannelRepository(impl: ChannelRepositoryImpl): ChannelRepository

    @Binds
    @Singleton
    abstract fun bindEpgRepository(impl: EpgRepositoryImpl): EpgRepository
}
