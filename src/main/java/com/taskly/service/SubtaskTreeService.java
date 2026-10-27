package com.taskly.service;

import com.taskly.common.error.ApiException;
import com.taskly.common.error.ProblemCodes;
import com.taskly.common.error.ProblemTexts;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Logic THUẦN trên cây công việc con (spec 5.4): tối đa 4 cấp, chặn vòng lặp, không gắn dưới cha đã xong, lan
 * truyền hoàn thành / mở lại / Undo, chọn phần cây cần sao chép (chỉ con đang mở). Caller nạp node
 * (id, parentId, sortOrder, completedAt, completionBatchId) của dự án rồi gọi.
 */
@Component
public class SubtaskTreeService {

    public static final int MAX_DEPTH = 4;

    private static final Comparator<Node> DISPLAY_ORDER =
            Comparator.comparingLong(Node::sortOrder).thenComparing(Node::id);

    /** completionBatchId: căn cứ của Undo (spec 5.1, 3.1) — null khi chưa xong hoặc xong từ trước khi có batch. */
    public record Node(UUID id, UUID parentId, long sortOrder, Instant completedAt, UUID completionBatchId) {

        /** Rút gọn cho test / nơi không quan tâm batch. */
        public Node(UUID id, UUID parentId, long sortOrder, Instant completedAt) {
            this(id, parentId, sortOrder, completedAt, null);
        }

        public boolean completed() {
            return completedAt != null;
        }
    }

    /** byId: mọi node; children: con trực tiếp đã sắp theo thứ tự hiển thị. */
    public record Tree(Map<UUID, Node> byId, Map<UUID, List<Node>> children) {

        public Node node(UUID id) {
            Node node = byId.get(id);
            if (node == null) {
                throw new IllegalArgumentException("Không có node " + id);
            }
            return node;
        }

        public List<Node> childrenOf(UUID id) {
            return children.getOrDefault(id, List.of());
        }
    }

    public Tree tree(Collection<Node> nodes) {
        Map<UUID, Node> byId = new HashMap<>();
        for (Node node : nodes) {
            byId.put(node.id(), node);
        }
        Map<UUID, List<Node>> children = new HashMap<>();
        for (Node node : nodes) {
            if (node.parentId() != null && byId.containsKey(node.parentId())) {
                children.computeIfAbsent(node.parentId(), key -> new ArrayList<>()).add(node);
            }
        }
        children.values().forEach(list -> list.sort(DISPLAY_ORDER));
        return new Tree(Map.copyOf(byId), Map.copyOf(children));
    }

    /** Cấp của node: task gốc = 1. */
    public int depth(Tree tree, UUID id) {
        int depth = 1;
        Node current = tree.node(id);
        Set<UUID> seen = new HashSet<>();
        while (current.parentId() != null && tree.byId().containsKey(current.parentId())) {
            if (!seen.add(current.id())) {
                throw new IllegalStateException("Cây công việc có vòng lặp");
            }
            current = tree.node(current.parentId());
            depth++;
        }
        return depth;
    }

    /** Số cấp của cây con tính cả node: lá = 1. */
    public int height(Tree tree, UUID id) {
        int max = 0;
        for (Node child : tree.childrenOf(id)) {
            max = Math.max(max, height(tree, child.id()));
        }
        return max + 1;
    }

    /** Con cháu theo thứ tự hiển thị (duyệt sâu, cha trước con), không gồm node. */
    public List<UUID> descendants(Tree tree, UUID id) {
        List<UUID> result = new ArrayList<>();
        collect(tree, id, result);
        return result;
    }

    private void collect(Tree tree, UUID id, List<UUID> result) {
        for (Node child : tree.childrenOf(id)) {
            result.add(child.id());
            collect(tree, child.id(), result);
        }
    }

    /** Tổ tiên, gần nhất trước. */
    public List<UUID> ancestors(Tree tree, UUID id) {
        List<UUID> result = new ArrayList<>();
        Node current = tree.node(id);
        while (current.parentId() != null && tree.byId().containsKey(current.parentId())
                && !result.contains(current.parentId())) {
            result.add(current.parentId());
            current = tree.node(current.parentId());
        }
        return result;
    }

