package com.nextplease.backend.util;

import java.sql.Date;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ngày bắt đầu / kết thúc của một kinh nghiệm (bảng {@code experiences}).
 *
 * <p>Người dùng giờ nhập đủ ngày ({@code dd/MM/yyyy}). Nhưng các bản ghi cũ
 * chỉ có tháng/năm và được lưu thành ngày 01 của tháng — con số 01 đó là
 * giả. Vì vậy mỗi ngày đi kèm một cờ {@code hasDay}
 * ({@code started_at_has_day}, {@code ended_at_has_day}): có cờ thì hiện
 * {@code dd/MM/yyyy}, không có thì chỉ hiện {@code MM/yyyy}, để hồ sơ công
 * khai không khẳng định một ngày mà ứng viên chưa từng khai.
 *
 * <p>Vẫn nhận định dạng cũ ({@code MM/yy}, {@code MM/yyyy}, {@code yyyy-MM})
 * vì app di động bản đang chạy và bản nháp lưu sẵn trên trình duyệt vẫn gửi
 * kiểu đó.
 */
public final class ExperienceDates {

    /** Mốc "hôm nay" theo giờ Việt Nam, không theo múi giờ của máy chủ. */
    public static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private static final Pattern DAY_MONTH_YEAR = Pattern.compile("^(\\d{1,2})/(\\d{1,2})/(\\d{4})$");
    private static final Pattern MONTH_YEAR = Pattern.compile("^(\\d{1,2})/(\\d{2}|\\d{4})$");
    private static final Pattern ISO_DATE = Pattern.compile("^(\\d{4})-(\\d{2})-(\\d{2})$");
    private static final Pattern ISO_MONTH = Pattern.compile("^(\\d{4})-(\\d{2})$");

    private ExperienceDates() {
    }

    /** Một ngày đã đọc được, kèm việc người dùng có khai ngày hay chỉ khai tháng. */
    public record Parsed(LocalDate date, boolean hasDay) {
    }

    /**
     * Đọc {@code dd/MM/yyyy}, {@code yyyy-MM-dd} (có ngày) hoặc {@code MM/yy},
     * {@code MM/yyyy}, {@code yyyy-MM} (chỉ tháng).
     *
     * @return {@code null} nếu rỗng hoặc không đọc được (vd. "Hiện tại")
     */
    public static Parsed parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.trim();
        try {
            Matcher m = DAY_MONTH_YEAR.matcher(v);
            if (m.matches()) {
                return new Parsed(LocalDate.of(num(m, 3), num(m, 2), num(m, 1)), true);
            }
            m = ISO_DATE.matcher(v);
            if (m.matches()) {
                return new Parsed(LocalDate.of(num(m, 1), num(m, 2), num(m, 3)), true);
            }
            m = MONTH_YEAR.matcher(v);
            if (m.matches()) {
                int year = num(m, 2);
                return new Parsed(LocalDate.of(year < 100 ? year + 2000 : year, num(m, 1), 1), false);
            }
            m = ISO_MONTH.matcher(v);
            if (m.matches()) {
                return new Parsed(LocalDate.of(num(m, 1), num(m, 2), 1), false);
            }
        } catch (DateTimeException ignored) {
            // 31/02/2026, tháng 13… → coi như không đọc được
        }
        return null;
    }

    /** {@code dd/MM/yyyy} nếu có ngày, {@code MM/yyyy} nếu chỉ có tháng, "" nếu không có. */
    public static String format(Date date, boolean hasDay) {
        if (date == null) {
            return "";
        }
        LocalDate d = date.toLocalDate();
        return hasDay
                ? String.format("%02d/%02d/%04d", d.getDayOfMonth(), d.getMonthValue(), d.getYear())
                : String.format("%02d/%04d", d.getMonthValue(), d.getYear());
    }

    /** Hôm nay theo giờ Việt Nam. */
    public static LocalDate today() {
        return LocalDate.now(ZONE);
    }

    /** Giá trị này có rơi vào tương lai không. Chỉ có tháng thì so theo tháng. */
    public static boolean isInFuture(Parsed p) {
        if (p == null) {
            return false;
        }
        LocalDate today = today();
        return p.hasDay() ? p.date().isAfter(today) : YearMonth.from(p.date()).isAfter(YearMonth.from(today));
    }

    /**
     * Ngày kết thúc có trước ngày bắt đầu không. Khi một trong hai chỉ có
     * tháng thì chỉ so được theo tháng — so theo ngày sẽ bắt lỗi oan cặp
     * "06/2026 → 10/06/2026".
     */
    public static boolean endsBeforeStart(Parsed start, Parsed end) {
        if (start == null || end == null) {
            return false;
        }
        if (start.hasDay() && end.hasDay()) {
            return end.date().isBefore(start.date());
        }
        return YearMonth.from(end.date()).isBefore(YearMonth.from(start.date()));
    }

    /** Giá trị đưa vào câu lệnh SQL (cột kiểu {@code date}). */
    public static Date toSql(Parsed p) {
        return p == null ? null : Date.valueOf(p.date());
    }

    /** Cờ {@code *_has_day} tương ứng. Không có ngày thì cờ cũng là false. */
    public static boolean hasDay(Parsed p) {
        return p != null && p.hasDay();
    }

    private static int num(Matcher m, int group) {
        return Integer.parseInt(m.group(group));
    }
}
