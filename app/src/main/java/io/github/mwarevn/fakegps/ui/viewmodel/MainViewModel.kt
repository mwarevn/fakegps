package io.github.mwarevn.fakegps.ui.viewmodel

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import io.github.mwarevn.fakegps.domain.model.FavoriteLocation
import io.github.mwarevn.fakegps.domain.model.LatLng
import io.github.mwarevn.fakegps.domain.model.VehicleType
import io.github.mwarevn.fakegps.domain.repository.IFavoriteRepository
import io.github.mwarevn.fakegps.domain.usecase.CalculateRouteUseCase
import io.github.mwarevn.fakegps.network.StatusService
import io.github.mwarevn.fakegps.update.UpdateChecker
import io.github.mwarevn.fakegps.utils.PrefManager
import io.github.mwarevn.fakegps.utils.ext.onIO
import io.github.mwarevn.fakegps.utils.ext.onMain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val favoriteRepository: IFavoriteRepository,
    private val prefManger: PrefManager,
    private val checkUpdates: UpdateChecker,
    private val statusService: StatusService,
    private val calculateRouteUseCase: CalculateRouteUseCase,
    @ApplicationContext context: Context
) : ViewModel() {

    val getLat: Double get() = prefManger.getLat
    val getLng: Double get() = prefManger.getLng
    val isStarted: Boolean get() = prefManger.isStarted
    val mapType: Int get() = prefManger.mapType

    private val _allFavList = MutableStateFlow<List<FavoriteLocation>>(emptyList())
    val allFavList : StateFlow<List<FavoriteLocation>> =  _allFavList
    
    fun doGetUserDetails(){
        onIO {
            favoriteRepository.getAllFavorites()
                .catch { e -> Timber.tag("MainViewModel").d(e.message.toString()) }
                .collectLatest { _allFavList.emit(it) }
        }
    }

    fun update(start: Boolean, la: Double, ln: Double, bearing: Float = 0F, speed: Float = 0F)  {
        prefManger.update(start, la, ln, bearing, speed)
    }

    private val _currentRoute = MutableStateFlow<List<LatLng>>(emptyList())
    val currentRoute: StateFlow<List<LatLng>> = _currentRoute.asStateFlow()

    private val _routeError = MutableSharedFlow<String>()
    val routeError = _routeError.asSharedFlow()

    private var routingJob: Job? = null

    fun calculateRoute(start: LatLng, dest: LatLng, vehicle: VehicleType) {
        routingJob?.cancel()
        routingJob = viewModelScope.launch {
            calculateRouteUseCase(start, dest, vehicle)
                .onSuccess { _currentRoute.emit(it) }
                .onFailure { _routeError.emit(it.message ?: "Unknown error") }
        }
    }

    fun clearRoute() {
        viewModelScope.launch { _currentRoute.emit(emptyList()) }
    }

    val isXposed = MutableLiveData<Boolean>(true)
    fun isModuleActive(): Boolean = false
    fun updateXposedState() { onMain { isXposed.value = isModuleActive() } }

    fun deleteFavorite(favorite: FavoriteLocation) = onIO { favoriteRepository.deleteFavorite(favorite) }

    // Check updates flow
    private val _update = MutableStateFlow<UpdateChecker.Update?>(null).apply {
        viewModelScope.launch {
            checkUpdates.getLatestRelease().collect { emit(it) }
        }
    }
    val update = _update.asStateFlow()

    fun clearUpdate() { viewModelScope.launch { _update.emit(null) } }

    private val _appStatus = MutableStateFlow<AppStatus>(AppStatus.Checking)
    val appStatus: StateFlow<AppStatus> = _appStatus.asStateFlow()

    sealed class AppStatus {
        object Checking : AppStatus()
        object Allowed : AppStatus()
        data class Disallowed(val message: String?) : AppStatus()
        object NoInternet : AppStatus()
        data class Error(val message: String?) : AppStatus()
    }

    fun checkAppStatus() {
        viewModelScope.launch {
            _appStatus.value = AppStatus.Checking
            try {
                val response = statusService.getStatus()
                if (response.isNotEmpty()) {
                    _appStatus.value = AppStatus.Allowed
                } else {
                    _appStatus.value = AppStatus.Error("Empty response from server")
                }
            } catch (e: Exception) {
                Timber.e(e, "Error checking app status")
                _appStatus.value = AppStatus.Error(e.message)
            }
        }
    }
    
    fun setNoInternetStatus() { _appStatus.value = AppStatus.NoInternet }

    fun storeFavorite(address: String, lat: Double, lon: Double) = onIO {
        favoriteRepository.addNewFavorite(FavoriteLocation(0, address, lat, lon))
    }
}
