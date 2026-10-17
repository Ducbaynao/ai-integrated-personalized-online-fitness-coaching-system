package com.fitnesscoaching.platform;

import com.fitnesscoaching.platform.modules.chat.ChatFailure;
import com.fitnesscoaching.platform.modules.chat.ChatService;
import com.fitnesscoaching.platform.modules.coaching.application.port.in.CoachingLifecycleUseCase;
import com.fitnesscoaching.platform.modules.notification.InAppNotificationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
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
    @Autowired CoachingLifecycleUseCase lifecycle;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;

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
        assertThat(notifications.list(trainer, 0, 20).items()).hasSize(1);
        assertThat(notifications.list(trainer, 0, 20).items().getFirst().body()).isEqualTo("Bạn có tin nhắn mới");
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
