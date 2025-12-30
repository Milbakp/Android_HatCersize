package com.hhfyp.fitmazeapp

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.google.android.gms.maps.model.LatLng

class SharedViewModel : ViewModel() {

    private val _coordinateUpdateTrigger = MutableLiveData<Boolean>()
    val coordinateUpdateTrigger: LiveData<Boolean> get() = _coordinateUpdateTrigger

    private var pickedCoordinate: LatLng? = null

    fun updateCoordinate(coordinate: LatLng) {
        pickedCoordinate = coordinate
    }

    fun getCoordinate(): LatLng? {
        return pickedCoordinate
    }

    fun triggerCoordinateUpdate() {
        _coordinateUpdateTrigger.value = true
    }

    fun resetTrigger() {
        _coordinateUpdateTrigger.value = false
    }
}