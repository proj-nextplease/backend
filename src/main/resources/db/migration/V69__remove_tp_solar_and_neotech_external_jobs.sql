-- ==============================================================================
-- V69__remove_tp_solar_and_neotech_external_jobs.sql
-- Loại bỏ hoàn toàn 2 tin tuyển dụng:
-- 1. "Nhân viên Booking PR (Kockol Affiliate)" - TP SOLAR
-- 2. "Nhân Viên Marketing" - CÔNG TY CỔ PHẦN CÔNG NGHỆ THIẾT BỊ MỚI VIỆT NAM (NEOTECH)
-- ==============================================================================

DELETE FROM external_jobs
WHERE source_key = '4ee70c05adbe1c2656f8a350ba3fa9fad4bfefcf2d38c22beca0b14aab2e3e16'
   OR lower(company_name) LIKE '%tp solar%'
   OR lower(company_name) LIKE '%neotech%'
   OR lower(title) LIKE '%booking pr%';

UPDATE external_jobs
SET is_active = false
WHERE lower(company_name) LIKE '%tp solar%'
   OR lower(company_name) LIKE '%neotech%'
   OR lower(title) LIKE '%booking pr%';
