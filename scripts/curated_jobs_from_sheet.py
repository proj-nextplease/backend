#!/usr/bin/env python3
"""
Tạo file SQL thay toàn bộ tin việc làm tuyển chọn bằng tin trong Google Sheet.

Cách dùng (sheet phải để "Bất kỳ ai có đường liên kết đều xem được"):

    python3 scripts/curated_jobs_from_sheet.py \\
        "https://docs.google.com/spreadsheets/d/<id>/edit?gid=0" \\
        --out src/main/resources/db/migration/V75__curated_jobs_from_sheet.sql

Có thể đưa đường dẫn file .csv thay cho link sheet.

File SQL làm hai việc trong một giao dịch:
  1. Xoá MỌI dòng trong external_jobs (tin tuyển chọn cũ và tin Careerjet).
  2. Thêm các tin trong sheet với source = 'FONLIO_CURATED', là nguồn duy nhất
     trang Việc làm hiển thị (xem ExternalJobService.CURATED_SOURCE).
Bảng jobs (tin do doanh nghiệp, CLB tự đăng trên fonlio) KHÔNG bị đụng tới.

Script dừng lại nếu một dòng có số điện thoại không đủ 10 số bắt đầu bằng 0
(thường do kéo ô trong sheet làm số tự tăng). Sửa trong sheet rồi chạy lại,
hoặc sửa tạm bằng --fix-phone STT="số đúng".
"""
import argparse
import csv
import hashlib
import io
import re
import sys
import unicodedata
import urllib.request
from datetime import datetime

CURATED_SOURCE = "FONLIO_CURATED"

# Tên cột trong sheet "CÁC JOB VIỆC LÀM".
COL = {
    "stt": "STT",
    "found": "Ngày tìm thấy",
    "company": "Tên doanh nghiệp",
    "brand": "Tập đoàn/Thương hiệu",
    "industry": "Ngành nghề",
    "title": "Vị trí tuyển dụng",
    "audience": "Đối tượng",
    "quantity": "Số lượng",
    "location": "Địa điểm làm việc",
    "hours": "Thời gian làm việc",
    "salary": "Lương cứng (VNĐ/tháng)",
    "bonus": "Hoa hồng/Thưởng",
    "income": "Thu nhập dự kiến",
    "payday": "Ngày trả lương",
    "bonus_day": "Ngày trả hoa hồng",
    "desc": "Mô tả công việc",
    "req": "Yêu cầu ứng viên",
    "benefit": "Quyền lợi",
    "phone": "Hotline/SĐT tuyển dụng",
    "email": "Email tuyển dụng",
    "contact": "Người liên hệ",
    "url": "Link bài tuyển dụng/Nguồn tin",
    "note": "Ghi chú",
}


def clean(value):
    """Chuẩn hoá chữ: chữ số kiểu 𝟎𝟗 thành 09, bỏ \\r, bỏ khoảng trắng thừa."""
    if value is None:
        return ""
    text = unicodedata.normalize("NFKC", value).replace("\r\n", "\n").replace("\r", "\n")
    lines = [ln.strip() for ln in text.split("\n")]
    return "\n".join(ln for ln in lines if ln).strip()


def sheet_csv_url(src):
    m = re.search(r"/spreadsheets/d/([A-Za-z0-9_-]+)", src)
    if not m:
        return None
    gid = re.search(r"[#&?]gid=(\d+)", src)
    return f"https://docs.google.com/spreadsheets/d/{m.group(1)}/export?format=csv&gid={gid.group(1) if gid else 0}"


def read_rows(src):
    url = sheet_csv_url(src)
    if url:
        with urllib.request.urlopen(url, timeout=30) as resp:
            text = resp.read().decode("utf-8")
    else:
        with open(src, encoding="utf-8") as fh:
            text = fh.read()
    rows = list(csv.DictReader(io.StringIO(text)))
    # Dòng chỉ có STT (sheet kéo sẵn số thứ tự) là dòng trống.
    return [r for r in rows if clean(r.get(COL["title"])) and clean(r.get(COL["company"]))]


