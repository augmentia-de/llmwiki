package com.example.llmwiki.resource;

import com.example.llmwiki.service.LintService;
import com.example.llmwiki.service.QueryService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

@Path("/query")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Slf4j
public class QueryResource {

    @Inject
    QueryService queryService;

    @Inject
    LintService lintService;

    @POST
    public Response query(Map<String, Object> request) {
        String question = (String) request.get("question");
        boolean saveAsPage = Boolean.TRUE.equals(request.get("saveAsPage"));

        if (question == null || question.isBlank()) {
            return Response.status(400).entity(Map.of("error", "question is required")).build();
        }

        try {
            QueryService.QueryResponse response = queryService.query(question, saveAsPage);
            return Response.ok(Map.of(
                "answer", response.answer(),
                "citedPages", response.citedPages()
            )).build();
        } catch (Exception e) {
            log.error("Query failed: {}", question, e);
            return Response.status(500).entity(Map.of("error", "Query failed: " + e.getMessage())).build();
        }
    }

    @GET
    @Path("/lint")
    public Response lint() {
        try {
            return Response.ok(lintService.lint()).build();
        } catch (Exception e) {
            return Response.status(500).entity(Map.of("error", e.getMessage())).build();
        }
    }
}
