package com.macrosniper.app;

/**
 * Bộ lọc "vuốt tâm" cho MỘT ngón tay (thuần Java, chạy được trong app_process lẫn trong app).
 *
 * Ngón thật cho ra các toạ độ thô (x, y) theo từng khung cảm ứng. Bộ lọc biến chúng thành quỹ đạo mượt hơn:
 *  1. Làm mượt thích nghi kiểu One-Euro: ngón gần như đứng yên (run tay) thì lọc mạnh, ngón vuốt nhanh thì gần như
 *     không lọc (không trễ). Lọc từng trục độc lập nên mọi hướng / mọi góc đều như nhau.
 *  2. Vùng chết (chống rung): chỉ có tác dụng khi ngón gần như đứng yên. Hễ ngón đã vuốt (tốc độ vượt ngưỡng) thì vùng
 *     chết thu về 0 -> đổi hướng, đổi góc không còn bị "dính" lại.
 *  3. Tăng lực vuốt tính theo QUÃNG DI CHUYỂN TỪNG KHUNG (delta), không tính theo khoảng cách tới điểm đặt tay:
 *     ngón ảo = ngón ảo cũ + delta_đã_lọc * gain. Nhờ vậy hướng đẩy luôn bám theo hướng ngón thật đang đi.
 *  4. Chạm mép: ngón ảo sắp ra khỏi màn hình theo hướng đang đi thì [needRebase] báo true; bên ngoài nhấc ngón ảo lên
 *     và đặt lại ở phía sau hướng đi ([rebase]) để vuốt tiếp không bị kẹt ở mép (trước đây bị kẹp cứng từng trục
 *     nên chỉ còn đi được theo đường thẳng tuyệt đối).
 *  5. Nhấc tay: [finish] chốt ngón ảo tại đúng vị trí cuối của ngón thật (không bị bỏ lại phía sau -> hết khựng).
 *
 * Đầu ra = vị trí ngón "ảo" mà app bơm vào hệ thống thay cho ngón thật.
 */
final class AimFilter {
    /** Tần số cắt (Hz) khi độ mượt = 1 và = 100 (lúc đứng yên). */
    static final double CUT_LOOSE = 30.0, CUT_TIGHT = 2.0;
    /** Mỗi px/giây tốc độ làm tần số cắt tăng thêm bấy nhiêu Hz (vuốt càng nhanh càng ít trễ). */
    static final double BETA = 0.05;
    /** Tần số cắt cho bộ ước lượng tốc độ. */
    static final double D_CUT = 12.0;
    /** Tốc độ (px/s) mà từ đó vùng chết thu về 0. */
    static final double DEAD_OFF_SPEED = 160.0;
    /** Khoảng cách tối thiểu giữa 2 lần đặt lại ngón ảo (ns). */
    static final long REBASE_GAP_NS = 60_000_000L;

    private final float zx, zy, zr;  // vòng vuốt tâm (để đặt lại ngón khi chạm mép)
    private final int smooth;        // 0..100
    private final float gain;        // hệ số nhân (1.0 = giữ nguyên)
    private final float dead;        // px

    private double fx, fy;           // sau làm mượt
    private double ox, oy;           // sau vùng chết
    private double qx, qy;           // ox, oy của lần trước (để lấy delta)
    private double px, py;           // mẫu thô trước đó
    private double vx, vy;           // tốc độ ước lượng (px/s)
    private double gx, gy;           // ngón ảo (đầu ra, chưa kẹp)
    private long lastNs;             // lần gọi update gần nhất
    private long sampleNs;           // lần gần nhất có mẫu thô MỚI
    private long rebaseNs;           // lần đặt lại gần nhất

    AimFilter(float x, float y, float zx, float zy, float zr, int smooth, int gainPct, float deadPx, long nowNs) {
        this.zx = zx;
        this.zy = zy;
        this.zr = zr;
        this.smooth = Math.max(0, Math.min(100, smooth));
        this.gain = Math.max(0.1f, Math.min(8f, gainPct / 100f));
        this.dead = Math.max(0f, deadPx);
        fx = ox = qx = px = gx = x;
        fy = oy = qy = py = gy = y;
        lastNs = nowNs;
        sampleNs = nowNs;
        rebaseNs = nowNs;
    }

    private static double clampDt(double dt) {
        return dt < 0.001 ? 0.001 : (dt > 0.1 ? 0.1 : dt);
    }

    private static double alpha(double cutoff, double dt) {
        double tau = 1.0 / (2.0 * Math.PI * cutoff);
        return 1.0 / (1.0 + tau / dt);
    }

