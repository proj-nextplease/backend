-- Dữ liệu thử cho tính năng Đại sứ thương hiệu (DB Docker tạm, không phải production).
insert into app_users (id, supabase_user_id, email, display_name) values
  ('00000000-0000-0000-0000-00000000000a', '10000000-0000-0000-0000-00000000000a', 'admin@test.local', 'Admin Test'),
  ('00000000-0000-0000-0000-00000000000c', '10000000-0000-0000-0000-00000000000c', 'daisu@test.local', 'Đại Sứ Test'),
  ('00000000-0000-0000-0000-00000000000d', '10000000-0000-0000-0000-00000000000d', 'thuong@test.local', 'Ứng Viên Thường'),
  ('00000000-0000-0000-0000-00000000000e', '10000000-0000-0000-0000-00000000000e', 'dn@test.local', 'Doanh Nghiệp Test');
insert into user_roles (user_id, role_code) values
  ('00000000-0000-0000-0000-00000000000a', 'admin'),
  ('00000000-0000-0000-0000-00000000000c', 'candidate_free'),
  ('00000000-0000-0000-0000-00000000000d', 'candidate_free'),
  ('00000000-0000-0000-0000-00000000000e', 'employer_free');
-- Cả hai ứng viên đều có 0 NP: mọi khoản phí đều sẽ thiếu tiền nếu không được miễn.
insert into wallets (user_id, np_balance, locked_np_balance) values
  ('00000000-0000-0000-0000-00000000000c', 0, 0),
  ('00000000-0000-0000-0000-00000000000d', 0, 0);
-- export_count = 1: lần xuất website kế tiếp là lần có phí.
insert into profiles (id, user_id, export_count) values
  ('20000000-0000-0000-0000-00000000000c', '00000000-0000-0000-0000-00000000000c', 1),
  ('20000000-0000-0000-0000-00000000000d', '00000000-0000-0000-0000-00000000000d', 1);
insert into companies (id, owner_user_id, name, company_type) values
  ('30000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-00000000000e', 'Cty Test', 'SME');
insert into jobs (id, company_id, title, description, job_type, created_by, compensation, status) values
  ('40000000-0000-0000-0000-000000000001', '30000000-0000-0000-0000-000000000001', 'Việc có lương 1', 'mô tả', 'PART_TIME', '00000000-0000-0000-0000-00000000000e', 500000, 'OPEN'),
  ('40000000-0000-0000-0000-000000000002', '30000000-0000-0000-0000-000000000001', 'Việc có lương 2', 'mô tả', 'PART_TIME', '00000000-0000-0000-0000-00000000000e', 500000, 'OPEN');
-- Đơn đã được mời nhận việc (OFFERED): bấm nhận việc có lương là bị tính 19.000 NP.
insert into applications (id, job_id, candidate_id, status) values
  ('50000000-0000-0000-0000-0000000000c1', '40000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-00000000000c', 'OFFERED'),
  ('50000000-0000-0000-0000-0000000000c2', '40000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-00000000000c', 'OFFERED'),
  ('50000000-0000-0000-0000-0000000000d1', '40000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-00000000000d', 'OFFERED');
-- Minh chứng đang chờ duyệt, để thử Xác thực nhanh rồi bị từ chối.
insert into experiences (id, profile_id, project_name, position, category, verification_status) values
  ('60000000-0000-0000-0000-0000000000c1', '20000000-0000-0000-0000-00000000000c', 'Dự án thử', 'Thành viên', 'CLUB_SMALL', 'PENDING');
