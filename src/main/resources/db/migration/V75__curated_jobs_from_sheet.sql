-- ==============================================================================
-- Thay toàn bộ tin việc làm tuyển chọn bằng tin trong Google Sheet.
-- Nguồn: https://docs.google.com/spreadsheets/d/1HhA5bA8AI3egisL_hdo1Tlg-XoXds3f2cuFkvZcLAuw/edit?gid=0#gid=0
-- Tạo bởi scripts/curated_jobs_from_sheet.py lúc 28/11/2026 16:28. 10 tin.
-- Không đụng tới bảng jobs (tin do doanh nghiệp, CLB tự đăng).
-- ==============================================================================

delete from external_jobs;

insert into external_jobs (source, source_key, title, company_name, location, excerpt,
    salary_text, salary_min, salary_max, salary_currency, salary_type, apply_url,
    posted_at, first_seen_at, last_seen_at, is_active)
values ('FONLIO_CURATED', '49375e1f38c26d2c886870e2c78c2e8b98e4cf1838c26e59d9fe3b811e09229d', 'Chuyên viên Kinh doanh Bất động sản', 'VINHOMES',
    'Vincom Mega Mall Thảo Điền, 161 Võ Nguyên Giáp, phường An Khánh, TP.HCM',
    'Ngành nghề: Bất động sản
Hoa hồng/Thưởng: Có hoa hồng (chưa nêu mức)
Thu nhập dự kiến: Lương cứng + hoa hồng
Ngày trả lương: Ngày 30–31 hằng tháng
Ngày trả hoa hồng: Ngày 15 hằng tháng

Mô tả công việc:
Tư vấn/kinh doanh sản phẩm bất động sản (cần liên hệ để hỏi mô tả chi tiết)

Yêu cầu ứng viên:
Chưa nêu trong tin tuyển dụng

Quyền lợi:
Môi trường chuyên nghiệp; sản phẩm và thương hiệu uy tín; cơ hội phát triển; lương cứng và hoa hồng

Liên hệ: Hotline: 0968 896 309',
    '12.000.000 VND/tháng', 12000000, 12000000, 'VND', 'M',
    'https://www.facebook.com/share/p/1DPqAAhXgq/',
    '2026-11-22 09:00:00+07', now(), now(), true);

insert into external_jobs (source, source_key, title, company_name, location, excerpt,
    salary_text, salary_min, salary_max, salary_currency, salary_type, apply_url,
    posted_at, first_seen_at, last_seen_at, is_active)
values ('FONLIO_CURATED', 'e5748112f2eb641c5162e38cc71af3f0ee0f1b3a039d9b3fe2ef975c139f4d98', 'Kinh doanh GT', 'BÁNH KẸO THIÊN BẢO',
    null,
    'Ngành nghề: Kinh doanh
Số lượng: 10 người

Mô tả công việc:
Đi tuyến bán hàng tại khu vực được phân công; chăm sóc hệ thống đại lý, cửa hàng bán lẻ, phát triển khách hàng và thúc đẩy doanh số.

Liên hệ: Hotline: 0905 22 88 33 (Đức Hà) · Email: ha.pd@tbcc.vn',
    null, null, null, null, null,
    'https://www.facebook.com/share/p/1Df5ykZPuS/',
    '2026-11-22 09:00:00+07', now(), now(), true);

insert into external_jobs (source, source_key, title, company_name, location, excerpt,
    salary_text, salary_min, salary_max, salary_currency, salary_type, apply_url,
    posted_at, first_seen_at, last_seen_at, is_active)
