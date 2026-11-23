# FIX35 — Giọng khớp hình trở lại khi trễ quá 10s (v1.2.14, code 19)

Ngày: 2026-11-23. Nền: FIX34 (v1.2.13). User báo ở bản 34: giọng bị
mất khi đã có bản dịch, giống như bị chặn lại — và hỏi có phải do trễ
hình và video gốc không.

## Đúng, do trễ hình — và là chỗ FIX31 sót
- FIX31 nâng trần trễ tự động lên 20s ở mọi chỗ (Kotlin, DelayNode
  22s, thanh trượt, bộ đệm khung hình) — nhưng SÓT một chỗ trong JS
  trễ hình ở `BrowserActivity.kt`: `__aladSetDelay` vẫn kẹp trễ ở 10
  giây (`Math.min(10, ...)`).
- Hậu quả: khi trễ tự động vượt 10s (ảnh user gửi trước đó ghi 12,901s),
  hình + tiếng gốc chỉ trễ đúng 10s, trong khi giọng dịch giữ theo trễ
  đầy đủ (12,9s) — giọng tới sau hình (trễ − 10s): ở 12,9s thì sau gần
  3s, ở trễ 20s thì sau tới 10s. Cảnh đã chuyển mà giọng câu trước mới
  tới, nên nghe như giọng bị mất dù phụ đề (hiện ngay khi dịch về) đã có.
- User đoán đúng hướng ngay từ đầu.

## Cách sửa
- Một dòng duy nhất: `__aladSetDelay` kẹp 10s → 20s. DelayNode đã 22s
  và bộ đệm khung hình đã theo công thức thưa dần của FIX31 nên 20s
  chạy được, không cần sửa thêm chỗ nào.

## Đã kiểm tra và loại trừ
- Cổng lọc tiếng kêu FIX30/FIX34: chỉ bỏ đoạn giọng khi chữ dịch của
  đợt đó toàn tiếng kêu — câu thật có từ thật thì không bao giờ bị bỏ;
  không phải nguyên nhân mất giọng hàng loạt.
- Hàng chờ giọng (128 đoạn): giữ giọng lúc đầu chỉ ~2s (phần trễ còn
  lại sau thời gian dịch), không làm tràn hàng chờ.
- Giữ giọng chỉ đặt một lần lúc mới có bản dịch đầu tiên, không đặt lại
  khi đổi key/nối lại — không phải do watchdog FIX34.
- Phụ đề hiện ngay khi bản dịch về (không trễ theo hình) nên phụ đề
  luôn sớm hơn giọng vài giây — đó là thiết kế cũ, không phải lỗi mới.

## Kiểm chứng
- Test kẹp trễ (node, trích đúng hàm JS): 7/7 (12,901 → 12,901; 20 →
  20; 25 → 20; 5 → 5; 0 → 0; số âm → 0; NaN → 0).
- Không còn kẹp 10s nào trên đường trễ trong toàn bộ source.
- `BrowserActivity.kt` cân ngoặc; diff với FIX34 chỉ đúng 2 file
  (BrowserActivity.kt + build.gradle.kts).
- CHƯA build APK thật, chưa chạy trên máy user.

## Cần user kiểm chứng
Mở video ở chế độ TỰ ĐỘNG, xem tới lúc trễ tự động vượt 10s (dòng
trạng thái ghi trên 10 giây): giọng còn khớp với miệng nhân vật không,
hay vẫn tới trễ sau hình.
