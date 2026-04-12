package com.example.llmwiki.resource;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

@Path("/")
public class PageResource {

    @GET
    @Produces(MediaType.TEXT_HTML)
    public String index() {
        try (var is = Thread.currentThread().getContextClassLoader().getResourceAsStream("META-INF/resources/index.html")) {
            if (is == null) return "<html><body>Template not found</body></html>";
            return new String(is.readAllBytes());
        } catch (Exception e) {
            return "<html><body>Error: " + e.getMessage() + "</body></html>";
        }
    }
}
