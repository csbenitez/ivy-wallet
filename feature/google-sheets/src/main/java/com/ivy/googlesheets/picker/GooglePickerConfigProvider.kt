package com.ivy.googlesheets.picker

import com.ivy.googlesheets.BuildConfig
import javax.inject.Inject

class GooglePickerConfigProvider @Inject constructor() {
    fun config(): GooglePickerConfig = GooglePickerConfig(
        apiKey = BuildConfig.GOOGLE_PICKER_API_KEY,
        appId = BuildConfig.GOOGLE_CLOUD_PROJECT_NUMBER,
    )
}
