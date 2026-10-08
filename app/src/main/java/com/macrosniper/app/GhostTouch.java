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
public final class GhostTouch {

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

    // ------------------------------------------------------------------ theo dõi ngón tay THẬT đang đặt trên màn hình

    static final class Tracker implements Runnable {
        private final String path;
        private final int evSize;
        private final int ghostSlot;
        private final boolean[] active;
        private volatile int curSlot = 0;
        volatile int lastRealSlot = 0;
        volatile boolean ok = false;

        Tracker(String path, boolean is64, int ghostSlot, int slots) {
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
        this.tracker = new Tracker(writePath, is64, ghostSlot, dev.slot.max + 1);
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
    void tap(int x, int y, int rot, int w, int h, int holdMs) throws Exception {
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
        GhostTouch g;
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
            try {
                g = new GhostTouch(d, d.path, is64());
            } catch (Throwable t) {
                so.println("FAIL không ghi được " + d.path + " (" + t.getMessage() + ")");
                return;
            }
            so.println("READY " + d.path + " " + (d.x.max + 1) + " " + (d.y.max + 1) + " " + g.ghostSlot);
        } catch (Throwable t) {
            so.println("FAIL " + t);
            return;
        }

        try (BufferedReader in = new BufferedReader(new InputStreamReader(System.in))) {
            String line;
            while ((line = in.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                if (line.equals("Q")) break;
                String[] p = line.split("\\s+");
                if (p[0].equals("T") && p.length >= 8) {
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
