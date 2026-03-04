package io.github.mwarevn.movingsimulation.xposed
import de.robv.android.xposed.XSharedPreferences
import io.github.mwarevn.movingsimulation.BuildConfig

class Xshare {

    private val xPref: XSharedPreferences by lazy {
        val pref = XSharedPreferences(BuildConfig.APPLICATION_ID, "${BuildConfig.APPLICATION_ID}_prefs")
        pref.makeWorldReadable()
        pref
    }

    private fun getPref(): XSharedPreferences {
        xPref.reload()
        return xPref
    }

    val isStarted: Boolean
        get() = getPref().getBoolean("start", false)

    val getLat: Double
        get() = getPref().getFloat("latitude", 45.0000000f).toDouble()

    val getLng: Double
        get() = getPref().getFloat("longitude", 0.0000000f).toDouble()

    val getBearing: Float
        get() = getPref().getFloat("bearing", 0f)

    val getSpeed: Float
        get() = getPref().getFloat("speed", 0f)

    val isHookedSystem: Boolean
        get() = getPref().getBoolean("system_hooked", false)

    val isRandomPosition: Boolean
        get() = getPref().getBoolean("random_position", false)

    val accuracy: String?
        get() = getPref().getString("accuracy_level", "10")
}