values ('FONLIO_CURATED', '11c277df1a09b2bbb80575cfc9193c1e5dcd575f0bba8630e8294908adda4fa0', 'Kinh doanh MT', 'BÁNH KẸO THIÊN BẢO',
    null,
    'Ngành nghề: Kinh doanh
Số lượng: 10 người

Mô tả công việc:
Đi tuyến chăm sóc các siêu thị được phân công; theo dõi tình hình hàng hóa, trưng bày sản phẩm, triển khai chương trình bán hàng và thúc đẩy doanh số.

Liên hệ: Hotline: 0905 22 88 33 (Đức Hà) · Email: ha.pd@tbcc.vn',
    null, null, null, null, null,
    'https://www.facebook.com/share/p/1Df5ykZPuS/',
    '2026-11-22 09:00:00+07', now(), now(), true);

insert into external_jobs (source, source_key, title, company_name, location, excerpt,
    salary_text, salary_min, salary_max, salary_currency, salary_type, apply_url,
    posted_at, first_seen_at, last_seen_at, is_active)
values ('FONLIO_CURATED', '97e22873136b70522629743804cb448df75705f371d6d49a54545bd3af49a5f6', 'Kinh doanh & Marketing', 'BÁNH KẸO THIÊN BẢO',
    null,
    'Ngành nghề: Kinh doanh
Số lượng: 10 người

Mô tả công việc:
Tham gia triển khai các hoạt động bán hàng, tư vấn quà tặng doanh nghiệp, chăm sóc khách hàng; phối hợp thực hiện các chương trình Marketing, quảng bá thương hiệu và thúc đẩy doanh số.

Liên hệ: Hotline: 0905 22 88 33 (Đức Hà) · Email: ha.pd@tbcc.vn',
    null, null, null, null, null,
    'https://www.facebook.com/share/p/1Df5ykZPuS/',
    '2026-11-22 09:00:00+07', now(), now(), true);

insert into external_jobs (source, source_key, title, company_name, location, excerpt,
    salary_text, salary_min, salary_max, salary_currency, salary_type, apply_url,
    posted_at, first_seen_at, last_seen_at, is_active)
values ('FONLIO_CURATED', 'e67c55be55ec08d2e024a94edd356249ca16af9a60749ca9431e7af3a0425ec7', 'Đóng gói quà tặng (Part-time)', 'BÁNH KẸO THIÊN BẢO',
    null,
    'Ngành nghề: Kinh doanh
Số lượng: 20 người

Mô tả công việc:
Phân loại, sắp xếp, đóng gói và hoàn thiện đơn hàng quà tặng doanh nghiệp. Làm việc linh hoạt theo ca.

Liên hệ: Hotline: 0905 22 88 33 (Đức Hà) · Email: ha.pd@tbcc.vn',
    null, null, null, null, null,
    'https://www.facebook.com/share/p/1Df5ykZPuS/',
    '2026-11-22 09:00:00+07', now(), now(), true);

insert into external_jobs (source, source_key, title, company_name, location, excerpt,
    salary_text, salary_min, salary_max, salary_currency, salary_type, apply_url,
    posted_at, first_seen_at, last_seen_at, is_active)
values ('FONLIO_CURATED', '65b255fe2edcee31d7398a6a334fe8c328a4ea266f93245de029fe7892af1998', 'Trợ lý Mua hàng', 'BÁNH KẸO THIÊN BẢO',
    null,
    'Ngành nghề: Kinh doanh
Số lượng: 5 người

Mô tả công việc:
Hỗ trợ các công tác làm việc với nhà cung cấp, khảo sát giá, hỗ trợ chứng từ và theo dõi tiến độ giao hàng.

Liên hệ: Hotline: 0905 22 88 33 (Đức Hà) · Email: ha.pd@tbcc.vn',
    null, null, null, null, null,
    'https://www.facebook.com/share/p/1Df5ykZPuS/',
    '2026-11-22 09:00:00+07', now(), now(), true);

insert into external_jobs (source, source_key, title, company_name, location, excerpt,
    salary_text, salary_min, salary_max, salary_currency, salary_type, apply_url,
    posted_at, first_seen_at, last_seen_at, is_active)
