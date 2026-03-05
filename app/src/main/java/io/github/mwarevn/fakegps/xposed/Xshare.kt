package io.github.mwarevn.fakegps.xposed

import io.github.mwarevn.fakegps.BuildConfig
import de.robv.android.xposed.XSharedPreferences

class Xshare {

    private val xPref: XSharedPreferences by lazy {
        val pref = XSharedPreferences(BuildConfig.APPLICATION_ID, "${BuildConfig.APPLICATION_ID}_prefs")
        pref.makeWorldReadable()
        pref
    }

    /**
     * Chỉ nạp lại file từ ổ cứng khi module yêu cầu (có kiểm soát thời gian)
     */
    fun reload() {
        xPref.reload()
    }

    val isStarted: Boolean
        get() = xPref.getBoolean("start", false)

    val getLat: Double
        get() = xPref.getFloat("latitude", 21.0285f).toDouble()

    val getLng: Double
        get() = xPref.getFloat("longitude", 105.8542f).toDouble()

    val getBearing: Float
        get() = xPref.getFloat("bearing", 0f)

    val getSpeed: Float
        get() = xPref.getFloat("speed", 0f)

    val isHookedSystem: Boolean
        get() = xPref.getBoolean("system_hooked", false)

    val isRandomPosition: Boolean
        get() = xPref.getBoolean("random_position", false)

    val accuracy: String?
        get() = xPref.getString("accuracy_level", "12")
}
