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
public class SynthesisService {

    @Inject
    ChatModel chatModel;

    private static final String SYSTEM_PROMPT = """
        Du bist ein Wiki-Editor. Deine Aufgabe ist es, die 'Compiled Truth' (Zusammenfassung) einer Wiki-Seite
        basierend auf neuen Einträgen in der 'Timeline' zu aktualisieren.

        AKTUELLER STAND:
        {{currentSummary}}

        NEUE TIMELINE-EINTRÄGE:
        {{timeline}}

        REGELN:
        1. Integriere neue Fakten präzise.
        2. Behalte widersprüchliche Informationen bei, wenn sie relevant sind (z.B. 'Zuerst wurde X geplant, später Y').
        3. Halte den Stil sachlich und enzyklopädisch.
        4. Antworte NUR mit dem neuen Text für die Zusammenfassung.
        """;

    public String synthesize(String currentSummary, String timeline) {
        try {
            String summaryText = currentSummary != null && !currentSummary.isBlank()
                ? currentSummary : "(Noch keine Zusammenfassung vorhanden)";
            String timelineText = timeline != null && !timeline.isBlank()
                ? timeline : "(Keine neuen Timeline-Einträge)";

            ChatRequest request = ChatRequest.builder()
                .messages(
                    SystemMessage.from(SYSTEM_PROMPT),
                    UserMessage.from("AKTUELLER STAND:\n" + summaryText + "\n\nNEUE TIMELINE-EINTRÄGE:\n" + timelineText)
                )
                .build();

            ChatResponse response = chatModel.chat(request);
            return response.aiMessage().text();
        } catch (Exception e) {
            log.error("Synthesis failed", e);
            return currentSummary;
        }
    }
}