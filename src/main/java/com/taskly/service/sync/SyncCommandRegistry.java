package com.taskly.service.sync;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.stereotype.Component;

/** Bảng đăng ký handler theo loại lệnh: gom MỌI bean SyncCommandHandler (plan M9 mục 8.3). Trùng type → lỗi khởi động. */
@Component
public class SyncCommandRegistry {

    private final Map<String, SyncCommandHandler> handlers = new HashMap<>();

    public SyncCommandRegistry(List<SyncCommandHandler> beans) {
        for (SyncCommandHandler handler : beans) {
            SyncCommandHandler previous = handlers.putIfAbsent(handler.type(), handler);
            if (previous != null) {
                throw new IllegalStateException("Trùng loại lệnh đồng bộ: " + handler.type());
            }
        }
    }

    public Optional<SyncCommandHandler> find(String type) {
        return Optional.ofNullable(handlers.get(type));
    }

    public Set<String> types() {
        return new TreeSet<>(handlers.keySet());
    }
}
