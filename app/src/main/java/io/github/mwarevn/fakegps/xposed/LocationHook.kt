package io.github.mwarevn.fakegps.xposed

import android.annotation.SuppressLint
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.mwarevn.fakegps.BuildConfig
import io.github.mwarevn.fakegps.utils.SpeedSyncManager
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.util.*
import kotlin.math.cos

object LocationHook {

    private var newlat: Double = 21.0285
    private var newlng: Double = 105.8542
    private var bearing: Float = 0F
    private var speed: Float = 0F
    private var accuracy: Float = 10f
    private var isStartedCache: Boolean = false
    
    private val rand: Random = Random()
    private const val earth = 6378137.0
    private const val pi = 3.14159265359
    private val settings = Xshare()
    private var mLastUpdated: Long = 0

    @JvmStatic
    private val ignorePkg = arrayListOf(BuildConfig.APPLICATION_ID, "com.android.location.fused")

    private fun updateLocation(force: Boolean = false) {
        try {
            val now = System.currentTimeMillis()
            // Tối ưu cho GPS tĩnh: nếu không random thì cập nhật chậm lại (2s)
            val interval = if (settings.isRandomPosition) 300 else 2000
            
            if (force || now - mLastUpdated > interval) {
                settings.reload()
                mLastUpdated = now
                isStartedCache = settings.isStarted
                
                if (isStartedCache) {
                    val lat = settings.getLat
                    val lng = settings.getLng
                    
                    if (settings.isRandomPosition) {
                        val x = (rand.nextInt(16) - 8).toDouble()
                        val y = (rand.nextInt(16) - 8).toDouble()
                        val dlat = x / earth
                        val dlng = y / (earth * cos(pi * lat / 180.0))
                        newlat = lat + (dlat * 180.0 / pi)
                        newlng = lng + (dlng * 180.0 / pi)
                    } else {
                        newlat = lat
                        newlng = lng
                    }
                    
                    accuracy = settings.accuracy?.toFloatOrNull() ?: 12f
                    
                    // Lấy Bearing và Speed thực tế từ SpeedSyncManager (nếu đang chạy route)
                    val syncedBearing = SpeedSyncManager.getBearing()
                    bearing = if (syncedBearing != 0f) syncedBearing else settings.getBearing
                    
                    val syncedSpeed = SpeedSyncManager.getActualSpeed()
                    speed = if (syncedSpeed > 0f) {
                        SpeedSyncManager.speedKmhToMs(syncedSpeed)
                    } else {
                        settings.getSpeed
                    }
                }
            }
        } catch (e: Exception) { }
    }
    
