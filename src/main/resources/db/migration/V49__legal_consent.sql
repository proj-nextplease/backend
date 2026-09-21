-- Ghi nhận việc người dùng đồng ý với Điều khoản dịch vụ và Chính sách bảo mật.
--
-- Vì sao là MỘT BẢNG chứ không phải vài cột thêm vào `profiles`:
-- bảng giữ được LỊCH SỬ — người này đã đồng ý với những phiên bản nào, vào lúc
-- nào. Với chứng cứ pháp lý thì lịch sử có giá trị hơn trạng thái hiện tại:
-- câu cần trả lời là "lúc xảy ra việc X, người này đang chịu ràng buộc bởi bản
-- điều khoản nào", chứ không phải "hiện giờ họ đồng ý với bản nào".
--
-- `version` là chuỗi tự do (hiện dùng dạng ngày, vd '2026-06-29') và phải khớp
-- với LEGAL_VERSION ở FE/src/lib/legalDocuments.js. Sửa nội dung văn bản thì
-- tăng hằng số đó, và mọi người dùng sẽ được hỏi lại một lần.

create table if not exists user_consents (
    id          uuid primary key default gen_random_uuid(),
    user_id     uuid not null references app_users(id) on delete cascade,
    version     text not null,
    accepted_at timestamptz not null default now(),
    -- Một người chỉ cần đồng ý MỘT LẦN cho mỗi phiên bản. Khoá duy nhất khiến
    -- việc gọi lại API (bấm hai lần, tải lại trang) không sinh thêm bản ghi.
    constraint user_consents_unique unique (user_id, version)
);

create index if not exists idx_user_consents_user on user_consents (user_id, accepted_at desc);
