package com.taskly.service.sync;

import java.util.function.Function;

/**
 * ĐIỂM CẮM của POST sync (plan M9 mục 8.3): mỗi loại lệnh là một bean SyncCommandHandler; SyncCommandRegistry tự gom mọi
 * bean (trùng type → lỗi khởi động). Mốc sau thêm lệnh = thêm bean, KHÔNG sửa SyncService / registry.
 * handle(...) chạy TRONG transaction riêng của lệnh (SyncCommandExecutor) và phải gọi lại service của mốc (quyền
 * AccessPolicy, giới hạn UsageLimits, khoá chống đua y như API REST). Lỗi → ném ApiException như REST.
 */
public interface SyncCommandHandler {

    /** Tên lệnh snake_case, vd "task_add". */
    String type();

    /**
     * true = lệnh tạo bản ghi: executor đặt args.id từ tempId (UUID dùng luôn, khác thì sinh mới) khi args chưa có id,
     * ghi tempIdMapping và chạy lại MỘT lần khi đua khoá chính (như TaskController.create).
     */
    default boolean creates() {
        return false;
    }

    SyncCommandOutcome handle(SyncCommandContext context);

    /** Handler không tạo bản ghi, viết bằng lambda. */
    static SyncCommandHandler of(String type, Function<SyncCommandContext, SyncCommandOutcome> body) {
        return new Simple(type, false, body);
    }

    /** Handler tạo bản ghi (xem creates()). */
    static SyncCommandHandler creating(String type, Function<SyncCommandContext, SyncCommandOutcome> body) {
        return new Simple(type, true, body);
    }

    record Simple(String type, boolean creates, Function<SyncCommandContext, SyncCommandOutcome> body)
            implements SyncCommandHandler {

        @Override
        public SyncCommandOutcome handle(SyncCommandContext context) {
            return body.apply(context);
        }
    }
}
