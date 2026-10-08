package com.macrosniper.app

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import sun.security.x509.AlgorithmId
import sun.security.x509.CertificateAlgorithmId
import sun.security.x509.CertificateExtensions
import sun.security.x509.CertificateIssuerName
import sun.security.x509.CertificateSerialNumber
import sun.security.x509.CertificateSubjectName
import sun.security.x509.CertificateValidity
import sun.security.x509.CertificateVersion
import sun.security.x509.CertificateX509Key
import sun.security.x509.KeyIdentifier
import sun.security.x509.PrivateKeyUsageExtension
import sun.security.x509.SubjectKeyIdentifierExtension
import sun.security.x509.X500Name
import sun.security.x509.X509CertImpl
import sun.security.x509.X509CertInfo
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
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
        state == State.CONNECTED -> "●  Đã kết nối · đang chạm bằng ADB"
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
                val pub = kp.public
                val pk = kp.private
                val alg = "SHA512withRSA"
                val notBefore = Date()
                val notAfter = Date(System.currentTimeMillis() + 20L * 365L * 86400000L)
                val ext = CertificateExtensions()
                ext.set("SubjectKeyIdentifier", SubjectKeyIdentifierExtension(KeyIdentifier(pub).identifier))
                ext.set("PrivateKeyUsage", PrivateKeyUsageExtension(notBefore, notAfter))
                val name = X500Name("CN=MacroSniper")
                val info = X509CertInfo()
                info.set("version", CertificateVersion(2))
                info.set("serialNumber", CertificateSerialNumber(Random().nextInt() and Int.MAX_VALUE))
                info.set("algorithmID", CertificateAlgorithmId(AlgorithmId.get(alg)))
                info.set("subject", CertificateSubjectName(name))
                info.set("key", CertificateX509Key(pub))
                info.set("validity", CertificateValidity(notBefore, notAfter))
                info.set("issuer", CertificateIssuerName(name))
                info.set("extensions", ext)
                val impl = X509CertImpl(info)
                impl.sign(pk, alg)
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
     * Ghép cặp (làm 1 lần): [port] và [code] là cổng + mã 6 số hiện trên hộp thoại
     * "Ghép nối thiết bị bằng mã ghép nối" trong Tùy chọn nhà phát triển → Gỡ lỗi không dây.
     */
    fun pair(ctx: Context, port: Int, code: String, done: (Boolean, String) -> Unit) {
        val app = ctx.applicationContext
        bg.execute {
            var ok = false
            var msg: String
            try {
                ok = manager(app).pair("127.0.0.1", port, code)
                msg = if (ok) "Ghép cặp thành công" else "Ghép cặp thất bại · kiểm tra lại cổng và mã"
            } catch (t: Throwable) {
                msg = "Lỗi ghép cặp: " + (t.message ?: t.javaClass.simpleName)
            }
            val r = ok
            val m = msg
            ui.post { done(r, m) }
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
                m.connectTls(app, 10_000L)
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
            } catch (t: Throwable) {
                closeQuietly()
                lastError = friendly(t)
                state = State.OFF
                notifyUi()
            }
        }
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
     * Gửi 1 cú chạm. [done] được gọi khi shell báo đã chạy xong lệnh (để chuỗi đi TUẦN TỰ).
     * Trả về false nếu chưa kết nối.
     */
    fun tap(x: Int, y: Int, done: () -> Unit): Boolean {
        val o = outS
        if (state != State.CONNECTED || o == null) return false
        val id = seq.incrementAndGet()
        acks[id] = done
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
