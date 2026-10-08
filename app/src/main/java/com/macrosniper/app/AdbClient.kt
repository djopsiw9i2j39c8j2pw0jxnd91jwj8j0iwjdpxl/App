package com.macrosniper.app

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.Random
import java.security.Signature
import java.text.SimpleDateFormat
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Chạm màn hình qua "Gỡ lỗi không dây" (Wireless debugging) của Android 11+.
 * App TỰ làm máy khách ADB (thư viện libadb-android nhúng sẵn trong APK) nên KHÔNG cần
 * Shizuku / app ngoài / máy tính. Lệnh `input tap` chạy với quyền shell nên hệ thống coi
 * là cú chạm thật, không phải cử chỉ Trợ năng.
 */
object AdbClient {

    enum class State { OFF, CONNECTING, CONNECTED }

    @Volatile
    var state: State = State.OFF
        private set

    @Volatile
    var lastError: String = ""
        private set

    /** true = người dùng muốn giữ kết nối -> tự nối lại khi rớt (đổi mạng, tắt/bật Wi-Fi...). */
    @Volatile
    private var wantUp = false
    private var lastTry = 0L

    private var mgr: AbsAdbConnectionManager? = null
    private var stream: Any? = null
    private var outS: OutputStream? = null

    // "Ngón tay phụ" (GhostTouch chạy bằng app_process quyền shell): ghi thẳng đa chạm vào màn hình cảm ứng
    // để Android coi là thêm 1 ngón thật -> ngón gốc của mình KHÔNG bị huỷ/kẹt khi macro đang bấm.
    @Volatile
    var ghostReady = false
        private set

    @Volatile
    private var ghostNote = ""
    private var ghostStream: Any? = null
    private var ghostOut: OutputStream? = null

    /** Thời gian ngón phụ đè xuống mỗi cú chạm (ms). */
    private const val GHOST_HOLD_MS = 35

    private val ui = Handler(Looper.getMainLooper())
    private val bg = Executors.newSingleThreadExecutor() // kết nối / ghép cặp (chặn lâu)
    private val io = Executors.newSingleThreadExecutor() // ghi lệnh vào shell
    private val seq = AtomicInteger()
    private val acks = ConcurrentHashMap<Int, () -> Unit>()

    /** Ai muốn biết trạng thái đổi (bảng nổi, MainActivity) thì đăng ký ở đây. */
    val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun supported(): Boolean = Build.VERSION.SDK_INT >= 30
    fun isConnected(): Boolean = state == State.CONNECTED

    fun statusText(): String = when {
        !supported() -> "Cần Android 11 trở lên để dùng Gỡ lỗi WiFi"
        state == State.CONNECTED && ghostReady -> "●  Đã kết nối · ngón tay phụ (không chặn ngón thật)"
        state == State.CONNECTED -> "●  Đã kết nối · chạm bằng input tap" +
                (if (ghostNote.isNotEmpty()) " · $ghostNote" else "")
        state == State.CONNECTING -> "…  Đang kết nối"
        else -> "○  Chưa kết nối" + (if (lastError.isNotEmpty()) " · $lastError" else "")
    }

    fun statusColor(): Int = when {
        !supported() -> Theme.DANGER
        state == State.CONNECTED -> Theme.ACCENT
        state == State.CONNECTING -> Theme.LIME
        else -> Theme.DANGER
    }

    private fun notifyUi() {
        ui.post {
            for (l in listeners) {
                try {
                    l()
                } catch (_: Throwable) {
                }
            }
        }
    }


    // ------------------------------------------------------------------ chứng chỉ tự ký (DER thủ công)

    private fun derLen(n: Int): ByteArray = when {
        n < 128 -> byteArrayOf(n.toByte())
        n < 256 -> byteArrayOf(0x81.toByte(), n.toByte())
        else -> byteArrayOf(0x82.toByte(), (n shr 8).toByte(), n.toByte())
    }

    private fun der(tag: Int, vararg parts: ByteArray): ByteArray {
        var body = ByteArray(0)
        for (p in parts) body += p
        return byteArrayOf(tag.toByte()) + derLen(body.size) + body
    }

