package com.example.llmwiki.service;

import jakarta.enterprise.context.ApplicationScoped;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.UUID;

@Slf4j
@ApplicationScoped
public class IngestAdapter {

    public NormalizedInput normalizeFromText(String text, String title) {
        return normalizeFromText(text, title, "note");
    }

    public NormalizedInput normalizeFromText(String text, String title, String sourceType) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Text cannot be empty");
        }

        String sourceId = generateSourceId(text);
        String syntheticUrl = "internal://" + sourceId;
        String normalizedTitle = title != null && !title.isBlank() ? title : extractTitle(text);
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);

        log.info("Normalized input: {} -> sourceId: {}", normalizedTitle, sourceId);

        return new NormalizedInput(
            sourceId,
            syntheticUrl,
            normalizedTitle,
            text,
            sourceType,
            timestamp
        );
    }

    public NormalizedInput normalizeFromUrl(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("URL cannot be empty");
        }

        String sourceId = generateSourceId(url);
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);

        return new NormalizedInput(
            sourceId,
            url,
            null,
            null,
            "url",
            timestamp
        );
    }

    private String generateSourceId(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            String hashStr = Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
            return hashStr.substring(0, 12);
        } catch (NoSuchAlgorithmException e) {
            return UUID.randomUUID().toString().substring(0, 12);
        }
    }

    private String extractTitle(String text) {
        String[] lines = text.split("\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && trimmed.length() > 3) {
                if (trimmed.length() > 60) {
                    trimmed = trimmed.substring(0, 60);
                }
                return trimmed;
            }
        }
        return "Untitled Note";
    }

    public record NormalizedInput(
        String sourceId,
        String syntheticUrl,
        String title,
        String content,
        String sourceType,
        String timestamp
    ) {}
}