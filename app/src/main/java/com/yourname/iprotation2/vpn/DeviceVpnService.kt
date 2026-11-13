package com.yourname.iprotation2.vpn

import android.content.Intent
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.annotation.RequiresApi
import android.widget.Toast
import com.yourname.iprotation2.engine.RotationEngine
import dagger.hilt.android.AndroidEntryPoint
import java.io.FileInputStream
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Makes the whole phone use the rotating proxy: creates a VPN whose system HTTP proxy points to
 * LocalProxyServer. This app itself is excluded from the VPN so its own proxy connections go out directly.
 * Needs Android 10 (API 29) or newer for VpnService.Builder.setHttpProxy.
 */
@AndroidEntryPoint
class DeviceVpnService : VpnService() {

    @Inject lateinit var engine: RotationEngine

    private var tun: ParcelFileDescriptor? = null
    private var server: LocalProxyServer? = null
    private var drain: Thread? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        Toast.makeText(this, "VPN service starting…", Toast.LENGTH_SHORT).show()
        if (tun == null) startVpn()
        return START_NOT_STICKY
    }

    private fun startVpn() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startVpnQ()
        } else {
            Toast.makeText(this, "Whole-phone IP change needs Android 10 or newer", Toast.LENGTH_LONG).show()
            stopSelf()
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun startVpnQ() {
        try {
            val srv = LocalProxyServer { engine.currentProxy }
            srv.start()
            server = srv
            // Every time the proxy rotates, drop open connections so apps reconnect via the NEW proxy.
            scope.launch {
                engine.state.map { it.rotations }.distinctUntilChanged().collect { srv.closeAll() }
            }

            val builder = Builder()
                .setSession("IP Rotation 2.0")
                .addAddress("10.8.0.2", 32)
                .addRoute("0.0.0.0", 0)
                .addAddress("fd00:1:fd00:1::2", 128)
                .addRoute("::", 0)
                .setHttpProxy(ProxyInfo.buildDirectProxy("127.0.0.1", srv.port))
            // Our own proxy checks / forwarding must NOT go through the VPN.
            builder.addDisallowedApplication(packageName)

            val fd = builder.establish()
            if (fd == null) {
                Toast.makeText(this, "VPN permission missing (establish returned null)", Toast.LENGTH_LONG).show()
                stopSelf()
                return
            }
            tun = fd
            // Drop anything that does not honour the proxy (nothing is forwarded from the TUN).
            drain = Thread {
                val input = FileInputStream(fd.fileDescriptor)
                val buf = ByteArray(32 * 1024)
                try { while (input.read(buf) >= 0) { /* discard */ } } catch (_: Exception) {}
            }.also { it.isDaemon = true; it.start() }
        } catch (e: Exception) {
            Toast.makeText(this, "VPN error: ${e.javaClass.simpleName}: ${e.message}", Toast.LENGTH_LONG).show()
            stopSelf()
        }
    }

    override fun onRevoke() {
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        try { tun?.close() } catch (_: Exception) {}
        tun = null
        server?.stop()
        server = null
        drain = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.yourname.iprotation2.action.STOP_VPN"
    }
}