values ('FONLIO_CURATED', '7b556e2ebb2a22bdcb21e0e3795f41a3a61defcd19719cb6057563bfd9196144', 'Nhân Viên Kinh Doanh (Sales)', 'CÔNG TY TNHH KINH DOANH XUẤT NHẬP KHẨU HẢI PHƯƠNG',
    '1702/3 QL1A, Phường An Phú Đông, Quận 12, TP HCM',
    'Ngành nghề: Kinh doanh
Số lượng: 3 người

Mô tả công việc:
- Chăm sóc khách hàng cũ và phát triển khách hàng mới theo khu vực được phân công
- Cập nhật thông tin thị trường (giá cả, đối thủ cạnh tranh)
- Theo dõi công nợ khách hàng
- Thực hiện các công việc khác theo yêu cầu cấp trên

Yêu cầu ứng viên:
- Vị trí Nhân viên kinh doanh (sales): nam giới
- Không yêu cầu kinh nghiệm, sẽ được đào tạo
- Đam mê kinh doanh
- Có tinh thần cầu tiến, chịu khó học hỏi, nghiêm túc trong công việc

Quyền lợi:
- Lương cứng kèm với Lương kinh doanh (thu nhập ổn định & nhiều cơ hội phát triển trong nghề Thép)
- Du lịch hàng năm
- Môi trường làm việc năng động, Sếp tâm lý
- Lương/thưởng dịp lễ, tết, tháng 13.
- Tham gia BHXH, BHYT, BHTN theo quy định Nhà nước

Liên hệ: Hotline: 090 68 68 348 (zalo)
Ghi chú: Ứng viên quan tâm nộp CV qua Sdt',
    'Lương cứng kèm với Lương kinh doanh', null, null, null, null,
    'https://www.facebook.com/share/p/1A5xtbKngE/',
    '2026-11-22 09:00:00+07', now(), now(), true);

insert into external_jobs (source, source_key, title, company_name, location, excerpt,
    salary_text, salary_min, salary_max, salary_currency, salary_type, apply_url,
    posted_at, first_seen_at, last_seen_at, is_active)
values ('FONLIO_CURATED', 'be538bd56e0db328b11bd13205c601aaca59ad733a6ed8161ed3f919b2163d45', 'Admin', 'CÔNG TY TNHH KINH DOANH XUẤT NHẬP KHẨU HẢI PHƯƠNG',
    '1702/3 QL1A, Phường An Phú Đông, Quận 12, TP HCM',
    'Ngành nghề: Kinh doanh
Số lượng: 2 người
Thời gian làm việc: Thứ 2-7

Mô tả công việc:
Theo dõi công nợ, tồn kho, hoá đơn... (chi tiết trao đổi cụ thể khi phỏng vấn)

Yêu cầu ứng viên:
- Vị trí Admin: không yêu cầu (nam/nữ)
- Không yêu cầu kinh nghiệm, sẽ được đào tạo
- Đam mê kinh doanh
- Có tinh thần cầu tiến, chịu khó học hỏi, nghiêm túc trong công việc

Quyền lợi:
- Lương cứng kèm với Lương kinh doanh (thu nhập ổn định & nhiều cơ hội phát triển trong nghề Thép)
- Du lịch hàng năm
- Môi trường làm việc năng động, Sếp tâm lý
- Lương/thưởng dịp lễ, tết, tháng 13.
- Tham gia BHXH, BHYT, BHTN theo quy định Nhà nước

Liên hệ: Hotline: 091 68 68 348 (zalo)
Ghi chú: Ứng viên quan tâm nộp CV qua Sdt',
    'Lương cứng kèm với Lương kinh doanh', null, null, null, null,
    'https://www.facebook.com/share/p/1A5xtbKngE/',
    '2026-11-22 09:00:00+07', now(), now(), true);

insert into external_jobs (source, source_key, title, company_name, location, excerpt,
    salary_text, salary_min, salary_max, salary_currency, salary_type, apply_url,
    posted_at, first_seen_at, last_seen_at, is_active)
