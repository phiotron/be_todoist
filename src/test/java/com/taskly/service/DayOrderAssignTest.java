package com.taskly.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.taskly.service.DayOrderService.Slot;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** PERM × DND: DayOrderService.assign chỉ đánh số task sửa được, task không sửa được (mốc) không có trong kết quả. */
class DayOrderAssignTest {

    private static final UUID A = UUID.randomUUID();
    private static final UUID B = UUID.randomUUID();
    private static final UUID C = UUID.randomUUID();
    private static final UUID D = UUID.randomUUID();

    private static Slot edit(UUID id, Long order) {
        return new Slot(id, true, order);
    }

    private static Slot fixed(UUID id, Long order) {
        return new Slot(id, false, order);
    }

    @Test
    void allEditable_renumbersWholeDayLikeBefore() {
        assertThat(DayOrderService.assign(List.of(edit(A, null), edit(B, 5L), edit(C, null))))
                .containsExactly(Map.entry(A, 1024L), Map.entry(B, 2048L), Map.entry(C, 3072L));
    }

    @Test
    void editableBetweenMarks_fillTheGap_marksNotWritten() {
        Map<UUID, Long> orders = DayOrderService.assign(List.of(edit(A, null), fixed(B, 1024L), edit(C, null),
                edit(D, null), fixed(UUID.randomUUID(), 2048L)));
        assertThat(orders).containsOnlyKeys(A, C, D);
        assertThat(orders.get(A)).isLessThan(1024L);
        assertThat(orders.get(C)).isGreaterThan(1024L).isLessThan(orders.get(D));
        assertThat(orders.get(D)).isLessThan(2048L);
    }

    @Test
    void afterLastMark_stepsForward_afterUnorderedMark_staysUnordered() {
        Map<UUID, Long> orders = DayOrderService.assign(List.of(fixed(A, 1024L), edit(B, null), fixed(C, null),
                edit(D, 4096L)));
        assertThat(orders.get(B)).isEqualTo(2048L);
        assertThat(orders).containsKey(D);
        assertThat(orders.get(D)).isNull();
    }

    @Test
    void noRoomBetweenMarks_tiesWithLowerMark() {
        Map<UUID, Long> orders = DayOrderService.assign(Arrays.asList(fixed(A, 10L), edit(B, null), edit(C, null),
                fixed(D, 11L)));
        assertThat(orders.get(B)).isEqualTo(10L);
        assertThat(orders.get(C)).isEqualTo(10L);
    }
}
