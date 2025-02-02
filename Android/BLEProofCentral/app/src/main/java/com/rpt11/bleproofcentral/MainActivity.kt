package com.rpt11.bleproofcentral

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.*
import android.util.Log
import android.view.View
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import java.lang.Math.pow
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.pow


private const val ENABLE_BLUETOOTH_REQUEST_CODE = 1
private const val LOCATION_PERMISSION_REQUEST_CODE = 2
private const val BLUETOOTH_ALL_PERMISSIONS_REQUEST_CODE = 3
private const val SERVICE_UUID = "25AE1441-05D3-4C5B-8381-93D4E07420CF"
private const val CHAR_FOR_READ_UUID = "25AE1442-05D3-4C5B-8381-93D4E07420CF"
private const val CHAR_FOR_WRITE_UUID = "25AE1443-05D3-4C5B-8381-93D4E07420CF"
private const val CHAR_FOR_INDICATE_UUID = "25AE1444-05D3-4C5B-8381-93D4E07420CF"
private const val CCC_DESCRIPTOR_UUID = "00002902-0000-1000-8000-00805f9b34fb"

@SuppressLint("MissingPermission")
class MainActivity : AppCompatActivity() {
    enum class BLELifecycleState {
        Disconnected,
        Scanning,
        Connecting,
        ConnectedDiscovering,
        ConnectedSubscribing,
        Connected
    }

    private var lifecycleState = BLELifecycleState.Disconnected
        @SuppressLint("SetTextI18n")
        set(value) {
            field = value
            appendLog("status = $value")
            runOnUiThread {
                textViewLifecycleState.text = "State: ${value.name}"
                if (value != BLELifecycleState.Connected) {
                    textViewSubscription.text = getString(R.string.text_not_subscribed)
                }
            }
        }
    private val textViewLifecycleState: TextView
        get() = findViewById<TextView>(R.id.textViewLifecycleState)
    private val textViewReadValue: TextView
        get() = findViewById<TextView>(R.id.textViewReadValue)
    private val editTextWriteValue: TextView
        get() = findViewById<EditText>(R.id.editTextWriteValue)
    private val textViewIndicateValue: TextView
        get() = findViewById<TextView>(R.id.textViewIndicateValue)
    private val textViewSubscription: TextView
        get() = findViewById<TextView>(R.id.textViewSubscription)
    private val textViewLog: TextView
        get() = findViewById<TextView>(R.id.textViewLog)
    private val scrollViewLog: ScrollView
        get() = findViewById<ScrollView>(R.id.scrollViewLog)

    private var isScanning = false
    private var connectedGatt: BluetoothGatt? = null
    private var characteristicForRead: BluetoothGattCharacteristic? = null
    private var characteristicForWrite: BluetoothGattCharacteristic? = null
    private var characteristicForIndicate: BluetoothGattCharacteristic? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        ensureBluetoothCanBeUsed { isSuccess, message ->
            prepareAndStartBleScan()
        }

