#!/bin/bash
# Kiểm thử đầu-cuối tính năng Đại sứ thương hiệu. Xem README.md cùng thư mục.
API=http://localhost:18080/api/v1
PASS=0; FAIL=0
b64() { printf '%s' "$1" | base64 | tr '+/' '-_' | tr -d '=\n'; }
tok() { local h p payload; payload='{"sub":"'$1'","email":"'$2'"}'; h=$(b64 '{"alg":"none"}'); p=$(b64 "$payload"); echo "$h.$p.x"; }
ADMIN=$(tok 10000000-0000-0000-0000-00000000000a admin@test.local)
DAISU=$(tok 10000000-0000-0000-0000-00000000000c daisu@test.local)
THUONG=$(tok 10000000-0000-0000-0000-00000000000d thuong@test.local)
CUID=00000000-0000-0000-0000-00000000000c
sql() { docker exec pgamb psql -U postgres -At -c "$1"; }
call() { # method token path [json]
  curl -s -o /tmp/amb_body -w "%{http_code}" -X "$1" -H "Authorization: Bearer $2" -H "Content-Type: application/json" ${4:+-d "$4"} "$API$3"; }
check() { # name expected actual
  if [ "$2" == "$3" ]; then echo "  PASS  $1"; PASS=$((PASS+1)); else echo "  FAIL  $1 (mong đợi '$2', nhận '$3') $(head -c 300 /tmp/amb_body)"; FAIL=$((FAIL+1)); fi; }
bal() { sql "select np_balance from wallets where user_id='$1'"; }

echo "== Phân quyền"
check "Ứng viên gọi API admin bị chặn (403)" 403 "$(call GET "$DAISU" /admin/ambassadors)"
check "Ứng viên tự cấp quyền cho mình bị chặn (403)" 403 "$(call POST "$DAISU" /admin/ambassadors '{"email":"daisu@test.local"}')"

echo "== Cấp quyền"
check "Email không tồn tại -> 404" 404 "$(call POST "$ADMIN" /admin/ambassadors '{"email":"khongco@test.local"}')"
check "Tài khoản doanh nghiệp -> 400" 400 "$(call POST "$ADMIN" /admin/ambassadors '{"email":"dn@test.local"}')"
check "Email rỗng -> 400" 400 "$(call POST "$ADMIN" /admin/ambassadors '{"email":"  "}')"
check "Cấp cho ứng viên (email viết hoa, có khoảng trắng) -> 200" 200 "$(call POST "$ADMIN" /admin/ambassadors '{"email":"  DaiSu@Test.Local ","note":"Đại sứ FPTU"}')"
check "Cấp lần hai -> 409" 409 "$(call POST "$ADMIN" /admin/ambassadors '{"email":"daisu@test.local"}')"
check "Cấp quyền không đổi số dư (0 NP)" 0 "$(bal $CUID)"
sleep 1
check "Email báo cấp quyền đã gửi" 1 "$(sql "select count(*) from email_logs where recipient_email='daisu@test.local' and template_code='AMBASSADOR_GRANTED' and status='SENT'")"
check "Thông báo trong app đã tạo" 1 "$(sql "select count(*) from notifications where user_id='$CUID' and type='AMBASSADOR_GRANTED'")"
check "Audit log cấp quyền" 1 "$(sql "select count(*) from audit_logs where action='admin.ambassador.granted' and entity_id='$CUID'")"
call GET "$ADMIN" /admin/ambassadors >/dev/null
check "Danh sách admin có đại sứ" 1 "$(grep -o '"email":"daisu@test.local"' /tmp/amb_body | wc -l | tr -d ' ')"

echo "== Ví hiện miễn phí"
call GET "$DAISU" /wallet >/dev/null
check "isAmbassador = true" true "$(grep -o '"isAmbassador":[a-z]*' /tmp/amb_body | cut -d: -f2)"
check "Phí nhận việc hiển thị 0" 0 "$(grep -o '"paidJobAcceptanceFeeNp":[0-9]*' /tmp/amb_body | cut -d: -f2)"
check "Phí cập nhật website hiển thị 0" 0 "$(grep -o '"portfolioExportFeeNp":[0-9]*' /tmp/amb_body | cut -d: -f2)"
call GET "$THUONG" /wallet >/dev/null
check "Ứng viên thường vẫn thấy phí 19000" 19000 "$(grep -o '"paidJobAcceptanceFeeNp":[0-9]*' /tmp/amb_body | cut -d: -f2)"

