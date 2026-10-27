package com.taskly.dto.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class PageTest {

    @Test
    void lookaheadRowPresent_trimsAndBuildsCursorFromLastItem() {
        Page<String> page = Page.fromLookahead(List.of("a", "b", "c"), 2, item -> "after-" + item);

        assertThat(page.items()).containsExactly("a", "b");
        assertThat(page.nextCursor()).isEqualTo("after-b");
    }

    @Test
    void noLookaheadRow_isLastPage() {
        Page<String> page = Page.fromLookahead(List.of("a", "b"), 2, item -> "after-" + item);

        assertThat(page.items()).containsExactly("a", "b");
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    void map_keepsCursor() {
        Page<Integer> page = Page.fromLookahead(List.of("a", "bb", "ccc"), 2, item -> "x").map(String::length);

        assertThat(page.items()).containsExactly(1, 2);
        assertThat(page.nextCursor()).isEqualTo("x");
    }
}
