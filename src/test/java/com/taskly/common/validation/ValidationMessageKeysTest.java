package com.taskly.common.validation;

import static org.assertj.core.api.Assertions.assertThat;

import com.taskly.support.MessageBundles;
import jakarta.validation.Constraint;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.util.ClassUtils;

/**
 * Mọi constraint trên DTO (com.taskly.dto..) phải dùng message dạng "{key}" và key có ở cả messages_vi lẫn
 * messages_en — để errors[].message dịch được theo Accept-Language. Câu viết cứng trong DTO → test đỏ.
 */
class ValidationMessageKeysTest {

    private static final Pattern KEY = Pattern.compile("\\{([a-z0-9.-]+)}");

    static List<Class<?>> dtoClasses() {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter((metadataReader, metadataReaderFactory) -> true);
        return scanner.findCandidateComponents("com.taskly.dto").stream()
                .<Class<?>>map(definition -> ClassUtils.resolveClassName(definition.getBeanClassName(), null))
                .toList();
    }

    @Test
    void scannerFindsTheM0Dtos() {
        assertThat(dtoClasses()).extracting(Class::getSimpleName)
                .contains("RegisterRequest", "LoginRequest", "RefreshRequest", "LogoutRequest");
    }

    @Test
    void everyConstraintMessageOnDtosIsAKeyPresentInBothLanguages() throws Exception {
        Properties vi = MessageBundles.load(MessageBundles.VI);
        Properties en = MessageBundles.load(MessageBundles.EN);
        List<String> problems = new ArrayList<>();

        for (Class<?> dto : dtoClasses()) {
            for (Field field : dto.getDeclaredFields()) {
                for (Annotation annotation : field.getAnnotations()) {
                    if (!annotation.annotationType().isAnnotationPresent(Constraint.class)) {
                        continue;
                    }
                    String where = dto.getSimpleName() + "." + field.getName()
                            + " @" + annotation.annotationType().getSimpleName();
                    String message = (String) annotation.annotationType().getMethod("message").invoke(annotation);
                    Matcher matcher = KEY.matcher(message);
                    if (!matcher.matches()) {
                        problems.add(where + ": message phải là {key}, đang là \"" + message + "\"");
                        continue;
                    }
                    String key = matcher.group(1);
                    if (!vi.containsKey(key)) {
                        problems.add(where + ": thiếu key " + key + " trong messages_vi.properties");
                    }
                    if (!en.containsKey(key)) {
                        problems.add(where + ": thiếu key " + key + " trong messages_en.properties");
                    }
                }
            }
        }

        assertThat(problems).isEmpty();
    }
}
