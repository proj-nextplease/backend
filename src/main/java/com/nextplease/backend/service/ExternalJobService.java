package com.nextplease.backend.service;

import com.nextplease.backend.exception.AppException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Tin tuyển dụng từ nguồn ngoài (Careerjet) — mô hình "cầu nối": hiển thị ở
 * nextplease, bấm ứng tuyển thì sang trang gốc.
 *
 * NGUYÊN TẮC: KHÔNG gọi API bên thứ ba lúc người dùng mở trang. Đồng bộ là
 * thao tác admin chủ động, dữ liệu nằm trong DB của mình, trang đọc từ DB.
 * Nếu để trang phụ thuộc API ngoài thì chỉ cần bên kia chậm hoặc hết quota là
 * mục việc làm trống trơn — và hết quota thường rơi đúng lúc đông người xem.
 */
@Service
public class ExternalJobService {

    private static final Logger log = LoggerFactory.getLogger(ExternalJobService.class);

    /** API v4. Bản legacy (public.api.careerjet.net) đã đóng với tài khoản mới. */
    private static final String API_URL = "https://search.api.careerjet.net/v4/query";

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final String apiKey;

    /**
     * Lần cuối gọi Careerjet cho mỗi truy vấn. Dùng để không gọi lại API khi
     * nhiều người mở cùng một tìm kiếm trong vài phút.
     *
     * Để trong bộ nhớ chứ không lưu DB: mất khi khởi động lại, nhưng hậu quả
     * chỉ là một lần gọi thừa. Thêm hẳn một bảng cho việc này là đổi lấy sự
     * phức tạp mà không được gì tương xứng ở quy mô hiện tại.
     */
    private final java.util.concurrent.ConcurrentHashMap<String, Long> lastFetchedAt =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Trong khoảng này thì phục vụ từ DB, không gọi lại API. */
    private static final long CACHE_TTL_MS = java.time.Duration.ofMinutes(15).toMillis();

    public ExternalJobService(NamedParameterJdbcTemplate jdbcTemplate,
                              @Value("${app.careerjet.api-key:}") String apiKey) {
        this.jdbcTemplate = jdbcTemplate;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
    }

    public boolean isConfigured() {
        return !apiKey.isBlank();
    }

    /* ─────────────────────────── ĐỌC ─────────────────────────── */

    public List<Map<String, Object>> list(String q, String location, int limit) {
        int capped = Math.max(1, Math.min(limit, 100));
        /* DISTINCT ON de gop tin trung.
         *
         * Careerjet tong hop tu nhieu trang nguon, nen CUNG mot viec tra ve
         * nhieu lan, moi lan mot link theo doi khac nhau. Khoa sha256(url) coi
         * chung la cac tin rieng biet — do duoc 13 cap trung trong 50 tin, co
         * tin lap 4 lan. Khong gop thi danh sach nhin nhu loi.
         *
         * Gop luc DOC chu khong luc ghi: giu nguyen moi ban trong DB thi sau
         * nay doi tieu chi gop khong phai keo lai du lieu.
         */
        return jdbcTemplate.queryForList("""
                select * from (
                    select distinct on (lower(title), lower(coalesce(company_name, '')))
                           id,
                           title,
                           company_name  as "companyName",
                           location,
                           excerpt,
                           salary_text   as "salaryText",
                           salary_min    as "salaryMin",
                           salary_max    as "salaryMax",
                           salary_type   as "salaryType",
                           apply_url     as "applyUrl",
                           posted_at     as "postedAt",
                           last_seen_at  as "lastSeenAt",
                           source
                    from external_jobs
                    where is_active
                      and (:q::text is null or title ilike '%' || :q || '%'
                                            or company_name ilike '%' || :q || '%')
                      and (:loc::text is null or location ilike '%' || :loc || '%')
                    -- DISTINCT ON giu HANG DAU TIEN cua moi nhom, nen thu tu o
                    -- day quyet dinh ban nao duoc giu: ban moi nhat.
                    order by lower(title), lower(coalesce(company_name, '')),
                             posted_at desc nulls last, last_seen_at desc
                ) t
                order by "postedAt" desc nulls last, "lastSeenAt" desc
                limit :limit
                """, new MapSqlParameterSource()
                .addValue("q", blankToNull(q))
                .addValue("loc", blankToNull(location))
                .addValue("limit", capped));
    }

