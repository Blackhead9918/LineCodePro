package cn.lineai.tool.builtin;

import android.content.Context;
import cn.lineai.model.tool.ToolResult;
import cn.lineai.tool.BaseTool;
import cn.lineai.tool.R;
import cn.lineai.tool.ToolArgs;
import cn.lineai.tool.ToolCategory;
import cn.lineai.tool.ToolContext;
import cn.lineai.tool.ToolDisplayCategory;
import cn.lineai.tool.ToolNames;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * FileOutlineTool: Generates a lightweight structural outline (classes, functions,
 * interfaces, headings, exports, line numbers) for code and markup files without
 * loading entire file contents into the context window.
 */
public final class FileOutlineTool extends BaseTool {
    public static final String NAME = ToolNames.FILE_OUTLINE;

    // Pattern for Java, Kotlin, Dart, C#, C++, Rust, Go, Swift, TS, JS
    private static final Pattern CODE_SYMBOL_PATTERN = Pattern.compile(
            "^(?:\\s*(?:public|protected|private|static|final|abstract|sealed|open|suspend|override|async|export|default)\\s+)*"
            + "(?:class|interface|enum|record|object|struct|trait|type|impl|fun|def|function|fn|func)\\b"
    );

    private static final Pattern PYTHON_DEF_CLASS_PATTERN = Pattern.compile(
            "^(\\s*)(?:async\\s+)?(def|class)\\s+([a-zA-Z0-9_]+)"
    );

    private static final Pattern MARKDOWN_HEADING_PATTERN = Pattern.compile(
            "^(#{1,6})\\s+(.+)$"
    );

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getDescription() {
        return "Extract high-level structural outline (classes, methods, functions, headings, and their exact line numbers) of a file without reading the whole file body. Highly token-efficient for code navigation.";
    }

    @Override
    public ToolCategory getCategory() {
        return ToolCategory.READ;
    }

    @Override
    public ToolDisplayCategory getDisplayCategory() {
        return ToolDisplayCategory.READ;
    }

    @Override
    public String getActionName(Context context) {
        return context != null ? context.getString(R.string.tool_call_action_outline) : "Outline";
    }

    @Override
    public int getActionIcon() {
        return ICON_SEARCH;
    }

    @Override
    public boolean isConcurrencySafe() {
        return true;
    }

    @Override
    public JSONObject getParameters() throws org.json.JSONException {
        return new JSONObject()
                .put("type", "object")
                .put("properties", new JSONObject()
                        .put("file_path", new JSONObject()
                                .put("type", "string")
                                .put("description", "Path to the file to inspect the structure of.")))
                .put("required", new org.json.JSONArray().put("file_path"));
    }

    @Override
    public ToolResult execute(JSONObject input, ToolContext context) {
        try {
            String filePath = input.optString("file_path");
            if (filePath == null || filePath.trim().isEmpty()) {
                return error(context.getString(R.string.tool_args_param_empty, "file_path"));
            }
            File file = FileToolPathPolicy.resolve(context, filePath);
            if (!file.exists() || !file.isFile()) {
                return error(context.getString(R.string.tool_file_read_not_found, FileToolPathPolicy.displayPath(context.getHomePath(), file)));
            }

            String displayPath = FileToolPathPolicy.displayPath(context.getHomePath(), file);
            String outline = extractOutline(file, displayPath);
            return ok(ToolResult.truncateContent(outline));
        } catch (Exception e) {
            return error(context.getString(R.string.tool_file_read_failed, e.getMessage()));
        }
    }

    public static String extractOutline(File file, String displayPath) {
        String fileName = file.getName().toLowerCase();
        List<String> symbols = new ArrayList<>();
        int totalLines = 0;
        long totalBytes = file.length();

        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                totalLines++;
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("//") || trimmed.startsWith("/*") || trimmed.startsWith("*")) {
                    continue;
                }

                if (fileName.endsWith(".md") || fileName.endsWith(".markdown")) {
                    Matcher m = MARKDOWN_HEADING_PATTERN.matcher(trimmed);
                    if (m.find()) {
                        symbols.add(String.format("L%d: %s", totalLines, trimmed));
                    }
                } else if (fileName.endsWith(".py")) {
                    Matcher m = PYTHON_DEF_CLASS_PATTERN.matcher(line);
                    if (m.find()) {
                        String indent = m.group(1);
                        String kind = m.group(2);
                        String name = m.group(3);
                        symbols.add(String.format("L%d: %s%s %s", totalLines, indent, kind, name));
                    }
                } else {
                    // Java, Kotlin, C/C++, Go, Rust, Swift, TS, JS, Dart, etc.
                    if (isSignificantCodeDeclaration(trimmed)) {
                        // Truncate line if too long for preview
                        String preview = trimmed;
                        int braceIdx = preview.indexOf('{');
                        if (braceIdx > 0) {
                            preview = preview.substring(0, braceIdx).trim();
                        }
                        if (preview.length() > 100) {
                            preview = preview.substring(0, 97) + "...";
                        }
                        symbols.add(String.format("L%d: %s", totalLines, preview));
                    }
                }
            }
        } catch (Exception e) {
            return "[Error extracting outline: " + e.getMessage() + "]";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("[OUTLINE: ").append(displayPath)
          .append(" (").append(totalLines).append(" lines, ")
          .append(totalBytes / 1024).append("KB)]\n");

        if (symbols.isEmpty()) {
            sb.append("(No explicit class/function definitions detected; total ").append(totalLines).append(" lines)");
        } else {
            for (String sym : symbols) {
                sb.append(sym).append('\n');
            }
        }
        return sb.toString().trim();
    }

    private static boolean isSignificantCodeDeclaration(String trimmed) {
        if (trimmed.startsWith("package ") || trimmed.startsWith("import ")) {
            return false;
        }
        if (trimmed.startsWith("class ") || trimmed.startsWith("interface ") || trimmed.startsWith("enum ")
                || trimmed.startsWith("record ") || trimmed.startsWith("object ") || trimmed.startsWith("struct ")
                || trimmed.startsWith("trait ") || trimmed.startsWith("impl ") || trimmed.startsWith("type ")) {
            return true;
        }
        if (CODE_SYMBOL_PATTERN.matcher(trimmed).find()) {
            return true;
        }
        // Match methods in Java/C#/TS: modifier / type + identifier(...)
        if (trimmed.contains("(") && trimmed.contains(")") && (trimmed.endsWith("{") || trimmed.endsWith(";") || trimmed.endsWith("}"))) {
            if (trimmed.startsWith("if ") || trimmed.startsWith("for ") || trimmed.startsWith("while ")
                    || trimmed.startsWith("switch ") || trimmed.startsWith("catch ") || trimmed.startsWith("return ")
                    || trimmed.startsWith("super(") || trimmed.startsWith("this(")) {
                return false;
            }
            return true;
        }
        return false;
    }
}
