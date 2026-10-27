package com.taskly.service.filterquery;

/** Cây biểu thức của một danh sách filter. describe() = dạng chuẩn để test và log (không chứa dữ liệu task). */
public sealed interface FilterNode {

    String describe();

    enum DateField {
        DUE("due"), DEADLINE("deadline"), CREATED("created");

        private final String label;

        DateField(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    enum Comparison {
        ON("="), BEFORE("<"), AFTER(">");

        private final String symbol;

        Comparison(String symbol) {
            this.symbol = symbol;
        }

        public String symbol() {
            return symbol;
        }
    }

    record Or(FilterNode left, FilterNode right) implements FilterNode {
        @Override
        public String describe() {
            return "(" + left.describe() + " | " + right.describe() + ")";
        }
    }

    record And(FilterNode left, FilterNode right) implements FilterNode {
        @Override
        public String describe() {
            return "(" + left.describe() + " & " + right.describe() + ")";
        }
    }

    record Not(FilterNode inner) implements FilterNode {
        @Override
        public String describe() {
            return "!" + inner.describe();
        }
    }

    /** due / deadline / created so với một ngày. */
    record DateTerm(DateField field, Comparison comparison, FilterDate date) implements FilterNode {
        @Override
        public String describe() {
            return field.label() + comparison.symbol() + date.describe();
        }
    }

    record Overdue() implements FilterNode {
        @Override
        public String describe() {
            return "overdue";
        }
    }

    record NoDate() implements FilterNode {
        @Override
        public String describe() {
            return "no-date";
        }
    }

    /** Có ngày nhưng không có giờ. */
    record NoTime() implements FilterNode {
        @Override
        public String describe() {
            return "no-time";
        }
    }

    /** Hôm nay và N-1 ngày sau (N ngày, tính cả hôm nay). */
    record NextDays(int days) implements FilterNode {
        @Override
        public String describe() {
            return "next-days=" + days;
        }
    }

    record NoDeadline() implements FilterNode {
        @Override
        public String describe() {
            return "no-deadline";
        }
    }

    record Priority(int level) implements FilterNode {
        @Override
        public String describe() {
            return "p" + level;
        }
    }

    /** pattern = mẫu LIKE đã escape, '*' → '%'. withChildren = "##" (kèm dự án con). */
    record ProjectName(String pattern, boolean withChildren) implements FilterNode {
        @Override
        public String describe() {
            return (withChildren ? "##" : "#") + pattern;
        }
    }

    record SectionName(String pattern) implements FilterNode {
        @Override
        public String describe() {
            return "/" + pattern;
        }
    }

    record AnySection() implements FilterNode {
        @Override
        public String describe() {
            return "/*";
        }
    }

    record LabelName(String pattern) implements FilterNode {
        @Override
        public String describe() {
            return "@" + pattern;
        }
    }

    record NoLabels() implements FilterNode {
        @Override
        public String describe() {
            return "no-labels";
        }
    }

    /** pattern = mẫu LIKE đã escape, '*' → '%' (chưa bọc '%' hai đầu). */
    record SearchText(String pattern) implements FilterNode {
        @Override
        public String describe() {
            return "search=" + pattern;
        }
    }

    record Subtask() implements FilterNode {
        @Override
        public String describe() {
            return "subtask";
        }
    }

    /** Task lặp (M6). Trước M6 recurrence_rule luôn null → không khớp task nào. */
    record Recurring() implements FilterNode {
        @Override
        public String describe() {
            return "recurring";
        }
    }
}
