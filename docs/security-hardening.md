# Security Hardening — Next Please Backend

Tài liệu này mô tả các biện pháp chống lạm dụng (abuse) ở tầng ứng dụng đã được
triển khai, và những việc **bắt buộc làm ở tầng hạ tầng** mà code không thể lo.

## 1. DDoS — phân tầng trách nhiệm

| Loại tấn công | Tầng xử lý | Trạng thái |
|---|---|---|
| Volumetric (L3/L4): SYN flood, UDP flood, amplification | **Hạ tầng / CDN** | ⛔ Chưa có — cần Cloudflare |
| HTTP flood, brute-force, spam endpoint (L7) | **Ứng dụng (Spring)** | ✅ Đã có rate-limit (xem mục 2) |

> **Quan trọng:** Code Spring Boot **không bao giờ** chống được DDoS volumetric — gói
> tin đã ngốn băng thông/CPU trước khi tới app. Việc này phải nằm ở edge.

### Khuyến nghị hạ tầng (ưu tiên #1, không cần sửa code)
Đặt **Cloudflare (bản free)** trước domain:
1. Thêm domain vào Cloudflare, đổi nameserver.
2. Bật **proxy (đám mây cam)** cho record trỏ tới server.
3. Bật **"Under Attack Mode"** khi bị tấn công; bật **Bot Fight Mode**.
4. (Tùy chọn) Tạo **Rate Limiting Rule** ở Cloudflare cho `/api/v1/auth/*`.

Khi đã có Cloudflare, dùng header `CF-Connecting-IP` để lấy IP thật — `ClientIpResolver`
đã ưu tiên header này.

## 2. Rate limiting tầng ứng dụng (đã triển khai)

Token-bucket **in-memory**, không phụ thuộc thư viện ngoài. Code tại
`com.nextplease.backend.security.ratelimit`:

- `RateLimitFilter` — chạy sau `AuthorizationFilter` của Spring Security.
- `RateLimiterService` — token bucket, tự dọn bucket nhàn rỗi.
- `ClientIpResolver` — lấy IP thật qua `CF-Connecting-IP` / `X-Forwarded-For`.

Ba chính sách (mặc định, chỉnh qua biến môi trường):

| Chính sách | Key | Mặc định | Env | Bảo vệ |
|---|---|---|---|---|
| Auth | per IP | 10/phút | `APP_RATE_LIMIT_AUTH` | brute-force login, bom OTP/email |
| Write | per user | 60/phút | `APP_RATE_LIMIT_WRITE` | spam action của user đã đăng nhập |
| Global | per IP | 240/phút | `APP_RATE_LIMIT_GLOBAL` | flood mù mọi endpoint |

Tắt toàn bộ: `APP_RATE_LIMIT_ENABLED=false`. Khi vượt giới hạn → HTTP **429** kèm
`errorCode=RATE_LIMITED` và header `Retry-After: 60`.

> Tầng này bổ sung cho cooldown OTP sẵn có trong `CandidateRegistrationService`
> (60s/email, tối đa 5 lần nhập sai). Rate-limit theo IP bịt lỗ hổng "đổi email để
> bom OTP".

### Lộ trình nâng cấp khi scale > 1 instance
Rate-limit in-memory là **process-local** — mỗi instance đếm riêng. Khi chạy nhiều
instance, chuyển sang **Redis** để giới hạn dùng chung:
1. Thêm Redis (hoặc Bucket4j + Redis).
2. Thay `ConcurrentHashMap` trong `RateLimiterService` bằng store Redis; giữ nguyên
   hợp đồng `tryConsume(key, ratePerMinute)`.
Không cần đụng tới `RateLimitFilter`.

## 3. Cấu hình bảo mật (đã vá)

- **JWT bắt buộc ở prod:** `APP_SECURITY_JWT_ENABLED=true`. Khi `false`, toàn bộ API
  mở (`anyRequest().permitAll()`). Khi bật, phải set `SUPABASE_JWKS_URI` và
  `SUPABASE_ISSUER` trỏ đúng project Supabase, nếu không mọi request bị từ chối.
- **OTP dev:** `expose-dev-otp` mặc định **false** (không trả OTP trong response).
  Chỉ bật `APP_AUTH_REGISTRATION_EXPOSE_DEV_OTP=true` ở môi trường dev.
- **Hikari pool:** mặc định nâng lên 10 (`DB_MAX_POOL_SIZE`) để spam không làm cạn
  connection tức thì. Giữ `pool_size * số_instance <= max_connections` của Postgres.
- **Audit log IP:** sự kiện `user.logged_in` nay ghi IP thật (qua `ClientIpResolver`)
  thay vì chuỗi cứng `local_dev`, phục vụ truy vết.

## 4. Bảo mật dữ liệu role Ứng viên / Đối tác (hiện trạng)

Đã tốt: mật khẩu do Supabase quản lý (không lưu plaintext), OTP lưu SHA-256, JWT
verify chữ ký/issuer/hạn, RBAC tổ chức enforce ở `CompanyAccessService`,
`GlobalExceptionHandler` không lộ stack trace.

Còn cần rà (chưa làm trong đợt này): **mask dữ liệu nhạy cảm** (email, SĐT,
student_email) của ứng viên khi đối tác xem hồ sơ / đơn ứng tuyển.

## Checklist trước khi deploy production
- [ ] `APP_SECURITY_JWT_ENABLED=true`
- [ ] `SUPABASE_JWKS_URI`, `SUPABASE_ISSUER` trỏ đúng Supabase thật
- [ ] `APP_AUTH_REGISTRATION_EXPOSE_DEV_OTP` **không** set (hoặc false)
- [ ] `APP_CORS_ALLOWED_ORIGINS` là domain FE thật, không phải localhost
- [ ] Đặt Cloudflare trước domain
- [ ] `DB_MAX_POOL_SIZE` phù hợp giới hạn Postgres
- [ ] (khi multi-instance) chuyển rate-limit sang Redis
