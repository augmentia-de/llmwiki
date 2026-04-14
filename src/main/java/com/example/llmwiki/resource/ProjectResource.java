package com.example.llmwiki.resource;

import com.example.llmwiki.model.ProjectAnalysis;
import com.example.llmwiki.service.ProjectAnalysisService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

@Path("/projects")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Slf4j
public class ProjectResource {

    @Inject
    ProjectAnalysisService projectAnalysisService;

    /**
     * Analyze a software project from GitHub URL or local path.
     *
     * Body: { "githubUrl": "https://github.com/user/repo" }
     *    or { "localPath": "/path/to/project" }
     */
    @POST
    @Path("/analyze")
    public Response analyzeProject(Map<String, String> request) {
        String githubUrl = request.get("githubUrl");
        String localPath = request.get("localPath");

        if ((githubUrl == null || githubUrl.isBlank()) &&
            (localPath == null || localPath.isBlank())) {
            return Response.status(400).entity(Map.of(
                "error", "Either 'githubUrl' or 'localPath' is required"
            )).build();
        }

        try {
            ProjectAnalysisService.AnalysisResult result =
                projectAnalysisService.analyzeProject(
                    githubUrl != null && !githubUrl.isBlank() ? githubUrl : null,
                    localPath != null && !localPath.isBlank() ? localPath : null
                );

            ProjectAnalysis analysis = result.analysis();

            return Response.ok(Map.of(
                "status", "analyzed",
                "projectName", analysis.projectName(),
                "description", analysis.description(),
                "technologies", analysis.technologies(),
                "keywords", analysis.keywords(),
                "dependencies", analysis.dependencies(),
                "pagesCreated", result.pagesCreated()
            )).build();

        } catch (Exception e) {
            log.error("Project analysis failed", e);
            return Response.status(500).entity(Map.of(
                "error", "Project analysis failed: " + e.getMessage()
            )).build();
        }
    }

    /**
     * List all analyzed projects.
     */
    @GET
    public Response listProjects() {
        try {
            var pages = projectAnalysisService.getClass(); // We'll use WikiFileService indirectly
            // For now, return a simple response - projects are stored as wiki pages
            return Response.ok(Map.of(
                "info", "Projects are stored as wiki pages with category 'project'. Use GET /wiki/category/project to list them."
            )).build();
        } catch (Exception e) {
            return Response.status(500).entity(Map.of("error", e.getMessage())).build();
        }
    }
}
