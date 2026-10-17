package com.fitnesscoaching.platform.modules.notification;

import com.fitnesscoaching.platform.modules.auth.domain.AccountStatus;
import com.fitnesscoaching.platform.modules.chat.ChatFailure;
import com.fitnesscoaching.platform.modules.coaching.application.port.in.CurrentCoachingContextQuery;
import com.fitnesscoaching.platform.modules.trainer.application.port.in.TrainerCoachingEligibilityQuery;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserAccountStatusQuery;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class InAppNotificationService implements ChatNotificationWriter {
    private final JdbcTemplate jdbc;
    private final CurrentCoachingContextQuery contexts;
    private final UserAccountStatusQuery accounts;
    private final TrainerCoachingEligibilityQuery trainers;

    public InAppNotificationService(JdbcTemplate jdbc, CurrentCoachingContextQuery contexts,
                                    UserAccountStatusQuery accounts, TrainerCoachingEligibilityQuery trainers) {
        this.jdbc = jdbc; this.contexts = contexts; this.accounts = accounts; this.trainers = trainers;
    }

    public record Item(UUID id, String eventType, String title, String body, UUID messageId,
                       UUID coachingPeriodId, Instant createdAt, Instant readAt) {}
    public record Page(List<Item> items, int page, int size, boolean hasMore) {}

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID messageCommitted(UUID recipient, UUID message, UUID period, UUID student, UUID trainer) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fitness.notifications(id,recipient_user_id,event_type,title,body,
                  related_entity_type,related_entity_id,coaching_period_id,chat_student_id,chat_trainer_id)
                VALUES (?,?,'CHAT_MESSAGE','Tin nhắn mới','Bạn có tin nhắn mới','MESSAGE',?,?,?,?)
                """, id, recipient, message, period, student, trainer);
        jdbc.update("""
                INSERT INTO fitness.notification_deliveries(notification_id,channel,status)
                VALUES (?,'IN_APP','DELIVERED')
                """, id);
        return id;
    }

    @Transactional(readOnly = true)
    public Page list(UUID actor, int page, int size) {
        validatePage(page, size);
        requireAccount(actor);
        List<Item> candidates = jdbc.query("""
                SELECT id,event_type,title,body,related_entity_id,coaching_period_id,
                       chat_student_id,chat_trainer_id,created_at,read_at
                FROM fitness.notifications WHERE recipient_user_id=? AND event_type='CHAT_MESSAGE'
                ORDER BY created_at DESC,id DESC
                """, (rs, row) -> visible(actor, rs.getObject("chat_student_id", UUID.class),
                rs.getObject("chat_trainer_id", UUID.class), rs.getObject("coaching_period_id", UUID.class))
                ? new Item(rs.getObject("id", UUID.class), rs.getString("event_type"), rs.getString("title"),
                    rs.getString("body"), rs.getObject("related_entity_id", UUID.class),
                    rs.getObject("coaching_period_id", UUID.class), rs.getTimestamp("created_at").toInstant(),
                    rs.getTimestamp("read_at") == null ? null : rs.getTimestamp("read_at").toInstant())
                : null, actor).stream().filter(item -> item != null).toList();
        int from = (int) Math.min((long) page * size, candidates.size());
        int until = Math.min(from + size, candidates.size());
        return new Page(candidates.subList(from, until), page, size, until < candidates.size());
    }

    @Transactional
    public Item markRead(UUID actor, UUID id) {
        requireAccount(actor);
        List<NotificationRow> rows = jdbc.query("""
                SELECT chat_student_id,chat_trainer_id,coaching_period_id FROM fitness.notifications
                WHERE id=? AND recipient_user_id=? FOR UPDATE
                """, (rs, row) -> new NotificationRow(rs.getObject(1, UUID.class),
                rs.getObject(2, UUID.class), rs.getObject(3, UUID.class)), id, actor);
        if (rows.isEmpty() || !visible(actor, rows.getFirst().student(), rows.getFirst().trainer(), rows.getFirst().period())) {
            throw new ChatFailure("CHAT_NOTIFICATION_NOT_FOUND", 404);
        }
        jdbc.update("UPDATE fitness.notifications SET read_at=COALESCE(read_at,clock_timestamp()) WHERE id=?", id);
        return jdbc.queryForObject("""
                SELECT id,event_type,title,body,related_entity_id,coaching_period_id,created_at,read_at
                FROM fitness.notifications WHERE id=?
                """, (rs, row) -> new Item(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getObject(5, UUID.class), rs.getObject(6, UUID.class),
                rs.getTimestamp(7).toInstant(), rs.getTimestamp(8).toInstant()), id);
    }

    private boolean visible(UUID actor, UUID student, UUID trainer, UUID period) {
        if (actor.equals(student)) return true;
        if (!actor.equals(trainer) || period == null || !trainers.canCoach(actor)) return false;
        return contexts.findEffectiveContext(student, false)
                .filter(context -> context.mode() == CurrentCoachingContextQuery.Mode.HUMAN_COACH
                        && period.equals(context.periodId()) && actor.equals(context.trainerId()))
                .isPresent();
    }

    private void requireAccount(UUID actor) {
        if (accounts.getAccountStatus(actor).orElse(null) != AccountStatus.ACTIVE)
            throw new ChatFailure("CHAT_NOTIFICATION_NOT_FOUND", 404);
    }

    private static void validatePage(int page, int size) {
        if (page < 0 || size < 1 || size > 50 || (long) page * size > Integer.MAX_VALUE)
            throw new ChatFailure("VALIDATION_FAILED", 400);
    }
    private record NotificationRow(UUID student, UUID trainer, UUID period) {}
}
