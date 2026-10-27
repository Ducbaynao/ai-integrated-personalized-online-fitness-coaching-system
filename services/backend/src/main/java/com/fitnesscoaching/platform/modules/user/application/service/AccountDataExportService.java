package com.fitnesscoaching.platform.modules.user.application.service;

import tools.jackson.databind.ObjectMapper;
import com.fitnesscoaching.platform.modules.auth.domain.AccountStatus;
import com.fitnesscoaching.platform.modules.chat.ChatOwnedExportQuery;
import com.fitnesscoaching.platform.modules.notification.PushPreferenceQuery;
import com.fitnesscoaching.platform.modules.user.application.model.CurrentUserView;
import com.fitnesscoaching.platform.modules.user.application.port.in.CurrentUserQuery;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserAccountStatusQuery;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Account-owned export composition. No server-side archive or reusable download URL is created. */
@Service
public class AccountDataExportService {
    private final CurrentUserQuery users;
    private final UserAccountStatusQuery status;
    private final ChatOwnedExportQuery chat;
    private final PushPreferenceQuery preferences;
    private final ObjectMapper json;

    public AccountDataExportService(CurrentUserQuery users, UserAccountStatusQuery status,
                                    ChatOwnedExportQuery chat, PushPreferenceQuery preferences,
                                    ObjectMapper json) {
        this.users = users; this.status = status; this.chat = chat;
        this.preferences = preferences; this.json = json;
    }

    public void requireActive(UUID actor) {
        if (actor == null || status.getAccountStatus(actor).orElse(null) != AccountStatus.ACTIVE)
            throw new AccountDataUnavailable();
    }

    public void writeExport(UUID actor, OutputStream target) throws IOException {
        requireActive(actor);
        CurrentUserView user = users.findCurrentUserById(actor).orElseThrow(AccountDataUnavailable::new);
        AbortableZipOutputStream zip = new AbortableZipOutputStream(target);
        try {
            write(zip, actor, "account.json", json.writeValueAsBytes(new AccountSnapshot(
                    user.id(), user.email(), user.displayName(), user.phoneNumber(),
                    user.preferredLocale(), user.timezone(), user.createdAt(), user.settings())));
            write(zip, actor, "push-preferences.json", json.writeValueAsBytes(
                    preferences.preferencesForExport(actor)));
            var ownMessages = chat.ownMessages(actor);
            requireActive(actor);
            zip.putNextEntry(new ZipEntry("sent-messages.ndjson"));
            for (var message : ownMessages) {
                chat.requireOwnMessageReadable(actor, message.id());
                writeBytes(zip, actor, message.id(), json.writeValueAsBytes(message));
                writeBytes(zip, actor, message.id(), new byte[] {'\n'});
            }
            zip.closeEntry();
            for (var message : ownMessages) {
                if (message.attachmentId() == null) continue;
                requireActive(actor);
                var file = chat.ownAttachment(actor, message.attachmentId());
                String extension = switch (file.contentType()) {
                    case "image/jpeg" -> ".jpg";
                    case "image/png" -> ".png";
                    case "application/pdf" -> ".pdf";
                    default -> throw new IllegalStateException("Unapproved attachment type in export");
                };
                write(zip, actor, message.id(), "attachments/" + message.attachmentId() + extension, file.bytes());
            }
            requireActive(actor);
            zip.finish();
        } finally {
            // ZipOutputStream.close() calls finish() even after an authority failure.
            // Release native compression state without writing a trailer or closing
            // the caller-owned HTTP response stream on either path.
            zip.abort();
        }
    }

    private void write(ZipOutputStream zip, UUID actor, String name, byte[] bytes) throws IOException {
        write(zip, actor, null, name, bytes);
    }

    private void write(ZipOutputStream zip, UUID actor, UUID messageId, String name, byte[] bytes) throws IOException {
        requireActive(actor);
        zip.putNextEntry(new ZipEntry(name));
        writeBytes(zip, actor, messageId, bytes);
        zip.closeEntry();
    }

    private void writeBytes(ZipOutputStream zip, UUID actor, UUID messageId, byte[] bytes) throws IOException {
        for (int offset = 0; offset < bytes.length; offset += 65536) {
            requireActive(actor);
            if (messageId != null) chat.requireOwnMessageReadable(actor, messageId);
            zip.write(bytes, offset, Math.min(65536, bytes.length - offset));
        }
    }

    private record AccountSnapshot(UUID id, String email, String displayName, String phoneNumber,
                                   String preferredLocale, String timezone, java.time.Instant createdAt,
                                   com.fitnesscoaching.platform.modules.user.application.model.UserSettingsView settings) {}

    public static final class AccountDataUnavailable extends RuntimeException {}

    private static final class AbortableZipOutputStream extends ZipOutputStream {
        private AbortableZipOutputStream(OutputStream target) {
            super(target, StandardCharsets.UTF_8);
        }

        private void abort() { def.end(); }
    }
}
