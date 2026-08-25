package cn.lineai.tool.builtin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileWriter;

public final class FileOutlineToolTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void extractsJavaClassAndMethodDeclarations() throws Exception {
        File javaFile = temporaryFolder.newFile("SampleService.java");
        try (FileWriter writer = new FileWriter(javaFile)) {
            writer.write("package com.example.service;\n");
            writer.write("\n");
            writer.write("import java.util.List;\n");
            writer.write("\n");
            writer.write("public class SampleService {\n");
            writer.write("    private final String apiKey;\n");
            writer.write("\n");
            writer.write("    public SampleService(String apiKey) {\n");
            writer.write("        this.apiKey = apiKey;\n");
            writer.write("    }\n");
            writer.write("\n");
            writer.write("    public List<String> fetchData(int limit) {\n");
            writer.write("        return null;\n");
            writer.write("    }\n");
            writer.write("}\n");
        }

        String outline = FileOutlineTool.extractOutline(javaFile, "SampleService.java");

        assertTrue(outline.contains("[OUTLINE: SampleService.java"));
        assertTrue(outline.contains("L5: public class SampleService"));
        assertTrue(outline.contains("L8: public SampleService(String apiKey)"));
        assertTrue(outline.contains("L12: public List<String> fetchData(int limit)"));
        assertFalse(outline.contains("package com.example.service"));
        assertFalse(outline.contains("import java.util.List"));
    }

    @Test
    public void extractsPythonClassAndFunctions() throws Exception {
        File pyFile = temporaryFolder.newFile("script.py");
        try (FileWriter writer = new FileWriter(pyFile)) {
            writer.write("#!/usr/bin/env python3\n");
            writer.write("import sys\n");
            writer.write("\n");
            writer.write("class DatabaseClient:\n");
            writer.write("    def __init__(self, url: str):\n");
            writer.write("        self.url = url\n");
            writer.write("\n");
            writer.write("    async def execute_query(self, query: str):\n");
            writer.write("        pass\n");
            writer.write("\n");
            writer.write("def main():\n");
            writer.write("    pass\n");
        }

        String outline = FileOutlineTool.extractOutline(pyFile, "script.py");

        assertTrue(outline.contains("L4: class DatabaseClient"));
        assertTrue(outline.contains("L5:     def __init__"));
        assertTrue(outline.contains("L8:     def execute_query"));
        assertTrue(outline.contains("L11: def main"));
    }

    @Test
    public void extractsMarkdownHeadings() throws Exception {
        File mdFile = temporaryFolder.newFile("README.md");
        try (FileWriter writer = new FileWriter(mdFile)) {
            writer.write("# Project Overview\n");
            writer.write("This is a cool project.\n");
            writer.write("## Features\n");
            writer.write("- Item 1\n");
            writer.write("- Item 2\n");
            writer.write("### Architecture\n");
            writer.write("Some details here.\n");
        }

        String outline = FileOutlineTool.extractOutline(mdFile, "README.md");

        assertTrue(outline.contains("L1: # Project Overview"));
        assertTrue(outline.contains("L3: ## Features"));
        assertTrue(outline.contains("L6: ### Architecture"));
        assertFalse(outline.contains("This is a cool project"));
    }
}
