package com.fitnesscoaching.platform;

import com.fitnesscoaching.platform.modules.chat.ChatFailure;
import com.fitnesscoaching.platform.modules.chat.ChatService;
import com.fitnesscoaching.platform.modules.chat.ChatObjectStorage;
import com.fitnesscoaching.platform.modules.chat.ClamdAttachmentScanner;
import com.fitnesscoaching.platform.modules.chat.ChatUpdatesSocket;
import com.fitnesscoaching.platform.modules.coaching.application.port.in.CoachingLifecycleUseCase;
import com.fitnesscoaching.platform.modules.notification.InAppNotificationService;
import com.fitnesscoaching.platform.modules.notification.ExpoPushGateway;
import com.fitnesscoaching.platform.modules.notification.PushDeliveryWorker;
import com.fitnesscoaching.platform.modules.user.application.service.AccountDataExportService;
import com.fitnesscoaching.platform.modules.user.application.service.AccountErasureService;
import com.fitnesscoaching.platform.modules.user.application.service.AccountErasureWorker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;
import java.util.Map;
import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.zip.ZipInputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.Mockito.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {"app.scheduling.enabled=false", "push.expo.enabled=true",
        "account.erasure.request-enabled=true", "account.erasure.worker-enabled=false"})
class ChatNotificationIntegrationTest {
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg18").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("chat_b08_disposable").withUsername("fitness_app").withPassword("testpass123");
    static { postgres.start(); }
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.schemas", () -> "fitness");
        registry.add("spring.flyway.default-schema", () -> "fitness");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Autowired ChatService chat;
    @Autowired InAppNotificationService notifications;
    @Autowired PushDeliveryWorker pushWorker;
    @Autowired CoachingLifecycleUseCase lifecycle;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ChatUpdatesSocket socket;
    @Autowired AccountDataExportService dataExport;
    @Autowired AccountErasureService erasure;
    @Autowired AccountErasureWorker erasureWorker;
    @MockitoBean ChatObjectStorage storage;
    @MockitoBean ClamdAttachmentScanner scanner;
    @MockitoBean ExpoPushGateway expo;