    /**
     * Kiểm tra gắn movingId (null = task mới) làm con của newParentId (null = thành task gốc, luôn được).
     * Thứ tự: vòng lặp → 400; vượt 4 cấp (tính cả chiều cao cây con đang di chuyển) → 400;
     * cha đã hoàn thành → 409 parent-completed (spec 5.4: nhờ vậy gốc đã xong luôn có cả cây con đã xong).
     */
    public void checkCanAttach(Tree tree, UUID movingId, UUID newParentId) {
        if (newParentId == null) {
            return;
        }
        if (movingId != null && (movingId.equals(newParentId) || descendants(tree, movingId).contains(newParentId))) {
            throw ApiException.badRequest(ProblemTexts.SUBTASK_CYCLE);
        }
        int subtreeHeight = movingId == null ? 1 : height(tree, movingId);
        if (depth(tree, newParentId) + subtreeHeight > MAX_DEPTH) {
            throw ApiException.badRequest(ProblemTexts.SUBTASK_TOO_DEEP);
        }
        if (tree.node(newParentId).completed()) {
            throw ApiException.conflict(ProblemTexts.PARENT_COMPLETED).withCode(ProblemCodes.PARENT_COMPLETED);
        }
    }

    /** Hoàn thành cha → cha (nếu đang mở) + mọi con cháu chưa xong (cùng completed_at, do caller ghi). */
    public List<UUID> idsToClose(Tree tree, UUID id) {
        List<UUID> result = new ArrayList<>();
        if (!tree.node(id).completed()) {
            result.add(id);
        }
        for (UUID descendant : descendants(tree, id)) {
            if (!tree.node(descendant).completed()) {
                result.add(descendant);
            }
        }
        return result;
    }

    /** Mở lại (không Undo) → task (nếu đang xong) + mọi tổ tiên đang hoàn thành; con giữ nguyên. */
    public List<UUID> idsToReopen(Tree tree, UUID id) {
        List<UUID> result = new ArrayList<>();
        if (tree.node(id).completed()) {
            result.add(id);
        }
        for (UUID ancestor : ancestors(tree, id)) {
            if (tree.node(ancestor).completed()) {
                result.add(ancestor);
            }
        }
        return result;
    }

    /**
     * Undo hoàn thành (spec 5.1, 3.1): task + mọi con cháu có CÙNG completion_batch_id với task (bị hoàn thành theo trong
     * cùng lần gọi) + mọi tổ tiên đang hoàn thành (như mở lại thường). KHÔNG dựa vào việc completed_at trùng nhau (dễ
     * vỡ). Task không có batch (null) → chỉ task + tổ tiên. Task đang mở → rỗng.
     */
    public List<UUID> idsToUndo(Tree tree, UUID id) {
        Node node = tree.node(id);
        if (!node.completed()) {
            return List.of();
        }
        List<UUID> result = new ArrayList<>();
        result.add(id);
        if (node.completionBatchId() != null) {
            for (UUID descendant : descendants(tree, id)) {
                if (node.completionBatchId().equals(tree.node(descendant).completionBatchId())) {
                    result.add(descendant);
                }
            }
        }
        for (UUID ancestor : ancestors(tree, id)) {
            if (tree.node(ancestor).completed()) {
                result.add(ancestor);
            }
        }
        return result;
    }

    /**
     * Phần cây cần sao chép (spec 5.4): node gốc rồi các con cháu ĐANG MỞ theo thứ tự hiển thị — con đã xong không
     * chép và cả nhánh dưới nó cũng không chép. Cha luôn đứng trước con.
     */
    public List<Node> subtreeToCopy(Tree tree, UUID rootId) {
        List<Node> result = new ArrayList<>();
        result.add(tree.node(rootId));
        collectOpen(tree, rootId, result);
        return result;
    }

    private void collectOpen(Tree tree, UUID id, List<Node> result) {
        for (Node child : tree.childrenOf(id)) {
            if (!child.completed()) {
                result.add(child);
                collectOpen(tree, child.id(), result);
            }
        }
    }

    /** Bỏ id có tổ tiên cũng nằm trong danh sách (cây con đi theo tổ tiên — tasks/batch MOVE / DELETE). Giữ thứ tự. */
    public List<UUID> withoutDescendants(Tree tree, List<UUID> ids) {
        Set<UUID> listed = new HashSet<>(ids);
        return ids.stream()
                .filter(id -> ancestors(tree, id).stream().noneMatch(listed::contains))
                .toList();
    }
}
