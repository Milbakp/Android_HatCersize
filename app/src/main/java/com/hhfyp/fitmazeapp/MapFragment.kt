package com.hhfyp.fitmazeapp

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions

class MapFragment : Fragment(), OnMapReadyCallback {

    private lateinit var myMap: GoogleMap
    private var lastPickedCoordinate: LatLng? = null

    private lateinit var saveButton: Button
    private lateinit var clearButton: Button

    private val sharedViewModel: SharedViewModel by activityViewModels()

    companion object {
        private val DEFAULT_COORDINATE = LatLng(2.924897, 101.641824) // Default coordinate
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_map, container, false)

        // Initialize buttons
        saveButton = view.findViewById(R.id.btn_save_coordinate)
        clearButton = view.findViewById(R.id.btn_clear_picker)

        // Hide buttons initially
        saveButton.visibility = View.GONE
        clearButton.visibility = View.GONE

        // Set up button click listeners
        saveButton.setOnClickListener {
            lastPickedCoordinate?.let { coordinate ->
                sharedViewModel.updateCoordinate(coordinate)
                sharedViewModel.triggerCoordinateUpdate()
                saveButton.visibility = View.GONE
                clearButton.visibility = View.GONE
            }
        }

        clearButton.setOnClickListener {
            myMap.clear()
            lastPickedCoordinate = null
            saveButton.visibility = View.GONE
            clearButton.visibility = View.GONE
        }

        val mapFragment = childFragmentManager.findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync(this)

        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        (activity as? MainFragActivity)?.hideSystemBars()
    }

    override fun onMapReady(googleMap: GoogleMap) {
        myMap = googleMap
        val initialLocation = lastPickedCoordinate ?: DEFAULT_COORDINATE
        myMap.moveCamera(CameraUpdateFactory.newLatLngZoom(initialLocation, 10f))
        myMap.addMarker(MarkerOptions().position(initialLocation).title("Default Location"))

        myMap.setOnMapClickListener { latLng ->
            val formattedLat = formatCoordinate(latLng.latitude)
            val formattedLng = formatCoordinate(latLng.longitude)
            myMap.clear()
            lastPickedCoordinate = LatLng(formattedLat.toDouble(), formattedLng.toDouble())
            myMap.addMarker(MarkerOptions().position(latLng).title(lastPickedCoordinate.toString()))
            saveButton.visibility = View.VISIBLE
            clearButton.visibility = View.VISIBLE
        }
    }

    override fun onResume() {
        super.onResume()
        (activity as? MainFragActivity)?.hideSystemBars()
        if (::myMap.isInitialized) {
            lastPickedCoordinate?.let {
                myMap.moveCamera(CameraUpdateFactory.newLatLngZoom(it, 15f))
            }
        }
    }

    private fun formatCoordinate(value: Double): String {
        return String.format("%.6f", value)
    }
}