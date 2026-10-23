package com.taskly.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.context.annotation.Configuration;

/** Thông tin tài liệu OpenAPI và cách gửi access JWT (header Authorization: Bearer). */
@Configuration(proxyBeanMethods = false)
@OpenAPIDefinition(info = @Info(title = "Taskly API", version = "v1", description = "REST API cho app Taskly"))
@SecurityScheme(name = OpenApiConfig.BEARER_AUTH, type = SecuritySchemeType.HTTP, scheme = "bearer",
        bearerFormat = "JWT")
public class OpenApiConfig {

    public static final String BEARER_AUTH = "bearerAuth";
}
