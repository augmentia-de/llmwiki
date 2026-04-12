package com.example.llmwiki.resource;

import com.example.llmwiki.model.WikiPage;
import com.example.llmwiki.service.FileNode;
import com.example.llmwiki.service.WikiFileService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@Path("/wiki")
@Produces(MediaType.APPLICATION_JSON)
@Slf4j
public class WikiResource {

    @Inject
    WikiFileService wikiFileService;

    @GET
    public Response getAllPages() {
        List<WikiPage> pages = wikiFileService.readAllPages();
        return Response.ok(pages).build();
    }

    @GET
    @Path("/{slug}")
    public Response getPage(@PathParam("slug") String slug) {
        try {
            WikiPage page = wikiFileService.readPage(slug);
            if (page == null) {
                return Response.status(404).entity(Map.of("error", "Page not found: " + slug)).build();
            }
            return Response.ok(page).build();
        } catch (IOException e) {
            return Response.status(500).entity(Map.of("error", e.getMessage())).build();
        }
    }

    @GET
    @Path("/category/{category}")
    public Response getPagesByCategory(@PathParam("category") String category) {
        List<WikiPage> pages = wikiFileService.readCategory(category);
        return Response.ok(pages).build();
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response createPage(Map<String, String> request) {
        String slug = request.get("slug");
        String title = request.get("title");
        String content = request.get("content");
        String category = request.getOrDefault("category", "concept");

        if (slug == null || title == null || content == null) {
            return Response.status(400).entity(Map.of("error", "slug, title, content required")).build();
        }

        try {
            WikiPage existing = wikiFileService.readPage(slug);
            if (existing != null) {
                return Response.status(409).entity(Map.of("error", "Page already exists: " + slug)).build();
            }

            WikiPage page = new WikiPage(
                slug, title, category, content,
                List.of(),
                java.time.LocalDate.now().toString(), null
            );
            wikiFileService.writePage(page);
            return Response.status(201).entity(page).build();
        } catch (Exception e) {
            return Response.status(500).entity(Map.of("error", e.getMessage())).build();
        }
    }

    @PUT
    @Path("/{slug}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response updatePage(@PathParam("slug") String slug, Map<String, String> request) {
        try {
            WikiPage existing = wikiFileService.readPage(slug);
            if (existing == null) {
                return Response.status(404).entity(Map.of("error", "Page not found: " + slug)).build();
            }

            WikiPage updated = new WikiPage(
                existing.slug(),
                request.getOrDefault("title", existing.title()),
                existing.category(),
                request.getOrDefault("content", existing.content()),
                existing.sources(),
                existing.created(),
                java.time.LocalDate.now().toString()
            );
            wikiFileService.writePage(updated);
            return Response.ok(updated).build();
        } catch (Exception e) {
            return Response.status(500).entity(Map.of("error", e.getMessage())).build();
        }
    }

    @DELETE
    @Path("/{slug}")
    public Response deletePage(@PathParam("slug") String slug) {
        try {
            boolean deleted = wikiFileService.deletePage(slug);
            if (deleted) {
                return Response.ok(Map.of("status", "deleted", "slug", slug)).build();
            } else {
                return Response.status(404).entity(Map.of("error", "Page not found: " + slug)).build();
            }
        } catch (Exception e) {
            return Response.status(500).entity(Map.of("error", e.getMessage())).build();
        }
    }

    @GET
    @Path("/index")
    public Response getIndex() {
        try {
            return Response.ok(Map.of("content", wikiFileService.readIndex())).build();
        } catch (IOException e) {
            return Response.status(500).entity(Map.of("error", e.getMessage())).build();
        }
    }

    @GET
    @Path("/log")
    public Response getLog() {
        try {
            return Response.ok(Map.of("content", wikiFileService.readLog())).build();
        } catch (IOException e) {
            return Response.status(500).entity(Map.of("error", e.getMessage())).build();
        }
    }

    // ─── File Browser API ────────────────────────────────────────────────

    /**
     * Lists the wiki directory structure as a tree.
     */
    @GET
    @Path("/files/tree")
    public Response getFilesTree() {
        try {
            List<FileNode> tree = wikiFileService.getFilesTree();
            List<FileNodeAlias> aliasTree = tree.stream().map(FileNodeAlias::fromFileNode).toList();
            return Response.ok(aliasTree).build();
        } catch (IOException e) {
            return Response.status(500).entity(Map.of("error", e.getMessage())).build();
        }
    }

    /**
     * Reads the raw content of a wiki file (Markdown).
     *
     * @param relativePath relative path within the wiki directory,
     *                     e.g. "index.md", "entities/karpathy.md"
     */
    @GET
    @Path("/files/content/{path:.*}")
    public Response getFileContent(@PathParam("path") String relativePath) {
        try {
            String content = wikiFileService.readFileContent(relativePath);
            if (content == null) {
                return Response.status(404).entity(Map.of("error", "File not found: " + relativePath)).build();
            }
            return Response.ok(Map.of(
                "path", relativePath,
                "content", content
            )).build();
        } catch (IOException e) {
            return Response.status(500).entity(Map.of("error", e.getMessage())).build();
        }
    }

    /**
     * Represents a node in the file tree.
     */
    public record FileNodeAlias(
        String name,
        String path,
        String type,
        List<FileNodeAlias> children
    ) {
        static FileNodeAlias fromFileNode(FileNode node) {
            return new FileNodeAlias(
                node.name(), node.path(), node.type(),
                node.children() != null ? node.children().stream().map(FileNodeAlias::fromFileNode).toList() : List.of()
            );
        }
    }
}
