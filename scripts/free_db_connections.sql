-- ============================================================================
-- GIẢI PHÓNG KẾT NỐI DB BỊ TREO (Supabase session pooler đầy 15 client).
-- Lỗi: FATAL (EMAXCONNSESSION) max clients reached in session mode.
-- Nguyên nhân: các lần chạy BE trước bị Stop đột ngột -> kết nối không đóng,
-- vẫn chiếm slot trong pool tới khi Supabase tự reap (vài phút).
--
-- Chạy file này trong Supabase SQL Editor (kết nối riêng, không qua pool app)
-- để đóng ngay các kết nối idle, rồi khởi động lại BE.
-- ============================================================================

-- 1) Xem các kết nối đang mở (để biết cái nào treo)
SELECT pid, usename, application_name, state, state_change, query_start
FROM pg_stat_activity
WHERE datname = current_database()
ORDER BY state_change;

-- 2) Đóng các kết nối idle / treo (KHÔNG đóng phiên SQL Editor hiện tại)
SELECT pg_terminate_backend(pid)
FROM pg_stat_activity
WHERE pid <> pg_backend_pid()
  AND datname = current_database()
  AND state IN ('idle', 'idle in transaction', 'idle in transaction (aborted)');