    private fun utcTime(ms: Long): ByteArray {
        val f = SimpleDateFormat("yyMMddHHmmss'Z'", java.util.Locale.US)
        f.timeZone = TimeZone.getTimeZone("UTC")
        return der(0x17, f.format(Date(ms)).toByteArray(Charsets.US_ASCII))
    }

    /** X.509 v3 tự ký, RSA-2048 + SHA256withRSA. [spki] là SubjectPublicKeyInfo của khoá công khai. */
    private fun selfSignedDer(spki: ByteArray, priv: PrivateKey): ByteArray {
        val sigAlg = der(
            0x30,
            byteArrayOf(0x06, 0x09, 0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x01, 0x0B),
            byteArrayOf(0x05, 0x00)
        )
        val name = der(
            0x30,
            der(
                0x31,
                der(
                    0x30,
                    byteArrayOf(0x06, 0x03, 0x55, 0x04, 0x03),
                    der(0x0C, "MacroSniper".toByteArray(Charsets.UTF_8))
                )
            )
        )
        val serial = ByteArray(8)
        SecureRandom().nextBytes(serial)
        serial[0] = (serial[0].toInt() and 0x7F or 0x40).toByte() // dương, khác 0
        val now = System.currentTimeMillis()
        val validity = der(0x30, utcTime(now - 86_400_000L), utcTime(now + 20L * 365L * 86_400_000L))
        val version = der(0xA0, der(0x02, byteArrayOf(0x02)))
        val tbs = der(0x30, version, der(0x02, serial), sigAlg, name, validity, name, spki)
        val sig = Signature.getInstance("SHA256withRSA")
        sig.initSign(priv)
        sig.update(tbs)
        val sigBytes = sig.sign()
        return der(0x30, tbs, sigAlg, der(0x03, byteArrayOf(0x00), sigBytes))
    }

    // ------------------------------------------------------------------ khoá + chứng chỉ

    private class Mgr(ctx: Context) : AbsAdbConnectionManager() {
        private val priv: PrivateKey
        private val cert: Certificate

        init {
            setApi(Build.VERSION.SDK_INT)
            val sp = ctx.getSharedPreferences("macro_sniper_adb", Context.MODE_PRIVATE)
            var k: PrivateKey? = null
            var c: Certificate? = null
            try {
                val ps = sp.getString("priv", null)
                val cs = sp.getString("cert", null)
                if (ps != null && cs != null) {
                    k = KeyFactory.getInstance("RSA")
                        .generatePrivate(PKCS8EncodedKeySpec(Base64.decode(ps, Base64.NO_WRAP)))
                    c = CertificateFactory.getInstance("X.509")
                        .generateCertificate(ByteArrayInputStream(Base64.decode(cs, Base64.NO_WRAP)))
                }
            } catch (_: Throwable) {
                k = null
                c = null
            }
            if (k == null || c == null) {
                val kpg = KeyPairGenerator.getInstance("RSA")
                kpg.initialize(2048, SecureRandom())
                val kp = kpg.generateKeyPair()
                val pk = kp.private
                val der = selfSignedDer(kp.public.encoded, pk)
                val impl = CertificateFactory.getInstance("X.509")
                    .generateCertificate(ByteArrayInputStream(der))
                k = pk
                c = impl
                sp.edit()
                    .putString("priv", Base64.encodeToString(pk.encoded, Base64.NO_WRAP))
                    .putString("cert", Base64.encodeToString(impl.encoded, Base64.NO_WRAP))
                    .apply()
            }
            priv = k!!
            cert = c!!
        }

        override fun getPrivateKey(): PrivateKey = priv
        override fun getCertificate(): Certificate = cert
        override fun getDeviceName(): String = "MacroSniper"
    }

    @Synchronized
    private fun manager(app: Context): AbsAdbConnectionManager {
        val m = mgr
        if (m != null) return m
        val n = Mgr(app)
        mgr = n
        return n
    }

    // ------------------------------------------------------------------ ghép cặp / kết nối

