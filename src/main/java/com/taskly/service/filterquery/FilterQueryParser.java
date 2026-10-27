package com.taskly.service.filterquery;

import com.taskly.service.filterquery.FilterNode.Comparison;
import com.taskly.service.filterquery.FilterNode.DateField;
import java.text.Normalizer;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.MonthDay;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cú pháp filter kiểu Todoist (spec 12.7) → cây FilterNode. Thuần: không DB, không Clock.
 * Ưu tiên: ! > & > |, ngoặc ( ). ',' chỉ ở cấp ngoài cùng. '\' thoát ký tự kế tiếp. '*' đại diện trong tên.
 * Vị trí lỗi = chỉ số 0-based (UTF-16) trong chuỗi query gốc.
 */
public final class FilterQueryParser {

    public static final int MAX_LENGTH = 1024;

    /** Một danh sách (giữa hai ',' cấp ngoài cùng) đã cắt khoảng trắng; offset = vị trí ký tự đầu trong query. */
    public record Part(String text, int offset) {
    }

    private enum Kind { LPAREN, RPAREN, OR, AND, NOT, TERM }

    private record Token(Kind kind, int position, String text) {
    }

    private static final Pattern SPACES = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern NEXT_DAYS = Pattern.compile("^(?:next )?(\\d{1,4}) days?$");
    private static final Pattern DATE_FIELD = Pattern.compile("^(date|deadline|created)(?: (before|after))? ?:(.*)$");
    private static final Pattern SEARCH = Pattern.compile("^search ?:.*$");
    private static final Pattern ASSIGNED = Pattern.compile("^assigned to\\b.*$");
    private static final Pattern ISO_DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
    /** yyyy-MM-ddTHH:mm[:ss] — keyword() đã chuyển chữ thường nên 'T' là 't'. */
    private static final Pattern ISO_DATETIME =
            Pattern.compile("^(\\d{4}-\\d{2}-\\d{2})t(\\d{2}:\\d{2})(?::(\\d{2}))?$");
    private static final Pattern SHORT_DATE = Pattern.compile("^(\\d{1,2})/(\\d{1,2})$");
    private static final Pattern FULL_DATE = Pattern.compile("^(\\d{1,2})/(\\d{1,2})/(\\d{4})$");

    private FilterQueryParser() {
    }

