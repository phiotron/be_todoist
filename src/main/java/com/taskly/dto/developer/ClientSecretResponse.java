package com.taskly.dto.developer;

import io.swagger.v3.oas.annotations.media.Schema;

public record ClientSecretResponse(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) String clientSecret) {
}