    /**
     * Ghép cặp (làm 1 lần). Chỉ cần [code] = mã 6 số trên hộp thoại "Ghép nối thiết bị bằng mã"
     * (hộp thoại phải đang MỞ). App tự dò cổng ghép cặp bằng mDNS.
     */
    fun pair(ctx: Context, code: String, done: (Boolean, String) -> Unit) {
        val app = ctx.applicationContext
        bg.execute {
            var ok = false
            var msg: String
            try {
                val f = discover(app, "_adb-tls-pairing._tcp", 5000L)
                if (f == null) {
                    msg = "Chưa thấy hộp thoại ghép nối. Vào Gỡ lỗi không dây → \"Ghép nối thiết bị bằng mã\", " +
                            "để hộp thoại đó MỞ rồi mới ghép (cần bật Wi-Fi)."
                } else {
                    val m = manager(app)
                    var err = ""
                    ok = try {
                        m.pair("127.0.0.1", f.port, code)
                    } catch (t: Throwable) {
                        err = t.message ?: t.javaClass.simpleName
                        false
                    }
                    if (!ok && f.host != null) {
                        ok = try {
                            m.pair(f.host, f.port, code)
                        } catch (t: Throwable) {
                            err = t.message ?: t.javaClass.simpleName
                            false
                        }
                    }
                    msg = if (ok) "Ghép cặp thành công" else
                        "Ghép cặp thất bại · sai mã hoặc hộp thoại đã đóng, hãy mở lại để lấy mã mới" +
                                (if (err.isNotEmpty()) " ($err)" else "")
                }
            } catch (t: Throwable) {
                msg = "Lỗi ghép cặp: " + (t.message ?: t.javaClass.simpleName)
            }
            val r = ok
            val m2 = msg
            ui.post { done(r, m2) }
        }
    }

    /** Tự dò cổng (mDNS) rồi kết nối + mở 1 shell dùng suốt để gửi lệnh chạm. */
    fun connect(ctx: Context) {
        val app = ctx.applicationContext
        if (!supported()) {
            lastError = ""
            notifyUi()
            return
        }
        synchronized(this) {
            if (state != State.OFF) return
            state = State.CONNECTING
            lastTry = SystemClock.uptimeMillis()
            wantUp = true
        }
        notifyUi()
        bg.execute {
            try {
                val m = manager(app)
                val f = discover(app, "_adb-tls-connect._tcp", 8000L)
                    ?: throw IllegalStateException("không thấy dịch vụ Gỡ lỗi không dây (đã bật chưa? có Wi-Fi chưa?)")
                m.connect("127.0.0.1", f.port)
                val st = m.openStream("shell:")
                val ins = st.openInputStream()
                val outs = st.openOutputStream()
                stream = st
                outS = outs
                lastError = ""
                state = State.CONNECTED
                notifyUi()
                val t = Thread { readLoop(st, ins) }
                t.isDaemon = true
                t.start()
                // Đã chạm được bằng input tap rồi; giờ thử bật "ngón tay phụ" (nếu máy cho phép thì tự dùng).
                startGhost(app, m)
            } catch (t: Throwable) {
                closeQuietly()
                lastError = friendly(t)
                state = State.OFF
                notifyUi()
            }
        }
    }

    private class Found(val port: Int, val host: String?)

