package cn.lineai.tool.builtin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import cn.lineai.model.tool.ToolResult;
import cn.lineai.tool.ToolContext;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileWriter;
import java.nio.file.Files;

public final class FileMultiEditToolTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private ToolContext createTestContext(File homeDir) {
        return ToolContext.builder()
                .homePath(homeDir.getAbsolutePath())
                .stringResolver(new ToolContext.StringResolver() {
                    @Override
                    public String getString(int resId) {
                        return "res_" + resId;
                    }

                    @Override
                    public String getString(int resId, Object... formatArgs) {
                        if (formatArgs == null || formatArgs.length == 0) {
                            return "res_" + resId;
                        }
                        return "res_" + resId + ": " + formatArgs[0];
                    }
                })
                .build();
    }

    @Test
    public void multiChunkEditAppliesAllChunksAtomically() throws Exception {
        File home = temporaryFolder.newFolder("home");
        File targetFile = new File(home, "Code.java");
        try (FileWriter writer = new FileWriter(targetFile)) {
            writer.write("package com.test;\n" +
                    "\n" +
                    "public class Code {\n" +
                    "    private String name = \"initial\";\n" +
                    "\n" +
                    "    public void run() {\n" +
                    "        System.out.println(\"running\");\n" +
                    "    }\n" +
                    "}\n");
        }

        FileMultiEditTool tool = new FileMultiEditTool();
        JSONObject input = new JSONObject()
                .put("file_path", "Code.java")
                .put("edits", new JSONArray()
                        .put(new JSONObject()
                                .put("old_string", "private String name = \"initial\";")
                                .put("new_string", "private String name = \"updated\";"))
                        .put(new JSONObject()
                                .put("old_string", "System.out.println(\"running\");")
                                .put("new_string", "System.out.println(\"done\");")));

        ToolResult result = tool.execute(input, createTestContext(home));
        assertFalse("Expected success result: " + result.getContent(), result.isError());

        String updatedContent = new String(Files.readAllBytes(targetFile.toPath()));
        assertTrue(updatedContent.contains("private String name = \"updated\";"));
        assertTrue(updatedContent.contains("System.out.println(\"done\");"));
        assertFalse(updatedContent.contains("initial"));
    }

    @Test
    public void multiChunkEditFailsIfChunkNotFoundAndKeepsFileUntouched() throws Exception {
        File home = temporaryFolder.newFolder("home2");
        File targetFile = new File(home, "App.java");
        String originalContent = "line 1\nline 2\nline 3\n";
        try (FileWriter writer = new FileWriter(targetFile)) {
            writer.write(originalContent);
        }

        FileMultiEditTool tool = new FileMultiEditTool();
        JSONObject input = new JSONObject()
                .put("file_path", "App.java")
                .put("edits", new JSONArray()
                        .put(new JSONObject()
                                .put("old_string", "line 1")
                                .put("new_string", "line 1 modified"))
                        .put(new JSONObject()
                                .put("old_string", "non_existent_string")
                                .put("new_string", "should not appear")));

        ToolResult result = tool.execute(input, createTestContext(home));
        assertTrue(result.isError());

        // Verify atomic rollback / untouched file
        String currentContent = new String(Files.readAllBytes(targetFile.toPath()));
        assertEquals(originalContent, currentContent);
    }

    @Test
    public void multiChunkEditFailsIfChunksOverlap() throws Exception {
        File home = temporaryFolder.newFolder("home3");
        File targetFile = new File(home, "Overlap.txt");
        String originalContent = "alpha beta gamma delta";
        try (FileWriter writer = new FileWriter(targetFile)) {
            writer.write(originalContent);
        }

        FileMultiEditTool tool = new FileMultiEditTool();
        JSONObject input = new JSONObject()
                .put("file_path", "Overlap.txt")
                .put("edits", new JSONArray()
                        .put(new JSONObject()
                                .put("old_string", "alpha beta")
                                .put("new_string", "first"))
                        .put(new JSONObject()
                                .put("old_string", "beta gamma")
                                .put("new_string", "second")));

        ToolResult result = tool.execute(input, createTestContext(home));
        assertTrue(result.isError());
        assertTrue(result.getContent().contains("overlap"));

        // File remains untouched
        String currentContent = new String(Files.readAllBytes(targetFile.toPath()));
        assertEquals(originalContent, currentContent);
    }
}
