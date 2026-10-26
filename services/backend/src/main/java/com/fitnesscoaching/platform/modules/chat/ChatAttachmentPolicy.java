package com.fitnesscoaching.platform.modules.chat;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

final class ChatAttachmentPolicy {
    static final int MAX_BYTES = 10 * 1024 * 1024;

    private ChatAttachmentPolicy() {}

    static String verifiedType(byte[] bytes, String declared) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES)
            throw new ChatFailure("CHAT_ATTACHMENT_SIZE_INVALID", 400);
        String actual;
        if (starts(bytes, new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff})) {
            actual = "image/jpeg";
        } else if (starts(bytes, new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a})) {
            actual = "image/png";
        } else if (starts(bytes, "%PDF-".getBytes(StandardCharsets.US_ASCII))) {
            actual = "application/pdf";
        } else {
            throw new ChatFailure("CHAT_ATTACHMENT_TYPE_INVALID", 400);
        }
        String canonicalDeclared = "image/jpg".equalsIgnoreCase(declared) ? "image/jpeg" : declared;
        if (!actual.equals(canonicalDeclared)) throw new ChatFailure("CHAT_ATTACHMENT_TYPE_INVALID", 400);
        return actual;
    }

    private static boolean starts(byte[] bytes, byte[] prefix) {
        return bytes.length >= prefix.length && Arrays.equals(bytes, 0, prefix.length, prefix, 0, prefix.length);
    }
}
