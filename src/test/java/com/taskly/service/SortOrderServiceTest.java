package com.taskly.service;

import com.taskly.common.error.ErrorMessage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.taskly.common.error.ApiException;
import com.taskly.service.SortOrderService.Item;
import com.taskly.service.SortOrderService.Placement;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SortOrderServiceTest {

    private final SortOrderService service = new SortOrderService();
    private final UUID a = UUID.randomUUID();
    private final UUID b = UUID.randomUUID();
    private final UUID c = UUID.randomUUID();

    @Test
    void emptyContainer_startsAtStep() {
        Placement placement = service.placeLast(List.of());
        assertThat(placement.sortOrder()).isEqualTo(1024);
        assertThat(placement.renumbers()).isFalse();
    }

    @Test
    void placeLast_addsOneStepAfterLast() {
        Placement placement = service.placeLast(List.of(new Item(a, 1024), new Item(b, 2048)));
        assertThat(placement.sortOrder()).isEqualTo(3072);
    }

    @Test
    void afterIdInMiddle_takesMidpoint() {
        Placement placement = service.placeAfter(List.of(new Item(a, 1024), new Item(b, 2048)), a);
        assertThat(placement.sortOrder()).isEqualTo(1536);
        assertThat(placement.renumbers()).isFalse();
    }

    @Test
    void nullAfterId_goesToTop() {
        Placement placement = service.placeAfter(List.of(new Item(a, 1024), new Item(b, 2048)), null);
        assertThat(placement.sortOrder()).isEqualTo(512);
    }

    @Test
    void noGap_renumbersWholeContainerInOrder() {
        Placement placement = service.placeAfter(List.of(new Item(a, 1024), new Item(b, 1025), new Item(c, 4096)), a);

        assertThat(placement.renumbers()).isTrue();
        assertThat(placement.renumbered()).containsEntry(a, 1024L).containsEntry(b, 3072L).containsEntry(c, 4096L);
        assertThat(placement.sortOrder()).isEqualTo(2048);
    }

    @Test
    void noGapAtTop_renumbers() {
        Placement placement = service.placeAfter(List.of(new Item(a, 1), new Item(b, 2)), null);

        assertThat(placement.sortOrder()).isEqualTo(1024);
        assertThat(placement.renumbered()).containsEntry(a, 2048L).containsEntry(b, 3072L);
    }

    @Test
    void afterIdOutsideContainer_is400() {
        assertThatThrownBy(() -> service.placeAfter(List.of(new Item(a, 1024)), UUID.randomUUID()))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatusCode().value()).isEqualTo(400);
                    assertThat(ex.getErrorMessage()).isEqualTo(ErrorMessage.AFTER_ID_NOT_IN_CONTAINER);
                });
    }

    // ---- placeAt (spec 3.1, I3): afterId VẮNG ≠ null tường minh ----

    @Test
    void placeAt_absentKey_goesToEnd() {
        Placement placement = service.placeAt(List.of(new Item(a, 1024), new Item(b, 2048)), null); // khoá vắng
        assertThat(placement.sortOrder()).isEqualTo(3072);
    }

    @Test
    void placeAt_explicitNull_goesToTop() {
        Placement placement = service.placeAt(List.of(new Item(a, 1024), new Item(b, 2048)), Optional.empty()); // "afterId": null
        assertThat(placement.sortOrder()).isEqualTo(512);
    }

    @Test
    void placeAt_presentId_goesAfterThatItem_andOutsideContainerIs400() {
        assertThat(service.placeAt(List.of(new Item(a, 1024), new Item(b, 2048)), Optional.of(a)).sortOrder())
                .isEqualTo(1536);
        assertThatThrownBy(() -> service.placeAt(List.of(new Item(a, 1024)), Optional.of(UUID.randomUUID())))
                .isInstanceOf(ApiException.class);
    }
}
