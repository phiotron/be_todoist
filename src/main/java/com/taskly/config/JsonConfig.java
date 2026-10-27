package com.taskly.config;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.BeanDescription;
import tools.jackson.databind.BeanProperty;
import tools.jackson.databind.DeserializationConfig;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JacksonModule;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.deser.Deserializers;
import tools.jackson.databind.deser.std.StdDeserializer;
import tools.jackson.databind.jsontype.TypeDeserializer;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.type.ReferenceType;

/**
 * Cấu hình Jackson riêng của API. Spring Boot tự đăng ký mọi bean JacksonModule.
 *
 * <p>Field {@code Optional<T>} của DTO request cần phân biệt khoá VẮNG với {@code "x": null} (spec I3: {@code afterId}
 * vắng = cuối, null = đầu). Mặc định Jackson 3 trả {@code Optional.empty()} cho cả hai (đã kiểm bằng spike); ở đây:
 * vắng → {@code null} của Java, null tường minh → {@code Optional.empty()}, có giá trị → {@code Optional.of(...)}.
 */
@Configuration(proxyBeanMethods = false)
public class JsonConfig {

    /**
     * Ngày / giờ không nhận chuỗi rỗng: mặc định Jackson đọc {@code ""} thành null — PATCH {@code "dueDatetime": ""} sẽ
     * âm thầm XOÁ due. Xoá phải là {@code null} tường minh; chuỗi rỗng → 400.
     */
    @Bean
    public JsonMapperBuilderCustomizer strictEmptyDates() {
        return builder -> {
            builder.withCoercionConfig(LocalDate.class,
                    cfg -> cfg.setCoercion(CoercionInputShape.EmptyString, CoercionAction.Fail));
            builder.withCoercionConfig(LocalDateTime.class,
                    cfg -> cfg.setCoercion(CoercionInputShape.EmptyString, CoercionAction.Fail));
        };
    }

    @Bean
    public JacksonModule absentOptionalModule() {
        return new SimpleModule("taskly-absent-optional") {
            @Override
            public void setupModule(SetupContext context) {
                super.setupModule(context);
                context.addDeserializers(new Deserializers.Base() {
                    @Override
                    public ValueDeserializer<?> findReferenceDeserializer(ReferenceType type,
                            DeserializationConfig config, BeanDescription.Supplier beanDesc,
                            TypeDeserializer contentTypeDeserializer, ValueDeserializer<?> contentDeserializer) {
                        return type.hasRawClass(Optional.class)
                                ? new PresenceAwareOptionalDeserializer(type, contentDeserializer) : null;
                    }

                    @Override
                    public boolean hasDeserializerFor(DeserializationConfig config, Class<?> valueType) {
                        return valueType == Optional.class;
                    }
                });
            }
        };
    }

    /** Optional: null tường minh → empty, khoá vắng → null của Java, có giá trị → Optional.of. */
    private static final class PresenceAwareOptionalDeserializer extends StdDeserializer<Optional<?>> {

        private final ReferenceType type;
        private final ValueDeserializer<?> content;

        PresenceAwareOptionalDeserializer(ReferenceType type, ValueDeserializer<?> content) {
            super(type);
            this.type = type;
            this.content = content;
        }

        @Override
        public ValueDeserializer<?> createContextual(DeserializationContext ctxt, BeanProperty property) {
            if (content != null) {
                return this;
            }
            return new PresenceAwareOptionalDeserializer(type,
                    ctxt.findContextualValueDeserializer(type.getReferencedType(), property));
        }

        @Override
        public Optional<?> deserialize(JsonParser parser, DeserializationContext ctxt) {
            return Optional.ofNullable(content.deserialize(parser, ctxt));
        }

        @Override
        public Object getNullValue(DeserializationContext ctxt) {
            return Optional.empty();
        }

        @Override
        public Object getAbsentValue(DeserializationContext ctxt) {
            return null;
        }
    }
}
