package com.macrosniper.app;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "NGÓN TAY PHỤ" — chạy bằng app_process với quyền shell (khởi động từ AdbClient qua Gỡ lỗi không dây).
 *
 * Vì sao cần: lệnh `input tap` bơm cú chạm như từ một thiết bị ảo KHÁC màn hình cảm ứng. Khi ngón tay thật
 * đang đặt trên màn hình, Android thấy "2 thiết bị chạm cùng lúc" nên HUỶ cử chỉ của ngón thật
 * -> ngón thật bị kẹt (không xoay được camera, không bấm được nút khác) cho tới khi nhấc lên chạm lại.
 *
 * Cách làm ở đây: ghi thẳng sự kiện đa chạm (giao thức MT type-B) vào chính /dev/input/eventX của màn hình
 * cảm ứng, dùng một SLOT + TRACKING_ID riêng. Với Android đó chỉ là thêm một ngón tay thật nữa trên cùng
 * một màn hình: ngón gốc vẫn xoay camera / bấm nút khác bình thường.
 *
 * Giao thức (mỗi dòng, qua stdin/stdout):
 *   khi khởi động -> "READY <dev> <rawW> <rawH> <slot>"  hoặc  "FAIL <lý do>"  (rồi thoát)
 *   "T <id> <x> <y> <rot> <w> <h> <holdMs>"  -> chạm tại (x,y) px của màn hình đang hiển thị; trả "D <id>" khi xong
 *                                                (hoặc "E <id> <lý do>")
 *   "Q" hoặc EOF -> thoát
 *
 * Chỉ dùng java.* (không Android API, không Kotlin) để chạy được trong app_process.
 */
public final class GhostTouch implements Toucher {

    // ---- hằng số Linux input
    static final int EV_SYN = 0, EV_KEY = 1, EV_ABS = 3;
    static final int SYN_REPORT = 0;
    static final int BTN_TOUCH = 0x14a, BTN_TOOL_FINGER = 0x145;
    static final int ABS_MT_SLOT = 0x2f, ABS_MT_TOUCH_MAJOR = 0x30, ABS_MT_WIDTH_MAJOR = 0x32,
            ABS_MT_POSITION_X = 0x35, ABS_MT_POSITION_Y = 0x36, ABS_MT_TRACKING_ID = 0x39,
            ABS_MT_PRESSURE = 0x3a;

    static final class Axis {
        int min, max;
    }

    static final class Dev {
        String path = "";
        String name = "";
        Axis x, y, slot, tid, pressure, major, width;
        boolean direct, btnTouch, toolFinger;

        boolean usable() {
            return x != null && y != null && slot != null && tid != null && x.max > x.min && y.max > y.min;
        }
    }

    // ------------------------------------------------------------------ đọc `getevent -lp`

    private static final Pattern ABS_LINE = Pattern.compile(
            "([A-Za-z0-9_]+)\\s*:\\s*value\\s+(-?\\d+),\\s*min\\s+(-?\\d+),\\s*max\\s+(-?\\d+)");

    static int absCode(String token) {
        switch (token) {
            case "ABS_MT_SLOT": case "002f": return ABS_MT_SLOT;
            case "ABS_MT_TOUCH_MAJOR": case "0030": return ABS_MT_TOUCH_MAJOR;
            case "ABS_MT_WIDTH_MAJOR": case "0032": return ABS_MT_WIDTH_MAJOR;
            case "ABS_MT_POSITION_X": case "0035": return ABS_MT_POSITION_X;
            case "ABS_MT_POSITION_Y": case "0036": return ABS_MT_POSITION_Y;
            case "ABS_MT_TRACKING_ID": case "0039": return ABS_MT_TRACKING_ID;
            case "ABS_MT_PRESSURE": case "003a": return ABS_MT_PRESSURE;
            default: return -1;
        }
    }

