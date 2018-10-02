package com.mohsenoid.androidble

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.ParcelUuid
import android.support.v4.app.ActivityCompat
import android.support.v4.content.ContextCompat
import android.support.v7.app.AppCompatActivity
import android.view.View
import android.widget.Toast
import com.polidea.rxandroidble2.RxBleClient
import com.polidea.rxandroidble2.RxBleConnection
import com.polidea.rxandroidble2.RxBleDevice
import com.polidea.rxandroidble2.exceptions.BleGattCallbackTimeoutException
import com.polidea.rxandroidble2.exceptions.BleGattCharacteristicException
import com.polidea.rxandroidble2.internal.RxBleLog
import com.polidea.rxandroidble2.scan.ScanFilter
import com.polidea.rxandroidble2.scan.ScanSettings
import io.reactivex.Observable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.Disposable
import io.reactivex.rxkotlin.subscribeBy
import kotlinx.android.synthetic.main.activity_main.*
import timber.log.Timber
import java.util.*
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var context: Context
    private lateinit var rxBleClient: RxBleClient

    private var scanDisposable: Disposable? = null
    private var connectionDisposable: Disposable? = null
    private var connectionStateDisposable: Disposable? = null

    private var bleConnection: RxBleConnection? = null

    private var device: RxBleDevice? = null

    private var currentMTU: Int = DEFAULT_MTU

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        context = applicationContext

        setupTimber()

        setupUI()

        checkAllRequiredPermissions()

        RxBleClient.setLogLevel(RxBleLog.VERBOSE)
        rxBleClient = RxBleClient.create(context)
    }

    private fun setupTimber() {
        Timber.plant(object : Timber.DebugTree() {
            override fun createStackElementTag(element: StackTraceElement): String {
                // adding file name and line number link to logs
                return String.format(Locale.US, "%s(%s:%d)", super.createStackElementTag(element), element.fileName, element.lineNumber)
            }
        })
    }

    private fun setupUI() {
        btScan.setOnClickListener(::onScanClick)
        btAdd.setOnClickListener(::onAddClick)
    }

    private fun checkAllRequiredPermissions(): Boolean {
        val requiredPermissions =
                arrayOf(
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                        Manifest.permission.BLUETOOTH,
                        Manifest.permission.BLUETOOTH_ADMIN
                )

        for (permission in requiredPermissions) {
            if (ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, requiredPermissions, REQUEST_ALL_PERMISSIONS)
                return false
            }
        }

        return true
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        Timber.i("DebugActivity cycle: onRequestPermissionsResult $requestCode")

        when (requestCode) {
            REQUEST_ALL_PERMISSIONS -> finishIfRequiredPermissionsNotGranted(grantResults)
            else -> {
            }
        }
    }

    private fun finishIfRequiredPermissionsNotGranted(grantResults: IntArray) {
        if (grantResults.isNotEmpty()) {
            for (grantResult in grantResults) {
                // If request is cancelled, the result arrays are empty.
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    // permission was granted, yay! Do the
                    // contacts-related task you need to do.
                } else {
                    // permission denied, boo! Disable the
                    // functionality that depends on this permission.
                    Toast.makeText(this, "Required permissions not granted! We need them all!!!", Toast.LENGTH_LONG).show()
                    finish()
                    break
                }
            }
        } else {
            Toast.makeText(this, "Required permissions not granted! We need them all!!!", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun onScanClick(view: View) {
        startScan()
    }

    private fun onAddClick(view: View) {
        writeCounter()
    }

    private fun startScan() {
        scanDisposable =
                rxBleClient
                        .scanBleDevices(getSettings(), getFilter())
//                        .observeOn(Schedulers.io())
                        .filter { scanResult ->
                            // filter previously founded devices
                            scanResult.bleDevice != device
                        }
                        .timeout(SCAN_TIMEOUT, TimeUnit.MILLISECONDS, Observable.empty())
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribeBy(
                                onNext = { scanResult ->
                                    device = scanResult.bleDevice
                                    Timber.i("Found $device")
                                },
                                onError = { throwable ->
                                    Timber.e(throwable)
                                },
                                onComplete = {
                                    device?.let {
                                        startConnecting(it)
                                    }
                                            ?: Toast.makeText(context, "No device found!", Toast.LENGTH_LONG).show()
                                })
    }

    private fun getSettings(): ScanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()

    private fun getFilter(): ScanFilter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()

    private fun mayDisconnect() {
        connectionStateDisposable?.apply { if (!isDisposed) dispose() }
        connectionDisposable?.apply { if (!isDisposed) dispose() }
        bleConnection = null
        device = null
        currentMTU = DEFAULT_MTU
    }

    private fun startConnecting(device: RxBleDevice) {
        Timber.i("connecting to $device")

        mayDisconnect()

        connectionStateDisposable = device.observeConnectionStateChanges()
                .observeOn(AndroidSchedulers.mainThread())
                .subscribeBy(
                        onNext = { connectionState: RxBleConnection.RxBleConnectionState ->
                            when (connectionState) {
                                RxBleConnection.RxBleConnectionState.CONNECTING -> Timber.i("connecting...")
                                RxBleConnection.RxBleConnectionState.CONNECTED -> Timber.i("connected!")
                                RxBleConnection.RxBleConnectionState.DISCONNECTING -> Timber.i("disconnecting...")
                                RxBleConnection.RxBleConnectionState.DISCONNECTED -> Timber.i("disconnected!!!")
                            }
                        },
                        onError = { throwable ->
                            Timber.w(throwable)
                        },
                        onComplete = {
                            Timber.i("state change complete!")
                        })

        connectionDisposable = device.establishConnection(true)
//                .observeOn(AndroidSchedulers.mainThread())
                .subscribeBy(
                        onNext = { bleConnection ->
                            this@MainActivity.bleConnection = bleConnection
                            requestMTU()
                            readCounter()
                            registerCounter()
                        },
                        onError = { throwable ->
                            Timber.e(throwable, "Device disconnected: ${device.name}(${device.macAddress})")
                        },
                        onComplete = {
                            Timber.i("connection complete!")
                        })
    }

    private fun requestMTU() {
        bleConnection?.apply {
            requestMtu(MAX_MTU)
                    .subscribeBy(
                            onSuccess = { mtu ->
                                Timber.i("new MTU: $mtu")
                                currentMTU = mtu
                            },
                            onError = { throwable ->
                                Timber.e(throwable)
                            })
        }
    }

    private fun readCounter() {
        bleConnection?.apply {
            readCharacteristic(CHARACTERISTIC_COUNTER_UUID)
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribeBy(
                            onSuccess = { value ->
                                Timber.i("counter value: ${value[0].toInt()}")
                                tvCounter.text = value[0].toInt().toString()
                            },
                            onError = { throwable ->
                                when (throwable) {
                                    is BleGattCharacteristicException,
                                    is BleGattCallbackTimeoutException -> {
                                        Timber.w("BLE require bonding!!!")
                                        bondDevice()
                                    }
                                    else -> Timber.e(throwable)
                                }
                            })
        }
    }

    private fun bondDevice() {
        // broadcast receiver for bond state changes
        val bondChangeReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                Timber.i("onReceive")

                val bondDevice = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                Timber.i("Bond Device: $bondDevice")

                val boundState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)

                when (boundState) {
                    BluetoothDevice.BOND_BONDING -> {
                        Timber.i("BOND_BONDING")
                        // context . startService (new Intent (context, BluetoothService.class))
                    }

                    BluetoothDevice.BOND_BONDED -> {
                        Timber.i("BOND_BONDED")
                        context.unregisterReceiver(this)
                    }

                    BluetoothDevice.BOND_NONE -> {
                        Timber.i("BOND_NONE")
                        context.unregisterReceiver(this)
                    }
                }
            }
        }

        val intentFilter = IntentFilter()
        with(intentFilter) {
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            priority = Int.MAX_VALUE
        }

        context.registerReceiver(bondChangeReceiver, intentFilter)

        device?.bluetoothDevice?.createBond()
    }

    private fun registerCounter() {
        bleConnection?.apply {
            setupNotification(CHARACTERISTIC_COUNTER_UUID)//, NotificationSetupMode.COMPAT)
                    .subscribeBy(
                            onNext = { observable ->
                                observable
                                        .observeOn(AndroidSchedulers.mainThread())
                                        .subscribeBy(
                                                onNext = { value ->
                                                    Timber.i("counter value: ${value[0].toInt()}")
                                                    tvCounter.text = value[0].toInt().toString()
                                                },
                                                onError = { throwable ->
                                                    Timber.e(throwable)
                                                },
                                                onComplete = {
                                                    Timber.i("Indication value complete!")
                                                })

                            },
                            onError = { throwable ->
                                Timber.e(throwable)
                            },
                            onComplete = {
                                Timber.i("Indication complete!")
                            })
        }
    }

    private fun writeCounter() {
        bleConnection?.apply {
            writeCharacteristic(CHARACTERISTIC_INTERACTOR_UUID, byteArrayOf(0))
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribeBy(
                            onSuccess = { value ->
                                Timber.i("write value: ${value[0].toInt()}")
                            },
                            onError = { throwable ->
                                when (throwable) {
                                    is BleGattCharacteristicException,
                                    is BleGattCallbackTimeoutException -> {
                                        Timber.w("BLE require bonding!!!")
                                        bondDevice()
                                    }
                                    else -> Timber.e(throwable)
                                }
                            })
        }
    }

    companion object {
        private const val REQUEST_ALL_PERMISSIONS = 1001

        private val SERVICE_UUID = UUID.fromString("795090c7-420d-4048-a24e-18e60180e23c")
        private val CHARACTERISTIC_COUNTER_UUID = UUID.fromString("31517c58-66bf-470c-b662-e352a6c80cba")
        private val CHARACTERISTIC_INTERACTOR_UUID = UUID.fromString("0b89d2d4-0ea6-4141-86bb-0c5fb91ab14a")

        private val DESCRIPTOR_CONFIG_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private const val SCAN_TIMEOUT: Long = 5_000
        private const val DEFAULT_MTU: Int = 23
        private const val MAX_MTU: Int = 517
    }
}
