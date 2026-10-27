package com.taskly.repository.listing;

import static org.assertj.core.api.Assertions.assertThat;

import com.taskly.repository.listing.SubtreeOrder.Node;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SubtreeOrderTest {

    private static UUID id(int n) {
        return UUID.fromString("00000000-0000-0000-0000-00000000000" + n);
    }

    @Test
    void rootsKeepTheirOrder_childrenFollowDepthFirstBySortOrder() {
        List<Node> nodes = List.of(
                new Node(id(3), id(1), 2048),   // con của 1, sau
                new Node(id(2), id(1), 1024),   // con của 1, trước
                new Node(id(4), id(2), 1024),   // cháu (con của 2)
                new Node(id(6), id(5), 1024));  // con của gốc 5

        assertThat(SubtreeOrder.arrange(List.of(id(5), id(1)), nodes))
                .containsExactly(id(5), id(6), id(1), id(2), id(4), id(3));
    }

    @Test
    void equalSortOrdersFallBackToId_andUnrelatedNodesAreIgnored() {
        List<Node> nodes = List.of(
                new Node(id(3), id(1), 1024),
                new Node(id(2), id(1), 1024),
                new Node(id(9), id(8), 1024));   // cha 8 không thuộc gốc nào → bỏ

        assertThat(SubtreeOrder.arrange(List.of(id(1)), nodes)).containsExactly(id(1), id(2), id(3));
    }

    @Test
    void noRootsOrNoDescendants() {
        assertThat(SubtreeOrder.arrange(List.of(), List.of())).isEmpty();
        assertThat(SubtreeOrder.arrange(List.of(id(1), id(2)), List.of())).containsExactly(id(1), id(2));
    }
}