def phone_digits(phone):
    """Phần số của mỗi số điện thoại trong ô (bỏ ghi chú trong ngoặc như "(zalo)")."""
    no_notes = re.sub(r"\([^)]*\)", " ", phone)
    return [re.sub(r"\D", "", part) for part in re.split(r"[,;/]| - ", no_notes) if re.sub(r"\D", "", part)]


def bad_phone(phone):
    if not phone:
        return False
    return any(not (len(d) == 10 and d.startswith("0")) for d in phone_digits(phone))


def parse_salary(raw):
    """Trả (salary_text, min, max). Chỉ điền min/max khi đọc ra số chắc chắn."""
    text = clean(raw)
    if not text:
        return None, None, None
    plain = text.replace(",", "").replace(".", "")
    if re.fullmatch(r"\d{6,}", plain):
        n = int(plain)
        return f"{n:,} VND/tháng".replace(",", "."), n, n
    m = re.fullmatch(r"(\d+(?:[.,]\d+)?)\s*[–-]\s*(\d+(?:[.,]\d+)?)\s*triệu", text, re.IGNORECASE)
    if m:
        lo = round(float(m.group(1).replace(",", ".")) * 1_000_000)
        hi = round(float(m.group(2).replace(",", ".")) * 1_000_000)
        return f"{text}/tháng", lo, hi
    return text[:160], None, None


def build_excerpt(r):
    """Khối thông tin ngắn (mỗi dòng một ý), rồi các khối dài cách nhau một dòng trống."""
    facts = []

    def fact(label, key):
        v = clean(r.get(COL[key]))
        if v:
            facts.append(f"{label}: {v}")

    fact("Ngành nghề", "industry")
    q = clean(r.get(COL["quantity"]))
    if q:
        facts.append(f"Số lượng: {q} người" if q.isdigit() else f"Số lượng: {q}")
    fact("Đối tượng", "audience")
    fact("Thời gian làm việc", "hours")
    fact("Hoa hồng/Thưởng", "bonus")
    fact("Thu nhập dự kiến", "income")
    fact("Ngày trả lương", "payday")
    fact("Ngày trả hoa hồng", "bonus_day")

    blocks = ["\n".join(facts)] if facts else []
    for label, key in (("Mô tả công việc", "desc"), ("Yêu cầu ứng viên", "req"), ("Quyền lợi", "benefit")):
        v = clean(r.get(COL[key]))
        if v:
            blocks.append(f"{label}:\n{v}")

    contact = [x for x in (
        clean(r.get(COL["contact"])),
        clean(r.get(COL["phone"])) and f"Hotline: {clean(r.get(COL['phone']))}",
        clean(r.get(COL["email"])) and f"Email: {clean(r.get(COL['email']))}",
    ) if x]
    tail = []
    if contact:
        tail.append("Liên hệ: " + " · ".join(contact))
    note = clean(r.get(COL["note"]))
    if note:
        tail.append(f"Ghi chú: {note}")
    if tail:
        blocks.append("\n".join(tail))
    return "\n\n".join(blocks)


def posted_at(raw):
    v = clean(raw)
    try:
        d = datetime.strptime(v, "%d/%m/%Y")
        return f"'{d:%Y-%m-%d} 09:00:00+07'"
    except ValueError:
        return "now()"


def sql_str(v):
    if v is None or v == "":
        return "null"
    return "'" + str(v).replace("'", "''") + "'"


def sql_num(v):
    return "null" if v is None else str(v)


def to_job(r):
    title = clean(r.get(COL["title"]))
    url = clean(r.get(COL["url"]))
    salary_text, smin, smax = parse_salary(r.get(COL["salary"]))
    # Nhiều tin dùng chung một bài đăng Facebook, nên khoá phải gồm cả vị trí.
    key = hashlib.sha256(f"{url}|{title}".lower().encode("utf-8")).hexdigest()
    return {
        "stt": clean(r.get(COL["stt"])),
        "key": key,
        "title": title[:300],
        "company": clean(r.get(COL["company"]))[:200],
        "location": clean(r.get(COL["location"]))[:200] or None,
        "excerpt": build_excerpt(r),
        "salary_text": salary_text,
        "salary_min": smin,
        "salary_max": smax,
        "salary_type": "M" if smin is not None else None,
        "apply_url": url,
        "posted_at": posted_at(r.get(COL["found"])),
    }


