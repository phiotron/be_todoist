package com.taskly.repository.listing;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Xếp task gốc + cây con thành danh sách phẳng: gốc theo thứ tự đã sắp, mỗi gốc kèm con cháu duyệt sâu trước,
 * anh em theo sort_order (hoà → id) — cùng thứ tự "danh sách theo dự án" của M1. Thuần, độ sâu ≤ 4.
 */
public final class SubtreeOrder {

    public record Node(UUID id, UUID parentId, long sortOrder) {
    }

    private SubtreeOrder() {
    }

    public static List<UUID> arrange(List<UUID> rootIds, Collection<Node> descendants) {
        Map<UUID, List<Node>> children = new HashMap<>();
        for (Node node : descendants) {
            children.computeIfAbsent(node.parentId(), parent -> new ArrayList<>()).add(node);
        }
        children.values().forEach(list -> list.sort(Comparator.comparingLong(Node::sortOrder).thenComparing(Node::id)));
        List<UUID> out = new ArrayList<>();
        for (UUID root : rootIds) {
            visit(root, children, out);
        }
        return out;
    }

    private static void visit(UUID id, Map<UUID, List<Node>> children, List<UUID> out) {
        out.add(id);
        for (Node child : children.getOrDefault(id, List.of())) {
            visit(child.id(), children, out);
        }
    }
}
