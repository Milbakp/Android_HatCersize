package com.hhfyp.fitmazeapp

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.bluetooth.*
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import java.nio.ByteBuffer
import kotlin.math.sqrt
import kotlin.text.toByteArray

class BluetoothSensorFragment : Fragment(), SensorEventListener, PermissionResultListener {

    private val sharedViewModel: SharedViewModel by activityViewModels()
    private var hasPromptedBluetooth = false
    private lateinit var bluetoothAdapter: BluetoothAdapter
    private lateinit var bluetoothLeAdvertiser: BluetoothLeAdvertiser
    private lateinit var gattServer: BluetoothGattServer
    private var connectedDevice: BluetoothDevice? = null
    private var currentMtu = 20
    private var serviceUuid: String = "94727bab-f1fe-4104-981c-83cd6c8636aa"
    private var stepCharUuid: String = "49832218-a470-4611-8a24-35c9a3ae5427"
    private var turnCharUuid: String = "f4660c4e-2d9a-4b46-aa68-00eb8e46e290"
    private var mapCharUuid: String = "da0805d7-016e-4cac-9366-b3aea37a1921"
    private var toggleSensorCharUuid: String = "601484c4-c192-4c5c-bca0-391feab42071"
    private var pauseCharUuid: String = "38d4b4d2-a43b-4a0b-8971-1032ae2a366e"
    private var screenshotCharUuid: String = "086ca2e7-1794-489f-aa01-b03dc91379cf"
    private var descriptorUuid: String = "00002902-0000-1000-8000-00805f9b34fb"

    private var attackUuid: String = "3d346fc8-400d-4d86-989b-8f9468b9bbcd"
    private var specialUuid: String = "2bac5234-b92f-4479-a0c7-db5093b22ec0"
    private var interactUuid: String = "7f2d0b4c-1f7a-4588-b2bb-97c4f174e0dd"

    private var sensorManager: SensorManager? = null
    private var totalStepsInSession = 0
    private var running = false
    private var previousStepTime: Long = 0
    private var isStepBegin = false
    private var previousSmoothedMagnitude = 0f
    private val stepIntervals = mutableListOf<Long>()
    private val maxBufferSize = 5

