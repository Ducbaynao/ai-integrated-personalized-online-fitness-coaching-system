package com.fitnesscoaching.platform.modules.chat;

import com.fitnesscoaching.platform.modules.auth.domain.AccountStatus;
import com.fitnesscoaching.platform.modules.coaching.application.port.in.CurrentCoachingContextQuery;
import com.fitnesscoaching.platform.modules.notification.ChatNotificationWriter;
import com.fitnesscoaching.platform.modules.student.application.port.in.StudentCapabilityQuery;
import com.fitnesscoaching.platform.modules.trainer.application.port.in.TrainerCoachingEligibilityQuery;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserAccountStatusQuery;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
public class ChatService implements ChatOwnedExportQuery {
    private final JdbcTemplate jdbc;
    private final CurrentCoachingContextQuery contexts;
    private final UserAccountStatusQuery accounts;
    private final StudentCapabilityQuery students;
    private final TrainerCoachingEligibilityQuery trainers;
    private final ChatNotificationWriter notifications;
    private final ApplicationEventPublisher events;
    private final ClamdAttachmentScanner scanner;
    private final ChatObjectStorage storage;

    public ChatService(JdbcTemplate jdbc, CurrentCoachingContextQuery contexts, UserAccountStatusQuery accounts,
                       StudentCapabilityQuery students, TrainerCoachingEligibilityQuery trainers,
                       ChatNotificationWriter notifications, ApplicationEventPublisher events,
                       ClamdAttachmentScanner scanner, ChatObjectStorage storage) {
        this.jdbc = jdbc; this.contexts = contexts; this.accounts = accounts; this.students = students;
        this.trainers = trainers; this.notifications = notifications; this.events = events;
        this.scanner = scanner; this.storage = storage;
    }

    public record Conversation(UUID id, UUID coachingPeriodId, UUID studentId, UUID trainerId,
                               Instant createdAt, Instant updatedAt, boolean sendAllowed) {}
    public record Message(UUID id, UUID conversationId, UUID senderId, String body, String status,
                          Instant sentAt, Instant deliveredAt, Instant readAt,
                          UUID attachmentId, String attachmentName, String attachmentContentType,
                          Long attachmentSize) {}
    public record Page<T>(List<T> items, int page, int size, boolean hasMore) {}
    public record Changed(UUID studentId, UUID trainerId, UUID periodId, UUID conversationId) {}
    private record Receipt(String hash, UUID messageId) {}

