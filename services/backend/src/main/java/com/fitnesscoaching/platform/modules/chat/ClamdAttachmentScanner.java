package com.fitnesscoaching.platform.modules.chat;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/** Fail-closed clamd INSTREAM client. The TCP endpoint must be private to the backend network. */
@Component
public class ClamdAttachmentScanner {
    private final String host;
    private final int port;

    public ClamdAttachmentScanner(@Value("${chat.attachments.clamd.host:}") String host,
                                  @Value("${chat.attachments.clamd.port:3310}") int port) {
        this.host = host;
        this.port = port;
    }

    public void requireClean(byte[] bytes) {
        if (host.isBlank()) throw new ChatFailure("CHAT_ATTACHMENT_SCANNER_UNAVAILABLE", 503);
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 3000);
            socket.setSoTimeout(15000);
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            out.write("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII));
            out.writeInt(bytes.length);
            out.write(bytes);
            out.writeInt(0);
            out.flush();
            byte[] reply = socket.getInputStream().readNBytes(512);
            String verdict = new String(reply, StandardCharsets.US_ASCII);
            if (verdict.startsWith("stream: OK")) return;
            if (verdict.contains("FOUND")) throw new ChatFailure("CHAT_ATTACHMENT_REJECTED", 400);
            throw new ChatFailure("CHAT_ATTACHMENT_SCANNER_UNAVAILABLE", 503);
        } catch (ChatFailure failure) {
            throw failure;
        } catch (Exception unavailable) {
            throw new ChatFailure("CHAT_ATTACHMENT_SCANNER_UNAVAILABLE", 503);
        }
    }
}
