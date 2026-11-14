# Kiểm thử đầu-cuối: Đại sứ thương hiệu

Chạy trên một Postgres **tạm** trong Docker — không bao giờ trỏ vào database thật.

```bash
docker run -d --name pgamb -e POSTGRES_PASSWORD=pw -p 55432:5432 postgres:16-alpine
SPRING_PROFILES_ACTIVE=dev DATABASE_URL=jdbc:postgresql://localhost:55432/postgres \
  DB_USERNAME=postgres DB_PASSWORD=pw APP_SECURITY_JWT_ENABLED=false APP_MAIL_ENABLED=false \
  PORT=18080 ./mvnw spring-boot:run          # Flyway tự tạo schema
docker exec -i pgamb psql -U postgres < scripts/ambassador-e2e/seed.sql
bash scripts/ambassador-e2e/run.sh
docker rm -f pgamb
```

`APP_SECURITY_JWT_ENABLED=false` cho phép gửi token không ký (chỉ ở dev), nên script tự
đóng vai admin / đại sứ / ứng viên thường. `APP_MAIL_ENABLED=false` ghi email vào
`email_logs` với provider `MOCK` thay vì gửi thật — script kiểm email qua bảng đó.

Các case: phân quyền admin; cấp quyền (email lạ, tài khoản doanh nghiệp, email rỗng,
cấp trùng); email + thông báo + audit khi cấp; ví hiện phí 0; đại sứ có 0 NP vẫn dùng
được cả 7 tính năng có phí và không bị trừ đồng nào; từ chối minh chứng đã Xác thực
nhanh miễn phí thì không hoàn NP; ứng viên thường vẫn bị tính phí; gỡ quyền (email,
lịch sử, phí quay lại); cấp lại sau khi gỡ.