    public Map<String, Object> stats() {
        return jdbcTemplate.queryForMap("""
                select count(*) filter (where is_active)            as "activeCount",
                       count(*)                                     as "totalCount",
                       max(last_seen_at)                            as "lastSyncedAt",
                       count(distinct company_name) filter (where is_active) as "companyCount"
                from external_jobs
                """, new MapSqlParameterSource());
    }

    /**
     * Đường đi chính: người dùng mở tab Việc làm thì gọi thẳng Careerjet bằng
     * IP và user-agent của CHÍNH họ.
     *
     * Careerjet bắt buộc hai tham số đó, tức API được thiết kế để gọi theo
     * từng người dùng ngay lúc họ tìm việc. Kéo hàng loạt bằng tài khoản admin
     * rồi phục vụ lại bản lưu là đi ngược ý đồ đó.
     *
     * Ba lớp bảo vệ, theo thứ tự:
     *   1. Vừa gọi cho truy vấn này dưới 15 phút → lấy từ DB, khỏi gọi lại.
     *   2. Gọi API, ghi kết quả vào DB rồi trả về.
     *   3. API lỗi hoặc hết quota → trả dữ liệu đã lưu thay vì để trống.
     */
    public List<Map<String, Object>> search(String q, String location, int limit,
                                            String callerIp, String userAgent) {
        if (!isConfigured()) {
            return list(q, location, limit);
        }
        String cacheKey = (q == null ? "" : q.trim().toLowerCase(Locale.ROOT))
                + "|" + (location == null ? "" : location.trim().toLowerCase(Locale.ROOT));
        Long last = lastFetchedAt.get(cacheKey);
        if (last != null && System.currentTimeMillis() - last < CACHE_TTL_MS) {
            return list(q, location, limit);
        }

        try {
            JsonNode root = callApi(q, location, 1, callerIp, userAgent);
            if ("JOBS".equals(root.path("type").asText(""))) {
                JsonNode jobs = root.path("jobs");
                if (jobs.isArray()) {
                    for (JsonNode job : jobs) {
                        String applyUrl = job.path("url").asText("");
                        if (!applyUrl.isBlank()) upsert(job, applyUrl);
                    }
                }
                lastFetchedAt.put(cacheKey, System.currentTimeMillis());
            } else {
                // type=LOCATIONS: địa điểm mơ hồ. Không phải lỗi hệ thống, và
                // người đang tìm việc không sửa được gì — cứ trả dữ liệu đã có.
                log.info("Careerjet không khớp địa điểm '{}': {}", location,
                        root.path("message").asText(""));
            }
        } catch (Exception e) {
            // Cố ý KHÔNG ném lên: đây là phần bổ sung của trang việc làm. Hỏng
            // thì rơi về dữ liệu đã lưu, người dùng vẫn thấy nội dung.
            log.warn("Gọi Careerjet thất bại, dùng dữ liệu đã lưu: {}", e.getMessage());
        }
        return list(q, location, limit);
    }

    /* ─────────────────────────── ĐỒNG BỘ (nạp sẵn hàng loạt) ─────────────────────────── */