    /**
     * Dò dịch vụ mDNS [type] (_adb-tls-connect._tcp hoặc _adb-tls-pairing._tcp).
     * Chỉ nhận dịch vụ của CHÍNH máy này (tránh nhầm máy khác trong cùng mạng Wi-Fi).
     */
    private fun discover(app: Context, type: String, timeoutMs: Long): Found? {
        val nsd = app.getSystemService(Context.NSD_SERVICE) as NsdManager
        val locals = HashSet<String>()
        try {
            for (ni in java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())) {
                for (a in java.util.Collections.list(ni.inetAddresses)) {
                    val h = a.hostAddress
                    if (h != null) locals.add(h.substringBefore('%'))
                }
            }
        } catch (_: Throwable) {
        }
        val latch = CountDownLatch(1)
        var found: Found? = null
        val listener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                latch.countDown()
            }

            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {}
            override fun onDiscoveryStarted(serviceType: String?) {}
            override fun onDiscoveryStopped(serviceType: String?) {}
            override fun onServiceLost(serviceInfo: NsdServiceInfo?) {}
            override fun onServiceFound(serviceInfo: NsdServiceInfo?) {
                if (serviceInfo == null) return
                val rl = object : NsdManager.ResolveListener {
                    private var tries = 0
                    override fun onResolveFailed(info: NsdServiceInfo?, errorCode: Int) {
                        // đang resolve dịch vụ khác -> thử lại sau chút
                        if (errorCode == NsdManager.FAILURE_ALREADY_ACTIVE && tries++ < 8 && found == null) {
                            val self = this
                            ui.postDelayed({
                                try {
                                    nsd.resolveService(serviceInfo, self)
                                } catch (_: Throwable) {
                                }
                            }, 300)
                        }
                    }

                    override fun onServiceResolved(info: NsdServiceInfo?) {
                        if (info == null || found != null) return
                        val h = info.host
                        val addr = h?.hostAddress?.substringBefore('%')
                        val mine = h == null || h.isLoopbackAddress || (addr != null && locals.contains(addr))
                        if (mine) {
                            found = Found(info.port, addr)
                            latch.countDown()
                        }
                    }
                }
                try {
                    nsd.resolveService(serviceInfo, rl)
                } catch (_: Throwable) {
                }
            }
        }
        try {
            nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener)
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: Throwable) {
        }
        try {
            nsd.stopServiceDiscovery(listener)
        } catch (_: Throwable) {
        }
        return found
    }

    private fun friendly(t: Throwable): String {
        val n = t.javaClass.simpleName
        return when {
            n.contains("Pairing", true) -> "chưa ghép cặp"
            t.message.isNullOrBlank() -> "không thấy dịch vụ Gỡ lỗi không dây (đã bật chưa? có Wi-Fi chưa?)"
            else -> t.message!!.take(60)
        }
    }

    /** Ngắt hẳn và ngừng tự nối lại. */
    fun disconnect() {
        wantUp = false
        closeQuietly()
        state = State.OFF
        lastError = ""
        notifyUi()
    }

    /** Gọi định kỳ: rớt mạng rồi có lại thì tự nối lại (nếu người dùng đang muốn dùng ADB). */
    fun tick(ctx: Context) {
        if (wantUp && state == State.OFF && SystemClock.uptimeMillis() - lastTry > 15_000L) connect(ctx)
    }

    // ------------------------------------------------------------------ ngón tay phụ (GhostTouch)

    private fun startGhost(app: Context, m: AbsAdbConnectionManager) {
        ghostReady = false
        ghostNote = "đang khởi tạo ngón phụ…"
        notifyUi()
        try {
            val pkg = app.packageName
            // exec: = shell thô, không pty (không bị dội lệnh, không đổi \n). CLASSPATH trỏ vào chính APK này,
            // nên lớp GhostTouch nằm sẵn trong app, không cần đẩy file nào lên máy.
            val cmd = "exec:CLASSPATH=\"\$(pm path $pkg | head -n 1 | cut -d: -f2)\" " +
                    "exec app_process / com.macrosniper.app.GhostTouch"
            val s = m.openStream(cmd)
            val ins = s.openInputStream()
            val outs = s.openOutputStream()
            ghostStream = s
            ghostOut = outs
            val latch = CountDownLatch(1)
            val t = Thread { ghostReadLoop(s, ins, latch) }
            t.isDaemon = true
            t.start()
            latch.await(8, TimeUnit.SECONDS)
            if (!ghostReady) {
                if (ghostNote.isEmpty() || ghostNote.startsWith("đang")) ghostNote = "ngón phụ không khởi động được"
                closeGhost()
            }
        } catch (t: Throwable) {
            ghostNote = "ngón phụ lỗi: " + (t.message ?: t.javaClass.simpleName).take(40)
            closeGhost()
        }
        notifyUi()
    }

    private fun ghostReadLoop(me: Any, ins: InputStream, latch: CountDownLatch) {
        try {
            val r = BufferedReader(InputStreamReader(ins))
            while (true) {
                val line = (r.readLine() ?: break).trim()
                when {
                    line.startsWith("READY") -> {
                        ghostNote = ""
                        ghostReady = true
                        latch.countDown()
                        notifyUi()
                    }
                    line.startsWith("FAIL") -> {
                        ghostNote = line.removePrefix("FAIL").trim().take(70)
                        latch.countDown()
                    }
                    line.startsWith("D ") -> {
                        line.substring(2).trim().substringBefore(' ').toIntOrNull()?.let { acks.remove(it)?.invoke() }
                    }
                    line.startsWith("E ") -> {
                        // lỗi giữa chừng -> các cú chạm sau tạm dùng input tap
                        ghostReady = false
                        ghostNote = "ngón phụ lỗi, tạm dùng input tap"
                        line.substring(2).trim().substringBefore(' ').toIntOrNull()?.let { acks.remove(it)?.invoke() }
                        notifyUi()
                    }
                }
            }
        } catch (_: Throwable) {
        }
        latch.countDown()
        if (ghostStream === me) {
            ghostReady = false
            if (ghostNote.isEmpty()) ghostNote = "ngón phụ đã dừng"
            ghostStream = null
            ghostOut = null
            notifyUi()
        }
    }

    private fun closeGhost() {
        ghostReady = false
        val o = ghostOut
        try {
            o?.write("Q\n".toByteArray())
            o?.flush()
        } catch (_: Throwable) {
        }
        try {
            o?.close()
        } catch (_: Throwable) {
        }
        try {
            (ghostStream as? Closeable)?.close()
        } catch (_: Throwable) {
        }
        ghostStream = null
        ghostOut = null
    }

    private fun closeQuietly() {
        closeGhost()
        try {
            outS?.close()
        } catch (_: Throwable) {
        }
        try {
            (stream as? Closeable)?.close()
        } catch (_: Throwable) {
        }
        try {
            (mgr as? Closeable)?.close()
        } catch (_: Throwable) {
        }
        mgr = null // lần sau dựng lại (khoá đã lưu nên không phải ghép cặp lại)
        stream = null
        outS = null
        val pending = acks.values.toList()
        acks.clear()
        for (a in pending) {
            try {
                a()
            } catch (_: Throwable) {
            }
        }
    }

    private fun readLoop(me: Any, ins: InputStream) {
        try {
            val r = BufferedReader(InputStreamReader(ins))
            while (true) {
                val line = r.readLine() ?: break
                val i = line.indexOf("__MSDONE_")
                if (i >= 0) {
                    val n = line.substring(i + 9).takeWhile { it.isDigit() }.toIntOrNull()
                    if (n != null) acks.remove(n)?.invoke()
                }
            }
        } catch (_: Throwable) {
        }
        if (stream === me) {
            closeQuietly()
            lastError = "mất kết nối"
            state = State.OFF
            notifyUi()
        }
    }

    // ------------------------------------------------------------------ chạm

    /**
     * Gửi 1 cú chạm tại (x, y) px của màn hình đang hiển thị ([rot] = Surface.ROTATION_*, [w] x [h] = kích thước
     * màn hình hiện tại). [done] được gọi khi đã chạm xong (để chuỗi đi TUẦN TỰ). Trả về false nếu chưa kết nối.
     *
     * Ưu tiên "ngón tay phụ" (GhostTouch); không có thì rơi về `input tap` như trước.
     */
    fun tap(x: Int, y: Int, rot: Int, w: Int, h: Int, done: () -> Unit): Boolean {
        val o = outS
        if (state != State.CONNECTED || o == null) return false
        val id = seq.incrementAndGet()
        acks[id] = done

        val g = ghostOut
        if (ghostReady && g != null) {
            io.execute {
                try {
                    g.write("T $id $x $y $rot $w $h $GHOST_HOLD_MS\n".toByteArray())
                    g.flush()
                } catch (_: Throwable) {
                    ghostReady = false
                    ghostNote = "ngón phụ lỗi, tạm dùng input tap"
                    acks.remove(id)?.invoke()
                    notifyUi()
                }
            }
            return true
        }

        io.execute {
            try {
                // __MS''DONE: dấu '' để dòng lệnh bị shell "dội" lại không chứa chuỗi đánh dấu thật
                o.write("input tap $x $y; echo __MS''DONE_$id\n".toByteArray())
                o.flush()
            } catch (_: Throwable) {
                acks.remove(id)?.invoke()
                if (stream != null) {
                    closeQuietly()
                    lastError = "mất kết nối"
                    state = State.OFF
                    notifyUi()
                }
            }
        }
        return true
    }
}
