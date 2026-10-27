package com.fitnesscoaching.platform.modules.user.application.service;

import com.fitnesscoaching.platform.modules.auth.domain.AccountStatus;
import com.fitnesscoaching.platform.modules.chat.ChatOwnedExportQuery;
import com.fitnesscoaching.platform.modules.chat.ChatService;
import com.fitnesscoaching.platform.modules.notification.PushPreferenceQuery;
import com.fitnesscoaching.platform.modules.user.application.model.CurrentUserView;
import com.fitnesscoaching.platform.modules.user.application.port.in.CurrentUserQuery;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserAccountStatusQuery;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AccountDataExportStreamTest {
    private static final UUID ACTOR = UUID.randomUUID();
    private static final UUID MESSAGE = UUID.randomUUID();
    private static final UUID CONVERSATION = UUID.randomUUID();
    private static final UUID ATTACHMENT = UUID.randomUUID();

    private final CurrentUserQuery users = mock(CurrentUserQuery.class);
    private final UserAccountStatusQuery accounts = mock(UserAccountStatusQuery.class);
    private final ChatOwnedExportQuery chat = mock(ChatOwnedExportQuery.class);
    private final PushPreferenceQuery preferences = mock(PushPreferenceQuery.class);
    private final AccountDataExportService export = new AccountDataExportService(
            users, accounts, chat, preferences, new ObjectMapper());

    private void fixture() {
        when(users.findCurrentUserById(ACTOR)).thenReturn(Optional.of(new CurrentUserView(
                ACTOR, "own@example.test", "Own Account", AccountStatus.ACTIVE, "vi-VN",
                "Asia/Ho_Chi_Minh", null, Instant.parse("2026-01-01T00:00:00Z"),
                null, List.of("STUDENT"), null, null)));
        when(preferences.preferencesForExport(ACTOR)).thenReturn(List.of());
        when(accounts.getAccountStatus(ACTOR)).thenReturn(Optional.of(AccountStatus.ACTIVE));
        when(chat.ownMessages(ACTOR)).thenReturn(List.of());
    }

    @Test void disabledAfterCompletedEntryDoesNotAppendCentralDirectoryOrNextEntry() throws Exception {
        fixture();
        AtomicInteger checks = new AtomicInteger();
        when(accounts.getAccountStatus(ACTOR)).thenAnswer(invocation ->
                Optional.of(checks.incrementAndGet() >= 4 ? AccountStatus.DISABLED : AccountStatus.ACTIVE));
        ByteArrayOutputStream response = new ByteArrayOutputStream();

        assertThatThrownBy(() -> export.writeExport(ACTOR, response))
                .isInstanceOf(AccountDataExportService.AccountDataUnavailable.class);
        byte[] bytes = response.toByteArray();
        assertThat(entryNames(bytes)).containsExactly("account.json");
        assertThat(hasEndOfCentralDirectory(bytes)).isFalse();
        assertThat(new String(bytes)).doesNotContain("push-preferences.json");
    }

    @Test void disabledBeforeFollowingEntryDoesNotWriteItsHeaderOrCentralDirectory() throws Exception {
        fixture();
        ByteArrayOutputStream response = new ByteArrayOutputStream();
        AtomicInteger beforeRevocation = new AtomicInteger(-1);
        when(chat.ownMessages(ACTOR)).thenAnswer(invocation -> {
            beforeRevocation.set(response.size());
            when(accounts.getAccountStatus(ACTOR)).thenReturn(Optional.of(AccountStatus.DISABLED));
            return List.of();
        });

        assertThatThrownBy(() -> export.writeExport(ACTOR, response))
                .isInstanceOf(AccountDataExportService.AccountDataUnavailable.class);
        assertThat(response.size()).isEqualTo(beforeRevocation.get());
        assertThat(entryNames(response.toByteArray())).containsExactly("account.json", "push-preferences.json");
        assertThat(hasEndOfCentralDirectory(response.toByteArray())).isFalse();
    }

    @Test void trainerLosingReadAuthorityDuringLargeAttachmentStopsAtNextCheckWithoutZipTrailer() throws Exception {
        fixture();
        byte[] large = new byte[1024 * 1024];
        new java.util.Random(7).nextBytes(large);
        var message = new ChatOwnedExportQuery.OwnMessage(MESSAGE, CONVERSATION, "mine",
                Instant.parse("2026-01-02T00:00:00Z"), ATTACHMENT, "mine.pdf", "application/pdf");
        when(chat.ownMessages(ACTOR)).thenReturn(List.of(message));
        when(chat.ownAttachment(ACTOR, ATTACHMENT))
                .thenReturn(new ChatService.AttachmentContent(large, "application/pdf", "mine.pdf"));
        AtomicBoolean revoked = new AtomicBoolean();
        RevokingOutputStream response = new RevokingOutputStream(revoked);
        AtomicInteger sizeAtFailedCheck = new AtomicInteger(-1);
        doAnswer(invocation -> {
            if (revoked.get()) {
                sizeAtFailedCheck.set(response.size());
                throw new IllegalStateException("Trainer period no longer readable");
            }
            return null;
        }).when(chat).requireOwnMessageReadable(ACTOR, MESSAGE);

        assertThatThrownBy(() -> export.writeExport(ACTOR, response))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no longer readable");
        assertThat(response.sizeAtRevocation).isGreaterThan(0);
        // Bytes emitted by an already-authorized write cannot be revoked. The
        // boundary is the next failed authority check, not the revoke instant.
        assertThat(sizeAtFailedCheck.get()).isGreaterThan(response.sizeAtRevocation);
        assertThat(response.size()).isEqualTo(sizeAtFailedCheck.get());
        assertThat(entryNames(response.toByteArray())).contains("account.json", "push-preferences.json", "sent-messages.ndjson");
        assertThat(hasEndOfCentralDirectory(response.toByteArray())).isFalse();
        assertThat(extractableAttachmentPrefix(response.toByteArray())).isGreaterThan(0);
    }

    private static List<String> entryNames(byte[] bytes) throws IOException {
        var names = new java.util.ArrayList<String>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                names.add(entry.getName());
                try { zip.readAllBytes(); }
                catch (IOException truncatedEntry) { break; }
            }
        }
        return names;
    }

    private static int extractableAttachmentPrefix(byte[] bytes) throws IOException {
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.getName().startsWith("attachments/")) {
                    int total = 0;
                    byte[] chunk = new byte[4096];
                    try { for (int read; (read = zip.read(chunk)) != -1;) total += read; }
                    catch (IOException truncatedEntry) { /* bytes before truncation were already delivered */ }
                    return total;
                }
                zip.readAllBytes();
            }
        }
        return 0;
    }

    private static boolean hasEndOfCentralDirectory(byte[] bytes) {
        for (int i = Math.max(0, bytes.length - 65557); i <= bytes.length - 4; i++)
            if (bytes[i] == 0x50 && bytes[i + 1] == 0x4b && bytes[i + 2] == 0x05 && bytes[i + 3] == 0x06)
                return true;
        return false;
    }

    private static final class RevokingOutputStream extends ByteArrayOutputStream {
        private final AtomicBoolean revoked;
        private int sizeAtRevocation = -1;
        private RevokingOutputStream(AtomicBoolean revoked) { this.revoked = revoked; }
        @Override public synchronized void write(byte[] bytes, int offset, int length) {
            super.write(bytes, offset, length);
            if (sizeAtRevocation < 0 && size() > 160_000) {
                sizeAtRevocation = size();
                revoked.set(true);
            }
        }
        @Override public synchronized void write(int value) {
            super.write(value);
            if (sizeAtRevocation < 0 && size() > 160_000) {
                sizeAtRevocation = size();
                revoked.set(true);
            }
        }
    }
}
