package com.taskly.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import com.taskly.support.MessageBundles;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Mọi key phải có đủ tiếng Việt và tiếng Anh (spec mục 17: "chữ lỗi của server phải có đủ bản vi / en"). */
class MessageBundlesTest {

    private static final Properties VI = MessageBundles.load(MessageBundles.VI);
    private static final Properties EN = MessageBundles.load(MessageBundles.EN);

    @Test
    void bothLanguagesHaveExactlyTheSameKeys() {
        Set<String> missingInEn = new HashSet<>(VI.stringPropertyNames());
        missingInEn.removeAll(EN.stringPropertyNames());
        Set<String> missingInVi = new HashSet<>(EN.stringPropertyNames());
        missingInVi.removeAll(VI.stringPropertyNames());

        assertThat(missingInEn).as("key có trong messages_vi nhưng thiếu trong messages_en").isEmpty();
        assertThat(missingInVi).as("key có trong messages_en nhưng thiếu trong messages_vi").isEmpty();
    }

    @Test
    void noValueIsBlank() {
        List<String> blank = new ArrayList<>();
        for (Properties bundle : List.of(VI, EN)) {
            bundle.stringPropertyNames().stream()
                    .filter(key -> bundle.getProperty(key).isBlank())
                    .forEach(blank::add);
        }
        assertThat(blank).as("key có giá trị rỗng").isEmpty();
    }

    @Test
    void everyErrorMessageHasBothTranslations() {
        for (ErrorMessage message : ErrorMessage.values()) {
            assertThat(VI.getProperty(message.key())).as("vi của %s", message).isNotBlank();
            assertThat(EN.getProperty(message.key())).as("en của %s", message).isNotBlank();
        }
    }

    @Test
    void everyProblemTitleAndDefaultDetailKeyHasBothTranslations() {
        List<String> keys = new ArrayList<>(List.of("problem.title.default", "problem.detail.default"));
        ProblemTexts.TITLED_STATUSES.forEach(status -> keys.add(ProblemTexts.titleKey(status)));
        ProblemTexts.DEFAULT_DETAIL_STATUSES.forEach(status -> keys.add(ProblemTexts.defaultDetailKey(status)));

        for (String key : keys) {
            assertThat(VI.getProperty(key)).as("vi của %s", key).isNotBlank();
            assertThat(EN.getProperty(key)).as("en của %s", key).isNotBlank();
        }
    }

    @Test
    void valuesWithArgumentsHaveNoSingleQuote() {
        for (Properties bundle : List.of(VI, EN)) {
            for (String key : bundle.stringPropertyNames()) {
                String value = bundle.getProperty(key);
                if (value.contains("{0}")) {
                    assertThat(value).as("MessageFormat nuốt dấu nháy đơn trong %s", key).doesNotContain("'");
                }
            }
        }
    }
}
