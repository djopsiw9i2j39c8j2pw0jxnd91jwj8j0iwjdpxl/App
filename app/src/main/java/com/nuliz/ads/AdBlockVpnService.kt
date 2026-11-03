package com.nuliz.ads

import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

class AdBlockVpnService : VpnService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    @Volatile
    private var tun: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopProtection()
            return START_NOT_STICKY
        }
        startProtection()
        return START_STICKY
    }

    override fun onRevoke() {
        stopProtection()
    }

    override fun onDestroy() {
        scope.cancel()
        tun?.close()
        super.onDestroy()
    }

    private fun startProtection() {
        if (job?.isActive == true) return
        val notification = Notifier.service(this, Stats.state.value.total)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(Notifier.ID_SERVICE, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(Notifier.ID_SERVICE, notification)
        }
        job = scope.launch {
            val blocklist = Blocklist.get(this@AdBlockVpnService)
            val fd = openTunnel() ?: return@launch stopProtection()
            tun = fd
            Stats.setListSize(blocklist.size)
            Stats.setRunning(true)
            pump(fd, blocklist)
            if (tun === fd) stopProtection()
        }
    }

    private fun stopProtection() {
        job?.cancel()
        job = null
        tun?.close()
        tun = null
        Stats.setRunning(false)
        Stats.persist()
        Notifier.clearBlocked(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun openTunnel(): ParcelFileDescriptor? = Builder()
        .setSession("Nuliz Ads")
        .setMtu(MTU)
        .addAddress(CLIENT_ADDR, 32)
        .addDnsServer(DNS_ADDR)
        .addRoute(DNS_ADDR, 32)
        .setBlocking(true)
        .establish()

    private fun pump(fd: ParcelFileDescriptor, blocklist: Blocklist) {
        val input = FileInputStream(fd.fileDescriptor)
        val output = FileOutputStream(fd.fileDescriptor)
        val upstream = Upstream(this)
        val buf = ByteArray(MTU)
        try {
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                val query = DnsQuery.parse(buf, n) ?: continue
                val category = blocklist.match(query.name)
                if (category == null) {
                    scope.launch { upstream.resolve(query.message())?.let { write(output, query.wrap(it)) } }
                } else {
                    write(output, query.blockedReply())
                    Stats.record(query.name, category)?.let { Notifier.blocked(this, it, Stats.state.value.total) }
                }
            }
        } catch (_: IOException) {
        }
    }

    private fun write(out: FileOutputStream, packet: ByteArray) {
        try {
            synchronized(out) { out.write(packet) }
        } catch (_: IOException) {
        }
    }

    companion object {
        const val ACTION_STOP = "com.nuliz.ads.STOP"
        private const val MTU = 1500
        private const val CLIENT_ADDR = "10.111.0.2"
        private const val DNS_ADDR = "10.111.0.1"

        fun start(context: Context) =
            ContextCompat.startForegroundService(context, Intent(context, AdBlockVpnService::class.java))

        fun stop(context: Context) {
            context.startService(Intent(context, AdBlockVpnService::class.java).setAction(ACTION_STOP))
        }
    }
}
