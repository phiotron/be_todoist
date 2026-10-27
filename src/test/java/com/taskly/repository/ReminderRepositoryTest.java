package com.taskly.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.taskly.IntegrationTestBase;
import com.taskly.entity.Reminder;
import com.taskly.entity.ReminderType;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Entity Reminder ⇄ bảng reminders (V5) và các truy vấn ReminderService dùng. */
class ReminderRepositoryTest extends IntegrationTestBase {

    private static final Instant TEN_AM_HCM = Instant.parse("2026-10-23T03:00:00Z");

    @Autowired
    private ReminderRepository reminderRepository;

    private UUID timedTask(RegisteredUser user) throws Exception {
        return readUuid(authPost(user.accessToken(), "/api/v1/tasks", """
                {"content": "Họp nhóm", "dueDatetime": "2026-10-23T12:00:00"}
                """).andExpect(status().isCreated()), "$.id");
    }

    @Test
    void savesBothTypesAndListsThemInCreationOrder() throws Exception {
        clock.freezeAt(TEN_AM_HCM);
        RegisteredUser user = registerUser(uniqueEmail());
        UUID taskId = timedTask(user);

        Reminder relative = reminderRepository.save(Reminder.relative(taskId, user.id(), 30));
        clock.advance(Duration.ofSeconds(1));
        Reminder absolute = reminderRepository.save(
                Reminder.absolute(taskId, user.id(), LocalDateTime.parse("2026-10-23T11:00")));

        List<Reminder> found = reminderRepository.findByTaskIdAndUserIdOrderByCreatedAtAscIdAsc(taskId, user.id());
        assertThat(found).extracting(Reminder::getId).containsExactly(relative.getId(), absolute.getId());
        assertThat(found.get(0).getType()).isEqualTo(ReminderType.RELATIVE);
        assertThat(found.get(0).getMinuteOffset()).isEqualTo(30);
        assertThat(found.get(0).getAt()).isNull();
        assertThat(found.get(1).getType()).isEqualTo(ReminderType.ABSOLUTE);
        assertThat(found.get(1).getAt()).isEqualTo(LocalDateTime.parse("2026-10-23T11:00"));
        assertThat(found.get(1).getMinuteOffset()).isNull();
        assertThat(found.get(1).getTaskId()).isEqualTo(taskId);
        assertThat(found.get(1).getUserId()).isEqualTo(user.id());
        assertThat(found.get(1).getCreatedAt()).isEqualTo(TEN_AM_HCM.plusSeconds(1));
        assertThat(jdbcTemplate.queryForObject("select type from reminders where id = ?", String.class,
                absolute.getId())).isEqualTo("ABSOLUTE");
    }

    @Test
    void findByIdAndTaskIdAndUserId_needsAllThreeToMatch() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID taskId = timedTask(user);
        UUID otherTaskId = timedTask(user);
        Reminder reminder = reminderRepository.save(Reminder.relative(taskId, user.id(), 10));

        assertThat(reminderRepository.findByIdAndTaskIdAndUserId(reminder.getId(), taskId, user.id())).isPresent();
        assertThat(reminderRepository.findByIdAndTaskIdAndUserId(reminder.getId(), otherTaskId, user.id())).isEmpty();
        assertThat(reminderRepository.findByIdAndTaskIdAndUserId(reminder.getId(), taskId, UUID.randomUUID()))
                .isEmpty();
    }

    @Test
    void countOnOpenTasks_countsOnlyOpenTasksOfTheUser() throws Exception {
        RegisteredUser owner = registerUser(uniqueEmail());
        RegisteredUser other = registerUser(uniqueEmail());
        UUID open = timedTask(owner);
        UUID willClose = timedTask(owner);
        UUID othersTask = timedTask(other);
        reminderRepository.save(Reminder.relative(open, owner.id(), 10));
        reminderRepository.save(Reminder.relative(open, owner.id(), 20));
        reminderRepository.save(Reminder.relative(willClose, owner.id(), 30));
        reminderRepository.save(Reminder.relative(othersTask, other.id(), 30));

        assertThat(reminderRepository.countOnOpenTasks(owner.id())).isEqualTo(3);

        authPost(owner.accessToken(), "/api/v1/tasks/" + willClose + "/close").andExpect(status().isOk());

        assertThat(reminderRepository.countOnOpenTasks(owner.id())).isEqualTo(2);
        assertThat(reminderRepository.countOnOpenTasks(other.id())).isEqualTo(1);
    }

    /** Truy vấn của listener TaskTimeClearedEvent (Task 6): chỉ nhắc RELATIVE của đúng các task được liệt kê. */
    @Test
    void deleteByTaskIdsAndType_removesOnlyRelativeRemindersOfTheListedTasks() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID listed = timedTask(user);
        UUID alsoListed = timedTask(user);
        UUID untouched = timedTask(user);
        reminderRepository.save(Reminder.relative(listed, user.id(), 10));
        reminderRepository.save(Reminder.relative(listed, user.id(), 20));
        Reminder absolute = reminderRepository.save(
                Reminder.absolute(listed, user.id(), LocalDateTime.parse("2026-10-23T11:00")));
        reminderRepository.save(Reminder.relative(alsoListed, user.id(), 30));
        Reminder untouchedRelative = reminderRepository.save(Reminder.relative(untouched, user.id(), 30));

        int deleted = reminderRepository.deleteByTaskIdsAndType(List.of(listed, alsoListed), ReminderType.RELATIVE);

        assertThat(deleted).isEqualTo(3);
        assertThat(reminderRepository.findByTaskIdAndUserIdOrderByCreatedAtAscIdAsc(listed, user.id()))
                .extracting(Reminder::getId).containsExactly(absolute.getId());
        assertThat(reminderRepository.findByTaskIdAndUserIdOrderByCreatedAtAscIdAsc(alsoListed, user.id())).isEmpty();
        assertThat(reminderRepository.findByTaskIdAndUserIdOrderByCreatedAtAscIdAsc(untouched, user.id()))
                .extracting(Reminder::getId).containsExactly(untouchedRelative.getId());
    }
}
