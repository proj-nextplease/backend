package com.nextplease.backend.util;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Sinh và kiểm tra "slug" — phần đuôi thân thiện trong link portfolio công khai
 * ({@code nextplease.vn/p/phat-nguyen}).
 *
 * Chỉ dùng chữ thường a–z, số và dấu gạch ngang: đó là tập ký tự an toàn trên
 * mọi URL, không cần mã hoá percent, và đọc được khi dán vào tin nhắn.
 */
public final class Slugs {

    public static final int MIN_LENGTH = 3;
    public static final int MAX_LENGTH = 40;

    private static final Pattern VALID = Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");
    private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{InCombiningDiacriticalMarks}+");
    private static final Pattern NON_SLUG_CHARS = Pattern.compile("[^a-z0-9]+");
    private static final Pattern EDGE_HYPHENS = Pattern.compile("^-+|-+$");

    /**
     * Những slug không được để người dùng chiếm, vì chúng trùng với đường dẫn
     * của ứng dụng hoặc dễ bị dùng để giả mạo trang chính thức.
     */
    private static final Set<String> RESERVED = Set.of(
            "admin", "api", "app", "auth", "login", "logout", "register", "signup", "signin",
            "me", "my", "profile", "profiles", "portfolio", "portfolios", "p",
            "jobs", "job", "quests", "quest", "companies", "company", "business", "businesses",
            "candidates", "candidate", "thao-luan", "discussions", "settings", "support",
            "help", "about", "terms", "privacy", "static", "assets", "public", "www",
            "nextplease", "next-please", "official", "verified", "system", "root", "null", "undefined"
    );

    private Slugs() {
    }

    /**
     * Chuyển một cái tên thành slug: bỏ dấu tiếng Việt, hạ chữ thường, thay mọi
     * ký tự còn lại bằng dấu gạch ngang.
     *
     * <p>"Nguyễn Tài Phát" → "nguyen-tai-phat"
     *
     * @return slug đã chuẩn hoá, hoặc chuỗi rỗng nếu tên không còn ký tự dùng được
     */
    public static String slugify(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        // đ/Đ không phải là chữ d kèm dấu phụ nên NFD không tách ra được.
        String text = raw.replace('đ', 'd').replace('Đ', 'D');
        text = Normalizer.normalize(text, Normalizer.Form.NFD);
        text = COMBINING_MARKS.matcher(text).replaceAll("");
        text = text.toLowerCase(Locale.ROOT);
        text = NON_SLUG_CHARS.matcher(text).replaceAll("-");
        text = EDGE_HYPHENS.matcher(text).replaceAll("");
        if (text.length() > MAX_LENGTH) {
            text = EDGE_HYPHENS.matcher(text.substring(0, MAX_LENGTH)).replaceAll("");
        }
        return text;
    }

    /** Slug do người dùng tự nhập có hợp lệ không (đúng định dạng, đủ dài, không bị giữ chỗ). */
    public static boolean isValid(String slug) {
        return slug != null
                && slug.length() >= MIN_LENGTH
                && slug.length() <= MAX_LENGTH
                && VALID.matcher(slug).matches()
                && !isReserved(slug);
    }

    public static boolean isReserved(String slug) {
        return slug != null && RESERVED.contains(slug);
    }

    /**
     * Thông báo lỗi tiếng Việt cho slug không hợp lệ, hoặc null nếu hợp lệ.
     * Tách riêng để API trả về đúng lý do thay vì một câu chung chung.
     */
    public static String validationError(String slug) {
        if (slug == null || slug.isBlank()) {
            return "Vui lòng nhập đường dẫn cho portfolio.";
        }
        if (slug.length() < MIN_LENGTH) {
            return "Đường dẫn phải có ít nhất " + MIN_LENGTH + " ký tự.";
        }
        if (slug.length() > MAX_LENGTH) {
            return "Đường dẫn tối đa " + MAX_LENGTH + " ký tự.";
        }
        if (!VALID.matcher(slug).matches()) {
            return "Đường dẫn chỉ gồm chữ thường không dấu, số và dấu gạch ngang; "
                    + "không bắt đầu, kết thúc hay lặp dấu gạch ngang.";
        }
        if (isReserved(slug)) {
            return "Đường dẫn này được hệ thống giữ chỗ, vui lòng chọn tên khác.";
        }
        return null;
    }
}
