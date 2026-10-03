package cn.lineai.tool.builtin;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import cn.lineai.model.tool.ToolResult;
import cn.lineai.tool.ToolContext;
import java.io.File;
import java.io.FileWriter;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class GlobToolTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void findsFilesInsideHiddenConfigDirectories() throws Exception {
        File root = temporaryFolder.getRoot();
        write(root, ".github/workflows/ci.yml", "name: ci\n");
        write(root, "src/main.yml", "key: value\n");
        write(root, "node_modules/pkg/hidden.yml", "dep: true\n");

        ToolResult result = new GlobTool().execute(
                new JSONObject().put("pattern", "**/*.yml"), context(root));

        assertFalse(result.isError());
        assertTrue(result.getContent().contains(".github/workflows/ci.yml"));
        assertTrue(result.getContent().contains("src/main.yml"));
        assertFalse(result.getContent().contains("node_modules"));
    }

    @Test
    public void skipsGitMetadataDirectory() throws Exception {
        File root = temporaryFolder.getRoot();
        write(root, ".git/workflows/ci.yml", "git: internal\n");
        write(root, "src/app.yml", "app: true\n");

        ToolResult result = new GlobTool().execute(
                new JSONObject().put("pattern", "**/*.yml"), context(root));

        assertFalse(result.isError());
        assertTrue(result.getContent().contains("src/app.yml"));
        assertFalse(result.getContent().contains(".git/"));
    }

    private static ToolContext context(File root) {
        return ToolContext.builder()
                .homePath(root.getAbsolutePath())
                .build();
    }

    private static void write(File root, String relativePath, String content) throws Exception {
        File file = new File(root, relativePath);
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            assertTrue(parent.mkdirs());
        }
        try (FileWriter writer = new FileWriter(file)) {
            writer.write(content);
        }
    }
}
