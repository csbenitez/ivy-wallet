package com.ivy.sharedwallet.di

import com.ivy.sharedwallet.data.DocumentFileTransportProvider
import com.ivy.sharedwallet.data.LedgerTransportProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

@Module
@InstallIn(SingletonComponent::class)
interface SharedWalletModule {
    @Binds
    @IntoSet
    fun bindDocumentFileTransport(impl: DocumentFileTransportProvider): LedgerTransportProvider
}