    /** Phân tích đầu ra của `getevent -lp` thành danh sách thiết bị. */
    static List<Dev> parseProps(String text) {
        List<Dev> out = new ArrayList<>();
        Dev cur = null;
        for (String raw : text.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.startsWith("add device")) {
                cur = new Dev();
                int i = line.indexOf(':');
                if (i >= 0) cur.path = line.substring(i + 1).trim();
                out.add(cur);
                continue;
            }
            if (cur == null) continue;
            if (line.startsWith("name:")) {
                cur.name = line.substring(5).trim().replace("\"", "");
                continue;
            }
            if (line.contains("INPUT_PROP_DIRECT")) cur.direct = true;
            if (line.contains("BTN_TOUCH")) cur.btnTouch = true;
            if (line.contains("BTN_TOOL_FINGER")) cur.toolFinger = true;
            Matcher m = ABS_LINE.matcher(line);
            if (m.find()) {
                int code = absCode(m.group(1));
                if (code < 0) continue;
                Axis a = new Axis();
                a.min = Integer.parseInt(m.group(3));
                a.max = Integer.parseInt(m.group(4));
                switch (code) {
                    case ABS_MT_SLOT: cur.slot = a; break;
                    case ABS_MT_TOUCH_MAJOR: cur.major = a; break;
                    case ABS_MT_WIDTH_MAJOR: cur.width = a; break;
                    case ABS_MT_POSITION_X: cur.x = a; break;
                    case ABS_MT_POSITION_Y: cur.y = a; break;
                    case ABS_MT_TRACKING_ID: cur.tid = a; break;
                    case ABS_MT_PRESSURE: cur.pressure = a; break;
                    default: break;
                }
            }
        }
        return out;
    }

    /** Chọn màn hình cảm ứng chính: ưu tiên INPUT_PROP_DIRECT, rồi tên có "touch", rồi cái đầu tiên dùng được. */
    static Dev pickTouchscreen(List<Dev> all) {
        Dev best = null;
        int bestScore = -1;
        for (Dev d : all) {
            if (!d.usable()) continue;
            int s = 1;
            if (d.direct) s += 4;
            String n = d.name.toLowerCase();
            if (n.contains("touch") || n.contains("ts")) s += 2;
            if (n.contains("pen") || n.contains("stylus") || n.contains("finger")) s -= 2;
            if (s > bestScore) {
                best = d;
                bestScore = s;
            }
        }
        return best;
    }

    private static String runGetevent() throws Exception {
        Process p = new ProcessBuilder("/system/bin/getevent", "-lp").redirectErrorStream(true).start();
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String l;
            while ((l = r.readLine()) != null) sb.append(l).append('\n');
        }
        p.waitFor();
        return sb.toString();
    }

    // ------------------------------------------------------------------ đổi toạ độ màn hình -> toạ độ thô của tấm cảm ứng

    /**
     * (x,y) là pixel trên màn hình ĐANG hiển thị (kích thước w x h, đã xoay [rot] = Surface.ROTATION_*).
     * Tấm cảm ứng luôn báo toạ độ theo hướng tự nhiên của máy; Android (InputReader) xoay lại theo hướng màn hình,
     * nên ở đây làm ngược lại.
     */
    static int[] mapToRaw(Dev d, int x, int y, int rot, int w, int h) {
        double nx = (x + 0.5) / Math.max(1, w);
        double ny = (y + 0.5) / Math.max(1, h);
        nx = Math.min(1.0, Math.max(0.0, nx));
        ny = Math.min(1.0, Math.max(0.0, ny));
        double rx, ry;
        switch (rot & 3) {
            case 1: rx = 1.0 - ny; ry = nx; break;       // ROTATION_90
            case 2: rx = 1.0 - nx; ry = 1.0 - ny; break; // ROTATION_180
            case 3: rx = ny; ry = 1.0 - nx; break;       // ROTATION_270
            default: rx = nx; ry = ny; break;            // ROTATION_0
        }
        int px = d.x.min + (int) Math.floor(rx * (d.x.max - d.x.min + 1));
        int py = d.y.min + (int) Math.floor(ry * (d.y.max - d.y.min + 1));
        px = Math.min(d.x.max, Math.max(d.x.min, px));
        py = Math.min(d.y.max, Math.max(d.y.min, py));
        return new int[]{px, py};
    }

    // ------------------------------------------------------------------ mã hoá struct input_event

    static boolean is64() {
        try {
            Class<?> c = Class.forName("dalvik.system.VMRuntime");
            Object rt = c.getMethod("getRuntime").invoke(null);
            return (Boolean) c.getMethod("is64Bit").invoke(rt);
        } catch (Throwable t) {
            String arch = System.getProperty("os.arch", "");
            return arch.contains("64");
        }
    }

    /** Một "khung" sự kiện: gom nhiều event, ghi bằng ĐÚNG MỘT lệnh write() để không bị xen giữa. */
    static final class Frame {
        final ByteBuffer b;
        final int evSize;

        Frame(boolean is64) {
            evSize = is64 ? 24 : 16;
            b = ByteBuffer.allocate(evSize * 32).order(ByteOrder.LITTLE_ENDIAN);
        }

        void add(int type, int code, int value) {
            b.position(b.position() + (evSize - 8)); // timeval = 0 (kernel tự đóng dấu thời gian)
            b.putShort((short) type);
            b.putShort((short) code);
            b.putInt(value);
        }

        byte[] bytes() {
            byte[] r = new byte[b.position()];
            System.arraycopy(b.array(), 0, r, 0, r.length);
            return r;
        }
    }

    // ------------------------------------------------------------------ báo vị trí ngón THẬT cho app (để nút main "xuyên" nhận được cú chạm)

    /**
     * Gom trạng thái ngón thật từ evdev; mỗi khi có ngón xuống / nhấc thì in 1 dòng "F tid:xPermille:yPermille ..."
     * (danh sách mọi ngón đang đặt; "F" trơn = không còn ngón nào). Toạ độ là tỉ lệ 0..1000 theo trục RAW của màn hình cảm ứng,
     * app tự quy đổi theo hướng màn hình. Ngón phụ của macro ([ghostSlot]) bị loại.
     */
    static final class Fingers {
        private final Axis ax, ay;
        private final int ghostSlot;
        private final boolean[] act, hx, hy;
        private final int[] rx, ry, tid;
        private int cur = 0;
        private boolean edge = false;
        private String last = "F";

        Fingers(Axis ax, Axis ay, int ghostSlot, int slots) {
            this.ax = ax;
            this.ay = ay;
            this.ghostSlot = ghostSlot;
            int n = Math.max(2, Math.min(64, slots));
            act = new boolean[n];
            hx = new boolean[n];
            hy = new boolean[n];
            rx = new int[n];
            ry = new int[n];
            tid = new int[n];
        }

        void feed(int type, int code, int value) {
            if (type == EV_ABS) {
                if (code == ABS_MT_SLOT) {
                    cur = (value >= 0 && value < act.length) ? value : -1;
                } else if (cur >= 0) {
                    if (code == ABS_MT_TRACKING_ID) {
                        act[cur] = value != -1;
                        tid[cur] = value;
                        if (value == -1) {
                            hx[cur] = false;
                            hy[cur] = false;
                        }
                        edge = true;
                    } else if (code == ABS_MT_POSITION_X) {
                        rx[cur] = value;
                        hx[cur] = true;
                    } else if (code == ABS_MT_POSITION_Y) {
                        ry[cur] = value;
                        hy[cur] = true;
                    }
                }
            } else if (type == EV_SYN && code == SYN_REPORT) {
                if (edge) {
                    edge = false;
                    emit();
                }
            }
        }

        void reset() {
            for (int i = 0; i < act.length; i++) {
                act[i] = false;
                hx[i] = false;
                hy[i] = false;
            }
            cur = 0;
            edge = false;
            emit();
        }

        private void emit() {
            StringBuilder sb = new StringBuilder("F");
            for (int i = 0; i < act.length; i++) {
                if (i == ghostSlot || !act[i] || !hx[i] || !hy[i]) continue;
                int px = (int) Math.round((rx[i] - ax.min) * 1000.0 / Math.max(1, ax.max - ax.min));
                int py = (int) Math.round((ry[i] - ay.min) * 1000.0 / Math.max(1, ay.max - ay.min));
                sb.append(' ').append(tid[i]).append(':').append(px).append(':').append(py);
            }
            String s = sb.toString();
            if (s.equals(last)) return;
            last = s;
            report(s);
        }
    }

    // ------------------------------------------------------------------ theo dõi ngón tay THẬT đang đặt trên màn hình

    static final class Tracker implements Runnable {
        private final String path;
        private final int evSize;
        private final int ghostSlot;
        private final boolean[] active;
        private volatile int curSlot = 0;
        volatile int lastRealSlot = 0;
        volatile boolean ok = false;

        private final Fingers fingers;

        Tracker(String path, boolean is64, int ghostSlot, int slots, Axis ax, Axis ay) {
            this.fingers = new Fingers(ax, ay, ghostSlot, slots);
            this.path = path;
            this.evSize = is64 ? 24 : 16;
            this.ghostSlot = ghostSlot;
            this.active = new boolean[Math.max(2, Math.min(64, slots))];
        }

        int realActive() {
            int n = 0;
            synchronized (active) {
                for (int i = 0; i < active.length; i++) if (i != ghostSlot && active[i]) n++;
            }
            return n;
        }

        @Override
        public void run() {
            try (InputStream in = new FileInputStream(path)) {
                byte[] buf = new byte[evSize * 64];
                ok = true;
                while (true) {
                    int n = in.read(buf);
                    if (n <= 0) break;
                    ByteBuffer bb = ByteBuffer.wrap(buf, 0, n - (n % evSize)).order(ByteOrder.LITTLE_ENDIAN);
                    while (bb.remaining() >= evSize) {
                        bb.position(bb.position() + (evSize - 8));
                        int type = bb.getShort() & 0xFFFF;
                        int code = bb.getShort() & 0xFFFF;
                        int value = bb.getInt();
                        fingers.feed(type, code, value);
                        if (type != EV_ABS) continue;
                        if (code == ABS_MT_SLOT) {
                            if (value >= 0 && value < active.length) {
                                curSlot = value;
                                if (value != ghostSlot) lastRealSlot = value;
                            }
                        } else if (code == ABS_MT_TRACKING_ID) {
                            int s = curSlot;
                            synchronized (active) {
                                if (s >= 0 && s < active.length) active[s] = value != -1;
                            }
                        }
                    }
                }
            } catch (Throwable t) {
                // không đọc được -> coi như không theo dõi được
            }
            ok = false;
        }
    }

    // ------------------------------------------------------------------ giao diện chung

    /** Nơi bơm MotionEvent vào hệ thống (tách ra để dễ thử). action đã mã hoá sẵn (POINTER_INDEX << 8). */
    interface Injector {
        long now();

        void inject(int action, int n, int[] ids, float[] xs, float[] ys, long downTime) throws Exception;
    }

    /** Bơm MotionEvent thật bằng InputManager.injectInputEvent (quyền shell có INJECT_EVENTS, giống lệnh `input`). */
    static final class ReflectInjector implements Injector {
        private final Object im;
        private final java.lang.reflect.Method m;
        private final boolean three;

        ReflectInjector() throws Exception {
            Object inst = null;
            java.lang.reflect.Method found = null;
            boolean th = false;
            String[] names = {"android.hardware.input.InputManagerGlobal", "android.hardware.input.InputManager"};
            Throwable last = null;
            for (String cn : names) {
                try {
                    Class<?> c = Class.forName(cn);
                    java.lang.reflect.Method gi = c.getDeclaredMethod("getInstance");
                    gi.setAccessible(true);
                    Object o = gi.invoke(null);
                    if (o == null) continue;
                    try {
                        found = o.getClass().getMethod("injectInputEvent", android.view.InputEvent.class, int.class);
                        th = false;
                    } catch (NoSuchMethodException e) {
                        found = o.getClass().getMethod("injectInputEvent", android.view.InputEvent.class, int.class, int.class);
                        th = true;
                    }
                    inst = o;
                    break;
                } catch (Throwable t) {
                    last = t;
                }
            }
            if (inst == null || found == null) throw new Exception("không có InputManager (" + last + ")");
            im = inst;
            m = found;
            three = th;
        }

        @Override
        public long now() {
            return android.os.SystemClock.uptimeMillis();
        }

        private final android.view.MotionEvent.PointerProperties[] pp = new android.view.MotionEvent.PointerProperties[32];
        private final android.view.MotionEvent.PointerCoords[] pc = new android.view.MotionEvent.PointerCoords[32];

        @Override
        public synchronized void inject(int action, int n, int[] ids, float[] xs, float[] ys, long downTime) throws Exception {
            for (int i = 0; i < n; i++) {
                if (pp[i] == null) {
                    pp[i] = new android.view.MotionEvent.PointerProperties();
                    pc[i] = new android.view.MotionEvent.PointerCoords();
                    pp[i].toolType = android.view.MotionEvent.TOOL_TYPE_FINGER;
                    pc[i].pressure = 1f;
                    pc[i].size = 1f;
                }
                pp[i].id = ids[i];
                pc[i].x = xs[i];
                pc[i].y = ys[i];
            }
            android.view.MotionEvent ev = android.view.MotionEvent.obtain(downTime, now(), action, n, pp, pc,
                    0, 0, 1f, 1f, 0, 0, 0x00001002 /* SOURCE_TOUCHSCREEN */, 0);
            try {
                Object r = three ? m.invoke(im, ev, 0, -1) : m.invoke(im, ev, 0); // 0 = INJECT_INPUT_EVENT_MODE_ASYNC
                if (r instanceof Boolean && !((Boolean) r)) throw new Exception("injectInputEvent trả về false");
            } finally {
                ev.recycle();
            }
        }
    }

    // ------------------------------------------------------------------ RELAY HỢP NHẤT (khi không ghi được /dev/input)

    /**
     * Dùng khi máy không cho GHI vào /dev/input (EACCES, vd. Samsung) nhưng vẫn cho ĐỌC.
     *
     * Gốc của lỗi "khựng / mất ngón": cú chạm macro bơm vào là một NGUỒN CHẠM THỨ 2 bên cạnh màn hình cảm ứng thật,
     * nên Android huỷ ngón thật, hoặc game nhận trùng 2 luồng (thật + bản sao) -> giật khi kéo camera.
     *
     * Cách xử lý: CHỈ CÒN MỘT NGUỒN.
     *  1. Đọc toạ độ ngón thật từ /dev/input.
     *  2. EVIOCGRAB: giành độc quyền thiết bị cảm ứng -> hệ thống không còn nhận cú chạm phần cứng nữa.
     *  3. Tự bơm LẠI toàn bộ (ngón thật + ngón macro) thành MỘT luồng đa chạm duy nhất.
     *     Ngón thật và ngón macro nằm chung 1 luồng nên không còn huỷ / ghi đè / trùng nhau.
     *  Chỉ đổi trạng thái grab khi KHÔNG có ngón nào đang đặt (tránh kẹt cảm ứng). Tiến trình chết / lỗi bơm
     *  liên tục -> tự nhả grab (đóng fd) để cảm ứng về lại bình thường.
     *  Nếu máy không cho EVIOCGRAB thì lùi về kiểu cũ: bơm bản sao ngón thật (có thể còn hơi khựng).
     */
    static final class Takeover implements Toucher, Runnable {
        static final int GHOST = -1; // "khoá" của ngón macro (ngón thật dùng khoá = số slot)
        static final int A_DOWN = 0, A_UP = 1, A_MOVE = 2, A_PDOWN = 5, A_PUP = 6;
        static final int EVIOCGRAB = 0x40044590; // _IOW('E', 0x90, int)

        private final Dev dev;
        private final String path;
        private final Injector inj;
        private final int evSize;
        private final int nSlots;
        private final Object lock = new Object();

        private final boolean[] act, hasX, hasY;
        private final int[] rawX, rawY, tid;
        private final Fingers fingers;
        private int cur = 0;
        private boolean dirty = false;
        private int rot = 0, w = 1, h = 1;
        private boolean pre = false;

        private boolean started = false;
        private long downTime = 0;
        private final int[] keys = new int[32], pids = new int[32], tids = new int[32];
        private final float[] xs = new float[32], ys = new float[32];
        private int n = 0;

        private FileInputStream curIn = null;
        private boolean grabbed = false;
        private boolean wantGrab = false;
        private boolean grabBroken = false;
        private int injFail = 0;

        volatile boolean alive = true;
        volatile String lastError = "";

        Takeover(Dev dev, String path, Injector inj, boolean is64) {
            this.dev = dev;
            this.path = path;
            this.inj = inj;
            this.evSize = is64 ? 24 : 16;
            this.nSlots = Math.max(2, Math.min(31, dev.slot.max + 1));
            act = new boolean[nSlots];
            hasX = new boolean[nSlots];
            hasY = new boolean[nSlots];
            rawX = new int[nSlots];
            rawY = new int[nSlots];
            tid = new int[nSlots];
            fingers = new Fingers(dev.x, dev.y, -1, nSlots);
        }

        // ---- cấu hình từ app: early = đang ở chế độ chạy macro (giành cảm ứng + bơm hợp nhất)
        @Override
        public void config(boolean early, int rot, int w, int h) {
            synchronized (lock) {
                this.pre = early;
                this.wantGrab = early;
                this.rot = rot;
                this.w = Math.max(1, w);
                this.h = Math.max(1, h);
                dirty = true;
                flushLocked();
                serviceGrabLocked();
            }
        }

        void shutdown() {
            alive = false;
            synchronized (lock) {
                try {
                    while (n > 0) removeAt(n - 1);
                } catch (Throwable ignored) {
                }
                n = 0;
                started = false;
            }
        }

        // ---- grab / nhả grab (chỉ khi KHÔNG có ngón nào đang đặt)
        private boolean idleLocked() {
            return !started && n == 0 && !anyReal();
        }

        private void serviceGrabLocked() {
            if (wantGrab && !grabbed && !grabBroken) {
                if (curIn == null || !idleLocked()) return;
                try {
                    grabFd(curIn.getFD());
                    grabbed = true;
                    injFail = 0;
                    report("GRAB 1");
                } catch (Throwable t) {
                    grabBroken = true;
                    report("GRAB 0 " + String.valueOf(t.getMessage() != null ? t.getMessage() : t));
                }
            } else if (!wantGrab && grabbed) {
                if (!idleLocked()) return; // nhả khi nhấc hết tay
                releaseLocked("GRAB 0 đã nhả");
            }
        }

        /** Nhả grab bằng cách đóng fd (kernel tự nhả); luồng đọc sẽ mở lại fd mới không grab. */
        private void releaseLocked(String why) {
            grabbed = false;
            FileInputStream f = curIn;
            if (f != null) {
                try {
                    f.close();
                } catch (Throwable ignored) {
                }
            }
            report(why);
        }

        private void resetStateLocked() {
            try {
                while (n > 0) removeAt(n - 1);
            } catch (Throwable ignored) {
            }
            n = 0;
            started = false;
            dirty = false;
            cur = 0;
            fingers.reset();
            for (int i = 0; i < nSlots; i++) {
                act[i] = false;
                hasX[i] = false;
                hasY[i] = false;
            }
        }

        // ---- đọc /dev/input
        @Override
        public void run() {
            try {
                android.os.Process.setThreadPriority(-8); // URGENT_DISPLAY: bơm kịp nhịp cảm ứng
            } catch (Throwable ignored) {
            }
            byte[] buf = new byte[evSize * 128];
            while (alive) {
                FileInputStream fin;
                try {
                    fin = new FileInputStream(path);
                } catch (Throwable t) {
                    lastError = String.valueOf(t);
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException ignored) {
                    }
                    continue;
                }
                synchronized (lock) {
                    curIn = fin;
                    resetStateLocked();
                    serviceGrabLocked();
                }
                try {
                    while (true) {
                        int len = fin.read(buf);
                        if (len <= 0) break;
                        ByteBuffer bb = ByteBuffer.wrap(buf, 0, len - (len % evSize)).order(ByteOrder.LITTLE_ENDIAN);
                        while (bb.remaining() >= evSize) {
                            bb.position(bb.position() + (evSize - 8));
                            int type = bb.getShort() & 0xFFFF;
                            int code = bb.getShort() & 0xFFFF;
                            int value = bb.getInt();
                            feed(type, code, value);
                        }
                        flush(); // gộp mọi khung đã đọc được thành 1 lần bơm (trạng thái mới nhất)
                    }
                } catch (Throwable t) {
                    lastError = String.valueOf(t);
                }
                synchronized (lock) {
                    if (curIn == fin) curIn = null;
                    grabbed = false; // đóng fd = kernel tự nhả grab
                    resetStateLocked();
                }
                try {
                    fin.close();
                } catch (Throwable ignored) {
                }
                try {
                    Thread.sleep(20);
                } catch (InterruptedException ignored) {
                }
            }
        }

        void flush() {
            synchronized (lock) {
                if (dirty) flushLocked();
                serviceGrabLocked();
            }
        }

        /** Nạp 1 sự kiện evdev; SYN_REPORT chỉ đánh dấu "có thay đổi" — việc bơm làm ở flush(). */
        void feed(int type, int code, int value) {
            synchronized (lock) {
                fingers.feed(type, code, value);
                if (type == EV_ABS) {
                    if (code == ABS_MT_SLOT) {
                        cur = (value >= 0 && value < nSlots) ? value : -1;
                    } else if (cur >= 0) {
                        if (code == ABS_MT_TRACKING_ID) {
                            if (dirty) flushLocked(); // chốt trạng thái cũ trước khi ngón này đổi (nhấc / chạm lại)
                            act[cur] = value != -1;
                            tid[cur] = value;
                            if (value == -1) {
                                hasX[cur] = false;
                                hasY[cur] = false;
                            }
                        } else if (code == ABS_MT_POSITION_X) {
                            rawX[cur] = value;
                            hasX[cur] = true;
                        } else if (code == ABS_MT_POSITION_Y) {
                            rawY[cur] = value;
                            hasY[cur] = true;
                        }
                    }
                } else if (type == EV_SYN && code == SYN_REPORT) {
                    dirty = true;
                }
            }
        }

        private void flushLocked() {
            dirty = false;
            try {
                if (!started) {
                    if (pre && w > 1 && h > 1 && anyReal()) {
                        started = true;
                        addAllReal();
                    }
                } else {
                    syncLocked();
                }
            } catch (Throwable t) {
                onInjectFailLocked(t);
            }
        }

        /** Bơm lỗi: bỏ luồng dở; nếu đang grab mà lỗi liên tục thì NHẢ NGAY để cảm ứng không bị chết. */
        private void onInjectFailLocked(Throwable t) {
            lastError = String.valueOf(t);
            n = 0;
            started = false;
            if (grabbed) {
                injFail++;
                if (injFail >= 3) {
                    grabBroken = true;
                    wantGrab = false;
                    releaseLocked("GRAB 0 bơm lỗi: " + lastError);
                }
            }
        }

        // ---- toạ độ
        private boolean real(int s) {
            return act[s] && hasX[s] && hasY[s];
        }

        private boolean anyReal() {
            for (int s = 0; s < nSlots; s++) if (real(s)) return true;
            return false;
        }

        private float screen(int s, boolean wantX) {
            double rx = (rawX[s] - dev.x.min + 0.5) / (double) (dev.x.max - dev.x.min + 1);
            double ry = (rawY[s] - dev.y.min + 0.5) / (double) (dev.y.max - dev.y.min + 1);
            double nx, ny;
            switch (rot & 3) {
                case 1: nx = ry; ny = 1.0 - rx; break;
                case 2: nx = 1.0 - rx; ny = 1.0 - ry; break;
                case 3: nx = 1.0 - ry; ny = rx; break;
                default: nx = rx; ny = ry; break;
            }
            return (float) (wantX ? nx * w : ny * h);
        }

        // ---- luồng bơm
        private int indexOfKey(int key) {
            for (int i = 0; i < n; i++) if (keys[i] == key) return i;
            return -1;
        }

        private int allocPid() {
            for (int id = 0; id < 32; id++) {
                boolean used = false;
                for (int i = 0; i < n; i++) if (pids[i] == id) { used = true; break; }
                if (!used) return id;
            }
            return 31;
        }

        private void emit(int action) throws Exception {
            inj.inject(action, n, pids, xs, ys, downTime);
            injFail = 0;
        }

        private void addPointer(int key, float x, float y) throws Exception {
            if (n >= 31) return;
            keys[n] = key;
            pids[n] = allocPid();
            tids[n] = key >= 0 ? tid[key] : 0;
            xs[n] = x;
            ys[n] = y;
            n++;
            if (n == 1) {
                downTime = inj.now();
                emit(A_DOWN);
            } else {
                emit(A_PDOWN | ((n - 1) << 8));
            }
        }

        private void removeAt(int idx) throws Exception {
            if (n == 1) emit(A_UP); else emit(A_PUP | (idx << 8));
            for (int i = idx; i < n - 1; i++) {
                keys[i] = keys[i + 1];
                pids[i] = pids[i + 1];
                tids[i] = tids[i + 1];
                xs[i] = xs[i + 1];
                ys[i] = ys[i + 1];
            }
            n--;
        }

        private void addAllReal() throws Exception {
            for (int s = 0; s < nSlots; s++) {
                if (real(s) && indexOfKey(s) < 0) addPointer(s, screen(s, true), screen(s, false));
            }
        }

        /** Đồng bộ luồng bơm theo trạng thái ngón thật hiện tại (đang giữ [lock] và đã started). */
        private void syncLocked() throws Exception {
            for (int i = n - 1; i >= 0; i--) {
                int k = keys[i];
                if (k != GHOST && (!real(k) || tids[i] != tid[k])) removeAt(i);
            }
            addAllReal();
            boolean moved = false;
            for (int i = 0; i < n; i++) {
                int k = keys[i];
                if (k == GHOST) continue;
                float x = screen(k, true), y = screen(k, false);
                if (Math.abs(x - xs[i]) >= 0.5f || Math.abs(y - ys[i]) >= 0.5f) {
                    xs[i] = x;
                    ys[i] = y;
                    moved = true;
                }
            }
            if (moved && n > 0) emit(A_MOVE);
            if (n == 0) started = false;
        }

        @Override
        public void tap(int x, int y, int rot, int w, int h, int holdMs) throws Exception {
            synchronized (lock) {
                this.rot = rot;
                this.w = Math.max(1, w);
                this.h = Math.max(1, h);
                try {
                    if (!started) {
                        started = true;
                        addAllReal(); // đang có ngón thật -> chép vào luồng bơm
                    }
                    int gi = indexOfKey(GHOST);
                    if (gi >= 0) removeAt(gi);
                    addPointer(GHOST, x, y);
                } catch (Throwable t) {
                    onInjectFailLocked(t);
                    throw new Exception(t);
                }
            }
            try {
                Thread.sleep(Math.max(8, holdMs));
            } finally {
                synchronized (lock) {
                    try {
                        int gi = indexOfKey(GHOST);
                        if (gi >= 0) removeAt(gi);
                        if (n == 0) started = false;
                        else syncLocked();
                    } catch (Throwable t) {
                        onInjectFailLocked(t);
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ ioctl EVIOCGRAB (giành độc quyền cảm ứng)

    /** Gọi EVIOCGRAB qua android.system.Os.ioctlInt / libcore Os (reflection). Ném lỗi nếu máy không cho. */
    static void grabFd(java.io.FileDescriptor fd) throws Exception {
        Throwable last = null;
        Object[] holders = new Object[2];
        Class<?>[] classes = new Class<?>[2];
        try {
            classes[0] = Class.forName("android.system.Os");
        } catch (Throwable t) {
            last = t;
        }
        try {
            Class<?> lc = Class.forName("libcore.io.Libcore");
            Object osObj = lc.getField("os").get(null);
            holders[1] = osObj;
            classes[1] = osObj.getClass();
        } catch (Throwable t) {
            if (last == null) last = t;
        }
        for (int k = 0; k < 2; k++) {
            if (classes[k] == null) continue;
            java.lang.reflect.Method[] ms;
            try {
                ms = classes[k].getMethods();
            } catch (Throwable t) {
                last = t;
                continue;
            }
            for (java.lang.reflect.Method m : ms) {
                if (!m.getName().equals("ioctlInt")) continue;
                Class<?>[] pt = m.getParameterTypes();
                try {
                    m.setAccessible(true);
                    if (pt.length == 2) {
                        m.invoke(holders[k], fd, EVIOCGRAB_CMD);
                        return;
                    } else if (pt.length == 3) {
                        Object ref = pt[2].getConstructor(int.class).newInstance(1);
                        m.invoke(holders[k], fd, EVIOCGRAB_CMD, ref);
                        return;
                    }
                } catch (java.lang.reflect.InvocationTargetException ite) {
                    last = ite.getCause() != null ? ite.getCause() : ite;
                } catch (Throwable t) {
                    last = t;
                }
            }
        }
        throw new Exception(last == null ? "không có ioctlInt" : String.valueOf(last));
    }

    static final int EVIOCGRAB_CMD = 0x40044590;

    static volatile PrintStream SO = null;

    static void report(String s) {
        PrintStream p = SO;
        if (p != null) p.println(s);
    }

    // ------------------------------------------------------------------ bộ chạm

    private final Dev dev;
    private final boolean is64;
    private final FileOutputStream out;
    private final Tracker tracker;
    private final int ghostSlot;
    private int tidCounter = 0;

    GhostTouch(Dev dev, String writePath, boolean is64) throws Exception {
        this.dev = dev;
        this.is64 = is64;
        this.ghostSlot = dev.slot.max; // slot cuối: ít khi trùng với ngón thật
        this.out = new FileOutputStream(writePath);
        this.tracker = new Tracker(writePath, is64, ghostSlot, dev.slot.max + 1, dev.x, dev.y);
        Thread t = new Thread(tracker, "ghost-tracker");
        t.setDaemon(true);
        t.start();
    }

    private int nextTid() {
        int max = dev.tid.max;
        int base = max >= 2000 ? max - 1000 : Math.max(dev.tid.min + 1, max / 2);
        int span = Math.max(1, max - base);
        return base + (tidCounter++ % span);
    }

    private static int mid(Axis a, int divisor) {
        int v = a.min + Math.max(1, (a.max - a.min) / divisor);
        return Math.min(a.max, Math.max(a.min + 1, v));
    }

    /** Chạm 1 cái: ngón phụ xuống -> giữ [holdMs] -> nhấc. Ngón thật (nếu có) không bị đụng tới. */
    @Override
    public void config(boolean early, int rot, int w, int h) {
        // chế độ ghi thẳng /dev/input không cần cấu hình
    }

    @Override
    public void tap(int x, int y, int rot, int w, int h, int holdMs) throws Exception {
        int[] raw = mapToRaw(dev, x, y, rot, w, h);
        int tid = nextTid();
        boolean trk = tracker.ok;
        int restore = tracker.lastRealSlot;

        Frame d = new Frame(is64);
        d.add(EV_ABS, ABS_MT_SLOT, ghostSlot);
        d.add(EV_ABS, ABS_MT_TRACKING_ID, tid);
        // Có ngón thật đang đặt thì BTN_TOUCH đã =1 (lệnh trùng sẽ bị kernel bỏ qua, vô hại).
        if (dev.btnTouch) d.add(EV_KEY, BTN_TOUCH, 1);
        if (dev.toolFinger) d.add(EV_KEY, BTN_TOOL_FINGER, 1);
        d.add(EV_ABS, ABS_MT_POSITION_X, raw[0]);
        d.add(EV_ABS, ABS_MT_POSITION_Y, raw[1]);
        if (dev.pressure != null) d.add(EV_ABS, ABS_MT_PRESSURE, mid(dev.pressure, 3));
        if (dev.major != null) d.add(EV_ABS, ABS_MT_TOUCH_MAJOR, mid(dev.major, 10));
        if (dev.width != null) d.add(EV_ABS, ABS_MT_WIDTH_MAJOR, mid(dev.width, 10));
        d.add(EV_ABS, ABS_MT_SLOT, restore); // trả "slot hiện hành" về chỗ driver đang dùng
        d.add(EV_SYN, SYN_REPORT, 0);
        out.write(d.bytes());
        out.flush();

        Thread.sleep(Math.max(8, holdMs));

        Frame u = new Frame(is64);
        u.add(EV_ABS, ABS_MT_SLOT, ghostSlot);
        u.add(EV_ABS, ABS_MT_TRACKING_ID, -1);
        // Chỉ hạ BTN_TOUCH khi CHẮC CHẮN không còn ngón thật nào; nếu không Android sẽ coi ngón thật là "lơ lửng" và huỷ nó.
        if (trk && tracker.realActive() == 0) {
            if (dev.btnTouch) u.add(EV_KEY, BTN_TOUCH, 0);
            if (dev.toolFinger) u.add(EV_KEY, BTN_TOOL_FINGER, 0);
        }
        u.add(EV_ABS, ABS_MT_SLOT, tracker.lastRealSlot);
        u.add(EV_SYN, SYN_REPORT, 0);
        out.write(u.bytes());
        out.flush();
    }

    // ------------------------------------------------------------------ main

    public static void main(String[] args) {
        PrintStream so = new PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out), true);
        SO = so;
        Toucher g;
        try {
            Dev d = pickTouchscreen(parseProps(runGetevent()));
            if (d == null) {
                so.println("FAIL không tìm thấy màn hình cảm ứng đa chạm");
                return;
            }
            if (d.slot.max < 1) {
                so.println("FAIL màn hình cảm ứng chỉ có 1 slot");
                return;
            }
            Toucher direct = null;
            String why = "";
            try {
                GhostTouch gt = new GhostTouch(d, d.path, is64());
                direct = gt;
                so.println("READY " + d.path + " " + (d.x.max + 1) + " " + (d.y.max + 1) + " " + gt.ghostSlot + " WRITE");
            } catch (Throwable t) {
                why = String.valueOf(t.getMessage());
            }
            if (direct == null) {
                // Không ghi được /dev/input -> tiếp quản ngón thật (đọc /dev/input + bơm MotionEvent)
                try {
                    new FileInputStream(d.path).close(); // đọc được không?
                    Injector inj = new ReflectInjector();
                    final Takeover tk = new Takeover(d, d.path, inj, is64());
                    Thread t = new Thread(tk, "takeover-reader");
                    t.setDaemon(true);
                    t.start();
                    Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
                        @Override
                        public void run() {
                            tk.shutdown(); // nhả mọi ngón đang bơm dở khi tiến trình thoát
                        }
                    }));
                    direct = tk;
                    so.println("READY " + d.path + " " + (d.x.max + 1) + " " + (d.y.max + 1) + " 0 TAKEOVER");
                } catch (Throwable t2) {
                    so.println("FAIL không ghi được " + d.path + " (" + why + "); tiếp quản lỗi: " + t2.getMessage());
                    return;
                }
            }
            g = direct;
        } catch (Throwable t) {
            so.println("FAIL " + t);
            return;
        }

        try {
            android.os.Process.setThreadPriority(-8);
        } catch (Throwable ignored) {
        }
        try (BufferedReader in = new BufferedReader(new InputStreamReader(System.in))) {
            String line;
            while ((line = in.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                if (line.equals("Q")) break;
                String[] p = line.split("\\s+");
                if (p[0].equals("P") && p.length >= 5) {
                    try {
                        g.config(p[1].equals("1"), Integer.parseInt(p[2]), Integer.parseInt(p[3]), Integer.parseInt(p[4]));
                    } catch (Throwable ignored) {
                    }
                } else if (p[0].equals("T") && p.length >= 8) {
                    String id = p[1];
                    try {
                        g.tap(Integer.parseInt(p[2]), Integer.parseInt(p[3]), Integer.parseInt(p[4]),
                                Integer.parseInt(p[5]), Integer.parseInt(p[6]), Integer.parseInt(p[7]));
                        so.println("D " + id);
                    } catch (Throwable t) {
                        so.println("E " + id + " " + t);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        System.exit(0);
    }
}

/** Một "bộ chạm": ghi thẳng /dev/input (GhostTouch) hoặc tiếp quản ngón thật (GhostTouch.Takeover). */
interface Toucher {
    void tap(int x, int y, int rot, int w, int h, int holdMs) throws Exception;

    /** Cấu hình từ app: [early] = tiếp quản sớm; [rot]/[w]/[h] = hướng + kích thước màn hình hiện tại. */
    void config(boolean early, int rot, int w, int h);
}