    /**
     * Nạp mẫu thô (px màn hình) tại thời điểm [nowNs] (nanoTime). Cũng được gọi đều đặn khi KHÔNG có mẫu mới
     * (màn hình cảm ứng ngừng báo lúc ngón đứng yên): bộ lọc vẫn "lắng" dần về ngón thật, còn tốc độ ước lượng chỉ
     * được cập nhật theo mẫu mới (hoặc giảm dần về 0 nếu lâu không có mẫu).
     */
    void update(float x, float y, long nowNs) {
        double dt = clampDt((nowNs - lastNs) / 1e9);
        lastNs = nowNs;
        boolean fresh = x != px || y != py;

        // tốc độ ngón thật (dùng cho cả làm mượt lẫn vùng chết)
        if (fresh) {
            double ds = clampDt((nowNs - sampleNs) / 1e9);
            sampleNs = nowNs;
            double ad = alpha(D_CUT, ds);
            vx += ad * ((x - px) / ds - vx);
            vy += ad * ((y - py) / ds - vy);
        } else if (nowNs - sampleNs > 30_000_000L) {
            double ad = alpha(D_CUT, dt); // lâu không có mẫu: coi như ngón đã dừng
            vx -= ad * vx;
            vy -= ad * vy;
        }
        double speed = Math.hypot(vx, vy);

        if (smooth <= 0) {
            fx = x;
            fy = y;
        } else {
            double minCut = CUT_LOOSE * Math.pow(CUT_TIGHT / CUT_LOOSE, (smooth - 1) / 99.0);
            double a = alpha(minCut + BETA * speed, dt);
            fx += a * (x - fx);
            fy += a * (y - fy);
        }
        px = x;
        py = y;

        // vùng chết: chỉ chống rung lúc ngón gần như đứng yên; đã vuốt thì thu về 0
        double dz = dead * Math.max(0.0, 1.0 - speed / DEAD_OFF_SPEED);
        double ex = fx - ox, ey = fy - oy;
        double d = Math.hypot(ex, ey);
        if (d > dz && d > 0) {
            double k = (d - dz) / d;
            ox += ex * k;
            oy += ey * k;
        }
        advance();
    }

    /** Ngón ảo đi thêm đúng delta (đã lọc) * gain. */
    private void advance() {
        gx += (ox - qx) * gain;
        gy += (oy - qy) * gain;
        qx = ox;
        qy = oy;
    }

    /** Ngón thật vừa nhấc ở (x, y): chốt ngón ảo tới đúng điểm cuối (bỏ qua lọc / vùng chết) để không bị bỏ lại phía sau. */
    void finish(float x, float y) {
        fx = ox = x;
        fy = oy = y;
        px = x;
        py = y;
        advance();
    }

    private double margin(float w, float h) {
        return Math.max(16.0, 0.07 * Math.min(w, h)); // chừa vùng cử chỉ vuốt mép của hệ thống
    }

    /** Ngón ảo đang chạm / sắp ra mép màn hình THEO HƯỚNG ĐANG ĐI (đi vào trong thì không tính). */
    boolean needRebase(float w, float h, long nowNs) {
        if (nowNs - rebaseNs < REBASE_GAP_NS) return false;
        double m = margin(w, h);
        return (gx < m && vx < 0) || (gx > w - 1 - m && vx > 0) || (gy < m && vy < 0) || (gy > h - 1 - m && vy > 0);
    }

    /**
     * Đặt lại ngón ảo ở phía SAU hướng đang đi (trong vòng vuốt tâm, kẹp trong vùng an toàn của màn hình) để còn chỗ
     * vuốt tiếp. Không có bước nhảy nào ở bộ lọc: ngón thật vẫn được theo dõi bằng delta như cũ.
     */
    void rebase(float w, float h, long nowNs) {
        double sp = Math.hypot(vx, vy);
        double dx, dy;
        if (sp > 1e-3) {
            dx = vx / sp;
            dy = vy / sp;
        } else { // đứng yên: lấy hướng từ tâm vòng tới ngón ảo
            dx = gx - zx;
            dy = gy - zy;
            double n = Math.hypot(dx, dy);
            if (n < 1e-3) { dx = 0; dy = 0; } else { dx /= n; dy /= n; }
        }
        double m = margin(w, h);
        gx = Math.max(m, Math.min(w - 1 - m, zx - dx * 0.6 * zr));
        gy = Math.max(m, Math.min(h - 1 - m, zy - dy * 0.6 * zr));
        rebaseNs = nowNs;
    }

    float x() {
        return (float) gx;
    }

    float y() {
        return (float) gy;
    }
}
