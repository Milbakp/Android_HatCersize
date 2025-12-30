package com.hhfyp.fitmazeapp

import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import com.google.android.material.tabs.TabLayout

class MainFragActivity : AppCompatActivity() {
    private lateinit var tabLayout: TabLayout
    private val bluetoothSensorFragment = BluetoothSensorFragment()
    private val mapFragment = MapFragment()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main_frag)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        tabLayout = findViewById(R.id.tab_layout)

        // Load the default fragment (BluetoothSensorFragment)
//        if (savedInstanceState == null) {
//            supportFragmentManager.beginTransaction()
//                .replace(R.id.fragment_container, BluetoothSensorFragment())
//                .commit()
//        }

        tabLayout.addTab(tabLayout.newTab().setText("Bluetooth & Sensors"))
        tabLayout.addTab(tabLayout.newTab().setText("Map"))

        supportFragmentManager.beginTransaction()
            .add(R.id.fragment_container, bluetoothSensorFragment, "BluetoothFragment")
            .add(R.id.fragment_container, mapFragment, "MapFragment")
            .hide(mapFragment) // Hide MapFragment by default
            .commit()

        // Handle tab selection
        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                when (tab?.position) {
                    0 -> showFragment(bluetoothSensorFragment, mapFragment)
                    1 -> showFragment(mapFragment, bluetoothSensorFragment)
                }
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemBars()
        }
    }

    private fun showFragment(toShow: androidx.fragment.app.Fragment, toHide: androidx.fragment.app.Fragment) {
        supportFragmentManager.beginTransaction()
            .hide(toHide) // Hide the fragment you want to switch from
            .show(toShow) // Show the fragment you want to switch to
            .commit()
    }

    fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // For API level 30 (Android 11) and above
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            // For API level 29 (Android 10) and below
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            or View.SYSTEM_UI_FLAG_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        // Let the active fragment handle permission results
        val currentFragment = supportFragmentManager.findFragmentById(R.id.fragment_container)
        if (currentFragment is PermissionResultListener) {
            currentFragment.onRequestPermissionsResult(requestCode, permissions, grantResults)
        }
    }
}