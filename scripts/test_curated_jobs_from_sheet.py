"""Chạy: python3 -m unittest scripts/test_curated_jobs_from_sheet.py   (từ thư mục BE)"""
import csv
import io
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(__file__))
import curated_jobs_from_sheet as m  # noqa: E402

HEADER = list(m.COL.values())


def row(**kw):
    r = {h: "" for h in HEADER}
    for k, v in kw.items():
        r[m.COL[k]] = v
    return r


def write_csv(rows):
    fh = tempfile.NamedTemporaryFile("w", suffix=".csv", delete=False, encoding="utf-8", newline="")
    w = csv.DictWriter(fh, fieldnames=HEADER)
    w.writeheader()
    for r in rows:
        w.writerow(r)
    fh.close()
    return fh.name


class CleanAndPhone(unittest.TestCase):
    def test_chu_so_kieu_dac_biet(self):
        self.assertEqual(m.clean("𝟎𝟗𝟑𝟕 𝟎𝟖 𝟒𝟒 𝟎𝟗"), "0937 08 44 09")

    def test_bo_xuong_dong_windows_va_dong_trong(self):
        self.assertEqual(m.clean(" a\r\n\r\n  b \r"), "a\nb")

    def test_so_dung(self):
        for p in ("0905 22 88 33 (Đức Hà)", "0968 896 309", "090 68 68 348 (zalo)", "0934.938.971"):
            self.assertFalse(m.bad_phone(p), p)

    def test_so_sai(self):
        # Mất số 0 ở đầu, hoặc bị kéo ô làm tăng số.
        for p in ("906 22 88 33 (Đức Hà)", "934.938.971", "90 68 68 348"):
            self.assertTrue(m.bad_phone(p), p)

    def test_o_trong_khong_bi_coi_la_sai(self):
        self.assertFalse(m.bad_phone(""))


class Salary(unittest.TestCase):
    def test_so_co_dau_phay(self):
        self.assertEqual(m.parse_salary("12,000,000"), ("12.000.000 VND/tháng", 12000000, 12000000))

    def test_khoang_trieu(self):
        self.assertEqual(m.parse_salary("8 – 10 triệu"), ("8 – 10 triệu/tháng", 8000000, 10000000))

    def test_chu_khong_doan_so(self):
        self.assertEqual(m.parse_salary("Lương cứng kèm với Lương kinh doanh"),
                         ("Lương cứng kèm với Lương kinh doanh", None, None))

    def test_trong(self):
        self.assertEqual(m.parse_salary(""), (None, None, None))


class Generate(unittest.TestCase):
    def setUp(self):
        url = "https://www.facebook.com/share/p/abc/"
        self.rows = [
            row(stt="1", found="22/11/2026", company="CTY A", title="Kinh doanh GT", quantity="10",
                url=url, phone="0905 22 88 33", desc="Đi tuyến", salary="12,000,000"),
            row(stt="2", found="22/11/2026", company="CTY A", title="Kinh doanh MT",
                url=url, phone="0905 22 88 33", desc="Chăm sóc siêu thị"),
            row(stt="3", company="Nhà O'Hara", title="Thực tập", url="https://x.vn/1", phone="0934 938 971"),
            row(stt="4"),  # dòng chỉ có STT
        ]

    def run_main(self, rows, *extra):
        path = write_csv(rows)
        out = path + ".sql"
        err = io.StringIO()
        old = sys.stderr
        sys.stderr = err
        try:
            code = m.main([path, "--out", out, *extra])
        finally:
            sys.stderr = old
            os.unlink(path)
        sql = ""
        if os.path.exists(out):
            with open(out, encoding="utf-8") as fh:
                sql = fh.read()
            os.unlink(out)
        return code, sql, err.getvalue()

    def test_tao_sql_xoa_roi_them(self):
        code, sql, _ = self.run_main(self.rows)
        self.assertEqual(code, 0)
        self.assertIn("delete from external_jobs;", sql)
        self.assertEqual(sql.count("insert into external_jobs"), 3, "dòng chỉ có STT phải bị bỏ")
        self.assertEqual(sql.count("'FONLIO_CURATED'"), 3)
        self.assertNotIn("delete from jobs", sql)

    def test_nhieu_tin_chung_mot_link_van_khac_khoa(self):
        jobs = [m.to_job(r) for r in self.rows[:2]]
        self.assertNotEqual(jobs[0]["key"], jobs[1]["key"])
        self.assertEqual(len(jobs[0]["key"]), 64)

    def test_dau_nhay_don_duoc_thoat(self):
        _, sql, _ = self.run_main(self.rows)
        self.assertIn("'Nhà O''Hara'", sql)

    def test_ngay_tim_thay_thanh_ngay_dang(self):
        self.assertEqual(m.to_job(self.rows[0])["posted_at"], "'2026-11-22 09:00:00+07'")
        self.assertEqual(m.to_job(self.rows[2])["posted_at"], "now()")

    def test_noi_dung_co_so_luong_va_lien_he(self):
        ex = m.to_job(self.rows[0])["excerpt"]
        self.assertIn("Số lượng: 10 người", ex)
        self.assertIn("Mô tả công việc:\nĐi tuyến", ex)
        self.assertIn("Liên hệ: Hotline: 0905 22 88 33", ex)

    def test_so_sai_thi_khong_tao_sql(self):
        rows = [dict(self.rows[0]), self.rows[1]]
        rows[0][m.COL["phone"]] = "906 22 88 33"
        code, sql, err = self.run_main(rows)
        self.assertEqual(code, 1)
        self.assertEqual(sql, "")
        self.assertIn("STT 1", err)

    def test_fix_phone_sua_tam_duoc(self):
        rows = [dict(self.rows[0]), self.rows[1]]
        rows[0][m.COL["phone"]] = "906 22 88 33"
        code, sql, _ = self.run_main(rows, "--fix-phone", '1="0905 22 88 33"')
        self.assertEqual(code, 0)
        self.assertIn("Hotline: 0905 22 88 33", sql)

    def test_thieu_link_thi_khong_tao_sql(self):
        rows = [dict(self.rows[0])]
        rows[0][m.COL["url"]] = ""
        code, _, err = self.run_main(rows)
        self.assertEqual(code, 1)
        self.assertIn("thiếu link", err)


if __name__ == "__main__":
    unittest.main()
