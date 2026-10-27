package com.taskly.service;

import com.taskly.common.error.ApiException;
import com.taskly.common.error.ProblemTexts;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Logic THUẦN trên cây dự án con (spec 5.7, 10.1): tối đa 3 cấp, chặn vòng lặp, thứ tự hiển thị (cha trước con,
 * anh em theo sort_order rồi id). Caller nạp node (id, parentId, sortOrder) của workspace rồi gọi. Inbox không
 * đưa vào cây (không làm cha / con — ProjectService kiểm trước).
 */
@Component
public class ProjectTreeService {

    public static final int MAX_DEPTH = 3;

    private static final Comparator<Node> ORDER = Comparator.comparingLong(Node::sortOrder).thenComparing(Node::id);

    public record Node(UUID id, UUID parentId, long sortOrder) {
    }

    /** byId: mọi node; children: con trực tiếp đã sắp; roots: node không cha hoặc cha không nằm trong tập. */
    public record Tree(Map<UUID, Node> byId, Map<UUID, List<Node>> children, List<Node> roots) {

        public Node node(UUID id) {
            Node node = byId.get(id);
            if (node == null) {
                throw new IllegalArgumentException("Không có dự án " + id);
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
        List<Node> roots = new ArrayList<>();
        for (Node node : nodes) {
            if (node.parentId() != null && byId.containsKey(node.parentId())) {
                children.computeIfAbsent(node.parentId(), key -> new ArrayList<>()).add(node);
            } else {
                roots.add(node);
            }
        }
        children.values().forEach(list -> list.sort(ORDER));
        roots.sort(ORDER);
        return new Tree(Map.copyOf(byId), Map.copyOf(children), List.copyOf(roots));
    }

    /** Cấp: dự án gốc = 1. */
    public int depth(Tree tree, UUID id) {
        int depth = 1;
        Node current = tree.node(id);
        while (current.parentId() != null && tree.byId().containsKey(current.parentId()) && depth <= tree.byId().size()) {
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

    /** Dự án con cháu theo thứ tự hiển thị, không gồm node. */
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

    /** Thứ tự hiển thị của cả cây: duyệt sâu từ các gốc. */
    public List<UUID> displayOrder(Tree tree) {
        List<UUID> result = new ArrayList<>();
        for (Node root : tree.roots()) {
            result.add(root.id());
            collect(tree, root.id(), result);
        }
        return result;
    }

    /**
     * Gắn movingId (null = dự án mới) dưới newParentId (null = thành gốc, luôn được về độ sâu).
     * Vòng lặp → 400; độ sâu sau khi gắn (tính cả chiều cao cây con) > 3 → 400.
     */
    public void checkCanAttach(Tree tree, UUID movingId, UUID newParentId) {
        if (newParentId == null) {
            return;
        }
        if (movingId != null && (movingId.equals(newParentId) || descendants(tree, movingId).contains(newParentId))) {
            throw ApiException.badRequest(ProblemTexts.PROJECT_CYCLE);
        }
        int subtreeHeight = movingId == null ? 1 : height(tree, movingId);
        if (depth(tree, newParentId) + subtreeHeight > MAX_DEPTH) {
            throw ApiException.badRequest(ProblemTexts.PROJECT_TOO_DEEP);
        }
    }
}