    /** Tách query theo ',' cấp ngoài cùng. Rỗng / quá dài / ',' trong ngoặc / phần rỗng → FilterSyntaxException. */
    public static List<Part> split(String query) {
        if (query == null || query.isBlank()) {
            throw new FilterSyntaxException(0, "empty");
        }
        if (query.length() > MAX_LENGTH) {
            throw new FilterSyntaxException(MAX_LENGTH, "too-long");
        }
        List<Part> parts = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < query.length(); i++) {
            char c = query.charAt(i);
            if (c == '\\') {
                i++; // ký tự sau '\' là chữ thường; '\' ở cuối để tokenizer báo dangling-escape
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth = Math.max(0, depth - 1);
            } else if (c == ',') {
                if (depth > 0) {
                    throw new FilterSyntaxException(i, "comma-in-parens");
                }
                parts.add(part(query, start, i));
                start = i + 1;
            }
        }
        parts.add(part(query, start, query.length()));
        return List.copyOf(parts);
    }

    /** Một danh sách (tasks?filter=): có ',' cấp ngoài cùng → lỗi "comma" tại vị trí dấu phẩy. */
    public static FilterNode parse(String query, DateOrder order) {
        List<Part> parts = split(query);
        if (parts.size() > 1) {
            throw new FilterSyntaxException(firstTopLevelComma(query), "comma");
        }
        return parsePart(parts.get(0), order);
    }

    /** Cả query của filter đã lưu (POST / PATCH filters, filters/validate): mọi phần đều phải đúng. */
    public static List<FilterNode> parseAll(String query, DateOrder order) {
        return split(query).stream().map(part -> parsePart(part, order)).toList();
    }

    private static Part part(String query, int from, int to) {
        int start = from;
        int end = to;
        while (start < end && Character.isWhitespace(query.charAt(start))) {
            start++;
        }
        while (end > start && Character.isWhitespace(query.charAt(end - 1))) {
            end--;
        }
        if (start == end) {
            throw new FilterSyntaxException(from, "empty-part");
        }
        return new Part(query.substring(start, end), start);
    }

    private static int firstTopLevelComma(String query) {
        int depth = 0;
        for (int i = 0; i < query.length(); i++) {
            char c = query.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth = Math.max(0, depth - 1);
            } else if (c == ',' && depth == 0) {
                return i;
            }
        }
        return 0;
    }

    private static FilterNode parsePart(Part part, DateOrder order) {
        List<Token> tokens = tokenize(part.text(), part.offset());
        Parser parser = new Parser(tokens, part.offset() + part.text().length(), order);
        FilterNode node = parser.orExpr();
        if (!parser.atEnd()) {
            throw new FilterSyntaxException(parser.peek().position(), "unexpected-token");
        }
        return node;
    }

    private static List<Token> tokenize(String text, int offset) {
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            Kind operator = switch (c) {
                case '(' -> Kind.LPAREN;
                case ')' -> Kind.RPAREN;
                case '|' -> Kind.OR;
                case '&' -> Kind.AND;
                case '!' -> Kind.NOT;
                default -> null;
            };
            if (operator != null) {
                tokens.add(new Token(operator, offset + i, String.valueOf(c)));
                i++;
                continue;
            }
            int start = i;
            int end = i; // sau ký tự không phải khoảng trắng cuối cùng của term
            while (i < text.length()) {
                char d = text.charAt(i);
                if (d == '\\') {
                    if (i + 1 >= text.length()) {
                        throw new FilterSyntaxException(offset + i, "dangling-escape");
                    }
                    i += 2;
                    end = i;
                    continue;
                }
                if (d == '(' || d == ')' || d == '|' || d == '&' || d == '!') {
                    break;
                }
                i++;
                if (!Character.isWhitespace(d)) {
                    end = i;
                }
            }
            tokens.add(new Token(Kind.TERM, offset + start, text.substring(start, end)));
        }
        return tokens;
    }

    private static final class Parser {

        private final List<Token> tokens;
        private final int end;
        private final DateOrder order;
        private int index;

        Parser(List<Token> tokens, int end, DateOrder order) {
            this.tokens = tokens;
            this.end = end;
            this.order = order;
        }

        boolean atEnd() {
            return index >= tokens.size();
        }

        Token peek() {
            return tokens.get(index);
        }

        private boolean match(Kind kind) {
            if (!atEnd() && peek().kind() == kind) {
                index++;
                return true;
            }
            return false;
        }

        FilterNode orExpr() {
            FilterNode left = andExpr();
            while (match(Kind.OR)) {
                left = new FilterNode.Or(left, andExpr());
            }
            return left;
        }

        private FilterNode andExpr() {
            FilterNode left = unary();
            while (match(Kind.AND)) {
                left = new FilterNode.And(left, unary());
            }
            return left;
        }

        private FilterNode unary() {
            if (match(Kind.NOT)) {
                return new FilterNode.Not(unary());
            }
            return primary();
        }

        private FilterNode primary() {
            if (atEnd()) {
                throw new FilterSyntaxException(end, "expected-term");
            }
            Token token = tokens.get(index++);
            if (token.kind() == Kind.LPAREN) {
                FilterNode inner = orExpr();
                if (!match(Kind.RPAREN)) {
                    throw new FilterSyntaxException(atEnd() ? end : peek().position(), "expected-rparen");
                }
                return inner;
            }
            if (token.kind() == Kind.TERM) {
                return parseTerm(token.text(), token.position(), order);
            }
            throw new FilterSyntaxException(token.position(), "unexpected-token");
        }
    }

    // ---- Một term ----

    private static FilterNode parseTerm(String raw, int position, DateOrder order) {
        if (raw.startsWith("##")) {
            return new FilterNode.ProjectName(name(raw.substring(2), position), true);
        }
        if (raw.startsWith("#")) {
            return new FilterNode.ProjectName(name(raw.substring(1), position), false);
        }
        if (raw.startsWith("/")) {
            String rest = raw.substring(1).strip();
            return "*".equals(rest) ? new FilterNode.AnySection() : new FilterNode.SectionName(name(rest, position));
        }
        if (raw.startsWith("@") || raw.startsWith("%")) {
            return new FilterNode.LabelName(name(raw.substring(1), position));
        }
        String keyword = keyword(raw);
        FilterNode fixed = fixedKeyword(keyword);
        if (fixed != null) {
            return fixed;
        }
        Matcher nextDays = NEXT_DAYS.matcher(keyword);
        if (nextDays.matches()) {
            int days = Integer.parseInt(nextDays.group(1));
            if (days < 1) {
                throw new FilterSyntaxException(position, "invalid-number");
            }
            return new FilterNode.NextDays(days);
        }
        if (SEARCH.matcher(keyword).matches()) {
            String value = raw.substring(firstUnescapedColon(raw) + 1).strip();
            if (value.isEmpty()) {
                throw new FilterSyntaxException(position, "empty-value");
            }
            return new FilterNode.SearchText(likePattern(value));
        }
        if (ASSIGNED.matcher(keyword).matches()) {
            throw new FilterSyntaxException(position, "unsupported"); // M8
        }
        Matcher dateField = DATE_FIELD.matcher(keyword);
        if (dateField.matches()) {
            DateField field = switch (dateField.group(1)) {
                case "deadline" -> DateField.DEADLINE;
                case "created" -> DateField.CREATED;
                default -> DateField.DUE;
            };
            Comparison comparison = dateField.group(2) == null ? Comparison.ON
                    : "before".equals(dateField.group(2)) ? Comparison.BEFORE : Comparison.AFTER;
            FilterDate date = dateValue(dateField.group(3).strip(), position, order);
            if (field == DateField.DEADLINE && date.hasTime()) {
                throw new FilterSyntaxException(position, "invalid-date"); // deadline chỉ có ngày (spec 5.2)
            }
            return new FilterNode.DateTerm(field, comparison, date);
        }
        FilterDate literal = literalDate(keyword, position, order);
        if (literal != null) {
            return new FilterNode.DateTerm(DateField.DUE, Comparison.ON, literal);
        }
        throw new FilterSyntaxException(position, "unknown-keyword");
    }

    private static FilterNode fixedKeyword(String keyword) {
        return switch (keyword) {
            case "today", "hôm nay" -> new FilterNode.DateTerm(DateField.DUE, Comparison.ON, new FilterDate.Relative(0));
            case "tomorrow", "ngày mai" ->
                    new FilterNode.DateTerm(DateField.DUE, Comparison.ON, new FilterDate.Relative(1));
            case "yesterday", "hôm qua" ->
                    new FilterNode.DateTerm(DateField.DUE, Comparison.ON, new FilterDate.Relative(-1));
            case "overdue", "od", "quá hạn" -> new FilterNode.Overdue();
            case "no date", "không có ngày" -> new FilterNode.NoDate();
            case "no time" -> new FilterNode.NoTime();
            case "no deadline" -> new FilterNode.NoDeadline();
            case "no labels" -> new FilterNode.NoLabels();
            case "no priority" -> new FilterNode.Priority(4);
            case "p1", "p2", "p3", "p4" -> new FilterNode.Priority(keyword.charAt(1) - '0');
            case "subtask" -> new FilterNode.Subtask();
            case "recurring" -> new FilterNode.Recurring();
            default -> null;
        };
    }

    private static FilterDate dateValue(String value, int position, DateOrder order) {
        FilterDate relative = switch (value) {
            case "today", "hôm nay" -> new FilterDate.Relative(0);
            case "tomorrow", "ngày mai" -> new FilterDate.Relative(1);
            case "yesterday", "hôm qua" -> new FilterDate.Relative(-1);
            default -> null;
        };
        if (relative != null) {
            return relative;
        }
        FilterDate literal = literalDate(value, position, order);
        if (literal == null) {
            throw new FilterSyntaxException(position, "invalid-date");
        }
        return literal;
    }

    /** null = không có dạng ngày; có dạng ngày nhưng sai (32/13, 30/02) → invalid-date. */
    private static FilterDate literalDate(String value, int position, DateOrder order) {
        try {
            if (ISO_DATE.matcher(value).matches()) {
                return new FilterDate.Absolute(LocalDate.parse(value));
            }
            Matcher isoDateTime = ISO_DATETIME.matcher(value);
            if (isoDateTime.matches()) { // floating: giây (nếu có) bị cắt về phút trong AbsoluteTime
                String seconds = isoDateTime.group(3) == null ? "00" : isoDateTime.group(3);
                return new FilterDate.AbsoluteTime(LocalDateTime.parse(
                        isoDateTime.group(1) + "T" + isoDateTime.group(2) + ":" + seconds));
            }
            Matcher shortDate = SHORT_DATE.matcher(value);
            if (shortDate.matches()) {
                int first = Integer.parseInt(shortDate.group(1));
                int second = Integer.parseInt(shortDate.group(2));
                return new FilterDate.DayMonth(order == DateOrder.DMY
                        ? MonthDay.of(second, first) : MonthDay.of(first, second));
            }
            Matcher fullDate = FULL_DATE.matcher(value);
            if (fullDate.matches()) {
                int first = Integer.parseInt(fullDate.group(1));
                int second = Integer.parseInt(fullDate.group(2));
                int year = Integer.parseInt(fullDate.group(3));
                return new FilterDate.Absolute(order == DateOrder.DMY
                        ? LocalDate.of(year, second, first) : LocalDate.of(year, first, second));
            }
            return null;
        } catch (DateTimeException ex) {
            throw new FilterSyntaxException(position, "invalid-date");
        }
    }

    /** Tên sau tiền tố (#, ##, /, @, %): cắt khoảng trắng, rỗng → lỗi, rồi thành mẫu LIKE. */
    private static String name(String rawName, int position) {
        String trimmed = rawName.strip();
        if (trimmed.isEmpty()) {
            throw new FilterSyntaxException(position, "empty-name");
        }
        return likePattern(trimmed);
    }

    /** "\x" → x thường (escape cho LIKE nếu là % _ \); '*' không thoát → '%'; % _ \ thường → escape. */
    static String likePattern(String raw) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\\' && i + 1 < raw.length()) {
                appendLiteral(out, raw.charAt(++i));
            } else if (c == '*') {
                out.append('%');
            } else {
                appendLiteral(out, c);
            }
        }
        return out.toString();
    }

    private static void appendLiteral(StringBuilder out, char c) {
        if (c == '%' || c == '_' || c == '\\') {
            out.append('\\');
        }
        out.append(c);
    }

    /** Bỏ escape, NFC, chữ thường, gộp khoảng trắng — để so từ khoá. */
    private static String keyword(String raw) {
        StringBuilder plain = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\\' && i + 1 < raw.length()) {
                plain.append(raw.charAt(++i));
            } else {
                plain.append(c);
            }
        }
        String normalized = Normalizer.normalize(plain.toString(), Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        return SPACES.matcher(normalized).replaceAll(" ").strip();
    }

    private static int firstUnescapedColon(String raw) {
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == ':') {
                return i;
            }
        }
        return raw.length() - 1;
    }
}
