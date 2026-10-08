# Macro Sniper

App macro chạm màn hình với nút nổi (Android 7.0+).

## Build ra APK bằng GitHub
1. Đẩy toàn bộ thư mục này lên một repo GitHub (nhánh `main`).
2. Vào tab **Actions** -> workflow **Build APK** (tự chạy sau khi push, hoặc bấm *Run workflow*).
3. Chạy xong, mở lần chạy đó -> mục **Artifacts** -> tải `MacroSniper-APK` -> giải nén ra file `.apk` rồi cài lên điện thoại.

## Sử dụng
1. Cài APK, mở app, bật dịch vụ **Macro Sniper** trong Cài đặt -> Trợ năng.
2. Bấm nút **BẮT ĐẦU DÙNG MACRO** -> bong bóng logo nổi lên (cũng chính nút này sẽ đổi thành **TẮT MACRO** để gỡ giao diện nổi).
3. Chạm bong bóng -> bảng setup: tạo nút trung tâm `main1`, chọn main đó rồi thêm nút số (mỗi main có số riêng: `main1 > 1 > 2`, `main2 > 1 > 2`...). Kéo vào đúng vị trí, chạm nút để chỉnh size / độ trong (0-100%, lúc setup luôn hiện tối thiểu 20%) / tốc độ.
4. Nút main có 3 kiểu kích hoạt: **Khi ấn**, **Khi thả**, **Giữ lặp** (giữ tay thì chuỗi lặp lại liên tục, thả tay là dừng).
5. Bấm ✕ để khóa nút. Bấm `main1` -> app tự chạm 1 -> 2 -> 3... theo tốc độ đã cài.

## Chế độ chạm (chọn tay ở màn hình đầu hoặc trên bảng nổi: Trợ năng / Gỡ lỗi WiFi)
Ghép cặp Gỡ lỗi không dây 1 lần (Android 11+): Tùy chọn nhà phát triển → Gỡ lỗi không dây → bật → mở app, nhập mã 6 số. Sau đó app tự kết nối mỗi khi bấm BẮT ĐẦU.

### Engine chạm mới (GhostTouch.java) — thứ tự ưu tiên
1. **Ghi thẳng /dev/input** (app tự dò màn hình cảm ứng bằng `getevent -lp`): macro là 1 ngón thật nữa trên cùng thiết bị → không xung đột. Chỉ chạy được nếu máy cho ghi.
2. **Relay hợp nhất** (máy chặn ghi, vd. Samsung): đọc ngón thật từ /dev/input, `EVIOCGRAB` giành độc quyền cảm ứng, rồi tự bơm LẠI ngón thật + ngón macro thành **một luồng duy nhất**. Hệ thống chỉ còn 1 nguồn chạm nên không huỷ ngón thật, không bị trùng 2 luồng (nguyên nhân khựng khi xoay camera). Chỉ đổi trạng thái grab khi không có ngón nào đặt; tiến trình chết / bơm lỗi 3 lần liên tiếp thì tự nhả. Trạng thái: `chạm hợp nhất (1 luồng…)`.
3. Nếu máy cấm `EVIOCGRAB`: lùi về bơm bản sao ngón thật (có thể còn hơi khựng) — bảng nổi hiện lý do.

Toạ độ dùng kích thước THẬT của màn hình (`Display.getRealMetrics`) để nút bấm trúng tâm.

## Tên tuỳ chỉnh cho nút
Chạm nút ở chế độ setup → ô **Tên nút** (tối đa 16 ký tự). Để trống = hiện số / `mainN`. Chữ tự co nhỏ, xuống 2 dòng (nếu có dấu cách) hoặc cắt "…" để luôn nằm gọn trong nút. Tên được lưu cùng macro.

## Xoay màn hình
Vị trí nút + bong bóng được nhớ RIÊNG cho hướng dọc và hướng ngang. Hướng chưa từng đặt thì tự suy ra theo tỉ lệ từ hướng kia, kéo đi đâu thì nhớ đó; xoay qua lại không còn bị kẹt vị trí của hướng cũ.
