package com.example.llmwiki.service;

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
public class WikiArchitect {

    @Inject
    ChatModel chatModel;

    @Inject
    ResearchTool researchTool;

    private static final String SYSTEM_PROMPT = """
        Du bist der Wiki-Architekt. Deine Aufgabe:
        1. Analysiere den Ingest-Text.
        2. Gleiche Informationen mit dem 'Existing Wiki' ab.
        3. Wenn Informationen fehlen oder widersprüchlich sind, nutze das 'searchTavily' Tool.
        4. Erstelle eine neue Version der Wiki-Seite (Compiled Truth).
        5. Wenn ein Konflikt unlösbar ist, markiere ihn für den 'Human-in-the-Loop' Review.

        Antworte IMMER im Format:
        ---NEW_CONTENT---
        [Markdown Text]
        ---CLAIMS_FOR_REVIEW---
        [JSON Liste von Claims oder 'NONE']
        """;

    public String processUpdate(String existingContent, String newSource) {
        try {
            ChatRequest request = ChatRequest.builder()
                .messages(
                    SystemMessage.from(SYSTEM_PROMPT),
                    UserMessage.from("""
                        Bestehende Seite: %s

                        Neue Quelle: %s
                        """.formatted(existingContent, newSource))
                )
                .build();

            ChatResponse response = chatModel.chat(request);
            return response.aiMessage().text();
        } catch (Exception e) {
            log.error("WikiArchitect failed", e);
            return "ERROR: " + e.getMessage();
        }
    }

    public String searchTavily(String query) {
        return researchTool.searchTavily(query);
    }
}