def render_sql(jobs, source_desc):
    out = [
        "-- ==============================================================================",
        "-- Thay toàn bộ tin việc làm tuyển chọn bằng tin trong Google Sheet.",
        f"-- Nguồn: {source_desc}",
        f"-- Tạo bởi scripts/curated_jobs_from_sheet.py lúc {datetime.now():%d/%m/%Y %H:%M}. {len(jobs)} tin.",
        "-- Không đụng tới bảng jobs (tin do doanh nghiệp, CLB tự đăng).",
        "-- ==============================================================================",
        "",
        "delete from external_jobs;",
        "",
    ]
    for j in jobs:
        out.append(
            "insert into external_jobs (source, source_key, title, company_name, location, excerpt,\n"
            "    salary_text, salary_min, salary_max, salary_currency, salary_type, apply_url,\n"
            "    posted_at, first_seen_at, last_seen_at, is_active)\n"
            f"values ({sql_str(CURATED_SOURCE)}, {sql_str(j['key'])}, {sql_str(j['title'])}, {sql_str(j['company'])},\n"
            f"    {sql_str(j['location'])},\n"
            f"    {sql_str(j['excerpt'])},\n"
            f"    {sql_str(j['salary_text'])}, {sql_num(j['salary_min'])}, {sql_num(j['salary_max'])}, "
            f"{sql_str('VND' if j['salary_min'] is not None else None)}, {sql_str(j['salary_type'])},\n"
            f"    {sql_str(j['apply_url'])},\n"
            f"    {j['posted_at']}, now(), now(), true);\n"
        )
    return "\n".join(out)


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("source", help="Link Google Sheet hoặc đường dẫn file .csv")
    ap.add_argument("--out", help="Ghi SQL vào file này (mặc định in ra màn hình)")
    ap.add_argument("--fix-phone", action="append", default=[], metavar='STT="SỐ"',
                    help="Sửa tạm số điện thoại của dòng STT, ví dụ --fix-phone 3=\"0905 22 88 33\"")
    args = ap.parse_args(argv)

    fixes = {}
    for f in args.fix_phone:
        stt, _, phone = f.partition("=")
        fixes[stt.strip()] = phone.strip().strip('"')

    rows = read_rows(args.source)
    errors = []
    for r in rows:
        stt = clean(r.get(COL["stt"]))
        if stt in fixes:
            r[COL["phone"]] = fixes[stt]
        url = clean(r.get(COL["url"]))
        if not url.startswith("http"):
            errors.append(f"Dòng STT {stt}: thiếu link bài tuyển dụng (cần cho nút Ứng tuyển).")
        if bad_phone(clean(r.get(COL["phone"]))):
            errors.append(f"Dòng STT {stt}: số điện thoại có vẻ sai: {clean(r.get(COL['phone']))!r}")
    if errors:
        print("Chưa tạo SQL vì sheet còn lỗi:", file=sys.stderr)
        for e in errors:
            print("  - " + e, file=sys.stderr)
        return 1

    jobs = [to_job(r) for r in rows]
    keys = [j["key"] for j in jobs]
    if len(set(keys)) != len(keys):
        print("Có hai tin trùng cả link lẫn vị trí tuyển dụng.", file=sys.stderr)
        return 1

    sql = render_sql(jobs, args.source)
    if args.out:
        with open(args.out, "w", encoding="utf-8") as fh:
            fh.write(sql)
        print(f"Đã ghi {len(jobs)} tin vào {args.out}", file=sys.stderr)
    else:
        print(sql)
    return 0


if __name__ == "__main__":
    sys.exit(main())
