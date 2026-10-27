package com.taskly.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.taskly.common.error.ApiException;
import com.taskly.service.ProjectTreeService.Node;
import com.taskly.service.ProjectTreeService.Tree;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Cây mẫu:  A(1024) ─┬─ A1(2048) ─── A11
 *                    └─ A2(1024)
 *           B(2048)
 */
class ProjectTreeServiceTest {

    private final ProjectTreeService service = new ProjectTreeService();
    private final UUID a = UUID.randomUUID();
    private final UUID a1 = UUID.randomUUID();
    private final UUID a11 = UUID.randomUUID();
    private final UUID a2 = UUID.randomUUID();
    private final UUID b = UUID.randomUUID();

    private Tree tree() {
        return service.tree(List.of(
                new Node(b, null, 2048),
                new Node(a1, a, 2048),
                new Node(a, null, 1024),
                new Node(a11, a1, 1024),
                new Node(a2, a, 1024)));
    }

    private static void assertBadRequest(Runnable action, String detail) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class, ex -> {
            assertThat(ex.getStatusCode().value()).isEqualTo(400);
            assertThat(ex.getBody().getDetail()).isEqualTo(detail);
        });
    }

    @Test
    void depthHeightAndDescendants() {
        Tree tree = tree();
        assertThat(service.depth(tree, a)).isEqualTo(1);
        assertThat(service.depth(tree, a11)).isEqualTo(3);
        assertThat(service.height(tree, a)).isEqualTo(3);
        assertThat(service.descendants(tree, a)).containsExactly(a2, a1, a11);
    }

    @Test
    void displayOrder_isDepthFirstBySortOrder_rootsWhoseParentIsMissingCountAsRoots() {
        assertThat(service.displayOrder(tree())).containsExactly(a, a2, a1, a11, b);

        UUID orphan = UUID.randomUUID(); // cha không nằm trong tập (vd danh sách dự án lưu trữ)
        Tree partial = service.tree(List.of(new Node(orphan, UUID.randomUUID(), 10), new Node(b, null, 2048)));
        assertThat(service.displayOrder(partial)).containsExactly(orphan, b);
    }

    @Test
    void attach_respectsMaxThreeLevels_includingMovedSubtreeHeight() {
        Tree tree = tree();
        assertThatCode(() -> service.checkCanAttach(tree, null, a1)).doesNotThrowAnyException(); // mới ở cấp 3
        assertBadRequest(() -> service.checkCanAttach(tree, null, a11), "Dự án con tối đa 3 cấp");
        assertThatCode(() -> service.checkCanAttach(tree, b, a1)).doesNotThrowAnyException();
        assertThatCode(() -> service.checkCanAttach(tree, a1, b)).doesNotThrowAnyException(); // cao 2 dưới cấp 1 → 3
        assertBadRequest(() -> service.checkCanAttach(tree, a1, a2), "Dự án con tối đa 3 cấp"); // cao 2 dưới cấp 2 → 4
        assertThatCode(() -> service.checkCanAttach(tree, a1, null)).doesNotThrowAnyException(); // thành gốc
    }

    @Test
    void attachUnderItselfOrDescendant_isCycle() {
        Tree tree = tree();
        assertBadRequest(() -> service.checkCanAttach(tree, a, a),
                "Không thể đặt dự án làm con của chính nó hoặc của dự án con của nó");
        assertBadRequest(() -> service.checkCanAttach(tree, a, a11),
                "Không thể đặt dự án làm con của chính nó hoặc của dự án con của nó");
    }
}
