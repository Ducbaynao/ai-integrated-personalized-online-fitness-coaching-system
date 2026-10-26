package com.fitnesscoaching.platform.modules.chat;

import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClamdAttachmentScannerTest {
    @Test void cleanFoundAndScannerFailureAreDistinct() throws Exception {
        scanReply("stream: OK\0", false);
        scanReply("stream: Eicar-Test-Signature FOUND\0", true);
        assertThatThrownBy(() -> new ClamdAttachmentScanner("", 3310).requireClean(new byte[] {1}))
                .isInstanceOf(ChatFailure.class).hasMessage("CHAT_ATTACHMENT_SCANNER_UNAVAILABLE");
    }

    private static void scanReply(String reply, boolean reject) throws Exception {
        try (ServerSocket server = new ServerSocket(0);
             var workers = Executors.newSingleThreadExecutor()) {
            var reader = workers.submit(() -> {
                try (var connection = server.accept()) {
                    var in = connection.getInputStream();
                    byte[] command = in.readNBytes(10);
                    if (!"zINSTREAM\0".equals(new String(command, StandardCharsets.US_ASCII)))
                        throw new AssertionError("Unexpected clamd command");
                    int length = java.nio.ByteBuffer.wrap(in.readNBytes(4)).getInt();
                    if (length != 3) throw new AssertionError("Unexpected chunk length");
                    in.readNBytes(length);
                    if (java.nio.ByteBuffer.wrap(in.readNBytes(4)).getInt() != 0)
                        throw new AssertionError("Missing stream terminator");
                    connection.getOutputStream().write(reply.getBytes(StandardCharsets.US_ASCII));
                }
                return true;
            });
            ClamdAttachmentScanner scanner = new ClamdAttachmentScanner("127.0.0.1", server.getLocalPort());
            if (reject) assertThatThrownBy(() -> scanner.requireClean(new byte[] {1, 2, 3}))
                    .isInstanceOf(ChatFailure.class).hasMessage("CHAT_ATTACHMENT_REJECTED");
            else scanner.requireClean(new byte[] {1, 2, 3});
            reader.get();
        }
    }
}
