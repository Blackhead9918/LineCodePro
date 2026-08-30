package cn.lineai.ai.prompt;

import android.content.Context;
import cn.lineai.data.db.LineCodeDatabase;
import cn.lineai.data.repository.PromptTemplateRepository;
import cn.lineai.data.repository.SettingsRepository;
import cn.lineai.model.AiBehaviorSettings;
import cn.lineai.model.ModelConfig;
import cn.lineai.model.ModelProtocolType;
import cn.lineai.resource.ResourceProvider;
import cn.lineai.workspace.WorkspacePaths;
import java.io.InputStream;
import java.util.HashMap;

public final class SystemPromptProvider {
    private final WorkspacePaths workspacePaths;
    private final PromptTemplateRepository promptTemplateRepository;

    public SystemPromptProvider(Context context, PromptTemplateRepository promptTemplateRepository) {
        Context appContext = context.getApplicationContext();
        this.workspacePaths = new WorkspacePaths(appContext);
        this.promptTemplateRepository = promptTemplateRepository;
    }

    public String build(String homePath) {
        return build(homePath, AiBehaviorSettings.TONE_CODING);
    }

    public String build(String homePath, String toneMode) {
        return build(homePath, toneMode, "");
    }

    public String build(String homePath, String toneMode, String learningContext) {
        return build(homePath, toneMode, learningContext, "");
    }

    public String build(String homePath, String toneMode, String learningContext, String toolsContext) {
        return build(homePath, toneMode, "", learningContext, toolsContext, null);
    }

    public String build(
            String homePath,
            String toneMode,
            String chatModeContext,
            String learningContext,
            String toolsContext
    ) {
        return build(homePath, toneMode, chatModeContext, learningContext, toolsContext, null, "");
    }

    public String build(
            String homePath,
            String toneMode,
            String chatModeContext,
            String learningContext,
            String toolsContext,
            ModelConfig model
    ) {
        return build(homePath, toneMode, chatModeContext, learningContext, toolsContext, model, "");
    }

    public String build(
            String homePath,
            String toneMode,
            String chatModeContext,
            String learningContext,
            String toolsContext,
            ModelConfig model,
            String todoStateContext
    ) {
        String enrichedLearningContext = enrichLearningContext(homePath, learningContext);
        HashMap<String, String> values = new HashMap<>();
        values.put("TONE_CONTEXT", toneContext(toneMode));
        values.put("CHAT_MODE_CONTEXT", chatModeContext == null ? "" : chatModeContext.trim());
        values.put("WORK_DIRECTORY_CONTEXT", workDirectoryContext(homePath));
        values.put("LEARNING_CONTEXT", enrichedLearningContext);
        values.put("TOOLS_CONTEXT", toolsContext == null ? "" : toolsContext.trim());
        values.put("MODEL_IDENTITY", modelIdentityContext(model));
        values.put("TODO_STATE", renderTodoStateContext(todoStateContext));
        return template().render(values);
    }

