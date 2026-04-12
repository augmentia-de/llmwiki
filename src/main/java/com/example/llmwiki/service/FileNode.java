package com.example.llmwiki.service;

import java.util.List;

/**
 * Represents a node in the file tree.
 */
public record FileNode(
    String name,
    String path,
    String type,          // "file" or "directory"
    List<FileNode> children
) {}