    /**
     * Gọi Careerjet và ghi vào DB.
     *
     * @param keywords từ khoá tìm kiếm
     * @param location địa điểm, để trống là toàn quốc
     * @param pages    số trang cần lấy (mỗi trang tối đa 100 tin, API cho tối đa 10 trang)
     */
    public Map<String, Object> sync(String keywords, String location, int pages, String callerIp) {
        if (!isConfigured()) {
            throw new AppException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Chưa cấu hình CAREERJET_API_KEY. Đặt biến môi trường rồi khởi động lại backend.");
        }
        int pageCount = Math.max(1, Math.min(pages, 10));
        int inserted = 0;
        int updated = 0;
        int skipped = 0;

        for (int page = 1; page <= pageCount; page++) {
            JsonNode root = callApi(keywords, location, page, callerIp, null);
            String type = root.path("type").asText("");
            if (!"JOBS".equals(type)) {
                // Careerjet trả type=LOCATIONS khi địa điểm mơ hồ hoặc không khớp.
                // Đây không phải lỗi HTTP nên phải bắt riêng, nếu không sẽ im lặng
                // ghi 0 tin mà người dùng tưởng đã đồng bộ xong.
                throw new AppException(HttpStatus.BAD_REQUEST,
                        "Careerjet không tìm được địa điểm '" + location + "': "
                                + root.path("message").asText("không rõ nguyên nhân"));
            }
            JsonNode jobs = root.path("jobs");
            if (!jobs.isArray() || jobs.isEmpty()) break;

            for (JsonNode job : jobs) {
                String applyUrl = job.path("url").asText("");
                if (applyUrl.isBlank()) { skipped++; continue; }
                int n = upsert(job, applyUrl);
                if (n == 1) inserted++; else updated++;
            }
            if (jobs.size() < 100) break; // trang cuối
        }

        log.info("Đồng bộ Careerjet xong: thêm {}, cập nhật {}, bỏ qua {}", inserted, updated, skipped);
        return Map.of("inserted", inserted, "updated", updated, "skipped", skipped);
    }

    /** Ẩn tin không còn thấy ở nguồn sau {days} ngày. Cũng là cách dọn link chết. */
    public int deactivateStale(int days) {
        int d = Math.max(1, Math.min(days, 365));
        return jdbcTemplate.update("""
                update external_jobs
                   set is_active = false
                 where is_active
                   and last_seen_at < now() - make_interval(days => :days)
                """, new MapSqlParameterSource().addValue("days", d));
    }

    /* ─────────────────────────── nội bộ ─────────────────────────── */

