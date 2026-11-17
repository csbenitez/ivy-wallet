package com.ivy.googlesheets.di

import com.ivy.googlesheets.remote.GoogleSheetsRestApi
import com.ivy.googlesheets.remote.SheetsApi
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class GoogleSheetsModule {
    @Binds
    abstract fun bindSheetsApi(impl: GoogleSheetsRestApi): SheetsApi
}
