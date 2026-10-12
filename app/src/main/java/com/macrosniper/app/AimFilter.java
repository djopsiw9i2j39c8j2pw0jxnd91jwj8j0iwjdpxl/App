package com.macrosniper.app;

/**
 * Bộ lọc "vuốt tâm" cho MỘT ngón tay (thuần Java, chạy được trong app_process lẫn trong app).
 *
 * Ngón thật cho ra các toạ độ thô (x, y) theo từng khung cảm ứng. Bộ lọc biến chúng thành quỹ đạo mượt hơn:
 *  1. Làm mượt thích nghi kiểu One-Euro: ngón gần như đứng yên (run tay) thì lọc rất mạnh, ngón vuốt nhanh thì lọc nhẹ
 *     để không bị trễ. Mọi hướng vuốt đều như nhau (lọc từng trục độc lập, không thiên về hướng nào).
 *  2. Vùng chết (chống rung): dao động nhỏ hơn [dead] px quanh điểm hiện tại bị bỏ qua hoàn toàn.
 *  3. Tăng lực vuốt: quãng đường vuốt tính từ điểm đặt tay được nhân với [gainPct]/100.
 *
 * Đầu ra = vị trí ngón "ảo" mà app bơm vào hệ thống thay cho ngón thật.
 */
final class AimFilter {
    /** Tần số cắt tối thiểu (Hz) khi độ mượt = 1 và = 100. */
    static final double CUT_LOOSE = 12.0, CUT_TIGHT = 0.5;
    /** Mỗi px/giây tốc độ làm tần số cắt tăng thêm bấy nhiêu Hz (vuốt càng nhanh càng ít trễ). */
    static final double BETA = 0.012;
    /** Tần số cắt cho bộ ước lượng tốc độ. */
    static final double D_CUT = 8.0;

    private final float ax, ay;      // điểm đặt tay (mốc tính quãng đường)
    private final int smooth;        // 0..100
    private final float gain;        // hệ số nhân (1.0 = giữ nguyên)
    private final float dead;        // px

    private double fx, fy;           // sau làm mượt
    private double ox, oy;           // sau vùng chết
    private double px, py;           // mẫu thô trước đó
    private double vx, vy;           // tốc độ ước lượng (px/s)
    private long lastNs;            // lần gọi update gần nhất
    private long sampleNs;          // lần gần nhất có mẫu thô MỚI
    private float outX, outY;

    AimFilter(float x, float y, int smooth, int gainPct, float deadPx, long nowNs) {
        this.ax = x;
        this.ay = y;
        this.smooth = Math.max(0, Math.min(100, smooth));
        this.gain = Math.max(0.1f, Math.min(8f, gainPct / 100f));
        this.dead = Math.max(0f, deadPx);
        fx = ox = px = x;
        fy = oy = py = y;
        lastNs = nowNs;
        sampleNs = nowNs;
        outX = x;
        outY = y;
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

        if (smooth <= 0) {
            fx = x;
            fy = y;
            if (fresh) sampleNs = nowNs;
        } else {
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
            double minCut = CUT_LOOSE * Math.pow(CUT_TIGHT / CUT_LOOSE, (smooth - 1) / 99.0);
            double a = alpha(minCut + BETA * speed, dt);
            fx += a * (x - fx);
            fy += a * (y - fy);
        }
        px = x;
        py = y;

        // vùng chết: chỉ đi theo khi điểm lọc cách điểm hiện tại quá [dead] px
        double ex = fx - ox, ey = fy - oy;
        double d = Math.hypot(ex, ey);
        if (d > dead && d > 0) {
            double k = (d - dead) / d;
            ox += ex * k;
            oy += ey * k;
        }

        outX = (float) (ax + (ox - ax) * gain);
        outY = (float) (ay + (oy - ay) * gain);
    }

    float x() {
        return outX;
    }

    float y() {
        return outY;
    }
}
