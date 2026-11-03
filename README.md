# Nuliz Ads

Ứng dụng Android chặn quảng cáo, theo dõi và tên miền độc hại cho **toàn bộ điện thoại** (app + web), không cần root.
Phiên bản hiện tại: **v1.000.2026**

## Cách hoạt động

Nuliz Ads tạo một VPN **chạy ngay trên máy** (không gửi dữ liệu đi đâu) chỉ để bắt các truy vấn DNS.
Tên miền nằm trong blocklist (quảng cáo / mã độc / lừa đảo) bị trả lời "không tồn tại" nên quảng cáo không tải được;
các tên miền còn lại được chuyển tiếp bình thường tới `1.1.1.1` / `8.8.8.8`. Mỗi lần chặn:
tổng số trong app tăng lên + hiện thông báo kèm tên miền.

Blocklist được **tự cập nhật mỗi lần build** (StevenBlack, AdAway, Yoyo, Phishing Army, AntiMalware).

## NaNGPT (chat AI)

Tab **NaNGPT** là trợ lý chat tích hợp sẵn, tự xưng là NaNGPT, nói chuyện kiểu người thật và biết cách dùng Nuliz Ads.
Gửi được ảnh (chụp trực tiếp hoặc chọn từ máy), tệp (văn bản, PDF, mp3, wav) và ghi âm trực tiếp.

- Dưới mỗi câu trả lời có hàng nút: sao chép, chia sẻ, đọc to (TextToSpeech của máy), thích / không thích, tạo lại câu trả lời.
- Nhiều đoạn chat: nút ba gạch mở ngăn kéo danh sách (nhóm theo ngày, có tìm kiếm không phân biệt dấu), nút cây bút mở đoạn chat mới, tên đoạn chat tự đặt theo tin đầu tiên và đổi được ở nút ba chấm.
- Xoá đoạn chat: bấm giữ một đoạn để chọn (chọn được nhiều) rồi bấm thùng rác, hoặc dùng dropdown mốc thời gian (Tất cả / 30 ngày / 15 ngày / 1 ngày / Hôm nay) kèm nút thùng rác bên cạnh. Luôn có hộp xác nhận.
- Dữ liệu lưu ở `Chat.kt` (`Prefs.chatsJson`); cuộc trò chuyện của bản cũ tự chuyển thành một đoạn chat.
- Logo AI nằm ở `res/drawable-nodpi/ai_logo.png`, dùng làm avatar NaNGPT.
- Tin của người dùng có nút sao chép, **sửa** và **thử lại**; dùng được cả khi đã bấm dừng giữa chừng. Sửa hoặc thử lại sẽ bỏ các tin phía sau (hỏi xác nhận nếu bỏ nhiều hơn một câu trả lời).
- Khối code hiện tên ngôn ngữ kèm nút sao chép riêng.
- Chụp ảnh dùng app camera của máy qua `FileProvider` nên không cần quyền camera.

- Lần đầu mở tab, app hiện hướng dẫn kèm nút mở trang tạo key; tạo xong quay lại dán key và bấm Lưu.
- Key chỉ lưu trong máy người dùng, app gọi thẳng tới dịch vụ AI, không qua server trung gian.
- Đổi hoặc xoá key ở **Cài đặt**. Tên model, endpoint nằm ở `Gemini.kt`; tính cách và kiến thức về app nằm ở `Persona.kt`.

## Lấy file APK (làm 1 lần)

1. Tạo repo mới trên GitHub (khuyến nghị **Private**, vì repo chứa key ký app).
2. Đẩy **toàn bộ nội dung thư mục này** lên repo, giữ nguyên cấu trúc (nhớ cả thư mục ẩn `.github`).
   Trên web: *Add file → Upload files*, kéo thả cả thư mục. Nếu `.github` không lên, chọn *Add file → Create new file*,
   gõ tên `.github/workflows/build.yml` rồi dán nội dung file đó vào.
3. Vào tab **Actions → Build Nuliz Ads**. Workflow tự chạy khi có commit; hoặc bấm **Run workflow**.
4. Chạy xong (~5 phút): mở lần chạy đó → mục **Artifacts** → tải `NulizAds-v1.000.2026` → giải nén ra `.apk` → cài lên điện thoại.
   Nếu chạy với tuỳ chọn **release = true**, APK nằm sẵn ở mục **Releases** của repo.

## Cập nhật sau này

Key ký nằm ở `keystore/` và được dùng cho **mọi bản build**, nên bản mới **cài đè** lên bản cũ mà không mất dữ liệu.
**Đừng xoá hay đổi thư mục `keystore/`** — mất key là không cập nhật đè được nữa.

Đổi phiên bản có 2 cách:

- Sửa `VERSION_NAME` trong `version.properties` rồi commit (ví dụ `1.001.2026`).
- Hoặc **Actions → Run workflow** và gõ tên phiên bản vào ô *version_name*, không cần sửa file.

Số build (`versionCode`) tự tăng mỗi lần chạy workflow nên luôn lớn hơn bản trước.

## Giới hạn cần biết

- Chặn theo **tên miền**: quảng cáo cùng tên miền với nội dung (ví dụ video quảng cáo trong YouTube) **không** chặn được bằng cách này.
- Nếu đặt **DNS riêng tư** (Private DNS) cứng trong Cài đặt Android thì truy vấn sẽ đi vòng qua app. Hãy để *Tắt* hoặc *Tự động*.
- Trình duyệt bật "DNS an toàn / DoH" riêng cũng bỏ qua bộ lọc; tắt tuỳ chọn đó trong cài đặt trình duyệt.

## Cấu trúc

```
version.properties            phiên bản (sửa ở đây)
keystore/                     key ký app (giữ cố định)
scripts/update_blocklist.sh   tải + gộp blocklist
app/src/main/java/com/nuliz/ads/
  AdBlockVpnService.kt        VPN: đọc gói DNS, chặn / chuyển tiếp
  Dns.kt                      phân tích + dựng gói DNS, chuyển tiếp lên DNS thật
  Blocklist.kt                tra cứu tên miền (khớp cả tên miền con)
  Stats.kt, Prefs.kt          bộ đếm + nhật ký + lưu trữ
  Chat.kt                     trạng thái chat, đính kèm, ghi âm
  Gemini.kt                   gọi API chat (stream)
  Persona.kt                  tính cách NaNGPT + kiến thức về app
  Notifier.kt                 thông báo mỗi lần chặn
  ui/                         giao diện Jetpack Compose (ChatScreen.kt, Icons.kt = icon Lucide)
```