    private String enrichLearningContext(String homePath, String learningContext) {
        StringBuilder sb = new StringBuilder();

        // 1. Injected Workspace Context Profile
        try {
            cn.lineai.data.repository.WorkspaceContextProfiler.WorkspaceProfile profile =
                    cn.lineai.data.repository.WorkspaceContextProfiler.getInstance().profileWorkspace(homePath);
            if (profile != null) {
                sb.append(profile.formatForPrompt()).append("\n\n");
            }
        } catch (Exception ignored) {
        }

        // 2. User & System Learning Context
        if (learningContext != null && !learningContext.trim().isEmpty()) {
            sb.append(learningContext.trim()).append("\n\n");
        }

        // 3. Grounded Scoped Invariants & Lessons
        try {
            java.util.List<cn.lineai.data.repository.ScopedMemoryRule> autoRules =
                    cn.lineai.data.repository.ScopedMemoryRegistry.getInstance().selectEligibleRules(homePath, null);
            if (autoRules != null && !autoRules.isEmpty()) {
                sb.append("### Grounded Project Invariants & Lessons:\n");
                for (cn.lineai.data.repository.ScopedMemoryRule rule : autoRules) {
                    sb.append("- [").append(rule.getCategory().name()).append("] ")
                            .append(rule.getRecommendedRule());
                    if (rule.getCondition() != null && !rule.getCondition().trim().isEmpty()) {
                        sb.append(" (Condition: ").append(rule.getCondition()).append(")");
                    }
                    sb.append("\n");
                }
                sb.append("\n");
            }
        } catch (Exception ignored) {
        }

        // 4. Grounded Pre-Flight Reasoning Guidelines
        sb.append("### Grounded Execution Guidelines:\n")
          .append("1. Always call `file_read` before attempting `file_edit` on any file.\n")
          .append("2. Maintain continuous grounded verification; do not assume file content based on assumptions.\n")
          .append("3. If an edit fails, check the diagnostic hint and re-read the exact physical lines.\n");

        return sb.toString().trim();
    }

    private String renderTodoStateContext(String todoListText) {
        String safeList = todoListText == null ? "" : todoListText.trim();
        if (safeList.length() == 0) {
            return new StringTemplate(promptTemplateRepository.getTemplateText(PromptTemplateRepository.ID_TODO_USAGE)).render(new HashMap<>());
        }
        HashMap<String, String> values = new HashMap<>();
        values.put("TODO_LIST", safeList);
        return new StringTemplate(promptTemplateRepository.getTemplateText(PromptTemplateRepository.ID_TODO_STATE)).render(values);
    }

    private StringTemplate template() {
        return new StringTemplate(promptTemplateRepository.getTemplateText(PromptTemplateRepository.ID_SYSTEM_PROMPT));
    }

    private String workDirectoryContext(String homePath) {
        if (homePath == null || homePath.trim().length() == 0) {
            return "";
        }
        HashMap<String, String> values = new HashMap<>();
        values.put("HOME_PATH", homePath.trim());
        values.put("LINECODE_ROOT", workspacePaths.getLinecodeRoot().getAbsolutePath());
        values.put("GLOBAL_SKILLS_ROOT", workspacePaths.getSkillsRoot().getAbsolutePath());
        values.put("WORKSPACE_PRIVATE_ROOT", WorkspacePaths.join(homePath.trim(), ".linecode"));
        values.put("WORKSPACE_SKILLS_ROOT", WorkspacePaths.join(homePath.trim(), ".linecode/skills"));
        return new StringTemplate(promptTemplateRepository.getTemplateText(PromptTemplateRepository.ID_WORK_DIRECTORY)).render(values);
    }

    private String toneContext(String toneMode) {
        if (AiBehaviorSettings.TONE_CHAT.equals(toneMode)) {
            return new StringTemplate(promptTemplateRepository.getTemplateText(PromptTemplateRepository.ID_TONE_CHAT)).render(new HashMap<>());
        }
        return new StringTemplate(promptTemplateRepository.getTemplateText(PromptTemplateRepository.ID_TONE_CODING)).render(new HashMap<>());
    }

    private String modelIdentityContext(ModelConfig model) {
        if (model == null) {
            return "";
        }
        String modelId = safe(model.getModelId());
        if (modelId.length() == 0) {
            return "";
        }
        HashMap<String, String> values = new HashMap<>();
        values.put("MODEL_ID", modelId);
        values.put("MODEL_NAME", safe(model.getName()));
        values.put("MODEL_PROVIDER", safe(model.getProviderLabel()));
        values.put("MODEL_PROTOCOL", protocolLabel(model.getProtocolType()));
        return new StringTemplate(promptTemplateRepository.getTemplateText(PromptTemplateRepository.ID_MODEL_IDENTITY)).render(values);
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private static String protocolLabel(ModelProtocolType type) {
        return type == null ? "" : type.getLabel();
    }
}