    private lateinit var stepCountTextView: TextView
    private lateinit var deviceStatusTextView: TextView
    private lateinit var advertiseStatusTextView: TextView
    private lateinit var turningTextView: TextView
    private lateinit var pauseButton: Button
    private lateinit var screenshotButton: Button
    private lateinit var attackButton: Button
    private lateinit var specialButton: Button
    private lateinit var interactButton: Button

    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)
    private val accelerometerReading = FloatArray(3)
    private val magnetometerReading = FloatArray(3)

    private var lastPitch: Float? = null

    // Local storage for characteristic values
    private var stepCharValue: ByteArray = ByteBuffer.allocate(4).putInt(0).array()
    private var turnCharValue: ByteArray = ByteBuffer.allocate(4).putInt(0).array()
    private var mapCharValue: ByteArray = ByteBuffer.allocate(4).putInt(0).array()
    private var toggleSensorCharValue: ByteArray = "stop".toByteArray(Charsets.UTF_8)
    private var pauseCharValue: ByteArray = "".toByteArray(Charsets.UTF_8)
    private var screenshotCharValue: ByteArray = "done".toByteArray(Charsets.UTF_8)
    private var attackCharValue: ByteArray = "".toByteArray(Charsets.UTF_8)
    private var specialCharValue: ByteArray = "".toByteArray(Charsets.UTF_8)
    private var interactCharValue: ByteArray = "".toByteArray(Charsets.UTF_8)


    // Register ActivityResultLauncher for enabling Bluetooth
    private val enableBluetoothLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        hasPromptedBluetooth = false
        if (result.resultCode == Activity.RESULT_OK) {
            Toast.makeText(requireContext(), "Bluetooth enabled", Toast.LENGTH_SHORT).show()
            bluetoothLeAdvertiser = bluetoothAdapter.bluetoothLeAdvertiser
            startGattServer()
        } else {
            Toast.makeText(requireContext(), "Bluetooth is required to use this app", Toast.LENGTH_LONG).show()
            advertiseStatusTextView.text = getString(R.string.controller_status_inactive)
        }
    }

    companion object {
        private const val REQUEST_BLUETOOTH_CONNECT = 1
        private const val REQUEST_BLUETOOTH_ADVERTISE = 2
        private const val ALPHA = 0.8f
        private const val THRESHOLD = 10.5f
    }

    // region Overrides
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_bluetooth_sensor, container, false)

        // Observe the trigger
        sharedViewModel.coordinateUpdateTrigger.observe(viewLifecycleOwner) { trigger ->
            if (trigger) {
                updateCoordinateToCharacteristic() // Use the command ("u|" or "s|")
                // Reset the trigger to avoid duplicate calls
                sharedViewModel.resetTrigger()
            }
        }

        sensorManager = requireContext().getSystemService(Context.SENSOR_SERVICE) as SensorManager

        stepCountTextView = view.findViewById(R.id.step_count_text)
        deviceStatusTextView = view.findViewById(R.id.device_status_text)
        advertiseStatusTextView = view.findViewById(R.id.controller_status_text)
        turningTextView = view.findViewById(R.id.turning_state_text)
        pauseButton = view.findViewById(R.id.pause_button)
        screenshotButton = view.findViewById(R.id.screenshot_button)
        attackButton = view.findViewById(R.id.attack_button)
        specialButton = view.findViewById(R.id.special_button)
        interactButton = view.findViewById(R.id.interact_button)

        val startButton: Button = view.findViewById(R.id.activate_button)
        val stopButton: Button = view.findViewById(R.id.deactivate_button)

        startButton.setOnClickListener {
            if (checkBluetoothAndPrompt()) {
                startBleAdvertising()
            }
        }

        stopButton.setOnClickListener {
            if (checkBluetoothAndPrompt()) {
                stopBleAdvertising()
            }
        }
        pauseButton.setOnClickListener {
            if (checkBluetoothAndPrompt()) {
                pauseGame()
            }
        }
        screenshotButton.setOnClickListener {
            if (checkBluetoothAndPrompt()) {
                takeScreenshot()
            }
        }
        attackButton.setOnClickListener {
            if (checkBluetoothAndPrompt()) {
                attackGame()
            }
        }
        specialButton.setOnClickListener {
            if (checkBluetoothAndPrompt()) {
                specialGame()
            }
        }
        interactButton.setOnClickListener {
            if (checkBluetoothAndPrompt()) {
                interactGame()
            }
        }

        try {
            initializeBluetooth()
            if (::bluetoothAdapter.isInitialized && bluetoothAdapter.isEnabled && ::bluetoothLeAdvertiser.isInitialized) {
                startGattServer()
            } else {
                Toast.makeText(requireContext(), "Bluetooth is not ready, please enable it", Toast.LENGTH_LONG).show()
                Log.e("BLE", "Bluetooth not enabled or BLE not initialized, skipping GATT server start")
            }
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Bluetooth setup failed: ${e.message}", Toast.LENGTH_LONG).show()
            Log.e("BLE", "Error initializing Bluetooth or GATT server: ${e.message}", e)
        }

        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        (activity as? MainFragActivity)?.hideSystemBars()
    }

    override fun onResume() {
        super.onResume()
        (activity as? MainFragActivity)?.hideSystemBars()
        running = true
        manageSensors()
    }

    override fun onPause() {
        super.onPause()
        running = false
        manageSensors()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        when (requestCode) {
            REQUEST_BLUETOOTH_CONNECT -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Log.d("Bluetooth", "BLUETOOTH_CONNECT permission granted.")
                    if (::bluetoothAdapter.isInitialized && bluetoothAdapter.isEnabled) {
                        try {
                            bluetoothLeAdvertiser = bluetoothAdapter.bluetoothLeAdvertiser
                            Toast.makeText(requireContext(), "Bluetooth ready", Toast.LENGTH_SHORT).show()
                            Log.d("BLE", "Initialized bluetoothLeAdvertiser after permission grant")
                            if (::bluetoothLeAdvertiser.isInitialized) {
                                startGattServer()
                            }
                        } catch (e: Exception) {
                            //Toast.makeText(requireContext(), "Failed to initialize BLE advertiser", Toast.LENGTH_LONG).show()
                            Log.e("BLE", "Error initializing bluetoothLeAdvertiser: ${e.message}", e)
                        }
                    }
                } else {
                    requireActivity().runOnUiThread {
                        Toast.makeText(requireContext(), "Please allow Nearby Devices permission in settings to use this app", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            REQUEST_BLUETOOTH_ADVERTISE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Log.d("Bluetooth", "BLUETOOTH_ADVERTISE permission granted.")
                    if (::bluetoothAdapter.isInitialized && bluetoothAdapter.isEnabled && ::bluetoothLeAdvertiser.isInitialized) {
                        startBleAdvertising()
                    }
                } else {
                    requireActivity().runOnUiThread {
                        Toast.makeText(requireContext(), "Please allow Nearby Devices permission in settings to use this app", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }
    // endregion

    // region Bluetooth
    private fun checkBluetoothAndPrompt(): Boolean {
        if (!::bluetoothAdapter.isInitialized) {
            Toast.makeText(requireContext(), "This device does not support Bluetooth", Toast.LENGTH_LONG).show()
            Log.e("BLE", "Bluetooth adapter not initialized")
            return false
        }
        if (!checkBluetoothConnectPermission()) {
            requestBluetoothConnectPermission()
            //Toast.makeText(requireContext(), "Please grant Bluetooth permission to enable Bluetooth", Toast.LENGTH_LONG).show()
            Log.w("BLE", "BLUETOOTH_CONNECT permission required")
            return false
        }
        if (!bluetoothAdapter.isEnabled && !hasPromptedBluetooth) {
            Toast.makeText(requireContext(), "Bluetooth is disabled, enabling...", Toast.LENGTH_SHORT).show()
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            try {
                enableBluetoothLauncher.launch(enableBtIntent)
                hasPromptedBluetooth = true
            } catch (e: SecurityException) {
                //Toast.makeText(requireContext(), "Bluetooth permission denied, please grant it", Toast.LENGTH_LONG).show()
                Log.e("BLE", "SecurityException in checkBluetoothAndPrompt: ${e.message}", e)
                requestBluetoothConnectPermission()
            }
            return false
        }
        // Initialize bluetoothLeAdvertiser if not already initialized
        if (!::bluetoothLeAdvertiser.isInitialized && bluetoothAdapter.isEnabled) {
            try {
                bluetoothLeAdvertiser = bluetoothAdapter.bluetoothLeAdvertiser
                Log.d("BLE", "Initialized bluetoothLeAdvertiser in checkBluetoothAndPrompt")
            } catch (e: Exception) {
                //Toast.makeText(requireContext(), "Failed to initialize BLE advertiser", Toast.LENGTH_LONG).show()
                Log.e("BLE", "Error initializing bluetoothLeAdvertiser: ${e.message}", e)
                return false
            }
        }
        return bluetoothAdapter.isEnabled && ::bluetoothLeAdvertiser.isInitialized
    }

    private fun initializeBluetooth() {
        val bluetoothManager = requireActivity().getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        if (!checkBluetoothConnectPermission()) {
            requestBluetoothConnectPermission()
            //Toast.makeText(requireContext(), "Please grant Bluetooth permission to initialize Bluetooth", Toast.LENGTH_LONG).show()
            Log.w("BLE", "BLUETOOTH_CONNECT permission required")
            return
        }
        if (!bluetoothAdapter.isEnabled && !hasPromptedBluetooth) {
            Toast.makeText(requireContext(), "Bluetooth is disabled, enabling...", Toast.LENGTH_SHORT).show()
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            try {
                enableBluetoothLauncher.launch(enableBtIntent)
                hasPromptedBluetooth = true
            } catch (e: SecurityException) {
                //Toast.makeText(requireContext(), "Bluetooth permission denied, please grant it", Toast.LENGTH_LONG).show()
                Log.e("BLE", "SecurityException in initializeBluetooth: ${e.message}", e)
                requestBluetoothConnectPermission()
            }
            return
        }
        if (bluetoothAdapter.isEnabled) {
            try {
                bluetoothLeAdvertiser = bluetoothAdapter.bluetoothLeAdvertiser
                Toast.makeText(requireContext(), "Bluetooth initialized successfully", Toast.LENGTH_SHORT).show()
                Log.d("BLE", "Bluetooth initialized")
            } catch (e: Exception) {
                //Toast.makeText(requireContext(), "Failed to initialize BLE advertiser", Toast.LENGTH_LONG).show()
                Log.e("BLE", "Error initializing bluetoothLeAdvertiser: ${e.message}", e)
            }
        }
    }

    // region Advertising parts
    private fun startBleAdvertising() {
        if (!checkBluetoothAdvertisePermission()) {
            requestBluetoothAdvertisePermission()
            return
        }
        if (!::bluetoothLeAdvertiser.isInitialized) {
            Toast.makeText(requireContext(), "Bluetooth advertiser not initialized", Toast.LENGTH_LONG).show()
            Log.e("BLE", "bluetoothLeAdvertiser not initialized")
            return
        }
        val advertiseSettings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setConnectable(true)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()

        val manufacturerId = 0x1234
        val manufacturerData = "controller".toByteArray(Charsets.UTF_8)

        val advertiseData = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .addManufacturerData(manufacturerId, manufacturerData)
            .build()

        bluetoothLeAdvertiser.startAdvertising(advertiseSettings, advertiseData, advertiseCallback)
        advertiseStatusTextView.text = getString(R.string.controller_status_active)
        Log.d("BLE", "Advertising started")
    }

    private fun stopBleAdvertising() {
        if (!checkBluetoothAdvertisePermission()) {
            requestBluetoothAdvertisePermission()
            return
        }
        bluetoothLeAdvertiser.stopAdvertising(advertiseCallback)
        advertiseStatusTextView.text = getString(R.string.controller_status_inactive)
        Log.d("BLE", "Advertising stopped")
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            super.onStartSuccess(settingsInEffect)
            Log.d("BLE", "Advertising started successfully")
        }

        override fun onStartFailure(errorCode: Int) {
            super.onStartFailure(errorCode)
            Log.e("BLE", "Advertising failed: Error code $errorCode")
        }
    }

    private fun startGattServer() {
        if (!checkBluetoothConnectPermission()) {
            requestBluetoothConnectPermission()
            return
        }
        val bluetoothManager =
            requireActivity().getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        try {
            gattServer = bluetoothManager.openGattServer(
                requireContext(),
                object : BluetoothGattServerCallback() {
                    override fun onConnectionStateChange(
                        device: BluetoothDevice?,
                        status: Int,
                        newState: Int
                    ) {
                        super.onConnectionStateChange(device, status, newState)
                        if (newState == BluetoothProfile.STATE_CONNECTED) {
                            if (!checkBluetoothConnectPermission()) {
//                                Toast.makeText(
//                                    requireContext(),
//                                    "Permission issue during connection",
//                                    Toast.LENGTH_LONG
//                                ).show()
                                Log.e("BLE", "Missing BLUETOOTH_CONNECT permission during connection")
                                requestBluetoothConnectPermission()
                                return
                            }
                            connectedDevice = device
                            requireActivity().runOnUiThread {
                                Toast.makeText(
                                    requireContext(),
                                    "Device connected: ${device?.name}",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                            Log.d("BLE", "Device connected: ${device?.address}")
                            deviceStatusTextView.text =
                                getString(R.string.device_connected, device?.name)
                            totalStepsInSession = 0
                            manageSensors()
                        } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                            connectedDevice = null
                            requireActivity().runOnUiThread {
                                Toast.makeText(
                                    requireContext(),
                                    "Device disconnected",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                            Log.d("BLE", "Device disconnected")
                            deviceStatusTextView.text = getString(R.string.no_device_connected)
                            manageSensors()
                        } else {
                            requireActivity().runOnUiThread {
                                Toast.makeText(
                                    requireContext(),
                                    "Failed to connect to device, please try again",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                            Log.e(
                                "BLE",
                                "Connection state change error, status: $status, newState: $newState"
                            )
                        }
                    }

                    override fun onServiceAdded(status: Int, service: BluetoothGattService?) {
                        super.onServiceAdded(status, service)
                        if (status == BluetoothGatt.GATT_SUCCESS) {
//                            requireActivity().runOnUiThread {
//                                Toast.makeText(
//                                    requireContext(),
//                                    "GATT service added successfully",
//                                    Toast.LENGTH_SHORT
//                                ).show()
//                            }
                            Log.d("BLE", "GATT service added successfully")
                        } else {
//                            requireActivity().runOnUiThread {
//                                Toast.makeText(
//                                    requireContext(),
//                                    "Failed to add GATT service, status: $status",
//                                    Toast.LENGTH_LONG
//                                ).show()
//                            }
                            Log.e("BLE", "Failed to add GATT service, status: $status")
                        }
                    }

                    override fun onMtuChanged(device: BluetoothDevice?, mtu: Int) {
                        super.onMtuChanged(device, mtu)
                        currentMtu = mtu - 3 // Subtract 3 bytes for ATT overhead
                        Log.d("BLE", "MTU changed to $mtu (payload size: $currentMtu bytes)")
                    }

                    override fun onCharacteristicReadRequest(
                        device: BluetoothDevice?,
                        requestId: Int,
                        offset: Int,
                        characteristic: BluetoothGattCharacteristic?
                    ) {
                        if (characteristic == null) {
                            Log.e("BLE", "Characteristic is null")
                            if (!checkBluetoothConnectPermission()) {
                                requestBluetoothConnectPermission()
                                return
                            }
                            gattServer.sendResponse(
                                device,
                                requestId,
                                BluetoothGatt.GATT_FAILURE,
                                offset,
                                null
                            )
                            return
                        }

                        val data = when (characteristic.uuid) {
                            ParcelUuid.fromString(stepCharUuid).uuid -> stepCharValue
                            ParcelUuid.fromString(turnCharUuid).uuid -> turnCharValue
                            ParcelUuid.fromString(mapCharUuid).uuid -> mapCharValue
                            ParcelUuid.fromString(toggleSensorCharUuid).uuid -> toggleSensorCharValue
                            ParcelUuid.fromString(pauseCharUuid).uuid -> pauseCharValue
                            ParcelUuid.fromString(screenshotCharUuid).uuid -> screenshotCharValue
                            ParcelUuid.fromString(attackUuid).uuid -> attackCharValue
                            ParcelUuid.fromString(specialUuid).uuid -> specialCharValue
                            ParcelUuid.fromString(interactUuid).uuid -> interactCharValue
                            else -> {
                                Log.e(
                                    "BLE",
                                    "Unknown characteristic read request: ${characteristic.uuid}"
                                )
                                gattServer.sendResponse(
                                    device,
                                    requestId,
                                    BluetoothGatt.GATT_FAILURE,
                                    offset,
                                    null
                                )
                                return
                            }
                        }

                        Log.d(
                            "BLE",
                            "Read request for characteristic ${characteristic.uuid}, data: ${
                                data.joinToString(" ")
                            }"
                        )
                        gattServer.sendResponse(
                            device,
                            requestId,
                            BluetoothGatt.GATT_SUCCESS,
                            offset,
                            data
                        )
                    }

                    override fun onDescriptorWriteRequest(
                        device: BluetoothDevice?,
                        requestId: Int,
                        descriptor: BluetoothGattDescriptor?,
                        preparedWrite: Boolean,
                        responseNeeded: Boolean,
                        offset: Int,
                        value: ByteArray?
                    ) {
                        if (descriptor == null || value == null) {
                            Log.e("BLE", "Descriptor or value is null")
                            if (responseNeeded) {
                                if (!checkBluetoothConnectPermission()) {
                                    requestBluetoothConnectPermission()
                                    return
                                }
                                gattServer.sendResponse(
                                    device,
                                    requestId,
                                    BluetoothGatt.GATT_FAILURE,
                                    offset,
                                    null
                                )
                            }
                            return
                        }

                        if (descriptor.uuid == ParcelUuid.fromString(descriptorUuid).uuid) {
                            val isNotificationEnabled =
                                value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)

                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                                descriptor.setValue(
                                    if (isNotificationEnabled) {
                                        Log.d(
                                            "BLE",
                                            "Notifications enabled for device: ${device?.address}"
                                        )
                                        BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                    } else {
                                        Log.d(
                                            "BLE",
                                            "Notifications disabled for device: ${device?.address}"
                                        )
                                        BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                                    }
                                )
                            } else {
                                @Suppress("DEPRECATION")
                                descriptor.value = if (isNotificationEnabled) {
                                    Log.d("BLE", "Notifications enabled for device: ${device?.address}")
                                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                } else {
                                    Log.d(
                                        "BLE",
                                        "Notifications disabled for device: ${device?.address}"
                                    )
                                    BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                                }
                            }

                            if (responseNeeded) {
                                gattServer.sendResponse(
                                    device,
                                    requestId,
                                    BluetoothGatt.GATT_SUCCESS,
                                    offset,
                                    value
                                )
                            }

                            // Ensure notifications are sent only when enabled
                            if (isNotificationEnabled && connectedDevice != null) {
                                val characteristic = descriptor.characteristic
                                if (characteristic != null) {
                                    Log.d(
                                        "BLE",
                                        "Notifying characteristic updates for ${characteristic.uuid}"
                                    )
                                    notifyCharacteristic(connectedDevice, characteristic)
                                } else {
                                    Log.e(
                                        "BLE",
                                        "Characteristic not found for descriptor: ${descriptor.uuid}"
                                    )
                                }
                            }
                        } else {
                            Log.e("BLE", "Unknown descriptor UUID: ${descriptor.uuid}")
                            if (responseNeeded) {
                                gattServer.sendResponse(
                                    device,
                                    requestId,
                                    BluetoothGatt.GATT_FAILURE,
                                    offset,
                                    null
                                )
                            }
                        }
                    }

                    override fun onCharacteristicWriteRequest(
                        device: BluetoothDevice?,
                        requestId: Int,
                        characteristic: BluetoothGattCharacteristic?,
                        preparedWrite: Boolean,
                        responseNeeded: Boolean,
                        offset: Int,
                        value: ByteArray?
                    ) {
                        if (!checkBluetoothConnectPermission()) {
                            requestBluetoothConnectPermission()
                            return
                        }
                        if (characteristic != null && value != null) {
                            val receivedData = value.toString(Charsets.UTF_8).trim()

                            if (characteristic.uuid == ParcelUuid.fromString(toggleSensorCharUuid).uuid) {
                                Log.d(
                                    "BLE",
                                    "Received sensor toggle command from client: $receivedData"
                                )

                                if (receivedData == "start" || receivedData == "stop") {
                                    toggleSensorCharValue = value // Store the received valid value
                                    manageSensors() // Re-check sensors based on new state
                                } else {
                                    Log.e(
                                        "BLE",
                                        "Unknown command received for toggling sensor: $receivedData"
                                    )
                                }
                            }
                        }

                        if (responseNeeded) {
                            gattServer.sendResponse(
                                device,
                                requestId,
                                BluetoothGatt.GATT_SUCCESS,
                                offset,
                                value
                            )
                        }
                    }
                })

            val service = BluetoothGattService(
                ParcelUuid.fromString(serviceUuid).uuid,
                BluetoothGattService.SERVICE_TYPE_PRIMARY
            )

            val stepCharacteristic = BluetoothGattCharacteristic(
                ParcelUuid.fromString(stepCharUuid).uuid,
                BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                        BluetoothGattCharacteristic.PROPERTY_READ or
                        BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE
            )

            val turnCharacteristic = BluetoothGattCharacteristic(
                ParcelUuid.fromString(turnCharUuid).uuid,
                BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                        BluetoothGattCharacteristic.PROPERTY_READ or
                        BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE
            )

            val mapCharacteristic = BluetoothGattCharacteristic(
                ParcelUuid.fromString(mapCharUuid).uuid,
                BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                        BluetoothGattCharacteristic.PROPERTY_READ or
                        BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE
            )

            val toggleSensorCharacteristic = BluetoothGattCharacteristic(
                ParcelUuid.fromString(toggleSensorCharUuid).uuid,
                BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                        BluetoothGattCharacteristic.PROPERTY_READ or
                        BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE
            )

            val pauseCharacteristic = BluetoothGattCharacteristic(
                ParcelUuid.fromString(pauseCharUuid).uuid,
                BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                        BluetoothGattCharacteristic.PROPERTY_READ or
                        BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE
            )

            val screenshotCharacteristic = BluetoothGattCharacteristic(
                ParcelUuid.fromString(screenshotCharUuid).uuid,
                BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                        BluetoothGattCharacteristic.PROPERTY_READ or
                        BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE
            )

            val attackCharacteristic = BluetoothGattCharacteristic(
                ParcelUuid.fromString(attackUuid).uuid,
                BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                        BluetoothGattCharacteristic.PROPERTY_READ or
                        BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE
            )

            val specialCharacteristic = BluetoothGattCharacteristic(
                ParcelUuid.fromString(specialUuid).uuid,
                BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                        BluetoothGattCharacteristic.PROPERTY_READ or
                        BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE
            )

            val interactCharacteristic = BluetoothGattCharacteristic(
                ParcelUuid.fromString(interactUuid).uuid,
                BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                        BluetoothGattCharacteristic.PROPERTY_READ or
                        BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE
            )

            val stepDescriptor = BluetoothGattDescriptor(
                ParcelUuid.fromString(descriptorUuid).uuid,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
            )

            val turnDescriptor = BluetoothGattDescriptor(
                ParcelUuid.fromString(descriptorUuid).uuid,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
            )

            val mapDescriptor = BluetoothGattDescriptor(
                ParcelUuid.fromString(descriptorUuid).uuid,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
            )

            val toggleSensorDescriptor = BluetoothGattDescriptor(
                ParcelUuid.fromString(descriptorUuid).uuid,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
            )

            val pauseDescriptor = BluetoothGattDescriptor(
                ParcelUuid.fromString(descriptorUuid).uuid,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
            )

            val screenshotDescriptor = BluetoothGattDescriptor(
                ParcelUuid.fromString(descriptorUuid).uuid,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
            )

            val attackDescriptor = BluetoothGattDescriptor(
                ParcelUuid.fromString(descriptorUuid).uuid,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
            )

            val specialDescriptor = BluetoothGattDescriptor(
                ParcelUuid.fromString(descriptorUuid).uuid,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
            )

            val interactDescriptor = BluetoothGattDescriptor(
                ParcelUuid.fromString(descriptorUuid).uuid,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
            )

            stepCharacteristic.addDescriptor(stepDescriptor)
            turnCharacteristic.addDescriptor(turnDescriptor)
            mapCharacteristic.addDescriptor(mapDescriptor)
            toggleSensorCharacteristic.addDescriptor(toggleSensorDescriptor)
            pauseCharacteristic.addDescriptor(pauseDescriptor)
            screenshotCharacteristic.addDescriptor(screenshotDescriptor)
            attackCharacteristic.addDescriptor(attackDescriptor)
            specialCharacteristic.addDescriptor(specialDescriptor)
            interactCharacteristic.addDescriptor(interactDescriptor)

            // Initialize characteristic values (still required for older APIs)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                @Suppress("DEPRECATION")
                stepCharacteristic.value = stepCharValue
                @Suppress("DEPRECATION")
                turnCharacteristic.value = turnCharValue
                @Suppress("DEPRECATION")
                mapCharacteristic.value = mapCharValue
                @Suppress("DEPRECATION")
                toggleSensorCharacteristic.value = toggleSensorCharValue
                @Suppress("DEPRECATION")
                pauseCharacteristic.value = pauseCharValue
                @Suppress("DEPRECATION")
                screenshotCharacteristic.value = screenshotCharValue
                @Suppress("DEPRECATION")
                attackCharacteristic.value = attackCharValue
                @Suppress("DEPRECATION")
                specialCharacteristic.value = specialCharValue
                @Suppress("DEPRECATION")
                interactCharacteristic.value = interactCharValue
            }

            service.addCharacteristic(stepCharacteristic)
            service.addCharacteristic(turnCharacteristic)
            service.addCharacteristic(mapCharacteristic)
            service.addCharacteristic(toggleSensorCharacteristic)
            service.addCharacteristic(pauseCharacteristic)
            service.addCharacteristic(screenshotCharacteristic)
            service.addCharacteristic(attackCharacteristic)
            service.addCharacteristic(specialCharacteristic)
            service.addCharacteristic(interactCharacteristic)
            gattServer.addService(service)
        }   catch (e: Exception) {
                Toast.makeText(requireContext(), "Bluetooth setup failed, please restart the app", Toast.LENGTH_LONG).show()
                Log.e("BLE", "Exception in startGattServer: ${e.message}", e)
        }
    }
    // endregion

    // region Characteristic methods
    private fun notifyCharacteristic(device: BluetoothDevice?, characteristic: BluetoothGattCharacteristic) {
        if (device == null || !checkBluetoothConnectPermission()) {
            requestBluetoothConnectPermission()
            return
        }

        val value = when (characteristic.uuid) {
            ParcelUuid.fromString(stepCharUuid).uuid -> stepCharValue
            ParcelUuid.fromString(turnCharUuid).uuid -> turnCharValue
            ParcelUuid.fromString(mapCharUuid).uuid -> mapCharValue
            ParcelUuid.fromString(toggleSensorCharUuid).uuid -> toggleSensorCharValue
            ParcelUuid.fromString(pauseCharUuid).uuid -> pauseCharValue
            ParcelUuid.fromString(screenshotCharUuid).uuid -> screenshotCharValue
            ParcelUuid.fromString(attackUuid).uuid -> attackCharValue
            ParcelUuid.fromString(specialUuid).uuid -> specialCharValue
            ParcelUuid.fromString(interactUuid).uuid -> interactCharValue
            else -> return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            gattServer.notifyCharacteristicChanged(device, characteristic, false, value)
        } else {
            @Suppress("DEPRECATION")
            characteristic.value = value
            @Suppress("DEPRECATION")
            gattServer.notifyCharacteristicChanged(device, characteristic, false)
        }
    }

    private fun updateStepToChar(stepInterval: Long) {
        // Convert the step interval to a string
        val dataString = stepInterval.toString()
        val byteArray = dataString.toByteArray(Charsets.UTF_8)

        val characteristic = gattServer.getService(
            ParcelUuid.fromString(serviceUuid).uuid
        )?.getCharacteristic(ParcelUuid.fromString(stepCharUuid).uuid)

        if (characteristic != null && connectedDevice != null) {
            stepCharValue = byteArray
            notifyCharacteristic(connectedDevice, characteristic)
        } else {
            Log.e("BLE", "Step characteristic or connected device is null")
        }
    }

    private fun updateTurnStateToChar(turnDirection: Int) {
        val byteArray = turnDirection.toString().toByteArray(Charsets.UTF_8)

        val characteristic = gattServer.getService(
            ParcelUuid.fromString(serviceUuid).uuid
        )?.getCharacteristic(ParcelUuid.fromString(turnCharUuid).uuid)

        if (characteristic != null && connectedDevice != null) {
            turnCharValue = byteArray
            notifyCharacteristic(connectedDevice, characteristic)
        } else {
            Log.e("BLE", "Turn characteristic or connected device is null")
        }
    }

    private fun updateCoordinateToCharacteristic() {
        val coordinate = sharedViewModel.getCoordinate()
        coordinate?.let {
            val latitude = String.format("%.6f", it.latitude)
            val longitude = String.format("%.6f", it.longitude)
            val coordinates = "$latitude,$longitude"
            val byteArray = coordinates.toByteArray(Charsets.UTF_8)

            if (byteArray.size > currentMtu) {
                Log.e("BLE", "Coordinate data ($byteArray.size bytes) exceeds MTU ($currentMtu bytes)")
                Toast.makeText(requireContext(), "Unable to send location data, please try again", Toast.LENGTH_SHORT).show()
                return
            }

            val characteristic = gattServer.getService(
                ParcelUuid.fromString(serviceUuid).uuid
            )?.getCharacteristic(ParcelUuid.fromString(mapCharUuid).uuid)

            if (!checkBluetoothConnectPermission()) {
                requestBluetoothConnectPermission()
                return
            }
            if (characteristic != null && connectedDevice != null) {
                mapCharValue = byteArray
                notifyCharacteristic(connectedDevice, characteristic)
                Log.d("BLE", "Sent coordinate: $coordinates (${byteArray.size} bytes)")
            }
        }
    }

    private fun getSensorToggleFromCharacteristic(): String {
        return toggleSensorCharValue.toString(Charsets.UTF_8).trim()
    }

    private fun updatePauseToChar(value: String) {
        val byteArray = value.toByteArray(Charsets.UTF_8)
        val characteristic = gattServer.getService(
            ParcelUuid.fromString(serviceUuid).uuid
        )?.getCharacteristic(ParcelUuid.fromString(pauseCharUuid).uuid)

        if (characteristic != null && connectedDevice != null) {
            pauseCharValue = byteArray
            notifyCharacteristic(connectedDevice, characteristic)
            Log.d("BLE", "Updated pause characteristic to: $value")
        } else {
            Log.e("BLE", "Pause characteristic or connected device is null")
        }
    }

    private fun updateScreenshotToChar(value: String) {
        val byteArray = value.toByteArray(Charsets.UTF_8)
        val characteristic = gattServer.getService(
            ParcelUuid.fromString(serviceUuid).uuid
        )?.getCharacteristic(ParcelUuid.fromString(screenshotCharUuid).uuid)

        if (characteristic != null && connectedDevice != null) {
            screenshotCharValue = byteArray
            notifyCharacteristic(connectedDevice, characteristic)
            Log.d("BLE", "Updated screenshot characteristic to: $value")
        } else {
            Log.e("BLE", "Screenshot characteristic or connected device is null")
        }
    }

    private fun updateAttackToChar(value: String) {
        val byteArray = value.toByteArray(Charsets.UTF_8)
        val characteristic = gattServer.getService(
            ParcelUuid.fromString(serviceUuid).uuid
        )?.getCharacteristic(ParcelUuid.fromString(attackUuid).uuid)

        if (characteristic != null && connectedDevice != null) {
            attackCharValue = byteArray
            notifyCharacteristic(connectedDevice, characteristic)
            Log.d("BLE", "Updated pause characteristic to: $value")
        } else {
            Log.e("BLE", "Pause characteristic or connected device is null")
        }
    }

    private fun updateSpecialToChar(value: String) {
        val byteArray = value.toByteArray(Charsets.UTF_8)
        val characteristic = gattServer.getService(
            ParcelUuid.fromString(serviceUuid).uuid
        )?.getCharacteristic(ParcelUuid.fromString(specialUuid).uuid)

        if (characteristic != null && connectedDevice != null) {
            specialCharValue = byteArray
            notifyCharacteristic(connectedDevice, characteristic)
            Log.d("BLE", "Updated pause characteristic to: $value")
        } else {
            Log.e("BLE", "Pause characteristic or connected device is null")
        }
    }

    private fun updateInteractToChar(value: String) {
        val byteArray = value.toByteArray(Charsets.UTF_8)
        val characteristic = gattServer.getService(
            ParcelUuid.fromString(serviceUuid).uuid
        )?.getCharacteristic(ParcelUuid.fromString(interactUuid).uuid)

        if (characteristic != null && connectedDevice != null) {
            interactCharValue = byteArray
            notifyCharacteristic(connectedDevice, characteristic)
            Log.d("BLE", "Updated pause characteristic to: $value")
        } else {
            Log.e("BLE", "Pause characteristic or connected device is null")
        }
    }

    private fun pauseGame() {
        if (connectedDevice == null) {
            Toast.makeText(requireContext(), "No device connected", Toast.LENGTH_SHORT).show()
            return
        }
        pauseButton.isEnabled = false
        updatePauseToChar("pause")
        Handler(Looper.getMainLooper()).postDelayed({
            pauseButton.isEnabled = true
            Toast.makeText(requireContext(), "Toggled pause", Toast.LENGTH_SHORT).show()
        }, 800)
    }

    private fun takeScreenshot() {
        if (connectedDevice == null) {
            Toast.makeText(requireContext(), "No device connected", Toast.LENGTH_SHORT).show()
            return
        }

        screenshotButton.isEnabled = false
        updateScreenshotToChar("take")

        Handler(Looper.getMainLooper()).postDelayed({
            updateScreenshotToChar("done")
            screenshotButton.isEnabled = true
            Toast.makeText(requireContext(), "Screenshot requested", Toast.LENGTH_SHORT).show()
        }, 1000)
    }

    private fun attackGame() {
        if (connectedDevice == null) {
            Toast.makeText(requireContext(), "No device connected", Toast.LENGTH_SHORT).show()
            return
        }
        attackButton.isEnabled = false
        updateAttackToChar("attack")
        Handler(Looper.getMainLooper()).postDelayed({
            attackButton.isEnabled = true
        }, 800)
    }

    private fun specialGame() {
        if (connectedDevice == null) {
            Toast.makeText(requireContext(), "No device connected", Toast.LENGTH_SHORT).show()
            return
        }
        specialButton.isEnabled = false
        updateSpecialToChar("special")
        Handler(Looper.getMainLooper()).postDelayed({
            specialButton.isEnabled = true
        }, 800)
    }

    private fun interactGame() {
        if (connectedDevice == null) {
            Toast.makeText(requireContext(), "No device connected", Toast.LENGTH_SHORT).show()
            return
        }
        interactButton.isEnabled = false
        updateInteractToChar("interact")
        Handler(Looper.getMainLooper()).postDelayed({
            interactButton.isEnabled = true
        }, 800)
    }

    // region Permissions part
    private fun checkBluetoothConnectPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.checkSelfPermission(
                requireActivity(),
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun checkBluetoothAdvertisePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.checkSelfPermission(
                requireActivity(),
                Manifest.permission.BLUETOOTH_ADVERTISE
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun requestBluetoothConnectPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.requestPermissions(
                requireActivity(),
                arrayOf(Manifest.permission.BLUETOOTH_CONNECT),
                REQUEST_BLUETOOTH_CONNECT
            )
        }
    }

    private fun requestBluetoothAdvertisePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.requestPermissions(
                requireActivity(),
                arrayOf(Manifest.permission.BLUETOOTH_ADVERTISE),
                REQUEST_BLUETOOTH_ADVERTISE
            )
        }
    }
    // endregion

    // region Sensors
    private fun startAccelerometerSensor() {
        val accelerometerSensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (accelerometerSensor != null) {
            sensorManager?.registerListener(this, accelerometerSensor, SensorManager.SENSOR_DELAY_FASTEST)
            Log.d("Sensor", "Accelerometer started.")
        } else {
            requireActivity().runOnUiThread {
                Toast.makeText(requireContext(), "No accelerometer on this device", Toast.LENGTH_SHORT).show()
            }
            Log.d("Sensor", "No accelerometer on this device")
        }
    }

    private fun startMagneticFieldSensor() {
        val magnetometerSensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        if (magnetometerSensor != null) {
            sensorManager?.registerListener(this, magnetometerSensor, SensorManager.SENSOR_DELAY_FASTEST)
            Log.d("Sensor", "Magnetic Field Sensor started.")
        } else {
            requireActivity().runOnUiThread {
                Toast.makeText(requireContext(), "No magnetometer on this device", Toast.LENGTH_SHORT).show()
            }
            Log.d("Sensor", "No magnetometer on this device")
        }
    }

    private fun unregisterSensors() {
        sensorManager?.unregisterListener(this)
        Log.d("Sensor", "Sensors stopped.")
    }

    private fun manageSensors() {
        Log.d("Sensor", "manageSensors called. running=$running, connectedDevice=${connectedDevice != null}")

        val sensorToggle = getSensorToggleFromCharacteristic()
        Log.d("BLE", "Current sensor toggle from characteristic: $sensorToggle")

        if (running && connectedDevice != null && sensorToggle == "start") {
            Log.d("Sensor", "Starting sensors because toggle is start.")
            startAccelerometerSensor()
            startMagneticFieldSensor()
            Log.d("Sensor", "Sensors registered: App in focus and device connected.")
        } else {
            unregisterSensors()
            Log.d("Sensor", "Sensors unregistered: Either app out of focus or no connected device, or toggle is not start")
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || !running || connectedDevice == null) return

        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]

                // Calculate magnitude from X and Z axes
                val magnitude = sqrt(x * x + y * y + z * z)

                // Optional: Apply smoothing (useful to reduce noise)
                val smoothedMagnitude =
                    ALPHA * previousSmoothedMagnitude + (1 - ALPHA) * magnitude
                previousSmoothedMagnitude = smoothedMagnitude

                detectStep(smoothedMagnitude)
                System.arraycopy(event.values, 0, accelerometerReading, 0, accelerometerReading.size)
            }

            Sensor.TYPE_MAGNETIC_FIELD -> {
                System.arraycopy(event.values, 0, magnetometerReading, 0, magnetometerReading.size)
            }
        }

        if (SensorManager.getRotationMatrix(rotationMatrix, null, accelerometerReading, magnetometerReading)) {
            SensorManager.getOrientation(rotationMatrix, orientationAngles)

            val pitch = Math.toDegrees(orientationAngles[1].toDouble()).toFloat()
            handlePitch(pitch)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
    }

    // region Process movement
    private fun handlePitch(pitch: Float) {
        if (pitch > 15 && lastPitch?.let { it <= 15 } == true) {
            updateTurnStateToChar(-1)
            turningTextView.text = getString(R.string.turning_state_left)
            Log.d("Orientation", "Left Turn")
        } else if (pitch < -15 && lastPitch?.let { it >= -15 } == true) {
            updateTurnStateToChar(1)
            turningTextView.text = getString(R.string.turning_state_right)
            Log.d("Orientation", "Right Turn")
        } else if (pitch in -15.0..15.0 && lastPitch?.let { it < -15 || it > 15 } == true) {
            updateTurnStateToChar(0)
            turningTextView.text = getString(R.string.turning_state_none)
            Log.d("Orientation", "Neutral")
        }

        lastPitch = pitch
    }

    private fun detectStep(processMagnitude: Float) {
        val currentTimeMillis = System.currentTimeMillis()

        if (!isStepBegin && processMagnitude > THRESHOLD) {
            // Step begins (crossing threshold upward)
            isStepBegin = true
        } else if (isStepBegin && processMagnitude < THRESHOLD) {
            // Step ends (crossing threshold downward)
            isStepBegin = false

            // Count this as a complete step
            val stepInterval = currentTimeMillis - previousStepTime
            previousStepTime = currentTimeMillis

            // Adaptive scaling: Add step interval to buffer
            if (stepIntervals.size >= maxBufferSize) {
                stepIntervals.removeAt(0) // Keep buffer size fixed
            }
            stepIntervals.add(stepInterval)

            // Calculate average interval
            val averageInterval = stepIntervals.average().toLong()
            val consistentIntervals = stepIntervals.count {
                it in (101..999) // Typical step interval range
            }
            // Dynamically set minimum step interval
            var dynamicMinInterval = (averageInterval * 0.6).toLong()
            dynamicMinInterval = dynamicMinInterval.coerceIn(100, 1000)

            // Ensure a valid step interval
            if (consistentIntervals >= maxBufferSize * 0.8 && stepInterval > dynamicMinInterval) {
                totalStepsInSession++

                // Update UI
                stepCountTextView.text = String.format(getString(R.string.step_count_number), totalStepsInSession)

                updateStepToChar(stepInterval)

                Log.d(
                    "Step",
                    "Step detected. Interval:$stepInterval, Dynamic min Interval:$dynamicMinInterval, Total steps: $totalStepsInSession"
                )
            }
        }
    }
    // endregion
}