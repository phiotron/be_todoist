package com.taskly.service;

import com.taskly.common.error.ApiException;
import com.taskly.common.error.ErrorMessage;
import com.taskly.common.error.ProblemCodes;
import com.taskly.entity.Task;
import com.taskly.repository.TaskListingRepository;
import com.taskly.repository.TaskListingRepository.DayOrderRow;
import com.taskly.security.AccessPolicy;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * DND — reorder type TASK_DAY: thứ tự trong NGÀY (day order kiểu Todoist) cho Today / Upcoming khi sort = date.
 * Vùng chứa = task mở, cùng due_day, dự án chưa lưu trữ trong phạm vi đọc của user (khác dự án vẫn chung một ngày).
 * Đặt id ngay sau afterId (null = đầu ngày) trong thứ tự đang hiện rồi đánh số lại các task user SỬA được (PERM:
 * task dự án nhóm mà user là Thành viên / Khách giữ nguyên day_order, làm mốc — xem {@link #assign}).
 * Native UPDATE: không tăng version (như reorder). day_order dùng chung cho mọi thành viên của dự án chia sẻ.
 */
@Service
@Transactional
public class DayOrderService {

    static final long STEP = 1024;

    private final TaskService taskService;
    private final TaskListingRepository listing;
    private final AccessPolicy accessPolicy;
    private final UsageLimits usageLimits;

    public DayOrderService(TaskService taskService, TaskListingRepository listing, AccessPolicy accessPolicy,
                           UsageLimits usageLimits) {
        this.taskService = taskService;
        this.listing = listing;
        this.accessPolicy = accessPolicy;
        this.usageLimits = usageLimits;
    }

    public long reorder(UUID userId, UUID taskId, UUID afterId) {
        Task task = taskService.lockAndRecheck(taskService.requireWritable(userId, taskId));
        if (task.getCompletedAt() != null) {
            throw ApiException.conflict(ErrorMessage.TASK_COMPLETED).withCode(ProblemCodes.TASK_COMPLETED);
        }
        LocalDate day = TaskDueRules.dueDay(task.getDueDate(), task.getDueDatetime());
        if (day == null) {
            throw ApiException.badRequest(ErrorMessage.DAY_ORDER_REQUIRES_DUE);
        }
        List<DayOrderRow> rows = listing.dayOrderRows(accessPolicy.listScope(userId), day);
        Set<UUID> projectIds = new HashSet<>();
        rows.forEach(row -> projectIds.add(row.projectId()));
        Map<UUID, AccessPolicy.ProjectAccessInfo> access = accessPolicy.projectAccess(userId, projectIds);
        List<Slot> ordered = new ArrayList<>();
        for (DayOrderRow row : rows) {
            if (!row.id().equals(taskId)) {
                AccessPolicy.ProjectAccessInfo info = access.get(row.projectId());
                ordered.add(new Slot(row.id(), info != null && info.canEdit(), row.dayOrder()));
            }
        }
        int at = 0;
        if (afterId != null) {
            int index = indexOf(ordered, afterId);
            if (index < 0) {
                throw ApiException.badRequest(ErrorMessage.AFTER_ID_NOT_IN_CONTAINER);
            }
            at = index + 1;
        }
        ordered.add(at, new Slot(taskId, true, null)); // requireWritable ở trên: task được kéo luôn sửa được
        Map<UUID, Long> orders = assign(ordered);
        List<UUID> toLock = new ArrayList<>(orders.keySet());
        usageLimits.lockTasks(toLock); // hàng task, id tăng dần (quy tắc khoá) — sau khoá P của lockAndRecheck
        listing.writeDayOrders(orders, day);
        Long order = orders.get(taskId);
        return order == null ? Long.MAX_VALUE : order;
    }

    /** Một task trong thứ tự mong muốn của ngày: editable = user được SỬA (PERM canEdit); dayOrder = giá trị hiện tại. */
    record Slot(UUID id, boolean editable, Long dayOrder) {
    }

    private static int indexOf(List<Slot> slots, UUID id) {
        for (int i = 0; i < slots.size(); i++) {
            if (slots.get(i).id().equals(id)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * PERM × DND: chỉ đánh số task user SỬA được; task không sửa được (dự án nhóm với Thành viên / Khách) giữ nguyên
     * day_order và làm MỐC. Mỗi đoạn task sửa được nằm giữa hai mốc liên tiếp được chia đều vào khoảng (trước, sau):
     * không mốc phía trước → lùi theo STEP từ mốc sau (có thể âm); không mốc phía sau → tiến theo STEP. Mốc "chưa xếp"
     * (null = cuối ngày) không có giá trị nào đứng sau được → đoạn phía sau nó cũng để null (theo khoá cũ). Khoảng hẹp
     * hơn số task → bằng mốc trước (hoà, khoá cũ phân định). Không mốc nào: đánh số lại cả ngày như trước (bước STEP).
     * Trả map id → giá trị (null = bỏ thứ tự) cho MỌI task sửa được của ngày.
     */
    static Map<UUID, Long> assign(List<Slot> ordered) {
        Map<UUID, Long> orders = new LinkedHashMap<>();
        Long lower = null;          // giá trị mốc gần nhất phía trước (null = chưa có mốc)
        boolean afterUnordered = false; // đã đi qua một mốc "chưa xếp"
        int i = 0;
        while (i < ordered.size()) {
            Slot slot = ordered.get(i);
            if (!slot.editable()) {
                if (slot.dayOrder() == null) {
                    afterUnordered = true;
                } else {
                    lower = slot.dayOrder();
                }
                i++;
                continue;
            }
            int end = i;
            while (end < ordered.size() && ordered.get(end).editable()) {
                end++;
            }
            int count = end - i;
            // mốc ngay sau đoạn (null = hết ngày hoặc mốc "chưa xếp": đoạn đứng trước nó với giá trị hữu hạn là đúng)
            Long upper = end < ordered.size() ? ordered.get(end).dayOrder() : null;
            for (int k = 0; k < count; k++) {
                Long value;
                if (afterUnordered) {
                    value = null;
                } else if (lower == null && upper == null) {
                    value = (k + 1) * STEP;
                } else if (lower == null) {
                    value = upper - (count - k) * STEP;
                } else if (upper == null) {
                    value = lower + (k + 1) * STEP;
                } else {
                    long gap = (upper - lower) / (count + 1);
                    value = lower + gap * (k + 1);
                }
                orders.put(ordered.get(i + k).id(), value);
            }
            i = end;
        }
        return orders;
    }
}