    @Test void pushStartsOffThenQueuesOnlyAfterOptInAndTracksProviderSeparatelyFromDevice() throws Exception {
        UUID student = student(), trainer = trainer(), device = UUID.randomUUID();
        activate(student, trainer);
        mvc.perform(get("/api/v1/notifications/me/push/preferences")
                .with(jwt().jwt(j -> j.subject(trainer.toString()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
        chat.send(student, student, "Before opt-in", UUID.randomUUID());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.push_delivery_attempts", Integer.class)).isZero();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                        "/api/v1/notifications/me/push/devices/{id}", device)
                .with(jwt().jwt(j -> j.subject(trainer.toString()))).contentType("application/json")
                .content("{\"expoPushToken\":\"ExpoPushToken[test-token]\"}"))
                .andExpect(status().isNoContent());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                        "/api/v1/notifications/me/push/preferences/CHAT")
                .with(jwt().jwt(j -> j.subject(trainer.toString()))).contentType("application/json")
                .content("{\"enabled\":true,\"timezone\":\"+07:00\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("PUSH_TIMEZONE_INVALID"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                        "/api/v1/notifications/me/push/preferences/CHAT")
                .with(jwt().jwt(j -> j.subject(trainer.toString()))).contentType("application/json")
                .content("{\"enabled\":true,\"timezone\":\"Asia/Ho_Chi_Minh\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(true));
        UUID key = UUID.randomUUID();
        ChatService.Message message = chat.send(student, student, "After opt-in", key);
        assertThat(chat.send(student, student, "After opt-in", key).id()).isEqualTo(message.id());
        UUID notice = jdbc.queryForObject("""
                SELECT n.id FROM fitness.notifications n WHERE n.related_entity_id=? AND n.recipient_user_id=?
                """, UUID.class, message.id(), trainer);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.push_delivery_attempts WHERE notification_id=?",
                Integer.class, notice)).isEqualTo(1);
        when(expo.send("ExpoPushToken[test-token]", notice))
                .thenReturn(new ExpoPushGateway.Result(true, "expo-ticket-1", null));
        pushWorker.dispatch();
        assertThat(jdbc.queryForObject("SELECT status FROM fitness.push_delivery_attempts WHERE notification_id=?",
                String.class, notice)).isEqualTo("TICKET_ACCEPTED");
        assertThat(jdbc.queryForObject("SELECT read_at FROM fitness.notifications WHERE id=?",
                java.sql.Timestamp.class, notice)).isNull();
        jdbc.update("UPDATE fitness.push_delivery_attempts SET attempted_at=clock_timestamp()-interval '1 minute', "
                + "next_attempt_at=clock_timestamp()-interval '1 minute' "
                + "WHERE notification_id=?", notice);
        when(expo.receipt("expo-ticket-1"))
                .thenReturn(new ExpoPushGateway.Result(true, "expo-ticket-1", null));
        pushWorker.inspectReceipts();
        assertThat(jdbc.queryForObject("SELECT status FROM fitness.push_delivery_attempts WHERE notification_id=?",
                String.class, notice)).isEqualTo("PROVIDER_ACCEPTED");
        mvc.perform(get("/api/v1/notifications/me/push/deliveries/{id}", notice)
                .with(jwt().jwt(j -> j.subject(trainer.toString()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].status").value("PROVIDER_ACCEPTED"));
        mvc.perform(post("/api/v1/notifications/me/push/devices/{device}/received/{notice}", device, notice)
                .with(jwt().jwt(j -> j.subject(trainer.toString()))))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT status FROM fitness.push_delivery_attempts WHERE notification_id=?",
                String.class, notice)).isEqualTo("DEVICE_ACKNOWLEDGED");
        assertThat(jdbc.queryForObject("SELECT read_at FROM fitness.notifications WHERE id=?",
                java.sql.Timestamp.class, notice)).isNull();
    }

    @Test void pausedTrainerCannotReceiveQueuedPushOrAcknowledgeIt() throws Exception {
        UUID student = student(), trainer = trainer(), device = UUID.randomUUID();
        UUID relationship = activate(student, trainer);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                        "/api/v1/notifications/me/push/devices/{id}", device)
                .with(jwt().jwt(j -> j.subject(trainer.toString()))).contentType("application/json")
                .content("{\"expoPushToken\":\"ExpoPushToken[other-token]\"}"))
                .andExpect(status().isNoContent());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                        "/api/v1/notifications/me/push/preferences/CHAT")
                .with(jwt().jwt(j -> j.subject(trainer.toString()))).contentType("application/json")
                .content("{\"enabled\":true,\"timezone\":\"Etc/UTC\"}"))
                .andExpect(status().isOk());
        ChatService.Message message = chat.send(student, student, "Pending", UUID.randomUUID());
        UUID notice = jdbc.queryForObject("SELECT id FROM fitness.notifications WHERE related_entity_id=?",
                UUID.class, message.id());
        lifecycle.relationshipAction(student, relationship, "PAUSE", 1, null, UUID.randomUUID());
        pushWorker.dispatch();
        assertThat(jdbc.queryForObject("SELECT status FROM fitness.push_delivery_attempts WHERE notification_id=?",
                String.class, notice)).isEqualTo("SKIPPED");
        verify(expo, never()).send("ExpoPushToken[other-token]", notice);
        mvc.perform(get("/api/v1/notifications/me/push/deliveries/{id}", notice)
                .with(jwt().jwt(j -> j.subject(trainer.toString()))))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/notifications/me/push/devices/{device}/received/{notice}", device, notice)
                .with(jwt().jwt(j -> j.subject(trainer.toString()))))
                .andExpect(status().isNotFound());
    }

    @Test void competingPushWorkersDoNotSubmitTheSameNoticeTwice() throws Exception {
        UUID student = student(), trainer = trainer(), device = UUID.randomUUID();
        activate(student, trainer);
        jdbc.update("INSERT INTO fitness.push_devices(user_id,device_key,expo_token) VALUES (?,?,?)",
                trainer, device, "ExpoPushToken[race-token]");
        jdbc.update("""
                INSERT INTO fitness.notification_preferences(user_id,event_type,channel,enabled,timezone)
                VALUES (?,'CHAT','PUSH',true,'Etc/UTC')
                """, trainer);
        ChatService.Message message = chat.send(student, student, "One push", UUID.randomUUID());
        UUID notice = jdbc.queryForObject("SELECT id FROM fitness.notifications WHERE related_entity_id=?",
                UUID.class, message.id());
        CountDownLatch providerEntered = new CountDownLatch(1), release = new CountDownLatch(1);
        when(expo.send("ExpoPushToken[race-token]", notice)).thenAnswer(invocation -> {
            providerEntered.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test latch timed out");
            return new ExpoPushGateway.Result(true, "ticket-race", null);
        });
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(pushWorker::dispatch);
            assertThat(providerEntered.await(10, TimeUnit.SECONDS)).isTrue();
            var second = workers.submit(pushWorker::dispatch);
            second.get(10, TimeUnit.SECONDS); // Claimed state is committed; provider I/O holds no row lock.
            release.countDown();
            first.get(10, TimeUnit.SECONDS);
        } finally { release.countDown(); }
        verify(expo, times(1)).send("ExpoPushToken[race-token]", notice);
        assertThat(jdbc.queryForObject("SELECT status FROM fitness.push_delivery_attempts WHERE notification_id=?",
                String.class, notice)).isEqualTo("TICKET_ACCEPTED");
    }

    @Test void attachmentUploadReplayScannerAndDownloadRecheckAuthority() throws Exception {
        UUID student = student(), trainer = trainer();
        UUID relationship = activate(student, trainer);
        byte[] png = new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1};
        when(storage.bucket()).thenReturn("private-chat-test");
        when(storage.get(anyString())).thenReturn(png);
        UUID key = UUID.randomUUID();
        String sent = mvc.perform(multipart("/api/v1/chat/students/{id}/attachments", student)
                        .file(new MockMultipartFile("file", "photo.png", "image/png", png))
                        .param("commandKey", key.toString())
                        .with(jwt().jwt(j -> j.subject(student.toString()))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.attachmentName").value("photo.png"))
                .andReturn().getResponse().getContentAsString();
        UUID attachment = UUID.fromString(com.jayway.jsonpath.JsonPath.read(sent, "$.attachmentId"));
        UUID message = UUID.fromString(com.jayway.jsonpath.JsonPath.read(sent, "$.id"));
        assertThat(notifications.list(trainer, 0, 20).items()).filteredOn(item -> item.eventType().equals("CHAT_MESSAGE"))
                .singleElement().satisfies(item -> assertThat(item.body()).isEqualTo("Bạn có tin nhắn mới")
                        .doesNotContain("photo.png"));
        mvc.perform(multipart("/api/v1/chat/students/{id}/attachments", student)
                        .file(new MockMultipartFile("file", "photo.png", "image/png", png))
                        .param("commandKey", key.toString())
                        .with(jwt().jwt(j -> j.subject(student.toString()))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(message.toString()));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.message_attachments WHERE message_id=?",
                Integer.class, message)).isEqualTo(1);
        mvc.perform(get("/api/v1/chat/attachments/{id}/content", attachment)
                        .with(jwt().jwt(j -> j.subject(trainer.toString()))))
                .andExpect(status().isOk());
        UUID outsider = user("Outsider");
        mvc.perform(get("/api/v1/chat/attachments/{id}/content", attachment)
                        .with(jwt().jwt(j -> j.subject(outsider.toString()))))
                .andExpect(status().isNotFound());
        lifecycle.relationshipAction(student, relationship, "PAUSE", 1, null, UUID.randomUUID());
        mvc.perform(get("/api/v1/chat/attachments/{id}/content", attachment)
                        .with(jwt().jwt(j -> j.subject(trainer.toString()))))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/chat/attachments/{id}/content", attachment)
                        .with(jwt().jwt(j -> j.subject(student.toString()))))
                .andExpect(status().isOk());
    }

    @Test void jpegAliasIsStoredCanonicallyOnlyWhenBytesHaveJpegSignature() throws Exception {
        UUID student = student(), trainer = trainer();
        activate(student, trainer);
        byte[] jpeg = new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xe0, 1};
        when(storage.bucket()).thenReturn("private-chat-test");
        mvc.perform(multipart("/api/v1/chat/students/{id}/attachments", student)
                        .file(new MockMultipartFile("file", "picture.jpg", "image/jpg", jpeg))
                        .param("commandKey", UUID.randomUUID().toString())
                        .with(jwt().jwt(j -> j.subject(student.toString()))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.attachmentContentType").value("image/jpeg"));
        mvc.perform(multipart("/api/v1/chat/students/{id}/attachments", student)
                        .file(new MockMultipartFile("file", "fake.jpg", "image/jpg", "%PDF-1.7".getBytes()))
                        .param("commandKey", UUID.randomUUID().toString())
                        .with(jwt().jwt(j -> j.subject(student.toString()))))
                .andExpect(status().isBadRequest());
    }

    @Test void disabledParticipantsCannotReadRetainedAttachmentOrNoticeAndQueuedPushIsSkipped() throws Exception {
        UUID student = student(), trainer = trainer(), device = UUID.randomUUID();
        activate(student, trainer);
        jdbc.update("INSERT INTO fitness.push_devices(user_id,device_key,expo_token) VALUES (?,?,?)",
                trainer, device, "ExpoPushToken[lifecycle-test]");
        jdbc.update("""
                INSERT INTO fitness.notification_preferences(user_id,event_type,channel,enabled,timezone)
                VALUES (?,'CHAT','PUSH',true,'Etc/UTC')
                """, trainer);
        byte[] png = new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1};
        when(storage.bucket()).thenReturn("private-chat-test");
        when(storage.get(anyString())).thenReturn(png);
        String sent = mvc.perform(multipart("/api/v1/chat/students/{id}/attachments", student)
                        .file(new MockMultipartFile("file", "photo.png", "image/png", png))
                        .param("commandKey", UUID.randomUUID().toString())
                        .with(jwt().jwt(j -> j.subject(student.toString()))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID attachment = UUID.fromString(com.jayway.jsonpath.JsonPath.read(sent, "$.attachmentId"));
        UUID message = UUID.fromString(com.jayway.jsonpath.JsonPath.read(sent, "$.id"));
        UUID notice = jdbc.queryForObject("""
                SELECT id FROM fitness.notifications WHERE recipient_user_id=? AND related_entity_id=?
                """, UUID.class, trainer, message);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.push_delivery_attempts WHERE notification_id=?",
                Integer.class, notice)).isEqualTo(1);

        jdbc.update("UPDATE fitness.users SET status='DISABLED' WHERE id=?", trainer);
        org.mockito.Mockito.clearInvocations(storage);
        mvc.perform(get("/api/v1/chat/attachments/{id}/content", attachment)
                        .with(jwt().jwt(j -> j.subject(trainer.toString()))))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/notifications/me")
                        .with(jwt().jwt(j -> j.subject(trainer.toString()))))
                .andExpect(status().isNotFound());
        pushWorker.dispatch();
        verify(expo, never()).send(anyString(), org.mockito.ArgumentMatchers.eq(notice));
        verify(storage, never()).get(anyString());
        assertThat(jdbc.queryForObject("SELECT status FROM fitness.push_delivery_attempts WHERE notification_id=?",
                String.class, notice)).isEqualTo("SKIPPED");

        mvc.perform(get("/api/v1/chat/attachments/{id}/content", attachment)
                        .with(jwt().jwt(j -> j.subject(student.toString()))))
                .andExpect(status().isOk());
        jdbc.update("UPDATE fitness.users SET status='DISABLED' WHERE id=?", student);
        mvc.perform(get("/api/v1/chat/attachments/{id}/content", attachment)
                        .with(jwt().jwt(j -> j.subject(student.toString()))))
                .andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM fitness.message_attachments ma
                JOIN fitness.media_files mf ON mf.id=ma.media_id
                WHERE ma.id=? AND mf.deleted_at IS NULL
                """, Integer.class, attachment)).isEqualTo(1);
    }

    @Test void accountExportContainsOnlyOwnAuthorizedMessagesAndAttachmentBytes() throws Exception {
        UUID student = student(), trainer = trainer();
        activate(student, trainer);
        byte[] png = new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1};
        when(storage.bucket()).thenReturn("private-chat-test");
        when(storage.get(anyString())).thenReturn(png);
        chat.send(student, student, "My own text", UUID.randomUUID());
        ChatService.Message own = chat.sendAttachment(student, student, "mine.png", "image/png",
                png, UUID.randomUUID());
        chat.send(trainer, student, "Trainer private reply", UUID.randomUUID());
        ChatService.Message other = chat.sendAttachment(trainer, student, "theirs.png", "image/png",
                png, UUID.randomUUID());

        var archive = new ByteArrayOutputStream();
        dataExport.writeExport(student, archive);
        Map<String, byte[]> entries = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive.toByteArray()))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) entries.put(entry.getName(), zip.readAllBytes());
        }
        assertThat(entries.keySet()).contains("account.json", "push-preferences.json", "sent-messages.ndjson",
                "attachments/" + own.attachmentId() + ".png")
                .doesNotContain("attachments/" + other.attachmentId() + ".png");
        assertThat(new String(entries.get("sent-messages.ndjson"), StandardCharsets.UTF_8))
                .contains("My own text", "mine.png").doesNotContain("Trainer private reply", "theirs.png");
        assertThat(entries.get("attachments/" + own.attachmentId() + ".png")).containsExactly(png);
        assertThat(new String(entries.get("account.json"), StandardCharsets.UTF_8))
                .contains(student.toString()).doesNotContain("password_hash", "expo_token");

        var streamed = mvc.perform(get("/api/v1/users/me/data-export")
                        .with(jwt().jwt(j -> j.subject(student.toString()))))
                .andExpect(request().asyncStarted()).andReturn();
        mvc.perform(asyncDispatch(streamed)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));

        jdbc.update("UPDATE fitness.users SET status='DISABLED' WHERE id=?", student);
        assertThatThrownBy(() -> dataExport.writeExport(student, new ByteArrayOutputStream()))
                .isInstanceOf(AccountDataExportService.AccountDataUnavailable.class);
        mvc.perform(get("/api/v1/users/me/data-export")
                        .with(jwt().jwt(j -> j.subject(student.toString()))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("ACCOUNT_DATA_NOT_FOUND"));
    }

    @Test void erasureKeepsOtherPartysMessageButConcealsAndDeletesOnlySendersObject() {
        UUID student = student(), trainer = trainer();
        activate(student, trainer);
        byte[] png = new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1};
        when(storage.bucket()).thenReturn("private-chat-test");
        ChatService.Message sent = chat.sendAttachment(student, student, "private.png", "image/png",
                png, UUID.randomUUID());
        ChatService.Message other = chat.send(trainer, student, "Retained trainer message", UUID.randomUUID());
        chat.acknowledge(student, other.id(), "READ");
        jdbc.update("""
                UPDATE fitness.conversation_members SET is_muted=true,last_read_at=clock_timestamp()
                WHERE conversation_id=? AND user_id=?
                """, sent.conversationId(), student);
        byte[] secret = new byte[32];
        new java.security.SecureRandom().nextBytes(secret);
        String key = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        var request = erasure.request(student, UUID.randomUUID(), key);
        assertThat(jdbc.queryForObject("SELECT body FROM fitness.messages WHERE id=?",
                String.class, sent.id())).isNull();
        assertThat(jdbc.queryForObject("SELECT body FROM fitness.messages WHERE id=?",
                String.class, other.id())).isEqualTo("Retained trainer message");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM fitness.media_files mf JOIN fitness.message_attachments ma ON ma.media_id=mf.id
                WHERE ma.id=? AND mf.deleted_at IS NOT NULL
                """, Integer.class, sent.attachmentId())).isEqualTo(1);
        assertThat(erasureWorker.processOne()).isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM fitness.account_erasure_requests WHERE id=?",
                String.class, request.id())).isEqualTo("DOMAIN_REVIEW_PENDING");
        var page = chat.messages(trainer, sent.conversationId(), 0, 20);
        var redacted = page.items().stream().filter(m -> m.id().equals(sent.id())).findFirst().orElseThrow();
        var retained = page.items().stream().filter(m -> m.id().equals(other.id())).findFirst().orElseThrow();
        assertThat(redacted.senderId()).isNull();
        assertThat(redacted.body()).isNull();
        assertThat(redacted.attachmentId()).isNull();
        assertThat(retained.body()).isEqualTo("Retained trainer message");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM fitness.message_read_receipts
                WHERE user_id=? AND (delivered_at IS NOT NULL OR read_at IS NOT NULL)
                """, Integer.class, student)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM fitness.conversation_members
                WHERE user_id=? AND (last_read_at IS NOT NULL OR is_muted)
                """, Integer.class, student)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM fitness.account_erasure_objects WHERE request_id=? AND status='VERIFIED'
                """, Integer.class, request.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.notifications WHERE recipient_user_id=?",
                Integer.class, student)).isZero();
    }

    @Test void legacyAttachmentWithoutVerifiedOwnerIsConcealedButNotPhysicallyDeleted() {
        UUID student = student(), trainer = trainer();
        activate(student, trainer);
        byte[] png = new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1};
        when(storage.bucket()).thenReturn("private-chat-test");
        var sent = chat.sendAttachment(student, student, "legacy.png", "image/png", png, UUID.randomUUID());
        jdbc.update("""
                UPDATE fitness.media_files mf SET owner_user_id=NULL
                FROM fitness.message_attachments ma WHERE ma.media_id=mf.id AND ma.id=?
                """, sent.attachmentId());
        byte[] secret = new byte[32];
        new java.security.SecureRandom().nextBytes(secret);
        String key = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        var request = erasure.request(student, UUID.randomUUID(), key);
        assertThat(jdbc.queryForObject("SELECT body FROM fitness.messages WHERE id=?",
                String.class, sent.id())).isNull();
        assertThat(jdbc.queryForObject("""
                SELECT o.status FROM fitness.account_erasure_objects o
                JOIN fitness.message_attachments ma ON ma.media_id=o.media_id
                WHERE o.request_id=? AND ma.id=?
                """, String.class, request.id(), sent.attachmentId())).isEqualTo("OWNER_REVIEW_REQUIRED");
        assertThat(chat.messages(trainer, sent.conversationId(), 0, 10).items().getFirst().attachmentId()).isNull();
        assertThat(erasureWorker.processOne()).isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM fitness.account_erasure_requests WHERE id=?",
                String.class, request.id())).isEqualTo("DOMAIN_REVIEW_PENDING");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM fitness.account_erasure_objects
                WHERE request_id=? AND status='VERIFIED'
                """, Integer.class, request.id())).isZero();
    }

    @Test void mediaReferencedByTwoMessagesIsConcealedAndRequiresOwnerReview() {
        UUID student = student(), trainer = trainer();
        activate(student, trainer);
        byte[] png = new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1};
        when(storage.bucket()).thenReturn("private-chat-test");
        var sent = chat.sendAttachment(student, student, "shared.png", "image/png", png, UUID.randomUUID());
        var other = chat.send(trainer, student, "Keep this message", UUID.randomUUID());
        UUID media = jdbc.queryForObject("""
                SELECT media_id FROM fitness.message_attachments WHERE id=?
                """, UUID.class, sent.attachmentId());
        UUID secondLink = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fitness.message_attachments(id,message_id,media_id)
                VALUES (?,?,?)
                """, secondLink, other.id(), media);
        String objectKey = jdbc.queryForObject("SELECT object_key FROM fitness.media_files WHERE id=?",
                String.class, media);
        byte[] secret = new byte[32];
        new java.security.SecureRandom().nextBytes(secret);
        var request = erasure.request(student, UUID.randomUUID(),
                java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(secret));
        assertThat(jdbc.queryForObject("SELECT body FROM fitness.messages WHERE id=?",
                String.class, sent.id())).isNull();
        assertThat(jdbc.queryForObject("SELECT body FROM fitness.messages WHERE id=?",
                String.class, other.id())).isEqualTo("Keep this message");

        assertThat(jdbc.queryForObject("""
                SELECT status FROM fitness.account_erasure_objects WHERE request_id=? AND media_id=?
                """, String.class, request.id(), media)).isEqualTo("OWNER_REVIEW_REQUIRED");
        assertThatThrownBy(() -> chat.download(trainer, sent.attachmentId()))
                .isInstanceOf(ChatFailure.class).hasMessage("CHAT_ATTACHMENT_NOT_FOUND");
        assertThatThrownBy(() -> chat.download(trainer, secondLink))
                .isInstanceOf(ChatFailure.class).hasMessage("CHAT_ATTACHMENT_NOT_FOUND");
        assertThat(erasureWorker.processOne()).isTrue();
        verify(storage, never()).deleteAndVerify("private-chat-test", objectKey);
        assertThat(jdbc.queryForObject("SELECT status FROM fitness.account_erasure_requests WHERE id=?",
                String.class, request.id())).isEqualTo("DOMAIN_REVIEW_PENDING");
        assertThat(chat.messages(trainer, other.conversationId(), 0, 10).items().stream()
                .filter(row -> row.id().equals(other.id())).findFirst().orElseThrow().body())
                .isEqualTo("Keep this message");
    }

    @Test void accountExportStopsStreamingWhenAccountIsDisabledMidResponse() {
        UUID student = student();
        java.io.OutputStream disablingClient = new java.io.OutputStream() {
            private boolean disabled;
            @Override public void write(int value) {
                if (!disabled) {
                    disabled = true;
                    jdbc.update("UPDATE fitness.users SET status='DISABLED' WHERE id=?", student);
                }
            }
            @Override public void write(byte[] bytes, int offset, int length) {
                if (length > 0) write(bytes[offset]);
            }
        };
        assertThatThrownBy(() -> dataExport.writeExport(student, disablingClient))
                .isInstanceOf(AccountDataExportService.AccountDataUnavailable.class);
    }

    @Test void formerTrainerCannotUseExportToBypassClosedPeriodReadAuthority() throws Exception {
        UUID student = student(), trainer = trainer();
        UUID relationship = activate(student, trainer);
        chat.send(trainer, student, "Former-period text", UUID.randomUUID());
        lifecycle.relationshipAction(student, relationship, "PAUSE", 1, null, UUID.randomUUID());
        var archive = new ByteArrayOutputStream();
        dataExport.writeExport(trainer, archive);
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive.toByteArray()))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if ("sent-messages.ndjson".equals(entry.getName())) {
                    assertThat(new String(zip.readAllBytes(), StandardCharsets.UTF_8)).isEmpty();
                }
            }
        }
    }

    @Test void scannerDenialAndSpoofedTypeCommitNoMessageOrObject() throws Exception {
        UUID student = student(), trainer = trainer();
        activate(student, trainer);
        byte[] pdf = "%PDF-1.7".getBytes();
        mvc.perform(multipart("/api/v1/chat/students/{id}/attachments", student)
                        .file(new MockMultipartFile("file", "fake.png", "image/png", pdf))
                        .param("commandKey", UUID.randomUUID().toString())
                        .with(jwt().jwt(j -> j.subject(student.toString()))))
                .andExpect(status().isBadRequest());
        org.mockito.Mockito.doThrow(new com.fitnesscoaching.platform.modules.chat.ChatFailure(
                "CHAT_ATTACHMENT_REJECTED", 400)).when(scanner).requireClean(any());
        mvc.perform(multipart("/api/v1/chat/students/{id}/attachments", student)
                        .file(new MockMultipartFile("file", "bad.pdf", "application/pdf", pdf))
                        .param("commandKey", UUID.randomUUID().toString())
                        .with(jwt().jwt(j -> j.subject(student.toString()))))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.messages WHERE sender_id=?", Integer.class,
                student)).isZero();
        verify(storage, never()).put(anyString(), any(), anyString());
    }

    @Test void httpChatNotificationAndPostCommitSignalRespectPeriodAuthority() throws Exception {
        UUID student = student(), trainer = trainer();
        UUID relationship = activate(student, trainer);
        WebSocketSession device = mock(WebSocketSession.class);
        when(device.getId()).thenReturn("b08-http-" + UUID.randomUUID());
        when(device.getAttributes()).thenReturn(Map.<String, Object>of("actor", trainer));
        when(device.isOpen()).thenReturn(true);
        socket.afterConnectionEstablished(device);
        try {
            socket.handleMessage(device, new TextMessage("{\"type\":\"SUBSCRIBE_INBOX\"}"));
            String sent = mvc.perform(post("/api/v1/chat/students/{id}/messages", student)
                    .with(jwt().jwt(j -> j.subject(student.toString()))).contentType("application/json")
                    .content("{\"body\":\"Xin chao\",\"commandKey\":\"" + UUID.randomUUID() + "\"}"))
                    .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("SENT"))
                    .andReturn().getResponse().getContentAsString();
            UUID messageId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(sent, "$.id"));
            UUID conversationId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(sent, "$.conversationId"));
            var signals = org.mockito.ArgumentCaptor.forClass(TextMessage.class);
            verify(device, times(2)).sendMessage(signals.capture());
            assertThat(signals.getAllValues().get(1).getPayload()).contains("CHAT_UPDATED", conversationId.toString())
                    .doesNotContain("Xin chao");

            mvc.perform(get("/api/v1/notifications/me").with(jwt().jwt(j -> j.subject(trainer.toString()))))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].body").value("Bạn có tin nhắn mới"));
            mvc.perform(post("/api/v1/chat/messages/{id}/receipts", messageId)
                    .with(jwt().jwt(j -> j.subject(trainer.toString()))).contentType("application/json")
                    .content("{\"kind\":\"DELIVERED\"}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DELIVERED"));
            mvc.perform(post("/api/v1/chat/messages/{id}/receipts", messageId)
                    .with(jwt().jwt(j -> j.subject(trainer.toString()))).contentType("application/json")
                    .content("{\"kind\":\"READ\"}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("READ"));
            mvc.perform(get("/api/v1/chat/conversations/{id}/messages", conversationId)
                    .with(jwt().jwt(j -> j.subject(student.toString()))))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].status").value("READ"));

            lifecycle.relationshipAction(student, relationship, "PAUSE", 1, null, UUID.randomUUID());
            mvc.perform(get("/api/v1/chat/conversations/{id}", conversationId)
                    .with(jwt().jwt(j -> j.subject(trainer.toString()))))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.errorCode").value("CHAT_CONVERSATION_NOT_FOUND"));
            mvc.perform(get("/api/v1/notifications/me")
                    .with(jwt().jwt(j -> j.subject(trainer.toString()))))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
            mvc.perform(get("/api/v1/chat/conversations/{id}/messages", conversationId)
                    .with(jwt().jwt(j -> j.subject(student.toString()))))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value(messageId.toString()));

            var resume = lifecycle.createResume(student, relationship, 2, null, UUID.randomUUID());
            lifecycle.resumeAction(trainer, relationship, resume.resume().id(), "ACCEPT", 2, 0, UUID.randomUUID());
            String next = mvc.perform(post("/api/v1/chat/students/{id}/messages", student)
                    .with(jwt().jwt(j -> j.subject(trainer.toString()))).contentType("application/json")
                    .content("{\"body\":\"Period two\",\"commandKey\":\"" + UUID.randomUUID() + "\"}"))
                    .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
            assertThat(UUID.fromString(com.jayway.jsonpath.JsonPath.read(next, "$.conversationId")))
                    .isNotEqualTo(conversationId);
            mvc.perform(get("/api/v1/chat/conversations/{id}", conversationId)
                    .with(jwt().jwt(j -> j.subject(trainer.toString())))).andExpect(status().isNotFound());
        } finally {
            socket.afterConnectionClosed(device, CloseStatus.NORMAL);
        }
    }

    @Test void messageReceiptNotificationAndReplayAreAuthoritative() throws Exception {
        UUID student = student(), trainer = trainer();
        activate(student, trainer);
        UUID key = UUID.randomUUID();
        ChatService.Message sent = chat.send(student, student, "Xin chào", key);
        assertThat(sent.status()).isEqualTo("SENT");
        assertThat(chat.send(student, student, "Xin chào", key).id()).isEqualTo(sent.id());
        assertThatThrownBy(() -> chat.send(student, student, "Nội dung khác", key))
                .isInstanceOf(ChatFailure.class).hasMessage("CHAT_IDEMPOTENCY_CONFLICT");
        assertThat(chat.conversations(student, 0, 20).items()).hasSize(1);
        assertThat(chat.conversations(trainer, 0, 20).items()).hasSize(1);
        assertThat(notifications.list(trainer, 0, 20).items())
                .filteredOn(item -> item.eventType().equals("CHAT_MESSAGE"))
                .singleElement().satisfies(item -> assertThat(item.body()).isEqualTo("Bạn có tin nhắn mới"));
        assertThat(chat.acknowledge(trainer, sent.id(), "DELIVERED").status()).isEqualTo("DELIVERED");
        assertThat(chat.acknowledge(trainer, sent.id(), "READ").status()).isEqualTo("READ");
        assertThat(chat.acknowledge(trainer, sent.id(), "DELIVERED").status()).isEqualTo("READ");
        assertThatThrownBy(() -> chat.acknowledge(student, sent.id(), "READ"))
                .isInstanceOf(ChatFailure.class).hasMessage("CHAT_MESSAGE_NOT_FOUND");
        mvc.perform(get("/api/v1/chat/conversations/{id}/messages", sent.conversationId())
                .with(jwt().jwt(j -> j.subject(student.toString()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].status").value("READ"));
        mvc.perform(post("/api/v1/chat/students/{id}/messages", student)
                .with(jwt().jwt(j -> j.subject(trainer.toString()))).contentType("application/json")
                .content("{\"body\":\"Phản hồi\",\"commandKey\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("SENT"));
    }

    @Test void pauseConcealsTrainerAndResumeCreatesNewPeriodConversation() {
        UUID student = student(), trainer = trainer();
        UUID relationship = activate(student, trainer);
        ChatService.Message old = chat.send(student, student, "Period 1", UUID.randomUUID());
        assertThat(chat.detail(student, old.conversationId()).sendAllowed()).isTrue();
        lifecycle.relationshipAction(student, relationship, "PAUSE", 1, null, UUID.randomUUID());
        assertThat(chat.conversations(trainer, 0, 20).items()).isEmpty();
        assertThat(notifications.list(trainer, 0, 20).items()).isEmpty();
        assertThat(chat.conversations(student, 0, 20).items()).hasSize(1);
        assertThat(chat.detail(student, old.conversationId()).sendAllowed()).isFalse();
        assertThatThrownBy(() -> chat.detail(trainer, old.conversationId()))
                .isInstanceOf(ChatFailure.class).hasMessage("CHAT_CONVERSATION_NOT_FOUND");
        var resume = lifecycle.createResume(student, relationship, 2, null, UUID.randomUUID());
        lifecycle.resumeAction(trainer, relationship, resume.resume().id(), "ACCEPT", 2, 0, UUID.randomUUID());
        ChatService.Message next = chat.send(student, student, "Period 2", UUID.randomUUID());
        assertThat(next.conversationId()).isNotEqualTo(old.conversationId());
        assertThat(chat.conversations(student, 0, 20).items()).hasSize(2);
        assertThat(chat.detail(student, old.conversationId()).sendAllowed()).isFalse();
        assertThat(chat.detail(student, next.conversationId()).sendAllowed()).isTrue();
        assertThat(chat.conversations(trainer, 0, 20).items()).extracting("id")
                .containsExactly(next.conversationId());
        lifecycle.relationshipAction(trainer, relationship, "END", 3, "Đã kết thúc", UUID.randomUUID());
        assertThat(chat.conversations(student, 0, 20).items()).hasSize(2);
        assertThat(chat.conversations(trainer, 0, 20).items()).isEmpty();
        assertThat(notifications.list(trainer, 0, 20).items()).isEmpty();
    }

    @Test void concurrentFirstMessagesCreateExactlyOneConversationForThePeriod() throws Exception {
        UUID student = student(), trainer = trainer();
        activate(student, trainer);
        var start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> { start.await(); return chat.send(student, student, "First", UUID.randomUUID()); });
            var second = workers.submit(() -> { start.await(); return chat.send(trainer, student, "Second", UUID.randomUUID()); });
            start.countDown();
            assertThat(first.get(15, TimeUnit.SECONDS).conversationId())
                    .isEqualTo(second.get(15, TimeUnit.SECONDS).conversationId());
        }
        assertThat(chat.conversations(student, 0, 20).items()).hasSize(1);
        assertThat(chat.messages(student, chat.conversations(student, 0, 20).items().getFirst().id(), 0, 20).items())
                .hasSize(2);
    }

    @Test void notificationFailureRollsBackMessageAndReceipt() {
        UUID student = student(), trainer = trainer();
        activate(student, trainer);
        jdbc.execute("ALTER TABLE fitness.notifications ADD CONSTRAINT b08_test_reject_notice CHECK (event_type <> 'CHAT_MESSAGE') NOT VALID");
        try {
            assertThatThrownBy(() -> chat.send(student, student, "Must roll back", UUID.randomUUID()))
                    .isInstanceOf(RuntimeException.class);
            assertThat(chat.conversations(student, 0, 20).items()).isEmpty();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.messages WHERE sender_id=?", Integer.class, student))
                    .isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.chat_command_receipts WHERE actor_id=?", Integer.class, student))
                    .isZero();
        } finally {
            jdbc.execute("ALTER TABLE fitness.notifications DROP CONSTRAINT b08_test_reject_notice");
        }
    }

    private UUID activate(UUID student, UUID trainer) {
        var pending = lifecycle.initiate(student, student, trainer, UUID.randomUUID());
        lifecycle.relationshipAction(trainer, pending.relationship().id(), "ACCEPT", 0, null, UUID.randomUUID());
        return pending.relationship().id();
    }
    private UUID student() {
        UUID id = user("Student");
        jdbc.update("INSERT INTO fitness.student_profiles(user_id) VALUES (?)", id);
        jdbc.update("INSERT INTO fitness.user_roles(user_id,role_id,assigned_by,assigned_at) "
                + "SELECT ?,id,?,clock_timestamp() FROM fitness.roles WHERE code='STUDENT'", id, id);
        return id;
    }
    private UUID trainer() {
        UUID id = user("Trainer");
        jdbc.update("INSERT INTO fitness.user_roles(user_id,role_id,assigned_by,assigned_at) "
                + "SELECT ?,id,?,clock_timestamp() FROM fitness.roles WHERE code='TRAINER'", id, id);
        jdbc.update("INSERT INTO fitness.trainer_profiles "
                + "(user_id,verification_status,verified_at,is_accepting_students,is_active) "
                + "VALUES (?,'VERIFIED',clock_timestamp(),true,true)", id);
        return id;
    }
    private UUID user(String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO fitness.users(id,email,password_hash,display_name,status,preferred_locale,timezone) "
                + "VALUES (?,?,'hash',?,'ACTIVE','vi-VN','Etc/UTC')", id, id + "@example.com", name);
        return id;
    }
}
