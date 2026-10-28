package com.taskly.dto.location;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** POST /api/v1/locations (M12). name được cắt khoảng trắng hai đầu TRƯỚC khi kiểm (compact constructor). */
public record CreateLocationRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 100, example = "Văn phòng")
        @NotBlank(message = "{validation.location.name.required}")
        @Size(max = 100, message = "{validation.location.name.size}")
        String name,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "-90", maximum = "90", example = "10.7769")
        @NotNull(message = "{validation.location.latitude.range}")
        @DecimalMin(value = "-90", message = "{validation.location.latitude.range}")
        @DecimalMax(value = "90", message = "{validation.location.latitude.range}")
        Double latitude,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "-180", maximum = "180", example = "106.7009")
        @NotNull(message = "{validation.location.longitude.range}")
        @DecimalMin(value = "-180", message = "{validation.location.longitude.range}")
        @DecimalMax(value = "180", message = "{validation.location.longitude.range}")
        Double longitude,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "100", maximum = "5000", example = "200",
                description = "Bán kính vùng nhắc, mét")
        @NotNull(message = "{validation.location.radius.range}")
        @Min(value = 100, message = "{validation.location.radius.range}")
        @Max(value = 5000, message = "{validation.location.radius.range}")
        Integer radius) {

    public CreateLocationRequest {
        name = name == null ? null : name.strip();
    }
}
