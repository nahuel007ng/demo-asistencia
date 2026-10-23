package dev.municipal.blelab

import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import dev.municipal.blelab.service.LabSessionService
import dev.municipal.blelab.ui.LabApp
import dev.municipal.blelab.ui.LabViewModel

class MainActivity : ComponentActivity() {
    private val viewModel: LabViewModel by viewModels()
    private var bound = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        viewModel.onPermissionResult(result)
        promoteServiceIfAllowed()
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val controller = (service as LabSessionService.LabBinder).controller()
            viewModel.attach(controller)
            bound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge()
        setContent {
            LabApp(
                viewModel = viewModel,
                onRequestPermissions = { names -> permissionLauncher.launch(names.toTypedArray()) },
                onExport = { shareExport() },
                onOpenBluetoothSettings = { startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)) },
                onOpenLocationSettings = { startActivity(Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS)) },
            )
        }
    }

    override fun onStart() {
        super.onStart()
        val intent = Intent(this, LabSessionService::class.java)
        promoteServiceIfAllowed(intent)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    private fun promoteServiceIfAllowed(intent: Intent = Intent(this, LabSessionService::class.java)) {
        if (LabSessionService.connectedDeviceForegroundAllowed(this)) {
            ContextCompat.startForegroundService(this, intent)
        }
    }

    override fun onStop() {
        if (bound) {
            unbindService(connection)
            bound = false
        }
        if (!viewModel.sessionRunning()) {
            stopService(Intent(this, LabSessionService::class.java))
        }
        super.onStop()
    }

    private fun shareExport() {
        val files = viewModel.exportFiles()
        if (files.isEmpty()) return
        val uris = ArrayList<Uri>(files.size)
        for (file in files) {
            uris.add(FileProvider.getUriForFile(this, "$packageName.files", file))
        }
        val clip = ClipData.newRawUri("sesion", uris.first())
        for (index in 1 until uris.size) {
            clip.addItem(ClipData.Item(uris[index]))
        }
        val share = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            clipData = clip
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(share, "Exportar sesión"))
    }
}
