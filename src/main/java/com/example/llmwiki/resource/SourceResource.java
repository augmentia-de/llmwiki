package com.example.llmwiki.resource;

import com.example.llmwiki.service.IngestAdapter;
import com.example.llmwiki.service.IngestService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

@Path("/sources")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Slf4j
public class SourceResource {

    @Inject
    IngestService ingestService;

    @Inject
    IngestAdapter ingestAdapter;

    @POST
    public Response ingestSource(Map<String, String> request) {
        String url = request.get("url");
        String text = request.get("text");
        String title = request.get("title");
        String type = request.getOrDefault("type", "url");

        if (url != null && !url.isBlank()) {
            return handleUrlIngest(url);
        } else if (text != null && !text.isBlank()) {
            return handleTextIngest(text, title, type);
        } else {
            return Response.status(400).entity(Map.of("error", "url or text is required")).build();
        }
    }

    private Response handleUrlIngest(String url) {
        try {
            IngestService.IngestResult result = ingestService.ingest(url);
            return Response.ok(Map.of(
                "status", "ingested",
                "sourceType", "url",
                "title", result.title(),
                "pagesTouched", result.pagesTouched(),
                "summary", result.analysis().summary()
            )).build();
        } catch (Exception e) {
            log.error("Ingest failed: {}", url, e);
            return Response.status(500).entity(Map.of("error", "Ingest failed: " + e.getMessage())).build();
        }
    }

    private Response handleTextIngest(String text, String title, String sourceType) {
        try {
            IngestService.IngestResult result = ingestService.ingestText(text, title, sourceType != null ? sourceType : "note");
            return Response.ok(Map.of(
                "status", "ingested",
                "sourceType", sourceType != null ? sourceType : "note",
                "title", result.title(),
                "pagesTouched", result.pagesTouched(),
                "summary", result.analysis().summary()
            )).build();
        } catch (Exception e) {
            log.error("Text ingest failed", e);
            return Response.status(500).entity(Map.of("error", "Text ingest failed: " + e.getMessage())).build();
        }
    }
}
