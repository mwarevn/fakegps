package io.github.mwarevn.fakegps.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.location.Geocoder
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.mwarevn.fakegps.R
import io.github.mwarevn.fakegps.network.OsrmClient
import io.github.mwarevn.fakegps.network.RoutingService
import io.github.mwarevn.fakegps.network.VehicleType
import io.github.mwarevn.fakegps.utils.PrefManager
import io.github.mwarevn.fakegps.utils.RouteSimulator
import io.github.mwarevn.fakegps.utils.ext.showToast
import kotlinx.coroutines.*
import java.util.*

/**
 * Clean redesigned MapActivity with Google Maps-like UX
 * Optimized for performance and reliability
 */
class MapActivity : BaseMapActivity(), OnMapReadyCallback, GoogleMap.OnMapClickListener, GoogleMap.OnMarkerDragListener {

    companion object {
        private const val LOCATION_PERMISSION_REQUEST_CODE = 99
        const val ROUTE_COLOR = "#006eff"
        const val ROUTE_WIDTH = 18f
        const val COMPLETED_ROUTE_COLOR = "#909090"
        const val FAKE_LOCATION_STROKE_COLOR = 0xFFFF4500.toInt()
        const val FAKE_LOCATION_FILL_COLOR = 0x50FF4500.toInt()
        const val FAKE_LOCATION_CENTER_COLOR = 0xFFFF4500.toInt()
        const val CIRCLE_RADIUS = 25.0
        const val CIRCLE_STROKE_WIDTH = 4f
        const val CIRCLE_Z_INDEX = 128f
        const val CENTER_DOT_Z_INDEX = 129f
        private const val CAMERA_UPDATE_INTERVAL_MS = 1000L
    }

    private lateinit var mMap: GoogleMap
    private var destMarker: Marker? = null
    private var startMarker: Marker? = null
    private var routePolyline: Polyline? = null
    private var fakeLocationCenterDot: Marker? = null
    private var routeSimulator: RouteSimulator? = null
    private var routePoints: List<LatLng> = emptyList()
    private var isDriving = false
    private var isPaused = false
    private var currentSpeed = 52.0
    private var totalRouteDistanceKm = 0.0
    private var traveledDistanceKm = 0.0
    private var lastDistancePosition: LatLng? = null
    private var fakeLocationCircle: Circle? = null
    private var completedPolyline: Polyline? = null
    private val completedPathPoints: MutableList<LatLng> = mutableListOf()
    private var currentPositionIndex = 0
    private var isGpsSet = false
    private var currentFakeLocationPos: LatLng? = null
    private var lastJoystickLat = 0.0
    private var lastJoystickLon = 0.0
    private var lastCameraUpdateTime = 0L
    private var isCameraFollowing = true
    private var lastGpsUpdateTime = 0L
    private var previousLocation: LatLng? = null
    private var currentNavigationPosition: LatLng? = null

    private enum class AppMode { SEARCH, ROUTE_PLAN, NAVIGATION }
    private var currentMode = AppMode.SEARCH
    private var hasSelectedStartPoint = false

    private val routeCache = mutableMapOf<String, List<LatLng>>()
    private val CACHE_EXPIRY_MS = 30 * 60 * 1000L

    private data class NavigationState(
        val routePoints: List<LatLng>,
        val currentSpeed: Double,
        val isPaused: Boolean,
        val currentPositionIndex: Int,
        val completedPathPoints: List<LatLng>
    )
    private var backgroundNavigationState: NavigationState? = null

