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

## Chế độ chạm
- **Trợ năng**: dùng dịch vụ Trợ năng để chạm (mặc định, chạy mọi máy Android 7+).
- **Gỡ lỗi WiFi** (Android 11+): app tự làm máy khách ADB qua *Gỡ lỗi không dây*, chạm bằng lệnh `input tap` với quyền shell. Không cần Shizuku hay app ngoài.
  - Ghép cặp 1 lần: Tùy chọn nhà phát triển → Gỡ lỗi không dây → bật, bấm BẮT ĐẦU, chạm bong bóng → *Chế độ chạm* → *Gỡ lỗi WiFi* → *Ghép cặp*, rồi nhập cổng + mã 6 số từ hộp thoại "Ghép nối thiết bị bằng mã".
  - Cần đang kết nối Wi-Fi (không cần có internet). Mất Wi-Fi thì đổi về **Trợ năng** ngay trên bảng nổi hoặc ở màn hình chính để dùng tạm; có Wi-Fi lại thì chọn **Gỡ lỗi WiFi**, app tự nối lại.

## Ngón tay phụ (sửa lỗi kẹt ngón khi dùng Gỡ lỗi WiFi)
Trước đây lệnh `input tap` bị Android coi là một "thiết bị chạm khác" nên mỗi lần macro bấm là **huỷ cử chỉ của ngón thật** (đang xoay camera / bấm nút khác bị kẹt). Bản này sửa 2 chỗ:
1. **FLAG_SPLIT_TOUCH** cho mọi cửa sổ nổi: 1 ngón đè nút main, ngón khác vẫn chạm được vào game (xoay cam, bấm nút khác).
2. **GhostTouch** (`GhostTouch.java`): khi kết nối Gỡ lỗi WiFi, app chạy thêm 1 tiến trình quyền shell, ghi thẳng sự kiện đa chạm vào `/dev/input/eventX` của màn hình cảm ứng bằng 1 slot riêng -> Android thấy đó là thêm 1 ngón tay thật, ngón gốc không bị đụng tới.
   - Trạng thái hiện trên bảng nổi / màn hình chính: `ngón tay phụ (không chặn ngón thật)` = đang dùng; `chạm bằng input tap · <lý do>` = máy không cho ghi `/dev/input` nên tự lùi về cách cũ.
   - Nếu máy không hỗ trợ ngón phụ: dùng chế độ **Trợ năng**.

### Chế độ "tiếp quản ngón thật" (máy chặn ghi /dev/input — lỗi `open failed: EACCES`)
Nhiều máy (vd. Samsung) không cho ghi `/dev/input/eventX` nhưng vẫn cho **đọc**. Khi đó app tự chuyển sang tiếp quản:
- App đọc toạ độ ngón thật từ `/dev/input` (đọc thì được phép).
- Khi macro cần chạm, app bơm lại **cả ngón thật lẫn ngón macro** thành MỘT luồng đa chạm nhất quán (qua `injectInputEvent` với quyền shell). Ngón thật vẫn xoay camera / bấm nút khác bình thường, ngón macro là con trỏ thêm.
- Mọi ngón thật nhấc lên thì app nhả luồng, trả lại cho hệ thống.
- Trạng thái hiện: `tiếp quản ngón thật (vừa chỉnh cam vừa macro)`.
- Game có thể thấy 1 lần "huỷ cử chỉ" lúc macro chạm đầu tiên khi tay đang đặt, rồi ngón được nuôi tiếp ngay tại chỗ (camera không rơi). Nút main đang giữ (Giữ lặp) đã được xử lý để không bị ngắt vì cú huỷ này.
