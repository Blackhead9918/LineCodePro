package cn.lineai.tool.builtin;

import android.content.Context;
import cn.lineai.data.repository.GroundedStateManager;
import cn.lineai.model.grounding.GroundedSourceType;
import cn.lineai.model.tool.ToolResult;
import cn.lineai.tool.BaseTool;
import cn.lineai.tool.R;
import cn.lineai.tool.ToolCategory;
import cn.lineai.tool.ToolContext;
import cn.lineai.tool.ToolDisplayCategory;
import cn.lineai.tool.ToolNames;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * FileMultiEditTool: Atomically applies multiple non-contiguous edits to a single file.
 * All edit chunks are validated upfront before any change is written to disk.
 */
public final class FileMultiEditTool extends BaseTool {
    public static final String NAME = ToolNames.FILE_MULTI_EDIT;

    public static final class EditChunk implements Comparable<EditChunk> {
        public final int index;
        public final String oldString;
        public final String newString;
        public int startIndex;
        public int endIndex;

        public EditChunk(int index, String oldString, String newString) {
            this.index = index;
            this.oldString = oldString;
            this.newString = newString;
        }

        @Override
        public int compareTo(EditChunk other) {
            return Integer.compare(this.startIndex, other.startIndex);
        }
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getDescription() {
        return "Atomically apply multiple non-contiguous search-and-replace edits to a single file. Each edit chunk must specify old_string and new_string, and must be unique in the file.";
    }

    @Override
    public ToolCategory getCategory() {
        return ToolCategory.WRITE;
    }

    @Override
    public ToolDisplayCategory getDisplayCategory() {
        return ToolDisplayCategory.WRITE;
    }

    @Override
    public boolean shouldRecordDiff() {
        return true;
    }

    @Override
    public JSONObject getParameters() throws org.json.JSONException {
        return new JSONObject()
                .put("type", "object")
                .put("properties", new JSONObject()
                        .put("file_path", new JSONObject()
                                .put("type", "string")
                                .put("description", "Path to the file to modify"))
                        .put("edits", new JSONObject()
                                .put("type", "array")
                                .put("description", "List of non-overlapping edits to apply")
                                .put("items", new JSONObject()
                                        .put("type", "object")
                                        .put("properties", new JSONObject()
                                                .put("old_string", new JSONObject()
                                                        .put("type", "string")
                                                        .put("description", "Exact text segment to replace"))
                                                .put("new_string", new JSONObject()
                                                        .put("type", "string")
                                                        .put("description", "Replacement text segment")))
                                        .put("required", new JSONArray().put("old_string").put("new_string")))))
                .put("required", new JSONArray().put("file_path").put("edits"));
    }

    @Override
    public ToolResult execute(JSONObject input, ToolContext context) {
        try {
            String path = input.optString("file_path");
            if (path == null || path.trim().isEmpty()) {
                return error(context.getString(R.string.tool_args_param_empty, "file_path"));
            }

            File file = FileToolPathPolicy.resolve(context, path);
            if (!file.exists()) {
                return error(context.getString(R.string.tool_file_edit_not_found, FileToolPathPolicy.displayPath(context.getHomePath(), file)));
            }
            if (file.isDirectory()) {
                return error(context.getString(R.string.tool_file_edit_is_directory, path));
            }

            JSONArray editsArray = input.optJSONArray("edits");
            if (editsArray == null || editsArray.length() == 0) {
                return error(context.getString(R.string.tool_file_multi_edit_no_edits));
            }

            String content = FileIo.readUtf8(file);
            boolean isGrounded = GroundedStateManager.getInstance().isGrounded(file.getAbsolutePath());
            if (!isGrounded) {
                GroundedStateManager.getInstance().recordState(
                        file.getAbsolutePath(),
                        content,
                        GroundedSourceType.READ,
                        context != null ? context.getToolCallId() : ""
                );
            }
            List<EditChunk> chunks = new ArrayList<>();

            for (int i = 0; i < editsArray.length(); i++) {
                JSONObject editObj = editsArray.optJSONObject(i);
                if (editObj == null) {
                    return error(context.getString(R.string.tool_file_multi_edit_chunk_failed, i + 1, "Invalid edit object"));
                }
                String oldString = editObj.optString("old_string");
                String newString = editObj.optString("new_string", "");
                if (oldString == null || oldString.isEmpty()) {
                    return error(context.getString(R.string.tool_file_multi_edit_chunk_failed, i + 1, context.getString(R.string.tool_file_edit_old_string_empty)));
                }

                int firstIndex = content.indexOf(oldString);
                if (firstIndex < 0) {
                    return error(context.getString(R.string.tool_file_multi_edit_chunk_failed, i + 1, context.getString(R.string.tool_file_edit_no_match))
                            + "\n[Diagnostic Hint]: TargetChunk #" + (i + 1) + " not found. Call file_read to inspect current line content.");
                }
                int secondIndex = content.indexOf(oldString, firstIndex + 1);
                if (secondIndex >= 0) {
                    int count = countOccurrences(content, oldString);
                    return error(context.getString(R.string.tool_file_multi_edit_chunk_failed, i + 1, context.getString(R.string.tool_file_edit_multiple_matches, count)));
                }

                EditChunk chunk = new EditChunk(i + 1, oldString, newString);
                chunk.startIndex = firstIndex;
                chunk.endIndex = firstIndex + oldString.length();
                chunks.add(chunk);
            }

            // Check for chunk overlaps
            Collections.sort(chunks);
            for (int i = 0; i < chunks.size() - 1; i++) {
                EditChunk current = chunks.get(i);
                EditChunk next = chunks.get(i + 1);
                if (current.endIndex > next.startIndex) {
                    return error(String.format("Edit chunks %d and %d overlap in the file. Edits must be distinct and non-overlapping.", current.index, next.index));
                }
            }

            // Apply edits from right to left (end to start) to preserve string indexes
            StringBuilder sb = new StringBuilder(content);
            for (int i = chunks.size() - 1; i >= 0; i--) {
                EditChunk chunk = chunks.get(i);
                sb.replace(chunk.startIndex, chunk.endIndex, chunk.newString);
            }

            String updatedContent = sb.toString();
            try (FileOutputStream output = new FileOutputStream(file, false)) {
                output.write(updatedContent.getBytes(StandardCharsets.UTF_8));
            }

            GroundedStateManager.getInstance().recordState(
                    file.getAbsolutePath(),
                    updatedContent,
                    GroundedSourceType.WRITE,
                    context != null ? context.getToolCallId() : ""
            );

            String displayPath = FileToolPathPolicy.displayPath(context.getHomePath(), file);
            return ok(context.getString(R.string.tool_file_multi_edit_success, chunks.size(), displayPath));
        } catch (Exception e) {
            return error(context.getString(R.string.tool_file_edit_failed, e.getMessage()));
        }
    }

    private static int countOccurrences(String content, String value) {
        int count = 0;
        int index = 0;
        while ((index = content.indexOf(value, index)) >= 0) {
            count++;
            index += value.length();
        }
        return count;
    }

    @Override
    public Class<? extends cn.lineai.tool.ToolCallCardView> getToolCallViewClass() {
        return cn.lineai.tool.ui.ToolCallWriteView.class;
    }
}
