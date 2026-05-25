package com.example.fixbid.di

import com.example.fixbid.data.payment.VnpayConfig
import com.example.fixbid.data.payment.VnpayService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object PaymentModule {

    @Provides
    @Singleton
    fun provideVnpayConfig(): VnpayConfig = VnpayConfig()

    @Provides
    @Singleton
    fun provideVnpayService(config: VnpayConfig): VnpayService = VnpayService(config)
}