    private val prefChangeListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "latitude" || key == "longitude") {
            if (!isDriving && isGpsSet) {
                syncJoystickPosition()
            }
        }
    }

    private val routingService: RoutingService get() = OsrmClient.createRoutingService(PrefManager.mapBoxApiKey)
    private var currentVehicleType: VehicleType
        get() = VehicleType.fromString(PrefManager.vehicleType)
        set(value) { PrefManager.vehicleType = value.name }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.doGetUserDetails() // Ensure favorites are loaded early
        observeFavorites()
    }

    private fun observeFavorites() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.allFavList.collect {
                    updateAddFavoriteButtonVisibility()
                }
            }
        }
    }

    override fun initializeMap() {
        (supportFragmentManager.findFragmentById(R.id.map) as? SupportMapFragment 
            ?: SupportMapFragment.newInstance().also {
                supportFragmentManager.beginTransaction().replace(R.id.map, it).commit()
            }).getMapAsync(this)
    }

    override fun getActivityInstance() = this
    override fun hasMarker() = destMarker != null || startMarker != null

    override fun moveMapToNewLocation(moveNewLocation: Boolean, shouldMark: Boolean) {
        if (moveNewLocation && ::mMap.isInitialized) {
            val newPos = LatLng(lat, lon)
            mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(newPos, 16f))
            
            // Only mark if explicitly requested (e.g., from favorite list)
            if (shouldMark && currentMode == AppMode.SEARCH) {
                setDestinationMarker(newPos)
            }
        }
    }

    @SuppressLint("MissingPermission")
    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap
        if (checkPermissions()) {
            mMap.isMyLocationEnabled = true
            getLastLocation()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), LOCATION_PERMISSION_REQUEST_CODE)
        }

        mMap.uiSettings.apply {
            isMyLocationButtonEnabled = false
            isZoomControlsEnabled = false
            isCompassEnabled = true
        }

        mMap.setOnMapClickListener(this)
        mMap.setOnMarkerDragListener(this)
        mMap.setOnCameraMoveListener { PrefManager.cameraBearing = mMap.cameraPosition.bearing }

        setupButtons()
        setupSearchBoxes()
        restoreFakeLocationMarker()
    }

    private fun syncJoystickPosition() {
        val currentLat = PrefManager.getLat
        val currentLon = PrefManager.getLng
        // Use a threshold to avoid unnecessary updates from slight precision variations
        if (Math.abs(currentLat - lastJoystickLat) > 1e-7 || Math.abs(currentLon - lastJoystickLon) > 1e-7) {
            lastJoystickLat = currentLat
            lastJoystickLon = currentLon
            val newPos = LatLng(currentLat, currentLon)
            lifecycleScope.launch(Dispatchers.Main) {
                fakeLocationCircle?.center = newPos
                fakeLocationCenterDot?.position = newPos
                currentFakeLocationPos = newPos
                updateActionButtonsVisibility()
            }
        }
    }

    override fun onMapClick(position: LatLng) {
        when (currentMode) {
            AppMode.SEARCH -> setDestinationMarker(position)
            AppMode.ROUTE_PLAN -> if (!hasSelectedStartPoint) setStartMarkerWithSelection(position)
            AppMode.NAVIGATION -> showToast("Không thể thay đổi khi đang di chuyển")
        }
    }

    private fun setupSearchBoxes() {
        binding.destinationSearch.setOnEditorActionListener { v, _, _ ->
            v.text.toString().trim().takeIf { it.isNotEmpty() }?.let { lifecycleScope.launch { searchLocation(it) { setDestinationMarker(it) } } }
            true
        }

        binding.startSearch.setOnEditorActionListener { v, _, _ ->
            v.text.toString().trim().takeIf { it.isNotEmpty() }?.let { lifecycleScope.launch { searchLocation(it) { setStartMarkerWithSelection(it) } } }
            true
        }
    }

    override fun setupButtons() {
        isGpsSet = viewModel.isStarted
        updateSetLocationButton()

        binding.actionButton.setOnClickListener {
            when (currentMode) {
                AppMode.SEARCH -> if (destMarker != null) enterRoutePlanMode() else showToast("Vui lòng chọn điểm đến")
                AppMode.ROUTE_PLAN -> if (startMarker != null && destMarker != null) startNavigation() else showToast("Vui lòng chọn đủ điểm đi và đến")
                AppMode.NAVIGATION -> stopNavigation()
            }
        }

        binding.getlocation.setOnClickListener { getLastLocation() }
        binding.getFakeLocation.setOnClickListener { currentFakeLocationPos?.let { mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(it, 15f)) } }

        binding.setLocationButton.setOnClickListener {
            if (isGpsSet) {
                fakeLocationCircle?.remove(); fakeLocationCircle = null
                fakeLocationCenterDot?.remove(); fakeLocationCenterDot = null
                isGpsSet = false; currentFakeLocationPos = null
                viewModel.update(false, 0.0, 0.0)
            } else {
                val pos = destMarker?.position ?: mMap.cameraPosition.target
                isGpsSet = true; currentFakeLocationPos = pos
                viewModel.update(true, pos.latitude, pos.longitude)
                updateFakeLocationMarker(pos)
            }
            updateSetLocationButton()
            updateActionButtonsVisibility()
        }

        binding.replaceLocationButton.setOnClickListener {
            destMarker?.position?.let { pos ->
                currentFakeLocationPos = pos
                viewModel.update(true, pos.latitude, pos.longitude)
                updateFakeLocationMarker(pos)
                updateReplaceLocationButtonVisibility()
                updateActionButtonsVisibility()
                showToast("Đã cập nhật vị trí Fake GPS")
            }
        }

        binding.addFavoriteButton.setOnClickListener {
            destMarker?.position?.let { showAddFavoriteDialog(it) }
        }

        binding.cameraFollowToggle.setOnClickListener { if (isDriving) { isCameraFollowing = !isCameraFollowing; updateCameraFollowButton() } }

        binding.speedSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                currentSpeed = if (isDriving && value <= 0) 1.0 else value.toDouble()
                updateSpeedLabel(currentSpeed)
                io.github.mwarevn.fakegps.utils.SpeedSyncManager.setSavedSpeed(currentSpeed.toFloat())
                if (isDriving && !isPaused) routeSimulator?.setSpeedKmh(currentSpeed)
            }
        }

        binding.autoCurveSpeedCheckbox.isChecked = PrefManager.autoCurveSpeed
        binding.autoCurveSpeedCheckbox.setOnCheckedChangeListener { _, isChecked -> PrefManager.autoCurveSpeed = isChecked }

        binding.pauseButton.setOnClickListener { if (isDriving && !isPaused) { isPaused = true; routeSimulator?.pause(); updateNavControlButtons() } }
        binding.resumeButton.setOnClickListener { if (isDriving && isPaused) { isPaused = false; routeSimulator?.resume(); updateNavControlButtons() } }
        binding.stopButton.setOnClickListener { if (isDriving && isPaused) onStopNavigationEarly() }

        binding.completedFinishButton.setOnClickListener { onFinishNavigation() }
        binding.completedRestartButton.setOnClickListener { onRestartNavigation() }

        binding.useCurrentLocationContainer.setOnClickListener {
            currentFakeLocationPos?.let { setStartMarkerWithSelection(it); updateUseCurrentLocationButtonVisibility() }
        }

        binding.routeRetryButton.setOnClickListener { drawRoute() }
        binding.routeErrorCancelButton.setOnClickListener { cancelRoutePlan() }
        binding.cancelRouteButton.setOnClickListener { 
            if (currentMode == AppMode.SEARCH) clearDestinationMarker() 
            else cancelRoutePlan() 
        }
        binding.swapLocationsButton.setOnClickListener { swapStartAndDestination() }
        binding.navControlsToggle.setOnClickListener { toggleNavigationControls() }
        
        restoreNavigationControlsState()
    }

    private fun showAddFavoriteDialog(latLng: LatLng) {
        val view = layoutInflater.inflate(R.layout.dialog, null)
        val editText = view.findViewById<EditText>(R.id.search_edittxt)
        
        lifecycleScope.launch {
            val address = getAddressFromLocation(latLng)
            editText.setText(address)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Thêm vào yêu thích")
            .setView(view)
            .setPositiveButton("Thêm") { _, _ ->
                val name = editText.text.toString().trim()
                if (name.isNotEmpty()) {
                    viewModel.storeFavorite(name, latLng.latitude, latLng.longitude)
                    showToast("Đã lưu vào yêu thích")
                }
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    private fun updateNavControlButtons() {
        binding.pauseButton.visibility = if (isPaused) View.GONE else View.VISIBLE
        binding.resumeButton.visibility = if (isPaused) View.VISIBLE else View.GONE
        binding.stopButton.visibility = if (isPaused) View.VISIBLE else View.GONE
    }

    private suspend fun searchLocation(query: String, onFound: (LatLng) -> Unit) {
        withContext(Dispatchers.IO) {
            try {
                val addresses = Geocoder(this@MapActivity, Locale.getDefault()).getFromLocationName(query, 1)
                if (!addresses.isNullOrEmpty()) {
                    val pos = LatLng(addresses[0].latitude, addresses[0].longitude)
                    withContext(Dispatchers.Main) { onFound(pos) }
                } else {
                    withContext(Dispatchers.Main) { showToast("Không tìm thấy địa điểm") }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { showToast("Lỗi tìm kiếm: ${e.message}") }
            }
        }
    }

    private suspend fun getAddressFromLocation(latLng: LatLng): String = withContext(Dispatchers.IO) {
        try {
            val addresses = Geocoder(this@MapActivity, Locale.getDefault()).getFromLocation(latLng.latitude, latLng.longitude, 1)
            if (!addresses.isNullOrEmpty()) {
                val addr = addresses[0]
                val parts = mutableListOf<String>()
                addr.featureName?.takeIf { it.isNotBlank() && !it.matches(Regex("^[\\d,\\.\\s]+$")) }?.let { parts.add(it) }
                val street = "${addr.subThoroughfare ?: ""} ${addr.thoroughfare ?: ""}".trim()
                if (street.isNotBlank()) parts.add(street)
                addr.subLocality?.let { parts.add(it) }
                addr.locality?.let { parts.add(it) }
                addr.adminArea?.let { parts.add(it) }
                if (parts.isNotEmpty()) return@withContext parts.distinct().joinToString(", ")
            }
            String.format("%.6f, %.6f", latLng.latitude, latLng.longitude)
        } catch (e: Exception) {
            String.format("%.6f, %.6f", latLng.latitude, latLng.longitude)
        }
    }

    private fun setDestinationMarker(position: LatLng) {
        destMarker?.remove()
        destMarker = mMap.addMarker(MarkerOptions().position(position).title("Điểm đến").draggable(true))
        mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(position, 16f))
        lifecycleScope.launch { binding.destinationSearch.setText(getAddressFromLocation(position)) }
        if (currentMode == AppMode.SEARCH) {
            updateActionButtonsVisibility()
        }
        routePolyline?.remove(); routePolyline = null
        updateSwapButtonVisibility()
        updateReplaceLocationButtonVisibility()
    }

    private fun updateActionButtonsVisibility() {
        if (currentMode != AppMode.SEARCH) return

        val markerPos = destMarker?.position
        val fakePos = currentFakeLocationPos

        // Use a tiny epsilon to account for Float vs Double precision variations
        val isAtFakeLocation = isGpsSet && markerPos != null && fakePos != null && 
                               Math.abs(markerPos.latitude - fakePos.latitude) < 1e-6 && 
                               Math.abs(markerPos.longitude - fakePos.longitude) < 1e-6

        if (isAtFakeLocation) {
            binding.actionButton.visibility = View.GONE
            binding.cancelRouteButton.visibility = View.GONE
            // Truly remove marker if it matches current fake location
            destMarker?.remove()
            destMarker = null
            binding.destinationSearch.text.clear()
        } else if (destMarker == null) {
            binding.actionButton.visibility = View.GONE
            binding.cancelRouteButton.visibility = View.GONE
        } else {
            binding.actionButton.apply {
                text = "Chỉ đường"
                visibility = View.VISIBLE
                setIconResource(R.drawable.ic_navigation)
            }
            binding.cancelRouteButton.apply {
                text = "Huỷ"
                visibility = View.VISIBLE
            }
            destMarker?.isVisible = true
        }
        updateAddFavoriteButtonVisibility()
    }

    private fun updateAddFavoriteButtonVisibility() {
        val dest = destMarker?.position
        if (currentMode == AppMode.SEARCH && dest != null) {
            // Check if this location is already in favorites (within ~15m for precision)
            val isFavorite = viewModel.allFavList.value.any { 
                val results = FloatArray(1)
                android.location.Location.distanceBetween(it.lat ?: 0.0, it.lng ?: 0.0, dest.latitude, dest.longitude, results)
                results[0] < 15
            }
            binding.addFavoriteButton.visibility = if (isFavorite) View.GONE else View.VISIBLE
        } else {
            binding.addFavoriteButton.visibility = View.GONE
        }
    }

    private fun clearDestinationMarker() {
        destMarker?.remove(); destMarker = null
        binding.destinationSearch.text.clear()
        if (currentMode == AppMode.ROUTE_PLAN) cancelRoutePlan()
        binding.actionButton.visibility = View.GONE
        binding.cancelRouteButton.visibility = View.GONE
        binding.addFavoriteButton.visibility = View.GONE
        updateSwapButtonVisibility()
        updateReplaceLocationButtonVisibility()
    }

    private fun setStartMarker(position: LatLng) {
        startMarker?.remove()
        startMarker = mMap.addMarker(MarkerOptions().position(position).title("Điểm bắt đầu").icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN)).draggable(true))
        lifecycleScope.launch { binding.startSearch.setText(getAddressFromLocation(position)) }
        destMarker?.let { 
            val bounds = LatLngBounds.builder().include(position).include(it.position).build()
            mMap.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 150))
            drawRoute()
        }
        updateSwapButtonVisibility()
        updateUseCurrentLocationButtonVisibility()
    }

    private fun setStartMarkerWithSelection(position: LatLng) {
        setStartMarker(position)
        hasSelectedStartPoint = true
    }

    private fun updateSwapButtonVisibility() {
        binding.swapButtonContainer.visibility = if (startMarker != null && destMarker != null && currentMode != AppMode.NAVIGATION) View.VISIBLE else View.GONE
    }

    private fun enterRoutePlanMode() {
        currentMode = AppMode.ROUTE_PLAN
        hasSelectedStartPoint = false
        binding.startSearchContainer.visibility = View.VISIBLE
        binding.startSearch.requestFocus()
        binding.cancelRouteButton.apply { text = "Huỷ"; visibility = View.VISIBLE }
        binding.actionButton.visibility = View.GONE
        binding.addFavoriteButton.visibility = View.GONE
        updateMarkersDraggableState()
        updateUseCurrentLocationButtonVisibility()
        updateReplaceLocationButtonVisibility()
    }

    private fun updateUseCurrentLocationButtonVisibility() {
        if (currentMode != AppMode.ROUTE_PLAN || !isGpsSet || currentFakeLocationPos == null) {
            binding.useCurrentLocationContainer.visibility = View.GONE
            return
        }
        val startPos = startMarker?.position
        val isSame = startPos != null && Math.abs(startPos.latitude - currentFakeLocationPos!!.latitude) < 1e-4 && Math.abs(startPos.longitude - currentFakeLocationPos!!.longitude) < 1e-4
        binding.useCurrentLocationContainer.visibility = if (!isSame) View.VISIBLE else View.GONE
        if (!isSame) binding.useCurrentLocationText.text = "Dùng vị trí hiện tại (${String.format("%.4f", currentFakeLocationPos!!.latitude)}, ${String.format("%.4f", currentFakeLocationPos!!.longitude)})"
    }

    private fun updateMarkersDraggableState() {
        destMarker?.isDraggable = currentMode != AppMode.NAVIGATION
        startMarker?.isDraggable = currentMode == AppMode.ROUTE_PLAN
    }

    private fun swapStartAndDestination() {
        if (startMarker == null || destMarker == null) return
        val sPos = startMarker!!.position; val dPos = destMarker!!.position
        val sText = binding.startSearch.text.toString(); val dText = binding.destinationSearch.text.toString()
        startMarker?.position = dPos; destMarker?.position = sPos
        binding.startSearch.setText(dText); binding.destinationSearch.setText(sText)
        if (currentMode == AppMode.ROUTE_PLAN) drawRoute()
    }

    private fun drawRoute() {
        val start = startMarker?.position ?: return
        val dest = destMarker?.position ?: return
        
        binding.actionButton.visibility = View.GONE
        binding.routeLoadingCard.visibility = View.VISIBLE
        binding.routeLoadingProgressText.text = "Đang tìm tuyến đường..."

        lifecycleScope.launch {
            try {
                val key = "${start.latitude},${start.longitude}-${dest.latitude},${dest.longitude}-${currentVehicleType.name}"
                routePoints = routeCache[key] ?: routingService.getRoute(start.latitude, start.longitude, dest.latitude, dest.longitude, currentVehicleType)?.routes?.get(0).also { 
                    if (it != null) routeCache[key] = it 
                } ?: emptyList()

                if (routePoints.isEmpty()) {
                    showRouteErrorUI()
                    return@launch
                }

                routePolyline?.remove()
                routePolyline = mMap.addPolyline(PolylineOptions().addAll(routePoints).color(Color.parseColor(ROUTE_COLOR)).width(ROUTE_WIDTH))
                binding.routeLoadingCard.visibility = View.GONE
                binding.actionButton.apply { text = "Bắt đầu"; setIconResource(R.drawable.ic_navigation); visibility = View.VISIBLE }
                binding.cancelRouteButton.apply { text = "Huỷ"; visibility = View.VISIBLE }
                binding.addFavoriteButton.visibility = View.GONE
            } catch (e: Exception) {
                showRouteErrorUI()
            }
        }
    }

    private fun showRouteErrorUI() {
        binding.routeLoadingCard.visibility = View.GONE
        binding.routeErrorCard.visibility = View.VISIBLE
        binding.actionButton.visibility = View.GONE
        binding.cancelRouteButton.apply { text = "Huỷ"; visibility = View.VISIBLE }
        binding.addFavoriteButton.visibility = View.GONE
    }

    private fun cancelRoutePlan() {
        routePolyline?.remove(); routePolyline = null
        startMarker?.remove(); startMarker = null
        binding.startSearch.text.clear()
        binding.startSearchContainer.visibility = View.GONE
        binding.useCurrentLocationContainer.visibility = View.GONE
        binding.routeLoadingCard.visibility = View.GONE
        binding.routeErrorCard.visibility = View.GONE
        currentMode = AppMode.SEARCH
        hasSelectedStartPoint = false
        // Back to state 1: Keep destination, show "Chỉ đường" and "Huỷ"
        updateActionButtonsVisibility()
        binding.searchCard.visibility = View.VISIBLE
        updateSwapButtonVisibility()
    }

    private fun startNavigation() {
        if (routePoints.isEmpty()) return
        isDriving = true; isPaused = false; currentMode = AppMode.NAVIGATION
        currentPositionIndex = 0; completedPathPoints.clear(); completedPathPoints.add(routePoints.first())
        
        fakeLocationCircle?.remove(); fakeLocationCircle = null
        fakeLocationCenterDot?.remove(); fakeLocationCenterDot = null

        binding.actionButton.visibility = View.GONE
        binding.navigationControlsCard.visibility = View.VISIBLE
        binding.cancelRouteButton.visibility = View.GONE
        binding.addFavoriteButton.visibility = View.GONE
        binding.searchCard.visibility = View.GONE
        binding.swapButtonContainer.visibility = View.GONE
        binding.getFakeLocation.visibility = View.GONE
        binding.setLocationButton.visibility = View.GONE
        binding.replaceLocationButton.visibility = View.GONE
        
        updateNavigationAddresses()
        updateNavControlButtons()
        isCameraFollowing = true
        binding.cameraFollowToggle.visibility = View.VISIBLE
        updateCameraFollowButton()

        totalRouteDistanceKm = calculateTotalRouteDistance(routePoints)
        traveledDistanceKm = 0.0; lastDistancePosition = null
        updateDistanceLabel()

        fakeLocationCircle = createStationaryLocationCircle(routePoints.first())
        routeSimulator = RouteSimulator(routePoints, currentSpeed, 300L, lifecycleScope)
        routeSimulator?.start(
            onPosition = { pos -> runOnUiThread { handleNavigationUpdate(pos) } },
            onComplete = { runOnUiThread { onNavigationComplete() } }
        )
    }

    private fun handleNavigationUpdate(pos: LatLng) {
        val time = System.currentTimeMillis()
        val bearing = previousLocation?.let { calculateBearing(it, pos) } ?: 0f
        val speedMs = (currentSpeed * 1000.0 / 3600.0).toFloat()
        
        viewModel.update(true, pos.latitude, pos.longitude, bearing, speedMs)
        currentNavigationPosition = pos
        updateTraveledDistance(pos)
        
        try {
            val actual = io.github.mwarevn.fakegps.utils.SpeedSyncManager.getActualSpeed()
            if (actual > 0.01f) binding.speedLabel.text = "${currentSpeed.toInt()} / ${actual.toInt()} km/h"
        } catch (e: Exception) {}

        fakeLocationCircle?.center = pos
        fakeLocationCenterDot?.position = pos
        updateCompletedPath(pos)

        if (isCameraFollowing && time - lastCameraUpdateTime >= CAMERA_UPDATE_INTERVAL_MS) {
            mMap.animateCamera(CameraUpdateFactory.newLatLng(pos))
            lastCameraUpdateTime = time
        }
        previousLocation = pos; currentPositionIndex++
    }

    /**
     * Calculate bearing between two lat/lng points
     */
    private fun calculateBearing(start: LatLng, end: LatLng): Float {
        val lat1 = Math.toRadians(start.latitude)
        val lat2 = Math.toRadians(end.latitude)
        val deltaLng = Math.toRadians(end.longitude - start.longitude)
        val y = Math.sin(deltaLng) * Math.cos(lat2)
        val x = Math.cos(lat1) * Math.sin(lat2) - Math.sin(lat1) * Math.cos(lat2) * Math.cos(deltaLng)
        var bearing = Math.toDegrees(Math.atan2(y, x))
        if (bearing < 0) bearing += 360.0
        return bearing.toFloat()
    }

    private fun updateNavigationAddresses() {
        lifecycleScope.launch {
            val sPos = startMarker?.position; val dPos = destMarker?.position
            if (sPos != null && dPos != null) {
                binding.navFromAddress.text = "• ${getAddressFromLocation(sPos)}"
                binding.navToAddress.text = "• ${getAddressFromLocation(dPos)}"
            }
        }
    }

    private fun stopNavigation() {
        routeSimulator?.stop(); routeSimulator = null
        isDriving = false; isPaused = false
        fakeLocationCircle?.remove(); fakeLocationCircle = null
        fakeLocationCenterDot?.remove(); fakeLocationCenterDot = null
        completedPolyline?.remove(); completedPolyline = null
        binding.searchCard.visibility = View.VISIBLE
        binding.navigationControlsCard.visibility = View.GONE
        binding.cameraFollowToggle.visibility = View.GONE
        if (isGpsSet) binding.getFakeLocation.visibility = View.VISIBLE
        binding.setLocationButton.visibility = View.VISIBLE
        resetToSearchMode()
    }

    private fun updateSetLocationButton() {
        // FIXED: Don't re-assign isGpsSet from viewModel here because it's async
        // Trust the local isGpsSet which was just updated in the click listener
        binding.setLocationButton.setImageResource(if (isGpsSet) R.drawable.ic_gps_off else R.drawable.ic_location_on)
        binding.getFakeLocation.visibility = if (isGpsSet) View.VISIBLE else View.GONE
        updateReplaceLocationButtonVisibility()
    }

    private fun updateSpeedLabel(speed: Double) {
        try {
            val actual = io.github.mwarevn.fakegps.utils.SpeedSyncManager.getActualSpeed()
            binding.speedLabel.text = if (actual > 0.01f) "${speed.toInt()} / ${actual.toInt()} km/h" else "${speed.toInt()} km/h"
        } catch (e: Exception) { binding.speedLabel.text = "${speed.toInt()} km/h" }
    }

    private fun updateReplaceLocationButtonVisibility() {
        binding.replaceLocationButton.visibility = if (currentMode == AppMode.SEARCH && isGpsSet && destMarker != null && currentFakeLocationPos != destMarker?.position) View.VISIBLE else View.GONE
    }

    private fun updateFakeLocationMarker(position: LatLng) {
        fakeLocationCircle?.remove()
        fakeLocationCenterDot?.remove()
        fakeLocationCircle = createStationaryLocationCircle(position)
    }

    private fun updateCompletedPath(pos: LatLng) {
        if (completedPathPoints.isEmpty() || distanceBetween(completedPathPoints.last(), pos) >= 5.0) {
            completedPathPoints.add(pos)
            if (completedPathPoints.size % 3 == 0) {
                if (completedPolyline == null) {
                    completedPolyline = mMap.addPolyline(PolylineOptions().addAll(completedPathPoints).color(Color.parseColor(COMPLETED_ROUTE_COLOR)).width(ROUTE_WIDTH + 1))
                } else {
                    completedPolyline?.points = completedPathPoints
                }
            }
        }
    }

    private fun updateCameraFollowButton() {
        binding.cameraFollowToggle.setImageResource(if (isCameraFollowing) R.drawable.ic_camera_follow else R.drawable.ic_camera_free)
    }

    private fun distanceBetween(p1: LatLng, p2: LatLng): Double {
        val results = FloatArray(1)
        android.location.Location.distanceBetween(p1.latitude, p1.longitude, p2.latitude, p2.longitude, results)
        return results[0].toDouble()
    }

    private fun calculateTotalRouteDistance(points: List<LatLng>): Double {
        var dist = 0.0
        for (i in 0 until points.size - 1) dist += distanceBetween(points[i], points[i + 1])
        return dist / 1000.0
    }

    private fun updateDistanceLabel() {
        binding.distanceLabel.text = String.format("%.2fkm/%.2fkm", traveledDistanceKm, totalRouteDistanceKm)
    }

    private fun updateTraveledDistance(pos: LatLng) {
        lastDistancePosition?.let { 
            val d = distanceBetween(it, pos)
            if (d in 0.5..100.0) { traveledDistanceKm += d / 1000.0; updateDistanceLabel() }
        }
        lastDistancePosition = pos
    }

    private fun createFakeLocationCircle(center: LatLng, stroke: Int, fill: Int, centerColor: Int): Circle {
        fakeLocationCenterDot?.remove()
        fakeLocationCenterDot = mMap.addMarker(MarkerOptions().position(center).icon(createCenterDotIcon(centerColor)).anchor(0.5f, 0.5f).zIndex(CENTER_DOT_Z_INDEX))
        return mMap.addCircle(CircleOptions().center(center).radius(CIRCLE_RADIUS).strokeColor(stroke).fillColor(fill).strokeWidth(CIRCLE_STROKE_WIDTH).zIndex(CIRCLE_Z_INDEX))
    }

    private fun createStationaryLocationCircle(center: LatLng) = createFakeLocationCircle(center, FAKE_LOCATION_STROKE_COLOR, FAKE_LOCATION_FILL_COLOR, FAKE_LOCATION_CENTER_COLOR)

    private fun createCenterDotIcon(color: Int): BitmapDescriptor {
        val size = 48
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.WHITE
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
        paint.color = color
        canvas.drawCircle(size / 2f, size / 2f, size * 0.375f, paint)
        return BitmapDescriptorFactory.fromBitmap(bitmap)
    }

    private fun onNavigationComplete() {
        isDriving = false; isPaused = false
        binding.speedSlider.value = 52f; currentSpeed = 52.0; binding.speedLabel.text = "52 km/h"
        binding.navigationControlsCard.visibility = View.GONE
        binding.cameraFollowToggle.visibility = View.GONE
        fakeLocationCircle?.remove(); fakeLocationCircle = null
        fakeLocationCenterDot?.remove(); fakeLocationCenterDot = null
        
        val dest = routePoints.lastOrNull() ?: destMarker?.position
        if (dest != null) {
            isGpsSet = true; currentFakeLocationPos = dest
            viewModel.update(true, dest.latitude, dest.longitude)
            updateFakeLocationMarker(dest); updateSetLocationButton()
        }
        binding.completionActionsCard.visibility = View.VISIBLE
    }

    private fun resetToSearchMode() {
        destMarker?.remove(); destMarker = null
        startMarker?.remove(); startMarker = null
        routePolyline?.remove(); routePolyline = null
        completedPolyline?.remove(); completedPolyline = null
        completedPathPoints.clear()
        fakeLocationCircle?.remove(); fakeLocationCircle = null
        fakeLocationCenterDot?.remove(); fakeLocationCenterDot = null
        routePoints = emptyList()
        currentMode = AppMode.SEARCH; hasSelectedStartPoint = false
        binding.actionButton.visibility = View.GONE
        binding.navigationControlsCard.visibility = View.GONE
        binding.cameraFollowToggle.visibility = View.GONE
        binding.startSearchContainer.visibility = View.GONE
        binding.useCurrentLocationContainer.visibility = View.GONE
        binding.destinationSearch.text.clear(); binding.startSearch.text.clear()
        binding.searchCard.visibility = View.VISIBLE
        binding.addFavoriteButton.visibility = View.GONE
        updateReplaceLocationButtonVisibility()
        routeSimulator?.stop(); routeSimulator = null
    }

    private fun restoreFakeLocationMarker() {
        if (viewModel.isStarted) {
            val lat = viewModel.getLat; val lng = viewModel.getLng
            if (lat != 0.0 && lng != 0.0) {
                val pos = LatLng(lat, lng)
                isGpsSet = true; currentFakeLocationPos = pos
                updateFakeLocationMarker(pos); updateSetLocationButton()
                lastJoystickLat = lat; lastJoystickLon = lng
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        routeSimulator?.stop()
        PrefManager.sharedPreferences.unregisterOnSharedPreferenceChangeListener(prefChangeListener)
    }

    override fun onPause() {
        super.onPause()
        if (isDriving) {
            backgroundNavigationState = NavigationState(routePoints, currentSpeed, isPaused, currentPositionIndex, ArrayList(completedPathPoints))
        }
    }

    private fun toggleNavigationControls() {
        val expanded = !PrefManager.navControlsExpanded
        PrefManager.navControlsExpanded = expanded
        animateNavigationControls(expanded)
    }

    private fun animateNavigationControls(expanded: Boolean) {
        binding.navControlsExpandable.visibility = if (expanded) View.VISIBLE else View.GONE
        binding.navControlsToggle.setImageResource(if (expanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more)
        binding.navControlsToggle.rotation = if (expanded) 0f else 180f
    }

    private fun restoreNavigationControlsState() {
        val expanded = PrefManager.navControlsExpanded
        binding.navControlsExpandable.visibility = if (expanded) View.VISIBLE else View.GONE
        binding.navControlsToggle.setImageResource(if (expanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more)
        binding.navControlsToggle.rotation = if (expanded) 0f else 180f
    }

    override fun onResume() {
        super.onResume()
        PrefManager.sharedPreferences.registerOnSharedPreferenceChangeListener(prefChangeListener)
        if (backgroundNavigationState != null) {
            val s = backgroundNavigationState!!
            routePoints = s.routePoints; currentSpeed = s.currentSpeed; isPaused = s.isPaused
            currentPositionIndex = s.currentPositionIndex; completedPathPoints.clear(); completedPathPoints.addAll(s.completedPathPoints)
            updateNavigationUI(); backgroundNavigationState = null
        }
    }

    private fun updateNavigationUI() {
        if (isDriving) {
            binding.navigationControlsCard.visibility = View.VISIBLE
            binding.searchCard.visibility = View.GONE
            binding.cameraFollowToggle.visibility = View.VISIBLE
            binding.setLocationButton.visibility = View.GONE
            binding.getFakeLocation.visibility = View.GONE
            updateNavControlButtons()
            binding.speedLabel.text = "${currentSpeed.toInt()} km/h"
            currentNavigationPosition?.let { updateFakeLocationMarker(it) }
        }
    }

    override fun onMarkerDragStart(marker: Marker) {}
    override fun onMarkerDrag(marker: Marker) {}
    override fun onMarkerDragEnd(marker: Marker) {
        if (currentMode == AppMode.NAVIGATION) return
        lifecycleScope.launch {
            val addr = getAddressFromLocation(marker.position)
            if (marker == destMarker) {
                binding.destinationSearch.setText(addr)
                updateReplaceLocationButtonVisibility()
                updateAddFavoriteButtonVisibility()
                updateActionButtonsVisibility()
            } else if (marker == startMarker) {
                binding.startSearch.setText(addr)
                updateUseCurrentLocationButtonVisibility()
            }
            if (currentMode == AppMode.ROUTE_PLAN && startMarker != null && destMarker != null) drawRoute()
        }
    }

    private fun onFinishNavigation() {
        binding.completionActionsCard.visibility = View.GONE
        binding.speedSlider.value = 52f; currentSpeed = 52.0; binding.speedLabel.text = "52 km/h"
        routePolyline?.remove(); routePolyline = null
        completedPolyline?.remove(); completedPolyline = null
        completedPathPoints.clear()
        startMarker?.remove(); startMarker = null
        destMarker?.remove(); destMarker = null
        routePoints = emptyList()
        binding.destinationSearch.text.clear(); binding.startSearch.text.clear()
        currentMode = AppMode.SEARCH
        binding.actionButton.visibility = View.GONE
        binding.navigationControlsCard.visibility = View.GONE
        binding.cameraFollowToggle.visibility = View.GONE
        binding.cancelRouteButton.visibility = View.GONE
        binding.addFavoriteButton.visibility = View.GONE
        binding.startSearchContainer.visibility = View.GONE
        binding.useCurrentLocationContainer.visibility = View.GONE
        binding.searchCard.visibility = View.VISIBLE
        binding.getFakeLocation.visibility = View.VISIBLE
        routeSimulator?.stop()
    }

    private fun onRestartNavigation() {
        binding.completionActionsCard.visibility = View.GONE
        isDriving = false; isPaused = false; currentPositionIndex = 0; completedPathPoints.clear()
        fakeLocationCircle?.remove(); fakeLocationCircle = null
        fakeLocationCenterDot?.remove(); fakeLocationCenterDot = null
        if (routePoints.isNotEmpty()) startNavigation() else showToast("Dữ liệu đã mất")
    }

    private fun onStopNavigationEarly() {
        routeSimulator?.stop(); routeSimulator = null
        isDriving = false; isPaused = false
        val pos = currentNavigationPosition
        routePolyline?.remove(); routePolyline = null
        completedPolyline?.remove(); completedPolyline = null
        completedPathPoints.clear()
        startMarker?.remove(); startMarker = null
        destMarker?.remove(); destMarker = null
        routePoints = emptyList()
        binding.destinationSearch.text.clear(); binding.startSearch.text.clear()
        binding.navigationControlsCard.visibility = View.GONE
        binding.cameraFollowToggle.visibility = View.GONE
        binding.cancelRouteButton.visibility = View.GONE
        binding.addFavoriteButton.visibility = View.GONE
        if (pos != null) {
            isGpsSet = true; currentFakeLocationPos = pos
            viewModel.update(true, pos.latitude, pos.longitude)
            updateFakeLocationMarker(pos)
            updateSetLocationButton()
        }
        currentMode = AppMode.SEARCH
        updateActionButtonsVisibility()
        binding.searchCard.visibility = View.VISIBLE
        binding.getFakeLocation.visibility = View.VISIBLE
        binding.setLocationButton.visibility = View.VISIBLE
    }
}
