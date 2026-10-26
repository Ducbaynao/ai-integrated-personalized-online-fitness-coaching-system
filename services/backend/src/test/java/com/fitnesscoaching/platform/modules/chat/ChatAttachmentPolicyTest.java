package com.fitnesscoaching.platform.modules.chat;

import com.fitnesscoaching.platform.common.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.nio.charset.StandardCharsets;
import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatAttachmentPolicyTest {
    @Test void containerMultipartLimitHasStableClientError() {
        var request = new MockHttpServletRequest("POST", "/api/v1/chat/students/student-id/attachments");
        var response = new GlobalExceptionHandler(Clock.systemUTC())
                .handleMaxUploadSize(new MaxUploadSizeExceededException(ChatAttachmentPolicy.MAX_BYTES), request);
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().errorCode()).isEqualTo("CHAT_ATTACHMENT_SIZE_INVALID");
    }

    @Test void acceptsOnlyMatchingSupportedSignatures() {
        assertThat(ChatAttachmentPolicy.verifiedType(new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 1},
                "image/jpeg")).isEqualTo("image/jpeg");
        assertThat(ChatAttachmentPolicy.verifiedType(new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xe0, 1},
                "image/jpg")).isEqualTo("image/jpeg");
        assertThat(ChatAttachmentPolicy.verifiedType(new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47,
                0x0d, 0x0a, 0x1a, 0x0a}, "image/png")).isEqualTo("image/png");
        assertThat(ChatAttachmentPolicy.verifiedType("%PDF-1.7".getBytes(StandardCharsets.US_ASCII),
                "application/pdf")).isEqualTo("application/pdf");
    }

    @Test void rejectsSpoofedUnsupportedAndOversizedFiles() {
        assertThatThrownBy(() -> ChatAttachmentPolicy.verifiedType("<script>".getBytes(), "image/png"))
                .isInstanceOf(ChatFailure.class).hasMessage("CHAT_ATTACHMENT_TYPE_INVALID");
        assertThatThrownBy(() -> ChatAttachmentPolicy.verifiedType("%PDF-1.7".getBytes(), "image/png"))
                .isInstanceOf(ChatFailure.class).hasMessage("CHAT_ATTACHMENT_TYPE_INVALID");
        assertThatThrownBy(() -> ChatAttachmentPolicy.verifiedType("%PDF-1.7".getBytes(), "image/jpg"))
                .isInstanceOf(ChatFailure.class).hasMessage("CHAT_ATTACHMENT_TYPE_INVALID");
        assertThatThrownBy(() -> ChatAttachmentPolicy.verifiedType(new byte[0], "application/pdf"))
                .isInstanceOf(ChatFailure.class).hasMessage("CHAT_ATTACHMENT_SIZE_INVALID");
        assertThatThrownBy(() -> ChatAttachmentPolicy.verifiedType(new byte[ChatAttachmentPolicy.MAX_BYTES + 1],
                "application/pdf")).isInstanceOf(ChatFailure.class).hasMessage("CHAT_ATTACHMENT_SIZE_INVALID");
    }
}
