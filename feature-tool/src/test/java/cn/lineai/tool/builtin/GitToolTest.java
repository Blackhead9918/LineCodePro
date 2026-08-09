package cn.lineai.tool.builtin;
import cn.lineai.model.tool.ToolResult;

import cn.lineai.tool.FakeResourceContext;
import cn.lineai.tool.ToolCategory;
import cn.lineai.tool.ToolContext;
import org.json.JSONObject;
import org.junit.Assert;
import org.junit.Test;

public final class GitToolTest {

    private static final ToolContext TEST_CONTEXT = ToolContext.builder()
            .homePath("/workspace")
            .stringResolver(new FakeResourceContext())
            .build();

    // ---- buildCommand (pure, no Android) ----

    @Test
    public void statusBuildsShortBranchCommand() {
        Assert.assertEquals(
                "git status --short --branch",
                GitTool.buildCommand(GitTool.NAME_STATUS, new JSONObject())
        );
    }

    @Test
    public void diffBuildsPlainCommand() {
        Assert.assertEquals(
                "git diff --no-ext-diff --no-color",
                GitTool.buildCommand(GitTool.NAME_DIFF, new JSONObject())
        );
    }

    @Test
    public void diffWithStagedAndStatAddsFlags() {
        Assert.assertEquals(
                "git diff --no-ext-diff --no-color --cached --stat",
                GitTool.buildCommand(GitTool.NAME_DIFF, new JSONObject()
                        .put("staged", true)
                        .put("stat", true))
        );
    }

    @Test
    public void diffWithPathQuotesPath() {
        Assert.assertEquals(
                "git diff --no-ext-diff --no-color -- 'src/Main.java'",
                GitTool.buildCommand(GitTool.NAME_DIFF, new JSONObject()
                        .put("path", "src/Main.java"))
        );
    }

    @Test
    public void logBuildsOnelineCommand() {
        Assert.assertEquals(
                "git log --oneline --no-color",
                GitTool.buildCommand(GitTool.NAME_LOG, new JSONObject())
        );
    }

    @Test
    public void logWithLimitAddsMaxCount() {
        Assert.assertEquals(
                "git log --oneline --no-color --max-count=10",
                GitTool.buildCommand(GitTool.NAME_LOG, new JSONObject()
                        .put("limit", 10))
        );
    }

    @Test
    public void logWithPathQuotesPath() {
        Assert.assertEquals(
                "git log --oneline --no-color --max-count=5 -- 'src/Main.java'",
                GitTool.buildCommand(GitTool.NAME_LOG, new JSONObject()
                        .put("limit", 5)
                        .put("path", "src/Main.java"))
        );
    }

    @Test
    public void commitStagesAllChangesWhenNoPaths() {
        Assert.assertEquals(
                "git add -A && git commit -m 'Fix the bug'",
                GitTool.buildCommand(GitTool.NAME_COMMIT, new JSONObject()
                        .put("message", "Fix the bug"))
        );
    }

    @Test
    public void commitWithPathsStagesOnlyThosePaths() {
        Assert.assertEquals(
                "git add 'a.txt' 'b/c.txt' && git commit -m 'Update docs'",
                GitTool.buildCommand(GitTool.NAME_COMMIT, new JSONObject()
                        .put("message", "Update docs")
                        .put("paths", new org.json.JSONArray().put("a.txt").put("b/c.txt")))
        );
    }

    @Test
    public void commitWithAllowEmptyAddsFlag() {
        Assert.assertEquals(
                "git add -A && git commit --allow-empty -m 'Empty'",
                GitTool.buildCommand(GitTool.NAME_COMMIT, new JSONObject()
                        .put("message", "Empty")
                        .put("allow_empty", true))
        );
    }

    @Test
    public void commitMessageQuotesSingleQuotes() {
        Assert.assertEquals(
                "git add -A && git commit -m 'It'\\''s done'",
                GitTool.buildCommand(GitTool.NAME_COMMIT, new JSONObject()
                        .put("message", "It's done"))
        );
    }

    @Test
    public void commitWithoutMessageReturnsNull() {
        Assert.assertNull(GitTool.buildCommand(GitTool.NAME_COMMIT, new JSONObject()));
    }

