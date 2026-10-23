package com.taskly.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import org.junit.jupiter.api.Test;

class EmailNormalizerTest {

    @Test
    void stripsWhitespaceAndLowercases() {
        assertThat(EmailNormalizer.normalize("  Foo.Bar@Example.COM \t")).isEqualTo("foo.bar@example.com");
    }

    @Test
    void returnsNullForNull() {
        assertThat(EmailNormalizer.normalize(null)).isNull();
    }

    @Test
    void blankBecomesEmpty() {
        assertThat(EmailNormalizer.normalize("   ")).isEmpty();
    }

    @Test
    void ignoresDefaultLocale() {
        // Locale tiếng Thổ Nhĩ Kỳ biến "I" thành "ı" nếu dùng toLowerCase() mặc định.
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr"));
            assertThat(EmailNormalizer.normalize("TITLE@MAIL.COM")).isEqualTo("title@mail.com");
        } finally {
            Locale.setDefault(previous);
        }
    }
}
