-- ============================================================================
-- Gỡ kẹt migration khi BE treo ở bước "Migrating schema ... to version N".
-- Nguyên nhân thường gặp: một session 'idle in transaction' còn sót từ lần chạy
-- trước đang GIỮ LOCK trên bảng mà ALTER cần (vd: experiences, profiles).
--
-- Cách dùng: GIỮ NGUYÊN backend đang chạy (nó đang chờ lock), mở Supabase SQL
-- Editor (kết nối riêng) và chạy file này. Lock được nhả -> ALTER hoàn tất ->
-- backend chạy tiếp bình thường (không cần flyway repair).
-- ============================================================================

-- (Chẩn đoán) Ai đang kết nối / giữ lock:
SELECT pid,
       state,
       age(now(), query_start) AS duration,
       wait_event_type,
       left(query, 100) AS query_preview
FROM pg_stat_activity
WHERE datname = current_database()
  AND pid <> pg_backend_pid()
ORDER BY query_start;

-- (Khắc phục) Hủy các session đang treo trong transaction (giữ lock vô thời hạn):
SELECT pg_terminate_backend(pid) AS terminated_pid
FROM pg_stat_activity
WHERE datname = current_database()
  AND pid <> pg_backend_pid()
  AND state IN ('idle in transaction', 'idle in transaction (aborted)');

-- (Tùy chọn, mạnh tay hơn) Nếu vẫn kẹt, hủy mọi session đang chờ lock quá 2 phút
-- NGOẠI TRỪ chính tiến trình migration của backend (đang 'active'):
-- SELECT pg_terminate_backend(pid)
-- FROM pg_stat_activity
-- WHERE datname = current_database()
--   AND pid <> pg_backend_pid()
--   AND state <> 'active'
--   AND age(now(), state_change) > interval '2 minutes';
