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
- **Gỡ lỗi WiFi** (Android 11+): app tự làm máy khách ADB qua *Gỡ lỗi không dây*, chạm bằng quyền shell: ghi 1 "ngón thứ N" (slot riêng) thẳng vào màn hình cảm ứng nên vẫn xoay/kéo màn hình được trong lúc macro chạm (nếu máy không cho thì tự lùi về `input tap`). Không cần Shizuku hay app ngoài.
  - Ghép cặp 1 lần: Tùy chọn nhà phát triển → Gỡ lỗi không dây → bật, bấm BẮT ĐẦU, chạm bong bóng → *Chế độ chạm* → *Gỡ lỗi WiFi* → *Ghép cặp*, rồi nhập cổng + mã 6 số từ hộp thoại "Ghép nối thiết bị bằng mã".
  - Cần đang kết nối Wi-Fi (không cần có internet). Mất Wi-Fi thì đổi về **Trợ năng** ngay trên bảng nổi hoặc ở màn hình chính để dùng tạm; có Wi-Fi lại thì chọn **Gỡ lỗi WiFi**, app tự nối lại.
