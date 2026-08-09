package cn.lineai.tool.builtin;
import cn.lineai.model.tool.ToolResult;

import android.content.Context;
import cn.lineai.ipc.IpcProviderManager;
import cn.lineai.tool.BaseTool;
import cn.lineai.tool.R;
import cn.lineai.tool.ToolCategory;
import cn.lineai.tool.ToolContext;
import cn.lineai.tool.ToolDisplayCategory;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Built-in Git tool. One class, five registered instances — each instance is a
 * separate tool with its own name, permission flags and parameter schema:
 *
 * <ul>
 *   <li>{@link #NAME_STATUS} (git_status) — read-only, no confirmation.</li>
 *   <li>{@link #NAME_DIFF} (git_diff) — read-only, no confirmation.</li>
 *   <li>{@link #NAME_LOG} (git_log) — read-only, no confirmation.</li>
 *   <li>{@link #NAME_COMMIT} (git_commit) — write, requires confirmation.</li>
 *   <li>{@link #NAME_PUSH} (git_push) — write, requires confirmation.</li>
 * </ul>
 *
 * <p>Command execution is delegated to {@link ShellExecuteTool}, so Git runs
 * through the same routing as shell_execute: SSH mode executes on the remote
 * host, terminal-provider mode executes via the Termux IPC bridge. The command
 * always runs with the workspace root (ToolContext.getHomePath()) as cwd.
 */
public final class GitTool extends BaseTool {
    public static final String NAME_STATUS = "git_status";
    public static final String NAME_DIFF = "git_diff";
    public static final String NAME_LOG = "git_log";
    public static final String NAME_COMMIT = "git_commit";
    public static final String NAME_PUSH = "git_push";

    private final String name;
    private final ShellExecuteTool shell;

    public GitTool(String name, Context context, IpcProviderManager ipcProviderManager) {
        this.name = name == null ? NAME_STATUS : name;
        this.shell = new ShellExecuteTool(context, ipcProviderManager);
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getDescription() {
        switch (name) {
            case NAME_STATUS:
                return "Show the working tree status (modified/added/deleted files and the current branch). Read-only, no user confirmation required.";
            case NAME_DIFF:
                return "Show the diff of uncommitted changes (optionally restricted to one path, or the staged diff). Read-only, no user confirmation required.";
            case NAME_LOG:
                return "Show the commit history as a one-line summary per commit (git log --oneline), optionally limited to a number of commits or one path. Read-only, no user confirmation required.";
            case NAME_COMMIT:
                return "Stage the given paths (or all changes) and create a git commit with the provided message. Requires user confirmation.";
            case NAME_PUSH:
            default:
                return "Push local commits to the remote repository. Requires user confirmation.";
        }
    }

    @Override
    public ToolCategory getCategory() {
        return isWriteAction() ? ToolCategory.WRITE : ToolCategory.READ;
    }

    @Override
    public ToolDisplayCategory getDisplayCategory() {
        return ToolDisplayCategory.SHELL;
    }

    @Override
    public boolean needsConfirmation() {
        return isWriteAction();
    }

    @Override
    public boolean isAllowedInReadonlyMode() {
        return !isWriteAction();
    }

    private boolean isWriteAction() {
        return NAME_COMMIT.equals(name) || NAME_PUSH.equals(name);
    }

    @Override
    public String promptSupplement(String executionMode, boolean isSsh) {
        return "git_* tools run `git` via the same execution target as shell_execute ("
                + (isSsh ? "SSH remote host" : "terminal provider")
                + ") in the workspace root directory. git_status, git_diff and git_log are read-only; "
                + "git_commit and git_push require user confirmation.";
    }

    @Override
    public String getActionName(Context context) {
        switch (name) {
            case NAME_STATUS:
                return context.getString(R.string.tool_call_action_git_status);
            case NAME_DIFF:
                return context.getString(R.string.tool_call_action_git_diff);
            case NAME_LOG:
                return context.getString(R.string.tool_call_action_git_log);
            case NAME_COMMIT:
                return context.getString(R.string.tool_call_action_git_commit);
            case NAME_PUSH:
            default:
                return context.getString(R.string.tool_call_action_git_push);
        }
    }

    @Override
    public int getActionIcon() {
        switch (name) {
            case NAME_STATUS:
                return ICON_BOOK_OPEN;
            case NAME_DIFF:
                return ICON_SEARCH;
            case NAME_LOG:
                return ICON_SCROLL_TEXT;
            case NAME_COMMIT:
                return ICON_SPARKLES;
            case NAME_PUSH:
            default:
                return ICON_GLOBE;
        }
    }

    @Override
    public String getDisplayLabel(Context ctx, JSONObject input, String workspacePath) {
        if (input == null) {
            return null;
        }
        switch (name) {
            case NAME_DIFF: {
                String path = input.optString("path", "");
                return path.length() > 0 ? path : null;
            }
            case NAME_LOG: {
                String path = input.optString("path", "");
                return path.length() > 0 ? path : null;
            }
            case NAME_COMMIT: {
                String message = input.optString("message", "");
                return message.length() > 0 ? message : null;
            }
            default:
                return null;
        }
    }

    @Override
    public JSONObject getParameters() throws org.json.JSONException {
        switch (name) {
            case NAME_DIFF:
                return new JSONObject()
                        .put("type", "object")
                        .put("properties", new JSONObject()
                                .put("path", new JSONObject()
                                        .put("type", "string")
                                        .put("description", "Optional file/directory path to restrict the diff to"))
                                .put("staged", new JSONObject()
                                        .put("type", "boolean")
                                        .put("description", "If true, show the staged diff (git diff --cached) instead of the unstaged one"))
                                .put("stat", new JSONObject()
                                        .put("type", "boolean")
                                        .put("description", "If true, show a compact summary of changed lines instead of the full diff")))
                        .put("required", new JSONArray());
            case NAME_LOG:
                return new JSONObject()
                        .put("type", "object")
                        .put("properties", new JSONObject()
                                .put("limit", new JSONObject()
                                        .put("type", "integer")
                                        .put("description", "Maximum number of commits to show; omit to show the whole history"))
                                .put("path", new JSONObject()
                                        .put("type", "string")
                                        .put("description", "Optional file/directory path to restrict the history to")))
                        .put("required", new JSONArray());
            case NAME_COMMIT:
                return new JSONObject()
                        .put("type", "object")
                        .put("properties", new JSONObject()
                                .put("message", new JSONObject()
                                        .put("type", "string")
                                        .put("description", "Commit message"))
                                .put("paths", new JSONObject()
                                        .put("type", "array")
                                        .put("items", new JSONObject().put("type", "string"))
                                        .put("description", "Optional paths to stage before committing. If omitted, all changes are staged with git add -A"))
                                .put("allow_empty", new JSONObject()
                                        .put("type", "boolean")
                                        .put("description", "If true, allow an empty commit (git commit --allow-empty)")))
                        .put("required", new JSONArray().put("message"));
            case NAME_PUSH:
                return new JSONObject()
                        .put("type", "object")
                        .put("properties", new JSONObject()
                                .put("remote", new JSONObject()
                                        .put("type", "string")
                                        .put("description", "Optional remote name, defaults to origin"))
                                .put("branch", new JSONObject()
                                        .put("type", "string")
                                        .put("description", "Optional branch to push; defaults to the current branch")))
                        .put("required", new JSONArray());
            case NAME_STATUS:
            default:
                return new JSONObject()
                        .put("type", "object")
                        .put("properties", new JSONObject())
                        .put("required", new JSONArray());
        }
    }

    @Override
    public ToolResult execute(JSONObject input, ToolContext context) {
        JSONObject args = input == null ? new JSONObject() : input;
        String command = buildCommand(name, args);
        if (command == null) {
            return error(context.getString(R.string.tool_git_commit_message_empty));
        }
        String cwd = context == null ? "" : context.getHomePath().trim();
        JSONObject shellInput = new JSONObject();
        try {
            shellInput.put("command", command);
            if (cwd.length() > 0) {
                shellInput.put("cwd", cwd);
            }
        } catch (org.json.JSONException ignored) {
            // Command and cwd are plain strings; JSONException cannot happen here.
        }
        return shell.execute(shellInput, context);
    }

    /**
     * Build the git command line for the given tool instance. Returns {@code null}
     * when the input is invalid (currently: git_commit without a message).
     * Package-private and static so unit tests can verify command construction
     * without an Android environment or a live shell.
     */
    static String buildCommand(String toolName, JSONObject input) {
        if (toolName == null || input == null) {
            return null;
        }
        switch (toolName) {
            case NAME_STATUS:
                return "git status --short --branch";
            case NAME_DIFF: {
                StringBuilder builder = new StringBuilder("git diff --no-ext-diff --no-color");
                if (input.optBoolean("staged", false)) {
                    builder.append(" --cached");
                }
                if (input.optBoolean("stat", false)) {
                    builder.append(" --stat");
                }
                String path = input.optString("path", "").trim();
                if (path.length() > 0) {
                    builder.append(" -- ").append(quote(path));
                }
                return builder.toString();
            }
            case NAME_COMMIT: {
                String message = input.optString("message", "").trim();
                if (message.length() == 0) {
                    return null;
                }
                StringBuilder builder = new StringBuilder();
                JSONArray paths = input.optJSONArray("paths");
                if (paths != null && paths.length() > 0) {
                    builder.append("git add");
                    for (int i = 0; i < paths.length(); i++) {
                        String path = paths.optString(i, "").trim();
                        if (path.length() > 0) {
                            builder.append(' ').append(quote(path));
                        }
                    }
                } else {
                    builder.append("git add -A");
                }
                builder.append(" && git commit");
                if (input.optBoolean("allow_empty", false)) {
                    builder.append(" --allow-empty");
                }
                builder.append(" -m ").append(quote(message));
                return builder.toString();
            }
            case NAME_LOG: {
                StringBuilder builder = new StringBuilder("git log --oneline --no-color");
                int limit = input.optInt("limit", 0);
                if (limit > 0) {
                    builder.append(" --max-count=").append(limit);
                }
                String path = input.optString("path", "").trim();
                if (path.length() > 0) {
                    builder.append(" -- ").append(quote(path));
                }
                return builder.toString();
            }
            case NAME_PUSH: {
                String remote = input.optString("remote", "").trim();
                String branch = input.optString("branch", "").trim();
                StringBuilder builder = new StringBuilder("git push");
                if (remote.length() > 0) {
                    builder.append(' ').append(quote(remote));
                } else {
                    builder.append(" origin");
                }
                if (branch.length() > 0) {
                    builder.append(' ').append(quote(branch));
                }
                return builder.toString();
            }
            default:
                return null;
        }
    }

    private static String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    @Override
    public Class<? extends cn.lineai.tool.ToolCallCardView> getToolCallViewClass() {
        return cn.lineai.tool.ui.ToolCallShellView.class;
    }
}
