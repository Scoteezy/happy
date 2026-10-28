package com.slopus.happy.config

import com.slopus.happy.BuildConfig

/** Which build this is (happy-ios `AppVariant`): debug is development, preview is preview, release is production. */
enum class AppVariant {
    Development, Preview, Production;

    companion object {
        val current: AppVariant = when (BuildConfig.BUILD_TYPE) {
            "debug" -> Development
            "preview" -> Preview
            else -> Production
        }
    }
}
