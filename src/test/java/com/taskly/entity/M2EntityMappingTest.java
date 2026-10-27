package com.taskly.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.taskly.IntegrationTestBase;
import com.taskly.repository.FilterRepository;
import com.taskly.repository.LabelRepository;
import com.taskly.repository.ViewOptionRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Entity M2 khớp schema V3 (Hibernate validate) và các truy vấn repository dùng ở service. */
class M2EntityMappingTest extends IntegrationTestBase {

    @Autowired
    private LabelRepository labels;

    @Autowired
    private FilterRepository filters;

    @Autowired
    private ViewOptionRepository viewOptions;

    @Test
    void label_roundTripsAndQueriesByUser() throws Exception {
        UUID user = registerUser(uniqueEmail()).id();
        Label urgent = labels.saveAndFlush(new Label(user, "Gấp", ProjectColor.red, 2048));
        Label home = labels.saveAndFlush(new Label(user, "nha", ProjectColor.charcoal, 1024));

        assertThat(urgent.getId()).isNotNull();
        assertThat(urgent.getCreatedAt()).isNotNull();
        assertThat(labels.findByUserIdOrderBySortOrderAscIdAsc(user)).extracting(Label::getName)
                .containsExactly("nha", "Gấp");
        assertThat(labels.countByUserId(user)).isEqualTo(2);
        assertThat(labels.existsWithName(user, "gấp")).isTrue();
        assertThat(labels.existsOtherWithName(user, "GẤP", urgent.getId())).isFalse();
        assertThat(labels.existsOtherWithName(user, "GẤP", home.getId())).isTrue();
        assertThat(labels.findByIdAndUserId(urgent.getId(), UUID.randomUUID())).isEmpty();
        assertThat(labels.existsByIdAndUserId(urgent.getId(), user)).isTrue();
        assertThat(labels.existsByIdAndUserId(urgent.getId(), UUID.randomUUID())).isFalse();
        assertThat(labels.findByUserIdAndIdIn(user, List.of(home.getId(), UUID.randomUUID())))
                .extracting(Label::getId).containsExactly(home.getId());
        assertThat(jdbcTemplate.queryForObject("select color from labels where id = ?", String.class, urgent.getId()))
                .isEqualTo("red");
    }

    @Test
    void label_withClientAssignedId_isInsertedNotMerged() throws Exception {
        UUID user = registerUser(uniqueEmail()).id();
        UUID clientId = UUID.randomUUID();

        Label saved = labels.saveAndFlush(new Label(clientId, user, "gấp", ProjectColor.red, 1024));

        assertThat(saved.getId()).isEqualTo(clientId);
        assertThat(saved.getCreatedAt()).as("persist (không merge) → auditing ghi vào chính đối tượng").isNotNull();
        assertThat(labels.findById(clientId)).get().extracting(Label::getName).isEqualTo("gấp");
        assertThat(labels.findById(clientId)).get().extracting(Label::isNew).isEqualTo(false);
    }

    @Test
    void filter_roundTrips() throws Exception {
        UUID user = registerUser(uniqueEmail()).id();
        Filter filter = filters.saveAndFlush(new Filter(user, "Gấp hôm nay", "today & p1", ProjectColor.blue, 1024));

        assertThat(filters.findByIdAndUserId(filter.getId(), user)).get()
                .extracting(Filter::getQuery).isEqualTo("today & p1");
        assertThat(filters.countByUserId(user)).isEqualTo(1);
        assertThat(filters.findByUserIdOrderBySortOrderAscIdAsc(user)).hasSize(1);
        assertThat(filters.existsByIdAndUserId(filter.getId(), user)).isTrue();
        UUID clientId = UUID.randomUUID();
        Filter withClientId = filters.saveAndFlush(new Filter(clientId, user, "B", "p2", ProjectColor.red, 2048));
        assertThat(withClientId.getId()).isEqualTo(clientId);
        assertThat(withClientId.getCreatedAt()).isNotNull();
    }

    @Test
    void viewOption_roundTripsEnumsAsUppercaseText() throws Exception {
        UUID user = registerUser(uniqueEmail()).id();
        ViewOption option = new ViewOption(user, "TODAY");
        option.apply(SortBy.PRIORITY, SortDirection.DESC, GroupBy.PROJECT, 1, null, DueFilter.NEXT_7_DAYS, false);
        viewOptions.saveAndFlush(option);

        Map<String, Object> row = jdbcTemplate.queryForMap("select sort_by, sort_direction, group_by, "
                + "filter_priority, filter_due, show_completed from view_options where id = ?", option.getId());
        assertThat(row).containsEntry("sort_by", "PRIORITY").containsEntry("sort_direction", "DESC")
                .containsEntry("group_by", "PROJECT").containsEntry("filter_priority", 1)
                .containsEntry("filter_due", "NEXT_7_DAYS").containsEntry("show_completed", false);
        assertThat(viewOptions.findByUserIdAndViewKey(user, "TODAY")).isPresent();
        assertThat(viewOptions.findByUserIdOrderByViewKeyAsc(user)).hasSize(1);
        assertThat(viewOptions.deleteViews(List.of("label:" + UUID.randomUUID(), "TODAY"))).isEqualTo(1);
        assertThat(viewOptions.findByUserIdAndViewKey(user, "TODAY")).isEmpty();
        assertThat(viewOptions.deleteView(user, "TODAY")).isZero();
    }
}