echo "== Đại sứ dùng mọi tính năng có phí với 0 NP"
check "Nhận việc có lương -> 200" 200 "$(call POST "$DAISU" /me/applications/50000000-0000-0000-0000-0000000000c1/accept-offer)"
check "Cập nhật website (lần 2, có phí) -> 200" 200 "$(call POST "$DAISU" /profiles/me/export-website)"
check "Đẩy tin nổi bật -> 200" 200 "$(call POST "$DAISU" "/premium/boost?applicationId=50000000-0000-0000-0000-0000000000c1")"
check "Mở khoá theme -> 200" 200 "$(call POST "$DAISU" /premium/theme/unlock)"
check "Đăng ký Job Match Alert -> 200" 200 "$(call POST "$DAISU" /premium/match-alert/subscribe)"
check "Xác thực nhanh -> 200" 200 "$(call POST "$DAISU" "/premium/express?experienceId=60000000-0000-0000-0000-0000000000c1")"
check "Mua Premium cũ -> 200" 200 "$(call POST "$DAISU" /wallet/subscribe)"
check "Số dư vẫn 0 NP sau tất cả" 0 "$(bal $CUID)"
check "Không có giao dịch trừ NP nào" 0 "$(sql "select count(*) from wallet_transactions t join wallets w on w.id=t.wallet_id where w.user_id='$CUID' and t.amount_np < 0")"
check "Lịch sử ví ghi rõ 'Miễn phí – Đại sứ'" 6 "$(sql "select count(*) from wallet_transactions t join wallets w on w.id=t.wallet_id where w.user_id='$CUID' and t.amount_np = 0 and t.reason like '[Miễn phí – Đại sứ thương hiệu]%'")"
check "Đơn chuyển sang ACCEPTED" ACCEPTED "$(sql "select status from applications where id='50000000-0000-0000-0000-0000000000c1'")"

echo "== Từ chối minh chứng đã Xác thực nhanh miễn phí: không được hoàn NP"
check "Admin từ chối minh chứng -> 200" 200 "$(call POST "$ADMIN" "/admin/dashboard/verification-queue/60000000-0000-0000-0000-0000000000c1/reject?reason=Thieu+minh+chung")"
check "Không hoàn 15.000 NP (vẫn 0)" 0 "$(bal $CUID)"

echo "== Ứng viên thường (đối chứng) vẫn bị tính phí"
check "Nhận việc có lương với 0 NP -> 402" 402 "$(call POST "$THUONG" /me/applications/50000000-0000-0000-0000-0000000000d1/accept-offer)"
check "Cập nhật website với 0 NP -> 402" 402 "$(call POST "$THUONG" /profiles/me/export-website)"

echo "== Gỡ quyền"
check "Gỡ quyền -> 200" 200 "$(call POST "$ADMIN" /admin/ambassadors/$CUID/revoke '{"reason":"Hết nhiệm kỳ"}')"
check "Gỡ lần hai -> 404" 404 "$(call POST "$ADMIN" /admin/ambassadors/$CUID/revoke '{}')"
check "Gỡ quyền không đổi số dư" 0 "$(bal $CUID)"
sleep 1
check "Email báo gỡ quyền đã gửi" 1 "$(sql "select count(*) from email_logs where recipient_email='daisu@test.local' and template_code='AMBASSADOR_REVOKED' and status='SENT'")"
check "Lịch sử nhiệm kỳ còn lưu (revoked_at có giá trị)" 1 "$(sql "select count(*) from brand_ambassadors where user_id='$CUID' and revoked_at is not null and revoke_reason='Hết nhiệm kỳ'")"
call GET "$DAISU" /wallet >/dev/null
check "Sau khi gỡ: phí nhận việc trở lại 19000" 19000 "$(grep -o '"paidJobAcceptanceFeeNp":[0-9]*' /tmp/amb_body | cut -d: -f2)"
check "Sau khi gỡ: nhận việc có lương với 0 NP -> 402" 402 "$(call POST "$DAISU" /me/applications/50000000-0000-0000-0000-0000000000c2/accept-offer)"
check "Sau khi gỡ: cập nhật website với 0 NP -> 402" 402 "$(call POST "$DAISU" /profiles/me/export-website)"

echo "== Cấp lại sau khi gỡ"
check "Cấp lại -> 200" 200 "$(call POST "$ADMIN" /admin/ambassadors '{"email":"daisu@test.local"}')"
check "Nhận việc có lương lại miễn phí -> 200" 200 "$(call POST "$DAISU" /me/applications/50000000-0000-0000-0000-0000000000c2/accept-offer)"
check "Có 2 nhiệm kỳ, 1 đang hiệu lực" "2|1" "$(sql "select count(*)||'|'||count(*) filter (where revoked_at is null) from brand_ambassadors where user_id='$CUID'")"
check "Số dư vẫn 0 NP" 0 "$(bal $CUID)"

echo
echo "KẾT QUẢ: $PASS đạt, $FAIL lỗi"
