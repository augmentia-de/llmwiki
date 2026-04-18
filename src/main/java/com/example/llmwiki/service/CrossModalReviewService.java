package com.example.llmwiki.service;

import com.example.llmwiki.model.Claim;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Slf4j
@ApplicationScoped
public class CrossModalReviewService {

    @Inject
    ChatModel chatModel;

    private static final String REVIEW_PROMPT = """
        Du bist ein Qualitäts-Reviewer. Deine Aufgabe ist es, neue Behauptungen gegen die bestehende 'Compiled Truth' zu prüfen.

        Prüfe auf:
        1. Widersprüche: Widerspricht die neue Information bestehendem Wissen?
        2. Faktenfehler: Enthält die neue Information offensichtlich falsche Daten?
        3. Redundanz: Ist die Information bereits vollständig vorhanden?

        Antworte im JSON-Format:
        {"conflict": true|false, "conflictDetails": "...", "isValid": true|false, "reason": "...", "action": "APPROVE|REJECT|MERGE"}

        Wenn kein Konflikt und gültig: action = "APPEOVE"
        Wenn Konflikt: action = "MERGE" (LLM soll später entscheiden)
        Wenn fehlerhaft: action = "REJECT"
        """;

    public ReviewResult validateClaim(String newClaim, String compiledTruth) {
        try {
            ChatRequest request = ChatRequest.builder()
                .messages(
                    SystemMessage.from(REVIEW_PROMPT),
                    UserMessage.from("""
                        Bestehende Compiled Truth:
                        %s

                        Neue Behauptung:
                        %s
                        """.formatted(compiledTruth, newClaim))
                )
                .build();

            ChatResponse response = chatModel.chat(request);
            String json = response.aiMessage().text();

            return parseReviewResult(json);
        } catch (Exception e) {
            log.error("Review failed", e);
            return new ReviewResult(false, "", false, "Review failed: " + e.getMessage(), "APPROVE");
        }
    }

    private ReviewResult parseReviewResult(String json) {
        try {
            json = json.trim();
            if (json.startsWith("```json")) {
                json = json.substring(7);
            }
            if (json.endsWith("```")) {
                json = json.substring(0, json.length() - 3);
            }
            json = json.trim();

            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode node = mapper.readTree(json);

            return new ReviewResult(
                node.has("conflict") && node.get("conflict").asBoolean(),
                node.has("conflictDetails") ? node.get("conflictDetails").asText() : "",
                node.has("isValid") && node.get("isValid").asBoolean(),
                node.has("reason") ? node.get("reason").asText() : "",
                node.has("action") ? node.get("action").asText() : "APPROVE"
            );
        } catch (Exception e) {
            log.warn("Failed to parse review result", e);
            return new ReviewResult(false, "", true, "Parse error", "APPROVE");
        }
    }

    public record ReviewResult(
        boolean conflict,
        String conflictDetails,
        boolean isValid,
        String reason,
        String action
    ) {}
}