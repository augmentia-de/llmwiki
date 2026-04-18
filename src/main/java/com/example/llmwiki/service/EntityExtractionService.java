package com.example.llmwiki.service;

import com.example.llmwiki.model.ExtractionResult;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@ApplicationScoped
public class EntityExtractionService {

    @Inject
    ChatModel chatModel;

    private static final String SYSTEM_PROMPT = """
        Du bist der Architekt eines semantischen Wikis. Dein Ziel ist es, aus dem Text Entitäten zu extrahieren,
        die als persistente Wiki-Seiten dienen.

        REGELN:
        1. fileName: snake_case (z.B. 'projekt_mars', 'dr_weber').
        2. category: PERSON, PROJECT, CONCEPT, ORGANIZATION, EVENT.
        3. Sei präzise. Erstelle nur Entitäten, die einen Mehrwert als eigene Wiki-Seite bieten.
        4. relation: Kurze Beschreibung, wie die Entität mit dem Dokument zusammenhängt.
        5. initialSummary: Ein Satz, was diese Entität im aktuellen Kontext tut.

        Gib das Ergebnis als valides JSON zurück im Format:
        {"entities": [{"fileName": "...", "displayName": "...", "category": "...", "relation": "...", "initialSummary": "..."}]}
        """;

    public ExtractionResult extract(String text) {
        try {
            ChatRequest request = ChatRequest.builder()
                .messages(
                    SystemMessage.from(SYSTEM_PROMPT),
                    UserMessage.from("TEXT:\n---\n" + text + "\n---")
                )
                .build();

            ChatResponse response = chatModel.chat(request);
            String json = response.aiMessage().text();

            return parseJson(json);
        } catch (Exception e) {
            log.error("Entity extraction failed", e);
            return new ExtractionResult();
        }
    }

    private ExtractionResult parseJson(String json) {
        ExtractionResult result = new ExtractionResult();
        result.entities = java.util.Collections.emptyList();

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
            ExtractionResult parsed = mapper.readValue(json, ExtractionResult.class);
            return parsed;
        } catch (Exception e) {
            log.warn("Failed to parse extraction JSON, using fallback", e);
            return result;
        }
    }
}