        appendLog("MainActivity.onCreate")
    }

    override fun onDestroy() {
        bleEndLifecycle()
        super.onDestroy()
    }

    fun onTapRead(view: View) {
        var gatt = connectedGatt ?: run {
            appendLog("ERROR: read failed, no connected device")
            return
        }
        var characteristic = characteristicForRead ?: run {
            appendLog("ERROR: read failed, characteristic unavailable $CHAR_FOR_READ_UUID")
            return
        }
        if (!characteristic.isReadable()) {
            appendLog("ERROR: read failed, characteristic not readable $CHAR_FOR_READ_UUID")
            return
        }
        gatt.readCharacteristic(characteristic)
    }

    fun onTapWrite(view: View) {
        var gatt = connectedGatt ?: run {
            appendLog("ERROR: write failed, no connected device")
            return
        }
        var characteristic = characteristicForWrite ?:  run {
            appendLog("ERROR: write failed, characteristic unavailable $CHAR_FOR_WRITE_UUID")
            return
        }
        if (!characteristic.isWriteable()) {
            appendLog("ERROR: write failed, characteristic not writeable $CHAR_FOR_WRITE_UUID")
            return
        }
        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        characteristic.value = editTextWriteValue.text.toString().toByteArray(Charsets.UTF_8)
        gatt.writeCharacteristic(characteristic)
    }

    fun onTapClearLog(view: View) {
        textViewLog.text = "Logs:"
        appendLog("log cleared")
    }

    private fun appendLog(message: String) {
        Log.d("appendLog", message)
        runOnUiThread {
            val strTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            textViewLog.text = textViewLog.text.toString() + "\n$strTime $message"

            // scroll after delay, because textView has to be updated first
            Handler().postDelayed({
                scrollViewLog.fullScroll(View.FOCUS_DOWN)
            }, 16)
        }
    }

    private fun bleEndLifecycle() {
        safeStopBleScan()
        connectedGatt?.close()
        setConnectedGattToNull()
        lifecycleState = BLELifecycleState.Disconnected
    }

    private fun setConnectedGattToNull() {
        connectedGatt = null
        characteristicForRead = null
        characteristicForWrite = null
        characteristicForIndicate = null
    }

    override fun onPause() {
        super.onPause()
        bleEndLifecycle()
        connectedGatt?.disconnect()
    }

    private fun prepareAndStartBleScan() {
        ensureBluetoothCanBeUsed { isSuccess, message ->
            appendLog(message)
            if (isSuccess) {
                safeStartBleScan()
            }
        }
    }

    private fun safeStartBleScan() {
        if (isScanning) {
            appendLog("Already scanning")
            return
        }

        isScanning = true
        lifecycleState = BLELifecycleState.Scanning
        bleScanner.startScan(mutableListOf(scanFilter), scanSettings, scanCallback)
    }

    private fun safeStopBleScan() {
        if (!isScanning) {
            appendLog("Already stopped")
            return
        }

        appendLog("Stopping BLE scan")
        isScanning = false
        bleScanner.stopScan(scanCallback)
    }

    private val bluetoothAdapter: BluetoothAdapter by lazy {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothManager.adapter
    }

    //region BLE Scanning
    private val bleScanner by lazy {
        bluetoothAdapter.bluetoothLeScanner
    }

    private val scanFilter = ScanFilter.Builder()
        .setServiceUuid(ParcelUuid(UUID.fromString(SERVICE_UUID)))
        .build()

    private val scanSettings: ScanSettings
        get() {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) scanSettingsSinceM
            else scanSettingsBeforeM
        }

    private val scanSettingsBeforeM = ScanSettings.Builder()
        .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
        .setReportDelay(0)
        .build()

    @RequiresApi(Build.VERSION_CODES.M)
    private val scanSettingsSinceM = ScanSettings.Builder()
        .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
        .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
        .setMatchMode(ScanSettings.MATCH_MODE_STICKY)
        .setNumOfMatches(ScanSettings.MATCH_NUM_ONE_ADVERTISEMENT)
        .setReportDelay(0)
        .build()

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name: String? = result.scanRecord?.deviceName ?: result.device.name// ?: result.device?.alias
            /*val eee = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                pow(10.0, ((result.txPower - Double(truncating: RSSI))/20))
            } else pow(10.0, ((result.scanRecord?.txPowerLevel?.minus(Double(truncating: RSSI)))?.div(20)!!))*/
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val eee = pow(10.0, ((result.scanRecord?.txPowerLevel?.toDouble() ?: 0.0)/20))
                appendLog(
//                    "onScanResult name=$name address= ${result.device?.address}," +
//                            " PowerLevel=${result.scanRecord?.txPowerLevel}," +
//                            " Power=${result.txPower}," +
//                            " advertisingSid=${result.advertisingSid}, " +
//                            " deviceName=${result.scanRecord?.deviceName}, " +
//                            " Distance = ${calcDistByRSSI(result.rssi, result.txPower)}, " +
                            " Distance = ${rssiToDistance(result.rssi, result.txPower)}, "
//                            " Distance = ${rssiToDistance(result.txPower, result.rssi.toDouble())}, "
//                            " isConnectable=${result.isConnectable} "
                )
                Log.e("Tag", "onScanResult: >>> ${result.rssi}")
            } else appendLog("onScanResult name=$name address= ${result.device?.address}, PowerLevel=${result.scanRecord?.txPowerLevel}")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                result.device.connectGatt(this@MainActivity, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            } else result.device.connectGatt(this@MainActivity, false, gattCallback)

        }

        override fun onBatchScanResults(result: MutableList<ScanResult>?) {
            appendLog("onBatchScanResults, ignoring")
            lifecycleState = BLELifecycleState.ConnectedDiscovering

            /*val name: String? = result?.get(0)?.scanRecord?.deviceName ?: result?.get(0)?.device?.name
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                appendLog("onScanResult name=$name address= ${result?.get(0)?.device?.address}, PowerLevel=${result?.get(0)?.scanRecord?.txPowerLevel}, Power=${result?.get(0)?.txPower}")
            } else appendLog("onScanResult name=$name address= ${result?.get(0)?.device?.address}, PowerLevel=${result?.get(0)?.scanRecord?.txPowerLevel}")*/
        }

        override fun onScanFailed(errorCode: Int) {
            appendLog("onScanFailed errorCode=$errorCode")
            safeStopBleScan()
            lifecycleState = BLELifecycleState.Disconnected
            lifecycleState = BLELifecycleState.Scanning
            prepareAndStartBleScan()
        }
    }

    fun calcDistByRSSI(rssi: Int, measurePower: Int = -59): String {
        val power:Double = (measurePower - rssi.toDouble())/20
//        val power1:Double = pow(10, ((power - Double(truncating: RSSI))/20))
        return 10.0.pow(power).toString()//String.format("%.2f meters", 10.0.pow(power))
    }

    fun rssiToDistance(RSSI: Int, txPower: Int): Double {
        /*
    * RSSI in dBm
    * txPower is a transmitter parameter that calculated according to its physic layer and antenna in dBm
    * Return value in meter
    *
    * You should calculate "PL0" in calibration stage:
    * PL0 = txPower - RSSI; // When distance is distance0 (distance0 = 1m or more)
    *
    * SO, RSSI will be calculated by below formula:
    * RSSI = txPower - PL0 - 10 * n * log(distance/distance0) - G(t)
    * G(t) ~= 0 //This parameter is the main challenge in achiving to more accuracy.
    * n = 2 (Path Loss Exponent, in the free space is 2)
    * distance0 = 1 (m)
    * distance = 10 ^ ((txPower - RSSI - PL0 ) / (10 * n))
    *
    * Read more details:
    *   https://en.wikipedia.org/wiki/Log-distance_path_loss_model
    */
//        pow(10, ((power - Double(truncating: RSSI))/20))
        return 10.0.pow(((txPower - RSSI).toDouble()) / 20)
    }
    //endregion

    //region BLE events, when connected
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            // TODO: timeout timer: if this callback not called - disconnect(), wait 120ms, close()

            val deviceAddress = gatt.device.address

            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    appendLog("Connected to $deviceAddress")
                    safeStopBleScan()
                    // TODO: bonding state

                    // recommended on UI thread https://punchthrough.com/android-ble-guide/
                    Handler(Looper.getMainLooper()).post {
                        lifecycleState = BLELifecycleState.ConnectedDiscovering
                        gatt.discoverServices()
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    appendLog("Disconnected from $deviceAddress")
                    setConnectedGattToNull()
                    gatt.close()
                    lifecycleState = BLELifecycleState.Disconnected
                    prepareAndStartBleScan()
                }
            } else {
                // TODO: random error 133 - close() and try reconnect

                appendLog("ERROR: onConnectionStateChange status=$status deviceAddress=$deviceAddress, disconnecting")

                setConnectedGattToNull()
                gatt.close()
                lifecycleState = BLELifecycleState.Disconnected
                prepareAndStartBleScan()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            appendLog("onServicesDiscovered services.count=${gatt.services.size} status=$status")

            if (status == 129 /*GATT_INTERNAL_ERROR*/) {
                // it should be a rare case, this article recommends to disconnect:
                // https://medium.com/@martijn.van.welie/making-android-ble-work-part-2-47a3cdaade07
                appendLog("ERROR: status=129 (GATT_INTERNAL_ERROR), disconnecting")
                gatt.disconnect()
                return
            }

            val service = gatt.getService(UUID.fromString(SERVICE_UUID)) ?: run {
                appendLog("ERROR: Service not found $SERVICE_UUID, disconnecting")
                gatt.disconnect()
                return
            }

            connectedGatt = gatt
            characteristicForRead = service.getCharacteristic(UUID.fromString(CHAR_FOR_READ_UUID))
            characteristicForWrite = service.getCharacteristic(UUID.fromString(CHAR_FOR_WRITE_UUID))
            characteristicForIndicate = service.getCharacteristic(UUID.fromString(CHAR_FOR_INDICATE_UUID))

            characteristicForIndicate?.let {
                lifecycleState = BLELifecycleState.ConnectedSubscribing
                subscribeToIndications(it, gatt)
            } ?: run {
                appendLog("WARN: characteristic not found $CHAR_FOR_INDICATE_UUID")
                lifecycleState = BLELifecycleState.Connected
            }
        }

        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (characteristic.uuid == UUID.fromString(CHAR_FOR_READ_UUID)) {
                val strValue = characteristic.value.toString(Charsets.UTF_8)
                val log = "onCharacteristicRead " + when (status) {
                    BluetoothGatt.GATT_SUCCESS -> "OK, value=\"$strValue\""
                    BluetoothGatt.GATT_READ_NOT_PERMITTED -> "not allowed"
                    else -> "error $status"
                }
                appendLog(log)
                runOnUiThread {
                    textViewReadValue.text = strValue
                }
            } else {
                appendLog("onCharacteristicRead unknown uuid $characteristic.uuid")
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (characteristic.uuid == UUID.fromString(CHAR_FOR_WRITE_UUID)) {
                val log: String = "onCharacteristicWrite " + when (status) {
                    BluetoothGatt.GATT_SUCCESS -> "OK"
                    BluetoothGatt.GATT_WRITE_NOT_PERMITTED -> "not allowed"
                    BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH -> "invalid length"
                    else -> "error $status"
                }
                appendLog(log)
            } else {
                appendLog("onCharacteristicWrite unknown uuid $characteristic.uuid")
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid == UUID.fromString(CHAR_FOR_INDICATE_UUID)) {
                val strValue = characteristic.value.toString(Charsets.UTF_8)
                appendLog("onCharacteristicChanged value=\"$strValue\"")
                runOnUiThread {
                    textViewIndicateValue.text = strValue
                }
            } else {
                appendLog("onCharacteristicChanged unknown uuid $characteristic.uuid")
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (descriptor.characteristic.uuid == UUID.fromString(CHAR_FOR_INDICATE_UUID)) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    val value = descriptor.value
                    val isSubscribed = value.isNotEmpty() && value[0].toInt() != 0
                    val subscriptionText = when (isSubscribed) {
                        true -> getString(R.string.text_subscribed)
                        false -> getString(R.string.text_not_subscribed)
                    }
                    appendLog("onDescriptorWrite $subscriptionText")
                    runOnUiThread {
                        textViewSubscription.text = subscriptionText
                    }
                } else {
                    appendLog("ERROR: onDescriptorWrite status=$status uuid=${descriptor.uuid} char=${descriptor.characteristic.uuid}")
                }

                // subscription processed, consider connection is ready for use
                lifecycleState = BLELifecycleState.Connected
            } else {
                appendLog("onDescriptorWrite unknown uuid $descriptor.characteristic.uuid")
            }
        }
    }

    private fun subscribeToIndications(characteristic: BluetoothGattCharacteristic, gatt: BluetoothGatt) {
        val cccdUuid = UUID.fromString(CCC_DESCRIPTOR_UUID)
        characteristic.getDescriptor(cccdUuid)?.let { cccDescriptor ->
            if (!gatt.setCharacteristicNotification(characteristic, true)) {
                appendLog("ERROR: setNotification(true) failed for ${characteristic.uuid}")
                return
            }
            cccDescriptor.value = BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            gatt.writeDescriptor(cccDescriptor)
        }
    }
    //endregion

    //region BluetoothGattCharacteristic extension
    fun BluetoothGattCharacteristic.isReadable(): Boolean =
        containsProperty(BluetoothGattCharacteristic.PROPERTY_READ)

    fun BluetoothGattCharacteristic.isWriteable(): Boolean =
        containsProperty(BluetoothGattCharacteristic.PROPERTY_WRITE)

    private fun BluetoothGattCharacteristic.containsProperty(property: Int): Boolean {
        return (properties and property) != 0
    }
    //endregion

    //region Permissions and Settings management
    enum class AskType {
        AskOnce,
        InsistUntilSuccess
    }

    private var activityResultHandlers = mutableMapOf<Int, (Int) -> Unit>()
    private var permissionResultHandlers = mutableMapOf<Int, (Array<out String>, IntArray) -> Unit>()

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        activityResultHandlers[requestCode]?.let { handler ->
            handler(resultCode)
        } ?: runOnUiThread {
            appendLog("ERROR: onActivityResult requestCode=$requestCode result=$resultCode not handled")
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        permissionResultHandlers[requestCode]?.let { handler ->
            handler(permissions, grantResults)
        } ?: runOnUiThread {
            appendLog("ERROR: onRequestPermissionsResult requestCode=$requestCode not handled")
        }
    }

    private fun ensureBluetoothCanBeUsed(completion: (Boolean, String) -> Unit) {
        grantBluetoothCentralPermissions(AskType.AskOnce) { isGranted ->
            if (!isGranted) {
                completion(false, "Bluetooth permissions denied")
                return@grantBluetoothCentralPermissions
            }

            enableBluetooth(AskType.AskOnce) { isEnabled ->
                if (!isEnabled) {
                    completion(false, "Bluetooth OFF")
                    return@enableBluetooth
                }

                grantLocationPermissionIfRequired(AskType.AskOnce) { isGranted ->
                    if (!isGranted) {
                        completion(false, "Location permission denied")
                        return@grantLocationPermissionIfRequired
                    }

                    completion(true, "Bluetooth ON, permissions OK, ready")
                }
            }
        }
    }

    private fun enableBluetooth(askType: AskType, completion: (Boolean) -> Unit) {
        if (bluetoothAdapter.isEnabled) {
            completion(true)
        } else {
            val intentString = BluetoothAdapter.ACTION_REQUEST_ENABLE
            val requestCode = ENABLE_BLUETOOTH_REQUEST_CODE

            // set activity result handler
            activityResultHandlers[requestCode] = { result -> Unit
                val isSuccess = result == Activity.RESULT_OK
                if (isSuccess || askType != AskType.InsistUntilSuccess) {
                    activityResultHandlers.remove(requestCode)
                    completion(isSuccess)
                } else {
                    // start activity for the request again
                    startActivityForResult(Intent(intentString), requestCode)
                }
            }

            // start activity for the request
            startActivityForResult(Intent(intentString), requestCode)
        }
    }

    private fun grantLocationPermissionIfRequired(askType: AskType, completion: (Boolean) -> Unit) {
        val wantedPermissions = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // BLUETOOTH_SCAN permission has flag "neverForLocation", so location not needed
            completion(true)
        } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || hasPermissions(wantedPermissions)) {
            completion(true)
        } else {
            runOnUiThread {
                val requestCode = LOCATION_PERMISSION_REQUEST_CODE

                // prepare motivation message
                val builder = AlertDialog.Builder(this)
                builder.setTitle("Location permission required")
                builder.setMessage("BLE advertising requires location access, starting from Android 6.0")
                builder.setPositiveButton(android.R.string.ok) { _, _ ->
                    requestPermissionArray(wantedPermissions, requestCode)
                }
                builder.setCancelable(false)

                // set permission result handler
                permissionResultHandlers[requestCode] = { permissions, grantResults ->
                    val isSuccess = grantResults.firstOrNull() != PackageManager.PERMISSION_DENIED
                    if (isSuccess || askType != AskType.InsistUntilSuccess) {
                        permissionResultHandlers.remove(requestCode)
                        completion(isSuccess)
                    } else {
                        // show motivation message again
                        builder.create().show()
                    }
                }

                // show motivation message
                builder.create().show()
            }
        }
    }

    private fun grantBluetoothCentralPermissions(askType: AskType, completion: (Boolean) -> Unit) {
        val wantedPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
            )
        } else {
            emptyArray()
        }

        if (wantedPermissions.isEmpty() || hasPermissions(wantedPermissions)) {
            completion(true)
        } else {
            runOnUiThread {
                val requestCode = BLUETOOTH_ALL_PERMISSIONS_REQUEST_CODE

                // set permission result handler
                permissionResultHandlers[requestCode] = { _ /*permissions*/, grantResults ->
                    val isSuccess = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
                    if (isSuccess || askType != AskType.InsistUntilSuccess) {
                        permissionResultHandlers.remove(requestCode)
                        completion(isSuccess)
                    } else {
                        // request again
                        requestPermissionArray(wantedPermissions, requestCode)
                    }
                }

                requestPermissionArray(wantedPermissions, requestCode)
            }
        }
    }

    private fun Context.hasPermissions(permissions: Array<String>): Boolean = permissions.all {
        ActivityCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun Activity.requestPermissionArray(permissions: Array<String>, requestCode: Int) {
        ActivityCompat.requestPermissions(this, permissions, requestCode)
    }
    //endregion
}