    @Test
    public void pushDefaultsToOrigin() {
        Assert.assertEquals(
                "git push origin",
                GitTool.buildCommand(GitTool.NAME_PUSH, new JSONObject())
        );
    }

    @Test
    public void pushWithRemoteAndBranch() {
        Assert.assertEquals(
                "git push 'upstream' 'feature/x'",
                GitTool.buildCommand(GitTool.NAME_PUSH, new JSONObject()
                        .put("remote", "upstream")
                        .put("branch", "feature/x"))
        );
    }

    @Test
    public void unknownNameReturnsNull() {
        Assert.assertNull(GitTool.buildCommand("git_rebase", new JSONObject()));
    }

    // ---- execute validation (no shell available in unit tests) ----

    @Test
    public void commitWithoutMessageErrorsBeforeShell() {
        GitTool tool = new GitTool(GitTool.NAME_COMMIT, null, null);
        ToolResult result = tool.execute(new JSONObject(), TEST_CONTEXT);
        Assert.assertTrue(result.isError());
        Assert.assertTrue(result.getContent().contains("Commit message cannot be empty"));
    }

    @Test
    public void statusWithoutShellTargetsErrorsClearly() {
        GitTool tool = new GitTool(GitTool.NAME_STATUS, null, null);
        ToolResult result = tool.execute(new JSONObject(), TEST_CONTEXT);
        Assert.assertTrue(result.isError());
        // Delegation reaches ShellExecuteTool, which has no SSH/terminal provider
        // in a unit test environment.
        Assert.assertTrue(result.getContent().contains("SSH service not initialized")
                || result.getContent().contains("terminal provider"));
    }

    // ---- permission flags per instance ----

    @Test
    public void readActionsAreAllowedInReadonlyModeWithoutConfirmation() {
        Assert.assertTrue(new GitTool(GitTool.NAME_STATUS, null, null).isAllowedInReadonlyMode());
        Assert.assertFalse(new GitTool(GitTool.NAME_STATUS, null, null).needsConfirmation());
        Assert.assertTrue(new GitTool(GitTool.NAME_DIFF, null, null).isAllowedInReadonlyMode());
        Assert.assertFalse(new GitTool(GitTool.NAME_DIFF, null, null).needsConfirmation());
        Assert.assertTrue(new GitTool(GitTool.NAME_LOG, null, null).isAllowedInReadonlyMode());
        Assert.assertFalse(new GitTool(GitTool.NAME_LOG, null, null).needsConfirmation());
    }

    @Test
    public void writeActionsNeedConfirmationAndAreBlockedInReadonlyMode() {
        Assert.assertFalse(new GitTool(GitTool.NAME_COMMIT, null, null).isAllowedInReadonlyMode());
        Assert.assertTrue(new GitTool(GitTool.NAME_COMMIT, null, null).needsConfirmation());
        Assert.assertFalse(new GitTool(GitTool.NAME_PUSH, null, null).isAllowedInReadonlyMode());
        Assert.assertTrue(new GitTool(GitTool.NAME_PUSH, null, null).needsConfirmation());
    }

    @Test
    public void categoriesMatchReadWriteSemantics() {
        Assert.assertEquals(ToolCategory.READ, new GitTool(GitTool.NAME_STATUS, null, null).getCategory());
        Assert.assertEquals(ToolCategory.READ, new GitTool(GitTool.NAME_DIFF, null, null).getCategory());
        Assert.assertEquals(ToolCategory.READ, new GitTool(GitTool.NAME_LOG, null, null).getCategory());
        Assert.assertEquals(ToolCategory.WRITE, new GitTool(GitTool.NAME_COMMIT, null, null).getCategory());
        Assert.assertEquals(ToolCategory.WRITE, new GitTool(GitTool.NAME_PUSH, null, null).getCategory());
    }

    @Test
    public void namesAreStable() {
        Assert.assertEquals("git_status", new GitTool(GitTool.NAME_STATUS, null, null).getName());
        Assert.assertEquals("git_diff", new GitTool(GitTool.NAME_DIFF, null, null).getName());
        Assert.assertEquals("git_log", new GitTool(GitTool.NAME_LOG, null, null).getName());
        Assert.assertEquals("git_commit", new GitTool(GitTool.NAME_COMMIT, null, null).getName());
        Assert.assertEquals("git_push", new GitTool(GitTool.NAME_PUSH, null, null).getName());
    }
}
