package com.slopus.happy.stemcells

import android.content.SharedPreferences
import com.slopus.happy.config.AppVariant

/**
 * Whether a paired computer is a home of its own, Stem Cells, beside the
 * Happy account's session list (happy-ios `StemCellsPreference`). Only in
 * development and preview builds: a production build cannot reach it,
 * whatever is stored.
 */
class StemCellsPreference(private val variant: AppVariant, private val storage: SharedPreferences) {
    /** A computer can be home at all. */
    val isAvailable: Boolean get() = variant != AppVariant.Production

    /**
     * The Direct connection experiment: computers paired straight to this
     * phone. Off until turned on, on this phone alone; never on in production.
     */
    var directConnection: Boolean
        get() = isAvailable && storage.getBoolean(DIRECT_KEY, false)
        set(value) {
            if (isAvailable) storage.edit().putBoolean(DIRECT_KEY, value).apply()
        }

    /** Paired computers are offered: production keeps its computer page; elsewhere, while the experiment is on. */
    val showsComputers: Boolean get() = !isAvailable || directConnection

    /** The paired computer Stem Cells shows when a Happy account is the active one. */
    var computerId: String?
        get() = if (isAvailable) storage.getString(COMPUTER_KEY, null) else null
        set(value) {
            if (isAvailable) storage.edit().putString(COMPUTER_KEY, value).apply()
        }

    companion object {
        const val PREFS_NAME = "stem-cells"
        const val COMPUTER_KEY = "stemCells.computer"
        const val DIRECT_KEY = "stemCells.direct"
    }
}
