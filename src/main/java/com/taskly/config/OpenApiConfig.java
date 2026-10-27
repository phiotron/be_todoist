package com.taskly.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Thông tin tài liệu OpenAPI và cách gửi access JWT (header Authorization: Bearer). */
@Configuration(proxyBeanMethods = false)
@OpenAPIDefinition(info = @Info(title = "Taskly API", version = "v1", description = "REST API cho app Taskly"))
@SecurityScheme(name = OpenApiConfig.BEARER_AUTH, type = SecuritySchemeType.HTTP, scheme = "bearer",
        bearerFormat = "JWT")
public class OpenApiConfig {

    public static final String BEARER_AUTH = "bearerAuth";

    /**
     * Lỗi trả về là ProblemDetail (application/problem+json) nhưng không controller nào khai nó làm kiểu trả về nên
     * springdoc không tự sinh schema. Khai tay để mobile (types.gen.ts) có kiểu lỗi với `code`, `requestId`, `errors`.
     */
    @Bean
    OpenApiCustomizer problemDetailSchemaCustomizer() {
        return openApi -> {
            Schema<?> errorItem = new ObjectSchema()
                    .addProperty("field", new StringSchema())
                    .addProperty("message", new StringSchema());
            errorItem.setRequired(java.util.List.of("field", "message"));
            Schema<?> problem = new ObjectSchema()
                    .description("Lỗi chuẩn RFC 9457; `code` chỉ có ở tình huống app cần phân biệt, `errors` chỉ có khi 400 validation")
                    .addProperty("type", new StringSchema())
                    .addProperty("title", new StringSchema())
                    .addProperty("status", new IntegerSchema())
                    .addProperty("detail", new StringSchema())
                    .addProperty("instance", new StringSchema())
                    .addProperty("code", new StringSchema().description("version-conflict, project-archived, limit-reached, id-conflict, ..."))
                    .addProperty("requestId", new StringSchema())
                    .addProperty("errors", new ArraySchema().items(errorItem));
            problem.setRequired(java.util.List.of("status", "detail"));
            openApi.getComponents().addSchemas("ProblemDetail", problem);
        };
    }
}
