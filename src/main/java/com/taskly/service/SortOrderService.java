package com.taskly.service;

import com.taskly.common.error.ApiException;
import com.taskly.common.error.ProblemTexts;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Sắp xếp thủ công "đặt X ngay sau Y" (mục 5.10): sort_order BIGINT bước 1024, lấy điểm giữa; hết khoảng trống
 * thì đánh số lại cả vùng chứa. Logic THUẦN (không DB): caller nạp vùng chứa, gọi hàm, rồi ghi kết quả trong cùng
 * transaction (dự án / section: qua entity; TASK: native UPDATE qua TaskService.writeSortOrder(s) — không tăng version,
 * spec 5.6).
 */
@Component
public class SortOrderService {

    public static final long STEP = 1024;

    /** Một phần tử của vùng chứa. */
    public record Item(UUID id, long sortOrder) {
    }

    /** sortOrder = vị trí mới của phần tử được đặt; renumbered (nếu khác rỗng) = sort_order mới của MỌI phần tử khác. */
    public record Placement(long sortOrder, Map<UUID, Long> renumbered) {

        public boolean renumbers() {
            return !renumbered.isEmpty();
        }
    }

    /**
     * siblings: vùng chứa đã sắp tăng dần theo (sortOrder, id), KHÔNG gồm phần tử đang đặt.
     * afterId null = lên đầu; afterId không thuộc vùng chứa → 400.
     */
    public Placement placeAfter(List<Item> siblings, UUID afterId) {
        int index = -1;
        if (afterId != null) {
            for (int i = 0; i < siblings.size(); i++) {
                if (siblings.get(i).id().equals(afterId)) {
                    index = i;
                    break;
                }
            }
            if (index < 0) {
                throw ApiException.badRequest(ProblemTexts.AFTER_ID_NOT_IN_CONTAINER);
            }
        }
        long previous = index < 0 ? 0 : siblings.get(index).sortOrder();
        long next = index + 1 < siblings.size() ? siblings.get(index + 1).sortOrder() : previous + 2 * STEP;
        if (next - previous >= 2) {
            return new Placement(previous + (next - previous) / 2, Map.of());
        }
        return renumber(siblings, index + 1);
    }

    /** Thêm vào cuối vùng chứa. */
    public Placement placeLast(List<Item> siblings) {
        return placeAfter(siblings, siblings.isEmpty() ? null : siblings.get(siblings.size() - 1).id());
    }

    /**
     * Cho DTO tạo / chuyển có `Optional<UUID> afterId` (spec 3.1, I3): afterId == null (khoá VẮNG) → CUỐI vùng chứa;
     * Optional.empty() ("afterId": null) → ĐẦU vùng chứa; có giá trị → ngay sau phần tử đó (không thuộc vùng chứa → 400).
     */
    public Placement placeAt(List<Item> siblings, Optional<UUID> afterId) {
        if (afterId == null) {
            return placeLast(siblings);
        }
        return placeAfter(siblings, afterId.orElse(null));
    }

    private Placement renumber(List<Item> siblings, int insertAt) {
        Map<UUID, Long> renumbered = new LinkedHashMap<>();
        long position = 0;
        long placed = 0;
        for (int i = 0; i <= siblings.size(); i++) {
            if (i == insertAt) {
                placed = ++position * STEP;
            }
            if (i < siblings.size()) {
                renumbered.put(siblings.get(i).id(), ++position * STEP);
            }
        }
        return new Placement(placed, renumbered);
    }
}