    @Transactional
    public Message send(UUID actor, UUID student, String body, UUID commandKey) {
        if (commandKey == null || body == null || body.isBlank() || body.length() > 4000)
            throw fail("VALIDATION_FAILED", 400);
        String content = body.strip();
        if (content.isEmpty()) throw fail("VALIDATION_FAILED", 400);
        // Period SHARE lock serializes against B03's period closure without altering its lifecycle.
        var context = current(student, true);
        if (!actor.equals(student) && !actor.equals(context.trainerId())) throw fail("CHAT_CONVERSATION_NOT_FOUND", 404);
        requireParticipant(actor, student, context.trainerId());
        String hash = hash(student + ":" + body);
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?::text,0))",
                actor + ":" + commandKey);
        List<Receipt> receipts = jdbc.query("""
                SELECT payload_hash,message_id FROM fitness.chat_command_receipts
                WHERE actor_id=? AND command_key=?
                """, (rs, row) -> new Receipt(rs.getString(1), rs.getObject(2, UUID.class)), actor, commandKey);
        if (!receipts.isEmpty()) {
            if (!hash.equals(receipts.getFirst().hash())) throw fail("CHAT_IDEMPOTENCY_CONFLICT", 409);
            Message prior = message(receipts.getFirst().messageId());
            if (!context.periodId().equals(conversationById(prior.conversationId()).coachingPeriodId()))
                throw fail("CHAT_CONVERSATION_NOT_FOUND", 404);
            return prior;
        }
        Conversation conversation = ensureConversation(context.periodId(), context.relationshipId(), student,
                context.trainerId(), actor);
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fitness.messages(id,conversation_id,sender_id,message_type,body,status)
                VALUES (?,?,?,'TEXT',?,'SENT')
                """, id, conversation.id(), actor, content);
        jdbc.update("UPDATE fitness.conversations SET updated_at=clock_timestamp() WHERE id=?", conversation.id());
        UUID recipient = actor.equals(student) ? context.trainerId() : student;
        jdbc.update("""
                INSERT INTO fitness.message_read_receipts(message_id,user_id) VALUES (?,?)
                """, id, recipient);
        jdbc.update("""
                INSERT INTO fitness.chat_command_receipts(actor_id,command_key,payload_hash,message_id)
                VALUES (?,?,?,?)
                """, actor, commandKey, hash, id);
        notifications.messageCommitted(recipient, id, context.periodId(), student, context.trainerId());
        events.publishEvent(new Changed(student, context.trainerId(), context.periodId(), conversation.id()));
        return message(id);
    }

    @Transactional
    public Message sendAttachment(UUID actor, UUID student, String filename, String declaredType,
                                  byte[] bytes, UUID commandKey) {
        if (commandKey == null || filename == null || filename.isBlank() || filename.length() > 255
                || filename.indexOf('/') >= 0 || filename.indexOf('\\') >= 0
                || filename.chars().anyMatch(c -> c < 32)) throw fail("VALIDATION_FAILED", 400);
        String contentType = ChatAttachmentPolicy.verifiedType(bytes, declaredType);
        var context = current(student, true);
        if (!actor.equals(student) && !actor.equals(context.trainerId()))
            throw fail("CHAT_CONVERSATION_NOT_FOUND", 404);
        requireParticipant(actor, student, context.trainerId());
        String checksum = hashBytes(bytes);
        String receiptHash = hash(student + ":ATTACHMENT:" + filename + ":" + contentType + ":" + checksum);
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?::text,0))", actor + ":" + commandKey);
        List<Receipt> receipts = jdbc.query("SELECT payload_hash,message_id FROM fitness.chat_command_receipts "
                + "WHERE actor_id=? AND command_key=?",
                (rs, row) -> new Receipt(rs.getString(1), rs.getObject(2, UUID.class)), actor, commandKey);
        if (!receipts.isEmpty()) {
            if (!receiptHash.equals(receipts.getFirst().hash())) throw fail("CHAT_IDEMPOTENCY_CONFLICT", 409);
            Message prior = message(receipts.getFirst().messageId());
            if (!context.periodId().equals(conversationById(prior.conversationId()).coachingPeriodId()))
                throw fail("CHAT_CONVERSATION_NOT_FOUND", 404);
            return prior;
        }
        scanner.requireClean(bytes); // No object is stored or message committed if scanning fails.
        UUID messageId = UUID.randomUUID();
        UUID mediaId = UUID.randomUUID();
        String objectKey = "chat/" + context.periodId() + "/" + messageId + "/" + mediaId;
        storage.put(objectKey, bytes, contentType);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) storage.delete(objectKey);
            }
        });
        Conversation conversation = ensureConversation(context.periodId(), context.relationshipId(), student,
                context.trainerId(), actor);
        jdbc.update("""
                INSERT INTO fitness.media_files(id,owner_user_id,storage_provider,bucket_name,object_key,
                    original_filename,content_type,size_bytes,checksum_sha256,media_purpose,visibility,scan_status)
                VALUES (?,?,'S3',?,?,?,?,?,?, 'CHAT_ATTACHMENT','PRIVATE','CLEAN')
                """, mediaId, actor, storage.bucket(), objectKey, filename, contentType, bytes.length, checksum);
        jdbc.update("""
                INSERT INTO fitness.messages(id,conversation_id,sender_id,message_type,status)
                VALUES (?,?,?,?::fitness.message_type,'SENT')
                """, messageId, conversation.id(), actor, contentType.startsWith("image/") ? "IMAGE" : "FILE");
        jdbc.update("INSERT INTO fitness.message_attachments(message_id,media_id) VALUES (?,?)", messageId, mediaId);
        jdbc.update("UPDATE fitness.conversations SET updated_at=clock_timestamp() WHERE id=?", conversation.id());
        UUID recipient = actor.equals(student) ? context.trainerId() : student;
        jdbc.update("INSERT INTO fitness.message_read_receipts(message_id,user_id) VALUES (?,?)", messageId, recipient);
        jdbc.update("INSERT INTO fitness.chat_command_receipts(actor_id,command_key,payload_hash,message_id) VALUES (?,?,?,?)",
                actor, commandKey, receiptHash, messageId);
        notifications.messageCommitted(recipient, messageId, context.periodId(), student, context.trainerId());
        events.publishEvent(new Changed(student, context.trainerId(), context.periodId(), conversation.id()));
        return message(messageId);
    }

    @Transactional(readOnly = true)
    public AttachmentContent download(UUID actor, UUID attachmentId) {
        requireAccount(actor);
        var rows = jdbc.query("""
                SELECT m.conversation_id,mf.object_key,mf.content_type,mf.original_filename,mf.size_bytes
                FROM fitness.message_attachments ma
                JOIN fitness.messages m ON m.id=ma.message_id
                JOIN fitness.media_files mf ON mf.id=ma.media_id
                WHERE ma.id=? AND mf.scan_status='CLEAN' AND mf.deleted_at IS NULL
                  AND mf.storage_provider='S3' AND mf.visibility='PRIVATE'
                """, (rs, row) -> new AttachmentRow(rs.getObject(1, UUID.class), rs.getString(2),
                rs.getString(3), rs.getString(4), rs.getLong(5)), attachmentId);
        if (rows.isEmpty()) throw fail("CHAT_ATTACHMENT_NOT_FOUND", 404);
        AttachmentRow row = rows.getFirst();
        try { detail(actor, row.conversationId()); }
        catch (ChatFailure concealed) { throw fail("CHAT_ATTACHMENT_NOT_FOUND", 404); }
        byte[] bytes = storage.get(row.objectKey());
        if (bytes.length != row.sizeBytes() || bytes.length > ChatAttachmentPolicy.MAX_BYTES)
            throw fail("CHAT_ATTACHMENT_STORAGE_UNAVAILABLE", 503);
        return new AttachmentContent(bytes, row.contentType(), row.filename());
    }

    public record AttachmentContent(byte[] bytes, String contentType, String filename) {}
    private record AttachmentRow(UUID conversationId, String objectKey, String contentType,
                                 String filename, long sizeBytes) {}

    @Override
    @Transactional(readOnly = true)
    public List<OwnMessage> ownMessages(UUID actor) {
        requireAccount(actor);
        return jdbc.query("""
                SELECT m.id,m.conversation_id,m.body,m.sent_at,
                       CASE WHEN mf.id IS NOT NULL THEN ma.id END,mf.original_filename,mf.content_type
                FROM fitness.messages m
                JOIN fitness.conversations c ON c.id=m.conversation_id
                LEFT JOIN fitness.message_attachments ma ON ma.message_id=m.id
                LEFT JOIN fitness.media_files mf ON mf.id=ma.media_id AND mf.deleted_at IS NULL
                WHERE m.sender_id=? AND c.coaching_period_id IS NOT NULL
                ORDER BY m.sent_at,m.id
                """, (rs, row) -> new OwnMessage(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getString(3), rs.getTimestamp(4).toInstant(), rs.getObject(5, UUID.class),
                rs.getString(6), rs.getString(7)), actor).stream()
                .filter(message -> {
                    try { detail(actor, message.conversationId()); return true; }
                    catch (ChatFailure concealed) { return false; }
                }).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public void requireOwnMessageReadable(UUID actor, UUID messageId) {
        requireAccount(actor);
        List<UUID> conversations = jdbc.query("""
                SELECT conversation_id FROM fitness.messages WHERE id=? AND sender_id=?
                """, (rs, row) -> rs.getObject(1, UUID.class), messageId, actor);
        if (conversations.isEmpty()) throw fail("CHAT_MESSAGE_NOT_FOUND", 404);
        detail(actor, conversations.getFirst());
    }

    @Override
    @Transactional(readOnly = true)
    public AttachmentContent ownAttachment(UUID actor, UUID attachmentId) {
        requireAccount(actor);
        boolean sentByActor = Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM fitness.message_attachments ma
                  JOIN fitness.messages m ON m.id=ma.message_id
                  WHERE ma.id=? AND m.sender_id=?)
                """, Boolean.class, attachmentId, actor));
        if (!sentByActor) throw fail("CHAT_ATTACHMENT_NOT_FOUND", 404);
        return download(actor, attachmentId);
    }

    @Transactional(readOnly = true)
    public Page<Conversation> conversations(UUID actor, int page, int size) {
        validatePage(page, size);
        requireAccount(actor);
        List<Conversation> allowed = jdbc.query("""
                SELECT id,coaching_period_id,student_id,trainer_id,created_at,updated_at
                FROM fitness.conversations WHERE coaching_period_id IS NOT NULL
                  AND (student_id=? OR trainer_id=?) ORDER BY updated_at DESC,id DESC
                """, (rs, row) -> new Conversation(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getObject(3, UUID.class), rs.getObject(4, UUID.class),
                rs.getTimestamp(5).toInstant(), rs.getTimestamp(6).toInstant(), false), actor, actor).stream()
                .filter(row -> visible(actor, row)).map(row -> withSendAllowed(actor, row)).toList();
        int from = (int) Math.min((long) page * size, allowed.size());
        int until = Math.min(from + size, allowed.size());
        return new Page<>(allowed.subList(from, until), page, size, until < allowed.size());
    }

    @Transactional(readOnly = true)
    public Conversation detail(UUID actor, UUID id) {
        requireAccount(actor);
        Conversation row = conversationById(id);
        if (!visible(actor, row)) throw fail("CHAT_CONVERSATION_NOT_FOUND", 404);
        return withSendAllowed(actor, row);
    }

    @Transactional(readOnly = true)
    public Page<Message> messages(UUID actor, UUID conversationId, int page, int size) {
        validatePage(page, size);
        detail(actor, conversationId);
        List<Message> rows = jdbc.query("""
                SELECT m.id,m.conversation_id,m.sender_id,m.body,m.status::text,m.sent_at,
                       r.delivered_at,r.read_at,ma.id,mf.original_filename,mf.content_type,mf.size_bytes
                       FROM fitness.messages m
                LEFT JOIN fitness.message_read_receipts r ON r.message_id=m.id
                LEFT JOIN fitness.message_attachments ma ON ma.message_id=m.id
                LEFT JOIN fitness.media_files mf ON mf.id=ma.media_id
                WHERE m.conversation_id=? ORDER BY m.sent_at DESC,m.id DESC LIMIT ? OFFSET ?
                """, (rs, row) -> mapMessage(rs), conversationId, size + 1, (long) page * size);
        boolean more = rows.size() > size;
        return new Page<>(more ? rows.subList(0, size) : rows, page, size, more);
    }

    @Transactional
    public Message acknowledge(UUID actor, UUID messageId, String kind) {
        if (!"DELIVERED".equals(kind) && !"READ".equals(kind)) throw fail("VALIDATION_FAILED", 400);
        requireAccount(actor);
        Message before = message(messageId);
        Conversation conversation;
        try { conversation = detail(actor, before.conversationId()); }
        catch (ChatFailure concealed) { throw fail("CHAT_MESSAGE_NOT_FOUND", 404); }
        if (actor.equals(before.senderId())) throw fail("CHAT_MESSAGE_NOT_FOUND", 404);
        if (!actor.equals(conversation.studentId()) && !actor.equals(conversation.trainerId()))
            throw fail("CHAT_MESSAGE_NOT_FOUND", 404);
        int changed = jdbc.update("""
                UPDATE fitness.message_read_receipts SET delivered_at=COALESCE(delivered_at,clock_timestamp()),
                  read_at=CASE WHEN ?='READ' THEN COALESCE(read_at,clock_timestamp()) ELSE read_at END
                WHERE message_id=? AND user_id=?
                """, kind, messageId, actor);
        if (changed != 1) throw fail("CHAT_MESSAGE_NOT_FOUND", 404);
        jdbc.update("""
                UPDATE fitness.messages SET status=CASE WHEN ?='READ' THEN 'READ'::fitness.message_status
                  WHEN status='SENT' THEN 'DELIVERED'::fitness.message_status ELSE status END
                WHERE id=?
                """, kind, messageId);
        events.publishEvent(new Changed(conversation.studentId(), conversation.trainerId(),
                conversation.coachingPeriodId(), conversation.id()));
        return message(messageId);
    }

    private CurrentCoachingContextQuery.CurrentCoachingContext current(UUID student, boolean lock) {
        return contexts.findEffectiveContext(student, lock)
                .filter(context -> context.mode() == CurrentCoachingContextQuery.Mode.HUMAN_COACH
                        && context.periodId() != null && context.trainerId() != null
                        && context.relationshipId() != null)
                .orElseThrow(() -> fail("CHAT_CONVERSATION_NOT_FOUND", 404));
    }

    private boolean visible(UUID actor, Conversation row) {
        if (actor.equals(row.studentId())) return students.hasProfile(actor);
        if (!actor.equals(row.trainerId()) || !trainers.canCoach(actor)) return false;
        return contexts.findEffectiveContext(row.studentId(), false)
                .filter(context -> context.mode() == CurrentCoachingContextQuery.Mode.HUMAN_COACH
                        && actor.equals(context.trainerId()) && row.coachingPeriodId().equals(context.periodId()))
                .isPresent();
    }

    private Conversation withSendAllowed(UUID actor, Conversation row) {
        boolean allowed = contexts.findEffectiveContext(row.studentId(), false)
                .filter(context -> context.mode() == CurrentCoachingContextQuery.Mode.HUMAN_COACH
                        && row.coachingPeriodId().equals(context.periodId())
                        && row.trainerId().equals(context.trainerId()))
                .isPresent()
                && accounts.getAccountStatus(row.studentId()).orElse(null) == AccountStatus.ACTIVE
                && accounts.getAccountStatus(row.trainerId()).orElse(null) == AccountStatus.ACTIVE
                && students.hasProfile(row.studentId()) && trainers.canCoach(row.trainerId());
        return new Conversation(row.id(), row.coachingPeriodId(), row.studentId(), row.trainerId(),
                row.createdAt(), row.updatedAt(), allowed);
    }

    private void requireParticipant(UUID actor, UUID student, UUID trainer) {
        requireAccount(actor);
        requireAccount(student);
        requireAccount(trainer);
        if (!students.hasProfile(student) || !trainers.canCoach(trainer))
            throw fail("CHAT_CONVERSATION_NOT_FOUND", 404);
    }

    private void requireAccount(UUID actor) {
        if (accounts.getAccountStatus(actor).orElse(null) != AccountStatus.ACTIVE)
            throw fail("CHAT_CONVERSATION_NOT_FOUND", 404);
    }

    private Conversation ensureConversation(UUID period, UUID relationship, UUID student, UUID trainer, UUID actor) {
        jdbc.update("""
                INSERT INTO fitness.conversations(conversation_type,coaching_relationship_id,
                  coaching_period_id,student_id,trainer_id,created_by)
                VALUES ('DIRECT_COACHING',?,?,?,?,?)
                ON CONFLICT (coaching_period_id) WHERE coaching_period_id IS NOT NULL DO NOTHING
                """, relationship, period, student, trainer, actor);
        Conversation result = conversation(period);
        jdbc.update("""
                INSERT INTO fitness.conversation_members(conversation_id,user_id) VALUES (?,?),(?,?)
                ON CONFLICT DO NOTHING
                """, result.id(), student, result.id(), trainer);
        return result;
    }

    private Conversation conversation(UUID period) {
        return jdbc.queryForObject("""
                SELECT id,coaching_period_id,student_id,trainer_id,created_at,updated_at
                FROM fitness.conversations WHERE coaching_period_id=?
                """, (rs, row) -> new Conversation(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getObject(3, UUID.class), rs.getObject(4, UUID.class),
                rs.getTimestamp(5).toInstant(), rs.getTimestamp(6).toInstant(), false), period);
    }

    private Conversation conversationById(UUID id) {
        List<Conversation> found = jdbc.query("""
                SELECT id,coaching_period_id,student_id,trainer_id,created_at,updated_at
                FROM fitness.conversations WHERE id=? AND coaching_period_id IS NOT NULL
                """, (rs, row) -> new Conversation(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getObject(3, UUID.class), rs.getObject(4, UUID.class),
                rs.getTimestamp(5).toInstant(), rs.getTimestamp(6).toInstant(), false), id);
        if (found.isEmpty()) throw fail("CHAT_CONVERSATION_NOT_FOUND", 404);
        return found.getFirst();
    }

    private Message message(UUID id) {
        List<Message> rows = jdbc.query("""
                SELECT m.id,m.conversation_id,m.sender_id,m.body,m.status::text,m.sent_at,
                       r.delivered_at,r.read_at,ma.id,mf.original_filename,mf.content_type,mf.size_bytes
                       FROM fitness.messages m
                LEFT JOIN fitness.message_read_receipts r ON r.message_id=m.id
                LEFT JOIN fitness.message_attachments ma ON ma.message_id=m.id
                LEFT JOIN fitness.media_files mf ON mf.id=ma.media_id
                WHERE m.id=?
                """, (rs, row) -> mapMessage(rs), id);
        if (rows.isEmpty()) throw fail("CHAT_MESSAGE_NOT_FOUND", 404);
        return rows.getFirst();
    }

    private static Message mapMessage(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Message(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getObject(3, UUID.class), rs.getString(4), rs.getString(5),
                rs.getTimestamp(6).toInstant(), rs.getTimestamp(7) == null ? null : rs.getTimestamp(7).toInstant(),
                rs.getTimestamp(8) == null ? null : rs.getTimestamp(8).toInstant(),
                rs.getObject(9, UUID.class), rs.getString(10), rs.getString(11),
                rs.getObject(12) == null ? null : rs.getLong(12));
    }

    private static void validatePage(int page, int size) {
        if (page < 0 || size < 1 || size > 50 || (long) page * size > Integer.MAX_VALUE)
            throw fail("VALIDATION_FAILED", 400);
    }
    private static ChatFailure fail(String code, int status) { return new ChatFailure(code, status); }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }
    private static String hashBytes(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }
}
