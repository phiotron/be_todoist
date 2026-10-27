package com.taskly.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.taskly.common.error.ApiException;
import com.taskly.service.SubtaskTreeService.Node;
import com.taskly.service.SubtaskTreeService.Tree;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Cây mẫu:  r ─┬─ a ─── a1 ─── a11
 *              └─ b (đã xong)
 *           s (task gốc khác)
 */
class SubtaskTreeServiceTest {

    private static final Instant DONE = Instant.parse("2026-10-20T08:00:00Z");
    private static final Instant DONE_LATER = Instant.parse("2026-10-21T08:00:00Z");

    private final SubtaskTreeService service = new SubtaskTreeService();
    private final UUID r = UUID.randomUUID();
    private final UUID a = UUID.randomUUID();
    private final UUID a1 = UUID.randomUUID();
    private final UUID a11 = UUID.randomUUID();
    private final UUID b = UUID.randomUUID();
    private final UUID s = UUID.randomUUID();

    private Tree tree() {
        return service.tree(List.of(
                new Node(r, null, 1024, null),
                new Node(b, r, 2048, DONE),
                new Node(a, r, 1024, null),
                new Node(a1, a, 1024, null),
                new Node(a11, a1, 1024, null),
                new Node(s, null, 2048, null)));
    }

    private static void assertBadRequest(Runnable action, String detail) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class, ex -> {
            assertThat(ex.getStatusCode().value()).isEqualTo(400);
            assertThat(ex.getBody().getDetail()).isEqualTo(detail);
        });
    }

    @Test
    void depthAndHeight() {
        Tree tree = tree();
        assertThat(service.depth(tree, r)).isEqualTo(1);
        assertThat(service.depth(tree, a11)).isEqualTo(4);
        assertThat(service.height(tree, r)).isEqualTo(4);
        assertThat(service.height(tree, b)).isEqualTo(1);
    }

    @Test
    void descendantsInDisplayOrder_andAncestorsNearestFirst() {
        Tree tree = tree();
        assertThat(service.descendants(tree, r)).containsExactly(a, a1, a11, b);
        assertThat(service.ancestors(tree, a11)).containsExactly(a1, a, r);
    }

    @Test
    void newSubtaskUnderLevel3_isAllowed_underLevel4_isTooDeep() {
        Tree tree = tree();
        assertThatCode(() -> service.checkCanAttach(tree, null, a1)).doesNotThrowAnyException();
        assertBadRequest(() -> service.checkCanAttach(tree, null, a11), "Công việc con tối đa 4 cấp");
    }

    @Test
    void movingSubtreeCountsItsHeight() {
        Tree tree = tree();
        // a (cao 3) dưới s (cấp 1) → sâu nhất cấp 4: được
        assertThatCode(() -> service.checkCanAttach(tree, a, s)).doesNotThrowAnyException();
        // a (cao 3) dưới b (cấp 2) → cấp 5: quá sâu (kiểm trước "cha đã xong")
        assertBadRequest(() -> service.checkCanAttach(tree, a, b), "Công việc con tối đa 4 cấp");
    }

    @Test
    void attachingUnderItselfOrDescendant_isCycle() {
        Tree tree = tree();
        assertBadRequest(() -> service.checkCanAttach(tree, a, a),
                "Không thể đặt công việc làm con của chính nó hoặc của con cháu nó");
        assertBadRequest(() -> service.checkCanAttach(tree, r, a11),
                "Không thể đặt công việc làm con của chính nó hoặc của con cháu nó");
    }

    @Test
    void attachingUnderCompletedParent_is409ParentCompleted() {
        Tree tree = tree();
        assertThatThrownBy(() -> service.checkCanAttach(tree, s, b)).isInstanceOfSatisfying(ApiException.class, ex -> {
            assertThat(ex.getStatusCode().value()).isEqualTo(409);
            assertThat(ex.getBody().getDetail()).isEqualTo("Công việc cha đã hoàn thành");
            assertThat(ex.getBody().getProperties()).containsEntry("code", "parent-completed");
        });
        assertThatThrownBy(() -> service.checkCanAttach(tree, null, b)).isInstanceOf(ApiException.class);
    }

    @Test
    void closeCollectsTaskAndOpenDescendants() {
        assertThat(service.idsToClose(tree(), r)).containsExactly(r, a, a1, a11);
    }

    @Test
    void reopenCollectsTaskAndCompletedAncestors() {
        Tree tree = service.tree(List.of(
                new Node(r, null, 1024, DONE),
                new Node(a, r, 1024, null),
                new Node(a1, a, 1024, DONE)));
        assertThat(service.idsToReopen(tree, a1)).containsExactly(a1, r);
    }

    @Test
    void undoReopensDescendantsOfTheSameCompletionBatch_only_notByEqualCompletedAt() {
        UUID batch = UUID.randomUUID();
        UUID otherBatch = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        // r, a, a1 cùng batch; b xong từ trước (DONE, không batch); c CÙNG completed_at (DONE_LATER) nhưng batch khác
        // → Undo không đụng b, c (spec 5.1: không dựa vào completed_at trùng nhau).
        Tree tree = service.tree(List.of(
                new Node(r, null, 1024, DONE_LATER, batch),
                new Node(a, r, 1024, DONE_LATER, batch),
                new Node(a1, a, 1024, DONE_LATER, batch),
                new Node(b, r, 2048, DONE, null),
                new Node(c, r, 3072, DONE_LATER, otherBatch)));
        assertThat(service.idsToUndo(tree, r)).containsExactly(r, a, a1);
        assertThat(service.idsToUndo(tree(), s)).isEmpty(); // task đang mở → không đổi gì
    }

    @Test
    void undoOfATaskWithoutBatch_reopensOnlyItselfAndCompletedAncestors() {
        UUID batch = UUID.randomUUID();
        Tree tree = service.tree(List.of(
                new Node(r, null, 1024, DONE_LATER, batch),
                new Node(a, r, 1024, DONE_LATER, null),
                new Node(a1, a, 1024, DONE_LATER, null)));
        assertThat(service.idsToUndo(tree, a)).containsExactly(a, r);
    }

    @Test
    void subtreeToCopy_takesOnlyOpenDescendants_andSkipsWholeCompletedBranch() {
        // r ─┬─ a (đã xong) ─── a1 (đang mở, nhưng dưới con đã xong → không chép)
        //    └─ c ─── c1
        UUID c = UUID.randomUUID();
        UUID c1 = UUID.randomUUID();
        Tree tree = service.tree(List.of(
                new Node(r, null, 1024, null),
                new Node(a, r, 1024, DONE),
                new Node(a1, a, 1024, null),
                new Node(c, r, 2048, null),
                new Node(c1, c, 1024, null)));
        assertThat(service.subtreeToCopy(tree, r).stream().map(Node::id)).containsExactly(r, c, c1);
    }

    @Test
    void withoutDescendants_dropsIdsWhoseAncestorIsAlsoListed_keepingOrder() {
        assertThat(service.withoutDescendants(tree(), List.of(a1, s, r, a11))).containsExactly(s, r);
    }
}
