package com.taskly.dto.search;

import com.taskly.common.error.ApiException;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Từ khoá tìm kiếm đã chuẩn hoá (hàm thuần, không đụng DB).
 * - normalized: các từ nối bằng MỘT dấu cách.
 * - terms: các từ khác nhau — mỗi từ phải xuất hiện (AND). '*' trong từ là ký tự đại diện.
 * Bỏ dấu + chữ thường làm ở SQL (f_unaccent(lower(...))) để Java và chỉ mục dùng chung một quy tắc.
 */
public record SearchQuery(String normalized, List<String> terms) {

    public static final int MAX_LENGTH = 100;

    /**
     * Nội dung lỗi 400 khi từ khoá quá dài. Giá trị trùng ProblemTexts.SEARCH_QUERY_TOO_LONG (M2 Task 1);
     * để cục bộ vì Task 4 chạy song song — điều phối gộp về ProblemTexts sau.
     */
    static final String TOO_LONG_DETAIL = "Từ khoá tìm kiếm tối đa 100 ký tự";

    /** Mọi khoảng trắng Unicode (kể cả NBSP) — \s mặc định của Java chỉ có khoảng trắng ASCII. */
    private static final Pattern WHITESPACE = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern EDGE_WHITESPACE = Pattern.compile("^\\s+|\\s+$", Pattern.UNICODE_CHARACTER_CLASS);

    public SearchQuery {
        terms = List.copyOf(terms);
    }

    /**
     * NFC → cắt khoảng trắng hai đầu → kiểm ≤ 100 code point → gộp khoảng trắng giữa các từ.
     * Rỗng → Optional.empty() (service trả kết quả rỗng, không truy vấn). Dài quá → 400.
     */
    public static Optional<SearchQuery> parse(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String trimmed = EDGE_WHITESPACE.matcher(Normalizer.normalize(raw, Normalizer.Form.NFC)).replaceAll("");
        if (trimmed.codePointCount(0, trimmed.length()) > MAX_LENGTH) {
            throw ApiException.badRequest(TOO_LONG_DETAIL);
        }
        if (trimmed.isEmpty()) {
            return Optional.empty();
        }
        String text = WHITESPACE.matcher(trimmed).replaceAll(" ");
        return Optional.of(new SearchQuery(text, Arrays.stream(text.split(" ")).distinct().toList()));
    }

    /** Mỗi từ → mẫu ILIKE "%…%" (escape \ % _, rồi '*' → '%'), dùng với ESCAPE '\'. */
    public List<String> likePatterns() {
        return terms.stream().map(term -> "%" + toLike(term) + "%").toList();
    }

    /** Chuỗi đưa vào similarity(): bỏ '*', gộp khoảng trắng. */
    public String similarityText() {
        return WHITESPACE.matcher(normalized.replace('*', ' ')).replaceAll(" ").trim();
    }

    /** Escape ký tự đặc biệt của LIKE/ILIKE dùng với ESCAPE '\': \ → \\, % → \%, _ → \_ (backslash trước). */
    public static String escapeLike(String term) {
        return term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /** escapeLike rồi đổi '*' thành '%' (ký tự đại diện). */
    public static String toLike(String term) {
        return escapeLike(term).replace("*", "%");
    }
}
