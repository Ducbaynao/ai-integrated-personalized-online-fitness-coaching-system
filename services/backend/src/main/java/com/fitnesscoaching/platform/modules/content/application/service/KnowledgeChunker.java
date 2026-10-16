package com.fitnesscoaching.platform.modules.content.application.service;

import tools.jackson.databind.ObjectMapper;
import com.fitnesscoaching.platform.modules.content.domain.KnowledgeChunk;
import com.fitnesscoaching.platform.modules.content.domain.KnowledgeDocument;
import com.fitnesscoaching.platform.modules.content.domain.KnowledgeVersion;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic chunking engine for governed knowledge documents.
 * Splits markdown content by headings and paragraph boundaries while preserving provenance and heading context.
 * Strictly excludes evaluation cases and holdouts from being chunked into the corpus.
 */
@Component
public class KnowledgeChunker {

    public static final String CHUNKING_VERSION = "1.0.0";
    private static final int MAX_CHUNK_CHARS = 1600;
    private static final Pattern HEADING_PATTERN = Pattern.compile("^(#{1,6})\\s+(.+)$", Pattern.MULTILINE);

    private final ObjectMapper objectMapper;

    public KnowledgeChunker(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<KnowledgeChunk> chunk(KnowledgeVersion version, KnowledgeDocument document, Instant now) {
        if (version == null) {
            throw new IllegalArgumentException("KnowledgeVersion must not be null");
        }
        if (document == null) {
            throw new IllegalArgumentException("KnowledgeDocument must not be null");
        }

        String contentPath = version.contentPath() != null ? version.contentPath().toLowerCase() : "";
        // Strict boundary: evaluation cases and holdouts must NEVER be chunked or indexed into the retrieval corpus
        if (contentPath.contains("eval") || contentPath.contains("candidate-cases") || contentPath.contains("holdout") || contentPath.contains("benchmark")) {
            throw new IllegalArgumentException("Evaluation, holdout, or test files must never be chunked for retrieval index: " + version.contentPath());
        }

        String rawContent = version.content();
        if (rawContent == null || rawContent.isBlank()) {
            return List.of();
        }

        List<Section> sections = parseSections(rawContent);
        List<KnowledgeChunk> chunks = new ArrayList<>();
        int chunkIndex = 0;

        for (Section section : sections) {
            List<String> textBlocks = splitIntoBlocks(section.body(), MAX_CHUNK_CHARS);
            for (String block : textBlocks) {
                if (block.isBlank()) {
                    continue;
                }

                String blockTrimmed = block.trim();
                String headingPath = section.headingPath();
                int tokenCount = estimateTokenCount(blockTrimmed);
                String chunkHash = computeSha256(blockTrimmed);

                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("documentCode", document.documentCode());
                metadata.put("versionLabel", version.versionLabel());
                metadata.put("sourceId", document.sourceId());
                metadata.put("sourcePath", version.contentPath());
                metadata.put("contentHash", version.contentHash());
                metadata.put("chunkHash", chunkHash);
                metadata.put("lineageBaseDocumentId", version.lineageBaseDocumentId());
                metadata.put("lineageBaseVersion", version.lineageBaseVersion());
                metadata.put("lineageBaseHash", version.lineageBaseHash());
                metadata.put("chunkingVersion", CHUNKING_VERSION);
                metadata.put("heading", section.heading());
                metadata.put("sectionIndex", chunkIndex);

                String metadataJson;
                try {
                    metadataJson = objectMapper.writeValueAsString(metadata);
                } catch (Exception e) {
                    metadataJson = "{}";
                }

                // Deterministic UUID derived from document code, version label, and chunk index
                UUID chunkId = UUID.nameUUIDFromBytes(
                        (document.documentCode() + ":" + version.versionLabel() + ":" + chunkIndex).getBytes(StandardCharsets.UTF_8)
                );

                chunks.add(new KnowledgeChunk(
                        chunkId,
                        version.id(),
                        chunkIndex,
                        headingPath,
                        blockTrimmed,
                        tokenCount,
                        metadataJson,
                        now
                ));

                chunkIndex++;
            }
        }

        return chunks;
    }

    private String computeSha256(String text) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }

