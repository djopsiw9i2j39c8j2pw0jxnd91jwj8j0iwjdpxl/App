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

    /**
     * Màn hình cảm ứng thật (/dev/input/eventN) + thông số trục MT, dò 1 lần sau khi kết nối.
     * Có cái này thì chạm bằng cách ghi thẳng 1 "ngón thứ N" (slot riêng) vào đúng thiết bị cảm ứng
     * -> chạy song song với ngón tay thật, không làm hủy thao tác xoay/kéo đang giữ.
     * null = không dùng được -> quay về `input tap`.
     */
    private class TouchDev(
        val path: String,
        val minX: Int, val maxX: Int,
        val minY: Int, val maxY: Int,
        val slot: Int,
        val minTid: Int, val maxTid: Int,
        val pressure: Int?, // giá trị ABS_MT_PRESSURE sẽ gửi (null = máy không có trục này)
        val major: Int?     // giá trị ABS_MT_TOUCH_MAJOR sẽ gửi
    )

    @Volatile
    private var touch: TouchDev? = null

    // Helper = TouchServer chạy bằng quyền shell (xem TouchServer.kt)
    @Volatile
    private var helperReady = false
    private var helperStream: Any? = null
    private var helperOut: OutputStream? = null

    @Volatile
    var helperError: String = ""
        private set
    private val tidCounter = AtomicInteger()

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
        state == State.CONNECTED && helperReady -> "●  Đã kết nối · helper chạm liền mạch (không chặn tay bạn)"
        state == State.CONNECTED && touch != null -> "●  Đã kết nối · chạm đa điểm (vẫn xoay/di chuyển được)"
        state == State.CONNECTED -> "●  Đã kết nối · chạm bằng input tap (có thể bị đơ)" + (if (helperError.isNotEmpty()) " · helper lỗi: $helperError" else "")
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
                // ưu tiên helper (tiến trình nền quyền shell); không chạy được mới thử sendevent / input tap
                val apk = app.applicationInfo.sourceDir
                if (!startHelper(m, apk)) touch = probeTouch(m)
                lastError = ""
                state = State.CONNECTED
                notifyUi()
                val t = Thread { readLoop(st, ins) }
                t.isDaemon = true
                t.start()
            } catch (t: Throwable) {
                closeQuietly()
                lastError = friendly(t)
                state = State.OFF
                notifyUi()
            }
        }
    }

    /** Chạy 1 lệnh shell độc lập, đọc hết kết quả (tối đa [timeoutMs]). Lỗi/quá giờ -> chuỗi rỗng. */
    private fun runOnce(m: AbsAdbConnectionManager, cmd: String, timeoutMs: Long = 4000L): String {
        val out = StringBuilder()
        val t = Thread {
            try {
                val s = m.openStream("shell:$cmd")
                try {
                    out.append(String(s.openInputStream().readBytes()))
                } finally {
                    try {
                        s.close()
                    } catch (_: Throwable) {
                    }
                }
            } catch (_: Throwable) {
            }
        }
        t.isDaemon = true
        t.start()
        t.join(timeoutMs)
        return synchronized(out) { out.toString() }
    }

    /**
     * Tìm màn hình cảm ứng trong `getevent -p`, rồi thử ghi 1 sự kiện vô hại (SYN_REPORT)
     * để chắc chắn quyền shell được ghi vào thiết bị đó. Không được -> null (dùng `input tap`).
     */
    private fun probeTouch(m: AbsAdbConnectionManager): TouchDev? {
        try {
            val text = runOnce(m, "getevent -p 2>/dev/null")
            val dev = parseTouch(text) ?: return null
            val r = runOnce(m, "sendevent ${dev.path} 0 0 0 2>&1; echo RC=$?").trim()
            return if (r == "RC=0") dev else null
        } catch (_: Throwable) {
            return null
        }
    }

    private fun parseTouch(text: String): TouchDev? {
        val axisRe = Regex("""\b([0-9a-fA-F]{4})\s*:\s*value\s+-?\d+,\s*min\s+(-?\d+),\s*max\s+(-?\d+)""")
        // tách theo từng "add device N: /dev/input/eventM"
        val paths = ArrayList<String>()
        val bodies = ArrayList<StringBuilder>()
        for (raw in text.lines()) {
            val line = raw.trimEnd()
            if (line.startsWith("add device")) {
                paths.add(line.substringAfter(": ", "").trim())
                bodies.add(StringBuilder())
            } else if (bodies.isNotEmpty()) {
                bodies.last().append(line).append('\n')
            }
        }
        var best: TouchDev? = null
        var bestDirect = false
        for (i in paths.indices) {
            val body = bodies[i].toString()
            val ax = HashMap<Int, IntArray>() // code -> [min, max]
            for (mm in axisRe.findAll(body)) {
                val code = mm.groupValues[1].toInt(16)
                ax[code] = intArrayOf(mm.groupValues[2].toInt(), mm.groupValues[3].toInt())
            }
            val x = ax[0x35] ?: continue // ABS_MT_POSITION_X
            val y = ax[0x36] ?: continue // ABS_MT_POSITION_Y
            val slot = ax[0x2f] ?: continue // ABS_MT_SLOT (giao thức B)
            val tid = ax[0x39] ?: continue // ABS_MT_TRACKING_ID
            if (slot[1] < 1 || x[1] <= x[0] || y[1] <= y[0] || tid[1] <= tid[0]) continue
            val direct = body.contains("INPUT_PROP_DIRECT")
            if (best != null && (bestDirect || !direct)) continue
            val pr = ax[0x3a]
            val mj = ax[0x30]
            best = TouchDev(
                paths[i],
                x[0], x[1], y[0], y[1],
                slot[1], // dùng slot cuối cùng, ít khi trùng ngón tay thật
                tid[0], tid[1],
                pr?.let { (it[0] + 1).coerceAtLeast(minOf(it[1], 50)).coerceAtMost(it[1]) },
                mj?.let { (it[0] + 1).coerceAtLeast(minOf(it[1], 6)).coerceAtMost(it[1]) }
            )
            bestDirect = direct
        }
        return best
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

    private fun closeQuietly() {
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
        touch = null
        closeHelper()
        val pending = acks.values.toList()
        acks.clear()
        for (a in pending) {
            try {
                a()
            } catch (_: Throwable) {
            }
        }
    }

    /** Mở TouchServer qua 1 luồng shell riêng. true = helper đã báo READY. */
    private fun startHelper(m: AbsAdbConnectionManager, apk: String): Boolean {
        helperError = ""
        try {
            val s = m.openStream("shell:CLASSPATH=$apk exec app_process /system/bin com.macrosniper.app.TouchServer")
            val ins = s.openInputStream()
            val outs = s.openOutputStream()
            helperStream = s
            helperOut = outs
            val latch = CountDownLatch(1)
            val t = Thread {
                try {
                    val r = BufferedReader(InputStreamReader(ins))
                    while (true) {
                        val line = r.readLine() ?: break
                        if (line.contains("__MSREADY")) {
                            helperReady = true
                            latch.countDown()
                        } else if (line.contains("__MSERR")) {
                            helperError = line.substringAfter("__MSERR").trim()
                            latch.countDown()
                        } else {
                            ackLine(line)
                        }
                    }
                } catch (_: Throwable) {
                }
                latch.countDown()
                if (helperStream === s) { // helper chết giữa chừng -> tạm lùi về cách khác
                    helperReady = false
                    helperStream = null
                    helperOut = null
                }
            }
            t.isDaemon = true
            t.start()
            if (latch.await(7, TimeUnit.SECONDS) && helperReady) return true
            if (helperError.isEmpty()) helperError = "helper không phản hồi"
            closeHelper()
            return false
        } catch (t: Throwable) {
            helperError = friendly(t)
            closeHelper()
            return false
        }
    }

    private fun closeHelper() {
        helperReady = false
        try {
            helperOut?.write("Q\n".toByteArray())
            helperOut?.flush()
        } catch (_: Throwable) {
        }
        try {
            helperOut?.close()
        } catch (_: Throwable) {
        }
        try {
            (helperStream as? Closeable)?.close()
        } catch (_: Throwable) {
        }
        helperStream = null
        helperOut = null
    }

    private fun ackLine(line: String) {
        val i = line.indexOf("__MSDONE_")
        if (i >= 0) {
            val n = line.substring(i + 9).takeWhile { it.isDigit() }.toIntOrNull()
            if (n != null) acks.remove(n)?.invoke()
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
     * Dựng chuỗi lệnh `sendevent` cho 1 cú chạm bằng slot RIÊNG (ngón thứ N).
     *
     * Vì sao không bị đơ: `input tap` luôn bắt đầu bằng ACTION_DOWN của "ngón số 0" trên cùng
     * thiết bị cảm ứng -> hệ thống thấy "đang giữ tay mà lại có DOWN mới" và HỦY cử chỉ thật
     * (đang xoay camera / kéo) của bạn. Ở đây ta ghi vào evdev kiểu đa điểm (protocol B) bằng
     * slot cuối + tracking id riêng, nên đó chỉ là thêm 1 ngón nữa, ngón thật không bị đụng tới.
     * Không bao giờ gửi BTN_TOUCH=0 để khỏi biến ngón thật (đang giữ) thành "hover".
     */
    private fun evTap(t: TouchDev, x: Int, y: Int, rot: Int, sw: Int, sh: Int): String {
        // kích thước + tọa độ theo hướng TỰ NHIÊN của tấm cảm ứng (dọc với điện thoại)
        val natW = if (rot % 2 == 0) sw else sh
        val natH = if (rot % 2 == 0) sh else sw
        val nx: Int
        val ny: Int
        when (rot) {
            1 -> { nx = y; ny = natH - x }            // ROTATION_90
            2 -> { nx = natW - x; ny = natH - y }     // ROTATION_180
            3 -> { nx = natW - y; ny = x }            // ROTATION_270
            else -> { nx = x; ny = y }
        }
        val rx = t.minX + (nx.coerceIn(0, natW).toLong() * (t.maxX - t.minX) / natW).toInt()
        val ry = t.minY + (ny.coerceIn(0, natH).toLong() * (t.maxY - t.minY) / natH).toInt()

        val n = tidCounter.incrementAndGet()
        val span = t.maxTid - t.minTid + 1
        val tid = if (span > 8) t.maxTid - (n and 7) else t.minTid + (n % span)

        val d = t.path
        fun ev(type: Int, code: Int, value: Int) = "sendevent $d $type $code $value"
        val sb = StringBuilder()
        // nhấn xuống
        sb.append(ev(3, 0x2f, t.slot)).append("; ")        // ABS_MT_SLOT
        sb.append(ev(3, 0x39, tid)).append("; ")           // ABS_MT_TRACKING_ID
        sb.append(ev(3, 0x35, rx)).append("; ")            // ABS_MT_POSITION_X
        sb.append(ev(3, 0x36, ry)).append("; ")            // ABS_MT_POSITION_Y
        t.pressure?.let { sb.append(ev(3, 0x3a, it)).append("; ") }
        t.major?.let { sb.append(ev(3, 0x30, it)).append("; ") }
        sb.append(ev(1, 0x14a, 1)).append("; ")            // BTN_TOUCH = 1 (nếu đã =1 thì bị bỏ qua)
        sb.append(ev(0, 0, 0)).append("; ")                // SYN_REPORT
        sb.append("sleep 0.02; ")
        // nhả ra
        sb.append(ev(3, 0x2f, t.slot)).append("; ")
        sb.append(ev(3, 0x39, -1)).append("; ")
        sb.append(ev(0, 0, 0))
        return sb.toString()
    }

    /**
     * Gửi 1 cú chạm. [done] được gọi khi shell báo đã chạy xong lệnh (để chuỗi đi TUẦN TỰ).
     * Trả về false nếu chưa kết nối.
     */
    fun tap(x: Int, y: Int, rotation: Int, screenW: Int, screenH: Int, done: () -> Unit): Boolean {
        val o = outS
        if (state != State.CONNECTED || o == null) return false
        val id = seq.incrementAndGet()
        acks[id] = done
        val ho = helperOut
        if (helperReady && ho != null) {
            // đường chính: helper bơm cảm ứng trực tiếp, chỉ cần gửi 1 dòng
            io.execute {
                try {
                    ho.write("$id T $x $y\n".toByteArray())
                    ho.flush()
                } catch (_: Throwable) {
                    helperReady = false // helper rớt -> các lần sau tự dùng cách dự phòng
                    acks.remove(id)?.invoke()
                }
            }
            return true
        }
        val td = touch
        val cmd = if (td != null && screenW > 0 && screenH > 0) {
            evTap(td, x, y, rotation and 3, screenW, screenH)
        } else {
            "input tap $x $y"
        }
        io.execute {
            try {
                // __MS''DONE: dấu '' để dòng lệnh bị shell "dội" lại không chứa chuỗi đánh dấu thật
                o.write("$cmd; echo __MS''DONE_$id\n".toByteArray())
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
