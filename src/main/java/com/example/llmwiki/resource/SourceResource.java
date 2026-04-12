package com.example.llmwiki.resource;

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

    @POST
    public Response ingestSource(Map<String, String> request) {
        String url = request.get("url");
        if (url == null || url.isBlank()) {
            return Response.status(400).entity(Map.of("error", "URL is required")).build();
        }
        try {
            IngestService.IngestResult result = ingestService.ingest(url);
            return Response.ok(Map.of(
                "status", "ingested",
                "title", result.title(),
                "pagesTouched", result.pagesTouched(),
                "summary", result.analysis().summary()
            )).build();
        } catch (Exception e) {
            log.error("Ingest failed: {}", url, e);
            return Response.status(500).entity(Map.of("error", "Ingest failed: " + e.getMessage())).build();
        }
    }
}