    private JsonNode callApi(String keywords, String location, int page,
                             String callerIp, String userAgent) {
        StringBuilder url = new StringBuilder(API_URL)
                .append("?locale_code=vi_VN")
                .append("&sort=date")
                .append("&page_size=100")
                .append("&page=").append(page)
                // user_ip và user_agent là THAM SỐ BẮT BUỘC; thiếu là HTTP 403.
                .append("&user_ip=").append(enc(callerIp == null || callerIp.isBlank() ? "127.0.0.1" : callerIp))
                .append("&user_agent=").append(enc(
                        userAgent == null || userAgent.isBlank()
                                ? "nextplease/1.0 (+https://nextplease.online)"
                                : userAgent));
        if (keywords != null && !keywords.isBlank()) url.append("&keywords=").append(enc(keywords));
        if (location != null && !location.isBlank()) url.append("&location=").append(enc(location));

        // Basic auth: tên đăng nhập là API key, mật khẩu để TRỐNG (vẫn phải có dấu ':').
        String credentials = Base64.getEncoder()
                .encodeToString((apiKey + ":").getBytes(StandardCharsets.UTF_8));

        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url.toString()))
                    .header("Authorization", "Basic " + credentials)
                    // BẮT BUỘC. Thiếu header này API trả
                    // "Undeclared referrer. Please add a Referer header."
                    // Tài liệu không nhắc tới, chỉ lộ ra khi gọi thật.
                    .header("Referer", "https://nextplease.online/jobs")
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(20))
                    .GET()
                    .build();
            HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                throw new AppException(HttpStatus.BAD_GATEWAY,
                        "Careerjet trả về HTTP " + res.statusCode() + ": " + truncate(res.body()));
            }
            return objectMapper.readTree(res.body());
        } catch (AppException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AppException(HttpStatus.BAD_GATEWAY, "Gọi Careerjet bị gián đoạn.");
        } catch (Exception e) {
            throw new AppException(HttpStatus.BAD_GATEWAY, "Không gọi được Careerjet: " + e.getMessage());
        }
    }

    private int upsert(JsonNode job, String applyUrl) {
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("sourceKey", sha256(applyUrl))
                .addValue("title", truncate(job.path("title").asText(""), 300))
                .addValue("companyName", truncate(job.path("company").asText(null), 200))
                .addValue("location", truncate(job.path("locations").asText(null), 200))
                // description trả về có chèn <b>...</b> quanh từ khoá tìm kiếm.
                // React sẽ escape nên người dùng thấy chuỗi "<b>" hiện nguyên
                // văn giữa câu. Gỡ thẻ ngay khi lưu thay vì xử lý ở giao diện:
                // dữ liệu bẩn thì mọi nơi đọc nó đều phải tự dọn.
                .addValue("excerpt", stripTags(job.path("description").asText(null)))
                .addValue("salaryText", truncate(job.path("salary").asText(null), 160))
                .addValue("salaryMin", job.hasNonNull("salary_min") ? job.get("salary_min").asDouble() : null)
                .addValue("salaryMax", job.hasNonNull("salary_max") ? job.get("salary_max").asDouble() : null)
                .addValue("salaryCurrency", truncate(job.path("salary_currency_code").asText(null), 3))
                .addValue("salaryType", normalizeSalaryType(job.path("salary_type").asText(null)))
                .addValue("applyUrl", applyUrl)
                .addValue("postedAt", parseDate(job.path("date").asText(null)));

        return jdbcTemplate.update("""
                insert into external_jobs (
                    source, source_key, title, company_name, location, excerpt,
                    salary_text, salary_min, salary_max, salary_currency, salary_type,
                    apply_url, posted_at
                ) values (
                    'CAREERJET', :sourceKey, :title, :companyName, :location, :excerpt,
                    :salaryText, :salaryMin, :salaryMax, :salaryCurrency, :salaryType,
                    :applyUrl, :postedAt
                )
                on conflict (source, source_key) do update set
                    title = excluded.title,
                    company_name = excluded.company_name,
                    location = excluded.location,
                    excerpt = excluded.excerpt,
                    salary_text = excluded.salary_text,
                    salary_min = excluded.salary_min,
                    salary_max = excluded.salary_max,
                    salary_currency = excluded.salary_currency,
                    salary_type = excluded.salary_type,
                    posted_at = excluded.posted_at,
                    last_seen_at = now(),
                    is_active = true
                """, p);
    }

    /** API không có id; sha256 của url là khoá ổn định duy nhất tự sinh được. */
    private static String sha256(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Không băm được url", e);
        }
    }

    /** Careerjet trả dạng RFC 1123: "Wed,15 Nov 2025 19:13:43 GMT" (thiếu dấu cách sau dấu phẩy). */
    private static OffsetDateTime parseDate(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String fixed = raw.replaceFirst("^(\\w{3}),(?=\\d)", "$1, ");
        try {
            return ZonedDateTime.parse(fixed, DateTimeFormatter.RFC_1123_DATE_TIME).toOffsetDateTime();
        } catch (Exception e) {
            log.debug("Không đọc được ngày '{}', bỏ trống", raw);
            return null;
        }
    }

    private static String normalizeSalaryType(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String t = raw.trim().toUpperCase(Locale.ROOT).substring(0, 1);
        return "YMWDH".contains(t) ? t : null;
    }

    /** Gỡ thẻ HTML và giải mã vài thực thể hay gặp trong trích đoạn Careerjet. */
    private static String stripTags(String raw) {
        if (raw == null) return null;
        String out = raw.replaceAll("<[^>]{0,40}>", "")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&nbsp;", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return out.isEmpty() ? null : out;
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private static String truncate(String s) {
        return truncate(s, 300);
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty()) return null;
        return t.length() <= max ? t : t.substring(0, max);
    }

    private static String enc(String s) {
        return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
