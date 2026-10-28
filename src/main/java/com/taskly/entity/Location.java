package com.taskly.entity;

import com.taskly.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * Địa điểm đã lưu cho nhắc theo vị trí (bảng locations, V16 — M12). Thuộc người tạo (user_id). Không sửa được (sửa =
 * xoá + tạo, như nhắc) nên mọi cột updatable = false. Xoá địa điểm → DB xoá các nhắc LOCATION dùng nó (FK cascade).
 */
@Entity
@Table(name = "locations")
public class Location extends BaseEntity {

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "name", nullable = false, length = 100, updatable = false)
    private String name;

    @Column(name = "latitude", nullable = false, updatable = false)
    private double latitude;

    @Column(name = "longitude", nullable = false, updatable = false)
    private double longitude;

    /** Bán kính vùng nhắc, mét (100–5000). */
    @Column(name = "radius_m", nullable = false, updatable = false)
    private int radius;

    protected Location() {
        // cho JPA
    }

    public Location(UUID userId, String name, double latitude, double longitude, int radius) {
        this.userId = userId;
        this.name = name;
        this.latitude = latitude;
        this.longitude = longitude;
        this.radius = radius;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getName() {
        return name;
    }

    public double getLatitude() {
        return latitude;
    }

    public double getLongitude() {
        return longitude;
    }

    public int getRadius() {
        return radius;
    }
}