    private fun setLocationFields(location: Location) {
        try {
            location.latitude = newlat
            location.longitude = newlng
            location.bearing = bearing
            location.speed = speed
            location.accuracy = accuracy
            location.time = System.currentTimeMillis()
            location.elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                location.verticalAccuracyMeters = 0.5f
                location.bearingAccuracyDegrees = 5f
                location.speedAccuracyMetersPerSecond = 0.1f
            }

            // Ép xóa cờ Mock bằng cách set mask nội bộ (Full mask = 255)
            try {
                HiddenApiBypass.invoke(Location::class.java, location, "setFieldsMask", 255)
            } catch (e: Throwable) {
                try {
                    val m = Location::class.java.getDeclaredMethod("setFieldsMask", Int::class.javaPrimitiveType)
                    m.isAccessible = true
                    m.invoke(location, 255)
                } catch (e2: Throwable) {}
            }
            
            try {
                HiddenApiBypass.invoke(location.javaClass, location, "setIsFromMockProvider", false)
            } catch (e: Exception) { }
            
            val extras = location.extras ?: Bundle()
            extras.remove("mockLocation")
            extras.putBoolean("mockLocation", false)
            extras.putInt("satellites", 12)
            location.extras = extras
        } catch (e: Throwable) { }
    }

    @SuppressLint("NewApi")
    fun initHooks(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName == "android") {
            updateLocation(true)
            if (isStartedCache && settings.isHookedSystem) {
                hookSystemServer(lpparam)
            }
            return
        }

        if (ignorePkg.contains(lpparam.packageName)) return

        // Hook GMS Core - Cực kỳ quan trọng cho các app hiện đại
        if (lpparam.packageName == "com.google.android.gms") {
            hookGmsCore(lpparam)
        }

        hookApplicationLevel(lpparam)
    }

    private fun hookSystemServer(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val lmsClassName = if (Build.VERSION.SDK_INT >= 34) 
                "com.android.server.location.LocationManagerService" 
                else "com.android.server.LocationManagerService"
            val lmsClass = XposedHelpers.findClass(lmsClassName, lpparam.classLoader)
            
            XposedBridge.hookAllMethods(lmsClass, "getLastLocation", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    updateLocation()
                    if (isStartedCache) {
                        val loc = Location(LocationManager.GPS_PROVIDER)
                        setLocationFields(loc)
                        param.result = loc
                    }
                }
            })

            // Hook báo cáo vị trí (Android 12+)
            try {
                val lpmClass = XposedHelpers.findClass("com.android.server.location.provider.LocationProviderManager", lpparam.classLoader)
                XposedBridge.hookAllMethods(lpmClass, "onReportLocation", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        updateLocation()
                        if (isStartedCache) {
                            val arg = param.args[0] ?: return
                            if (arg is Location) {
                                setLocationFields(arg)
                            } else if (arg.javaClass.name.contains("LocationResult")) {
                                val locations = XposedHelpers.callMethod(arg, "getLocations") as? List<*>
                                locations?.forEach { (it as? Location)?.let { loc -> setLocationFields(loc) } }
                            }
                        }
                    }
                })
            } catch (e: Throwable) { }
        } catch (e: Throwable) { }
    }

    private fun hookGmsCore(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val callbackClass = XposedHelpers.findClassIfExists("com.google.android.gms.location.internal.IFusedLocationProviderCallback.Stub", lpparam.classLoader)
            if (callbackClass != null) {
                XposedBridge.hookAllMethods(callbackClass, "onLocationResult", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        updateLocation()
                        if (isStartedCache) {
                            val result = param.args[0] ?: return
                            try {
                                val locations = XposedHelpers.callMethod(result, "getLocations") as? List<*>
                                locations?.forEach { (it as? Location)?.let { loc -> setLocationFields(loc) } }
                            } catch (e: Throwable) { }
                        }
                    }
                })
            }
        } catch (e: Throwable) { }
    }

    private fun hookApplicationLevel(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val locClass = Location::class.java
            val lmClass = XposedHelpers.findClass("android.location.LocationManager", lpparam.classLoader)

            // Getters cơ bản
            XposedHelpers.findAndHookMethod(locClass, "getLatitude", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) { if (isHookActive()) param.result = newlat }
            })
            XposedHelpers.findAndHookMethod(locClass, "getLongitude", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) { if (isHookActive()) param.result = newlng }
            })
            XposedHelpers.findAndHookMethod(locClass, "isFromMockProvider", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) { if (isHookActive()) param.result = false }
            })

            // Hook Location.set() - Cực kỳ quan trọng vì app thường copy object
            XposedHelpers.findAndHookMethod(locClass, "set", Location::class.java, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (isHookActive()) {
                        val src = param.args[0] as? Location ?: return
                        setLocationFields(src)
                    }
                }
            })

            // Hook LocationManager level
            XposedBridge.hookAllMethods(lmClass, "getLastKnownLocation", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (isHookActive()) {
                        val loc = Location(param.args[0]?.toString() ?: LocationManager.GPS_PROVIDER)
                        setLocationFields(loc)
                        param.result = loc
                    }
                }
            })

            XposedBridge.hookAllMethods(lmClass, "requestLocationUpdates", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    updateLocation()
                    if (!isStartedCache) return
                    val listenerIdx = param.args.indexOfFirst { it is android.location.LocationListener }
                    if (listenerIdx != -1) {
                        val original = param.args[listenerIdx] as android.location.LocationListener
                        if (original.javaClass.name.contains("io.github.mwarevn")) return
                        
                        param.args[listenerIdx] = object : android.location.LocationListener {
                            override fun onLocationChanged(location: Location) {
                                updateLocation()
                                setLocationFields(location)
                                try { original.onLocationChanged(location) } catch (e: Throwable) {}
                            }

                            // Thêm hỗ trợ cho API mới nhận danh sách Location
                            override fun onLocationChanged(locations: List<Location>) {
                                locations.forEach { onLocationChanged(it) }
                            }
                            
                            @Suppress("DEPRECATION")
                            override fun onStatusChanged(p: String?, s: Int, e: Bundle?) = try { original.onStatusChanged(p, s, e) } catch (e: Throwable) {}
                            
                            override fun onProviderEnabled(p: String) = try { original.onProviderEnabled(p) } catch (e: Throwable) {}
                            override fun onProviderDisabled(p: String) = try { original.onProviderDisabled(p) } catch (e: Throwable) {}
                        }
                    }
                }
            })
            
            try {
                val wmClass = XposedHelpers.findClass("android.net.wifi.WifiManager", lpparam.classLoader)
                XposedHelpers.findAndHookMethod(wmClass, "getScanResults", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (isHookActive()) param.result = emptyList<android.net.wifi.ScanResult>()
                    }
                })
            } catch (e: Throwable) {}

        } catch (e: Throwable) { }
    }

    private fun isHookActive(): Boolean {
        updateLocation()
        return isStartedCache
    }
}