values ('FONLIO_CURATED', '8b367bee7e32e3445b51bed4ba25fea9ee30a105e52ed93a2df2367efe174a6f', 'Thực tập sinh kế toán', 'CÔNG TY TNHH QUẢNG CÁO VÀ THƯƠNG MẠI DELTA',
    '128E Nguyễn Đình Chính, Phường Phú Nhuận, TP Hồ Chí Minh',
    'Ngành nghề: Kế toán, Kiểm toán.
Số lượng: 2 người
Đối tượng: Sinh viên năm 2, 3, cuối Đại học chuyên ngành Kế toán, Kiểm toán.
Thời gian làm việc: Từ thứ 2 đến thứ 6
Sáng: 08h30 – 12h00
Chiều: 13h30 – 18h00

Mô tả công việc:
• Nhập liệu, xử lý số liệu;
• Lập báo cáo định kỳ;
• Hỗ trợ trưởng nhóm thực hiện các công việc dịch vụ kế toán.
• Các công việc khác theo yêu cầu.

Yêu cầu ứng viên:
• Sinh viên chuyên ngành kế toán, kiểm toán của các Trường Đại học;
• Sức khỏe tốt;
• Chủ động, tích cực, tự giác, chăm chỉ, trung thực và trách nhiệm
• Có laptop cá nhân;
• Thành thạo vi tính văn phòng (Word, Excel)

Quyền lợi:
• Môi trường học hỏi, phát triển chuyên nghiệp;
• Được phụ cấp theo thỏa thuận;
• Các chế độ khác theo quy định của công ty

Liên hệ: Hotline: 0934 938 971 · Email: trucloan.dang@delta-vn.net',
    null, null, null, null, null,
    'https://www.facebook.com/share/p/1LpN4qbUWV/',
    '2026-11-22 09:00:00+07', now(), now(), true);

insert into external_jobs (source, source_key, title, company_name, location, excerpt,
    salary_text, salary_min, salary_max, salary_currency, salary_type, apply_url,
    posted_at, first_seen_at, last_seen_at, is_active)
values ('FONLIO_CURATED', '0421608cf1a154c6ffd796eda3d82186b933d9a8f952e21df8fa09ad106b12e2', 'DIGITAL MARKETING', 'JASMINE BEAUTY SCHOOL',
    '29 Trần Phú, P.4, Q.5, TP. HCM',
    'Ngành nghề: DIGITAL MARKETING
Hoa hồng/Thưởng: Thưởng KPI xứng đáng theo năng lực.

Mô tả công việc:
BẠN SẼ ĐƯỢC THỎA SỨC VẪY VÙNG:
• Làm chủ đa kênh: Vận hành và phát triển hệ thống Facebook, TikTok, Youtube, Website của trường.
• Sáng tạo không giới hạn: Trực tiếp viết nội dung, thiết kế hình ảnh và edit video cơ bản.
• Tối ưu dựa trên con số: Đo lường, phân tích dữ liệu và đề xuất cải thiện hiệu quả chiến dịch.

Yêu cầu ứng viên:
• Có tư duy nhạy bén, chủ động và tự chủ trong công việc.
• Điểm cộng cực lớn: Thành thạo các công cụ AI hỗ trợ Marketing.

Quyền lợi:
• Thu nhập: Lương cứng 8 – 10 triệu + Thưởng KPI xứng đáng theo năng lực.
• Chế độ phúc lợi: Đầy đủ BHXH, nghỉ lễ Tết, LƯƠNG THÁNG 13 và du lịch công ty hằng năm.
• Phát triển bản thân: Được đào tạo bài bản, lộ trình thăng tiến rõ ràng.

Liên hệ: Hotline: 0937 08 44 09 · Email: lienhe@jasminevietnam.edu.vn',
    '8 – 10 triệu/tháng', 8000000, 10000000, 'VND', 'M',
    'https://www.facebook.com/share/p/1dJCu6J7Dh/',
    '2026-11-22 09:00:00+07', now(), now(), true);