    private List<Section> parseSections(String markdown) {
        List<Section> result = new ArrayList<>();
        Matcher matcher = HEADING_PATTERN.matcher(markdown);

        List<Integer> headingStarts = new ArrayList<>();
        List<String> headings = new ArrayList<>();
        List<Integer> headingLevels = new ArrayList<>();

        while (matcher.find()) {
            headingStarts.add(matcher.start());
            headingLevels.add(matcher.group(1).length());
            headings.add(matcher.group(2).trim());
        }

        if (headingStarts.isEmpty()) {
            // No headings: entire document is one section
            result.add(new Section("Tổng quan", "Tổng quan", markdown.trim()));
            return result;
        }

        // Preamble before first heading if any
        if (headingStarts.get(0) > 0) {
            String preamble = markdown.substring(0, headingStarts.get(0)).trim();
            if (!preamble.isBlank()) {
                result.add(new Section("Lời mở đầu", "Lời mở đầu", preamble));
            }
        }

        List<String> hierarchy = new ArrayList<>();

        for (int i = 0; i < headingStarts.size(); i++) {
            int startPos = headingStarts.get(i);
            int endPos = (i + 1 < headingStarts.size()) ? headingStarts.get(i + 1) : markdown.length();
            String sectionText = markdown.substring(startPos, endPos).trim();

            int level = headingLevels.get(i);
            String title = headings.get(i);

            // Update hierarchy stack
            while (hierarchy.size() >= level) {
                hierarchy.remove(hierarchy.size() - 1);
            }
            hierarchy.add(title);
            String headingPath = String.join(" > ", hierarchy);

            result.add(new Section(title, headingPath, sectionText));
        }

        return result;
    }

    private List<String> splitIntoBlocks(String text, int maxChars) {
        if (text.length() <= maxChars) {
            return List.of(text);
        }

        List<String> blocks = new ArrayList<>();
        String[] paragraphs = text.split("\n\n+");
        StringBuilder currentBlock = new StringBuilder();

        for (String para : paragraphs) {
            if (currentBlock.length() + para.length() + 2 > maxChars && currentBlock.length() > 0) {
                blocks.add(currentBlock.toString().trim());
                currentBlock.setLength(0);
            }

            if (para.length() > maxChars) {
                // Large paragraph: split by sentences
                String[] sentences = para.split("(?<=[.!?])\\s+");
                for (String sentence : sentences) {
                    if (sentence.length() > maxChars) {
                        // Very long sentence: split by words
                        String[] words = sentence.split("\\s+");
                        for (String word : words) {
                            if (word.length() > maxChars) {
                                // Word itself exceeds maxChars: slice hard
                                for (int i = 0; i < word.length(); i += maxChars) {
                                    String slice = word.substring(i, Math.min(i + maxChars, word.length()));
                                    if (currentBlock.length() + slice.length() + 1 > maxChars && currentBlock.length() > 0) {
                                        blocks.add(currentBlock.toString().trim());
                                        currentBlock.setLength(0);
                                    }
                                    if (currentBlock.length() > 0) {
                                        currentBlock.append(" ");
                                    }
                                    currentBlock.append(slice);
                                }
                            } else {
                                if (currentBlock.length() + word.length() + 1 > maxChars && currentBlock.length() > 0) {
                                    blocks.add(currentBlock.toString().trim());
                                    currentBlock.setLength(0);
                                }
                                if (currentBlock.length() > 0) {
                                    currentBlock.append(" ");
                                }
                                currentBlock.append(word);
                            }
                        }
                    } else {
                        if (currentBlock.length() + sentence.length() + 1 > maxChars && currentBlock.length() > 0) {
                            blocks.add(currentBlock.toString().trim());
                            currentBlock.setLength(0);
                        }
                        if (currentBlock.length() > 0) {
                            currentBlock.append(" ");
                        }
                        currentBlock.append(sentence);
                    }
                }
            } else {
                if (currentBlock.length() > 0) {
                    currentBlock.append("\n\n");
                }
                currentBlock.append(para);
            }
        }

        if (currentBlock.length() > 0) {
            blocks.add(currentBlock.toString().trim());
        }

        return blocks;
    }

    private int estimateTokenCount(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        String[] words = text.trim().split("\\s+");
        return (int) Math.ceil(words.length * 1.3);
    }

    private record Section(String heading, String headingPath, String body) {}
}
