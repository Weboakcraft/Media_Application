package com.oakcraft.lumen.blocker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import com.oakcraft.lumen.LumenApp
import com.oakcraft.lumen.R
import com.oakcraft.lumen.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicLong

/**
 * A DNS-only tunnel.
 *
 * Only the stub resolver address is routed into the tun device, so the device's actual
 * traffic — video, downloads, everything — never passes through this process. That keeps
 * throughput untouched and the battery cost close to nil: the service wakes only when an
 * app resolves a name.
 *
 * Queries for blocked names are answered locally with NXDOMAIN. Everything else is
 * forwarded to the upstream resolver over a protected socket, which bypasses the tunnel
 * and so cannot loop back into this service.
 */
class LumenVpnService : VpnService() {

    private var tunnel: ParcelFileDescriptor? = null
    private var scope: CoroutineScope? = null
    private var workerJob: Job? = null

    private val engine = BlocklistEngine()
    private val writeLock = Mutex()
    // Caps how many upstream lookups can be in flight, so a burst of queries cannot
    // open an unbounded number of sockets.
    private val upstreamSlots = Semaphore(MAX_CONCURRENT_UPSTREAM)

    private val pendingQueries = AtomicLong(0)
    private val pendingBlocked = AtomicLong(0)

    private lateinit var upstreamAddress: InetAddress

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopTunnel()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> startTunnel()
        }
        return START_STICKY
    }

    private fun startTunnel() {
        if (tunnel != null) return
        ShieldState.setStarting()

        val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = serviceScope

        serviceScope.launch {
            try {
                val app = applicationContext as LumenApp
                val settings = app.settings

                upstreamAddress = withContext(Dispatchers.IO) {
                    runCatching { InetAddress.getByName(settings.upstreamDns.first()) }
                        .getOrElse { InetAddress.getByName(FALLBACK_UPSTREAM) }
                }

                val blocked = app.blocklistRepository.loadCached() + settings.userDeny.first()
                engine.replace(blocked = blocked, allowed = settings.userAllow.first())

                val descriptor = buildTunnel(settings.bypassPackages.first())
                    ?: error("The system did not grant a VPN interface.")
                tunnel = descriptor

                startForegroundNotification(blocked.size)
                ShieldState.setRunning(blocked.size)

                workerJob = launch { pumpPackets(descriptor) }
                launch { flushCountersPeriodically() }
            } catch (e: Exception) {
                Log.e(TAG, "Could not start the tunnel", e)
                ShieldState.setStopped(e.message ?: "The tunnel could not be started.")
                stopTunnel()
                stopSelf()
            }
        }
    }

    private fun buildTunnel(bypassPackages: Set<String>): ParcelFileDescriptor? {
        val builder = Builder()
            .setSession(getString(R.string.app_name))
            .setMtu(MTU)
            // A /32 and /128 route for the stub resolvers is the entire routing table of
            // this tunnel. Nothing else is captured.
            .addAddress(TUN_IPV4, 32)
            .addDnsServer(DNS_IPV4)
            .addRoute(DNS_IPV4, 32)
            .addAddress(TUN_IPV6, 128)
            .addDnsServer(DNS_IPV6)
            .addRoute(DNS_IPV6, 128)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        for (packageName in bypassPackages) {
            try {
                builder.addDisallowedApplication(packageName)
            } catch (e: Exception) {
                // The app was uninstalled since the bypass list was saved; skip it.
                Log.w(TAG, "Bypass package no longer installed: $packageName")
            }
        }

        val configureIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        builder.setConfigureIntent(configureIntent)

        return builder.establish()
    }

    /**
     * Reads packets off the tun device until the service is torn down.
     *
     * The read is blocking, so this coroutine parks on the file descriptor rather than
     * spinning; closing the descriptor in [stopTunnel] is what unblocks it.
     */
    private suspend fun pumpPackets(descriptor: ParcelFileDescriptor) {
        val input = FileInputStream(descriptor.fileDescriptor)
        val output = FileOutputStream(descriptor.fileDescriptor)
        val buffer = ByteArray(MTU)

        try {
            while (currentScopeActive()) {
                val read = try {
                    input.read(buffer)
                } catch (e: Exception) {
                    if (currentScopeActive()) Log.w(TAG, "tun read ended", e)
                    break
                }
                if (read <= 0) {
                    if (read < 0) break
                    continue
                }

                val datagram = IpPacket.parseUdp(buffer, read) ?: continue
                if (datagram.destPort != DnsMessage.PORT) continue

                val question = DnsMessage.readQuestion(datagram.payload)
                if (question == null) {
                    // Not a question we can read — forward it rather than dropping it,
                    // so unusual queries still resolve normally.
                    scope?.launch { forward(datagram, output) }
                    continue
                }

                pendingQueries.incrementAndGet()

                if (engine.isBlocked(question.name)) {
                    pendingBlocked.incrementAndGet()
                    ShieldState.recordBlocked(question.name)
                    val refusal = DnsMessage.refuse(datagram.payload, question)
                    writePacket(output, IpPacket.buildUdpReply(datagram, refusal))
                } else {
                    ShieldState.recordAllowed()
                    scope?.launch { forward(datagram, output) }
                }
            }
        } finally {
            runCatching { input.close() }
            runCatching { output.close() }
        }
    }

    /**
     * Sends a query upstream and writes the answer back into the tunnel.
     *
     * Each lookup gets its own short-lived socket, which keeps request and response
     * matching trivial and means a slow or lost answer cannot stall anything else.
     */
    private suspend fun forward(request: IpPacket.UdpDatagram, output: FileOutputStream) {
        upstreamSlots.withPermit {
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket()
                // Keeps this socket outside the tunnel we just created.
                if (!protect(socket)) {
                    Log.w(TAG, "Could not protect the upstream socket")
                    return
                }
                socket.soTimeout = UPSTREAM_TIMEOUT_MS

                socket.send(
                    DatagramPacket(
                        request.payload,
                        request.payload.size,
                        upstreamAddress,
                        DnsMessage.PORT
                    )
                )

                val response = ByteArray(MAX_DNS_RESPONSE)
                val packet = DatagramPacket(response, response.size)
                socket.receive(packet)

                val answer = response.copyOfRange(0, packet.length)
                writePacket(output, IpPacket.buildUdpReply(request, answer))
            } catch (e: Exception) {
                // A dropped upstream answer looks to the client like a lost UDP packet,
                // which its resolver already knows how to retry.
                Log.d(TAG, "Upstream lookup failed", e)
            } finally {
                runCatching { socket?.close() }
            }
        }
    }

    private suspend fun writePacket(output: FileOutputStream, packet: ByteArray) {
        writeLock.withLock {
            try {
                output.write(packet)
            } catch (e: Exception) {
                Log.d(TAG, "tun write failed", e)
            }
        }
    }

    /** Folds session counters into persistent totals occasionally rather than per query. */
    private suspend fun flushCountersPeriodically() {
        val settings = (applicationContext as LumenApp).settings
        while (currentScopeActive()) {
            delay(COUNTER_FLUSH_MS)
            val queries = pendingQueries.getAndSet(0)
            val blocked = pendingBlocked.getAndSet(0)
            if (queries > 0 || blocked > 0) {
                runCatching { settings.addCounters(queries, blocked) }
            }
        }
    }

    private fun currentScopeActive(): Boolean = scope?.isActive == true

    private fun startForegroundNotification(ruleCount: Int) {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_shield_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.channel_shield_desc)
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }

        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, LumenVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.shield_notification_title))
            .setContentText(
                resources.getQuantityString(R.plurals.shield_rules_loaded, ruleCount, ruleCount)
            )
            .setSmallIcon(R.drawable.ic_shield)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_shield),
                    getString(R.string.shield_notification_stop),
                    stopIntent
                ).build()
            )
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopTunnel() {
        workerJob?.cancel()
        workerJob = null
        scope?.cancel()
        scope = null

        runCatching { tunnel?.close() }
        tunnel = null

        ShieldState.setStopped()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onRevoke() {
        // The user granted the VPN slot to another app, or revoked ours.
        stopTunnel()
        stopSelf()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopTunnel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "LumenVpnService"

        const val ACTION_START = "com.oakcraft.lumen.action.START_SHIELD"
        const val ACTION_STOP = "com.oakcraft.lumen.action.STOP_SHIELD"

        private const val CHANNEL_ID = "lumen_shield"
        private const val NOTIFICATION_ID = 1001

        private const val MTU = 1500
        private const val MAX_DNS_RESPONSE = 4096
        private const val UPSTREAM_TIMEOUT_MS = 5000
        private const val MAX_CONCURRENT_UPSTREAM = 48
        private const val COUNTER_FLUSH_MS = 30_000L

        private const val TUN_IPV4 = "10.111.222.1"
        private const val DNS_IPV4 = "10.111.222.2"
        private const val TUN_IPV6 = "fd00:1:f1ee:1::1"
        private const val DNS_IPV6 = "fd00:1:f1ee:1::2"
        private const val FALLBACK_UPSTREAM = "1.1.1.1"

        fun start(context: Context) {
            val intent = Intent(context, LumenVpnService::class.java).setAction(ACTION_START)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, LumenVpnService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }
    }
}
