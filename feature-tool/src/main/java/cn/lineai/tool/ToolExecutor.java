package cn.lineai.tool;
import cn.lineai.model.tool.ToolCall;
import cn.lineai.model.tool.ToolResult;

import cn.lineai.data.repository.LearningContextStore;
import cn.lineai.data.repository.PromptTemplateRepository;
import cn.lineai.data.repository.SshFileTreeStore;
import cn.lineai.data.repository.ToolSettingsStore;
import cn.lineai.model.ModelStore;
import org.json.JSONObject;

public final class ToolExecutor {
    private final ToolRegistry registry;
    private final ToolSettingsStore settingsRepository;
    private final ModelStore modelRepository;
    private final SshFileTreeStore sshFileTreeRepository;
    private final ModelServiceProvider modelServiceProvider;
    private final PromptTemplateRepository promptTemplateRepository;
    private final LearningContextStore learningContextStore;
    private final DiffRecorder diffRecorder;

    public ToolExecutor(
            ToolRegistry registry,
            ToolSettingsStore settingsRepository,
            DiffRecorder diffRecorder,
            ModelStore modelRepository,
            SshFileTreeStore sshFileTreeRepository,
            ModelServiceProvider modelServiceProvider,
            PromptTemplateRepository promptTemplateRepository
    ) {
        this(registry, settingsRepository, diffRecorder, modelRepository, sshFileTreeRepository,
                modelServiceProvider, promptTemplateRepository, null);
    }

    public ToolExecutor(
            ToolRegistry registry,
            ToolSettingsStore settingsRepository,
            DiffRecorder diffRecorder,
            ModelStore modelRepository,
            SshFileTreeStore sshFileTreeRepository,
            ModelServiceProvider modelServiceProvider,
            PromptTemplateRepository promptTemplateRepository,
            LearningContextStore learningContextStore
    ) {
        this.registry = registry;
        this.settingsRepository = settingsRepository;
        this.diffRecorder = diffRecorder;
        this.modelRepository = modelRepository;
        this.sshFileTreeRepository = sshFileTreeRepository;
        this.modelServiceProvider = modelServiceProvider;
        this.promptTemplateRepository = promptTemplateRepository;
        this.learningContextStore = learningContextStore;
    }

    public ToolResult execute(ToolCall toolCall, ToolContext context) {
        return execute(toolCall, context, false);
    }

    public ToolResult executeConfirmed(ToolCall toolCall, ToolContext context) {
        return execute(toolCall, context, true);
    }

    private ToolResult execute(ToolCall toolCall, ToolContext context, boolean confirmed) {
        if (toolCall == null) {
            return ToolResult.error("工具调用为空");
        }
        ToolContext baseContext = context == null ? ToolContext.builder().homePath("").build() : context;
        if (context == null || needsInjection(context)) {
            baseContext = injectDependencies(baseContext);
        }
        ToolContext callContext = baseContext.withToolCallId(toolCall.getId());
        BaseTool tool = registry.get(toolCall.getName());
        if (tool == null) {
            return ToolResult.of(toolCall.getId(), toolCall.getName(), "未知工具: " + toolCall.getName(), true);
        }
        PermissionResult permission = settingsRepository.canExecuteTool(tool.getName(), tool.getCategory());
        if (!permission.isAllowed()) {
            return ToolResult.of(toolCall.getId(), tool.getName(), permission.getReason(), true);
        }
        if (tool.needsConfirmation() && settingsRepository.needsConfirmation(tool.getName()) && !confirmed) {
            return ToolResult.of(toolCall.getId(), tool.getName(), "工具需要确认后才能执行: " + tool.getName(), true);
        }
        JSONObject input;
        try {
            String args = ToolArgsCleaner.clean(toolCall.getArguments());
            input = args.trim().length() == 0 ? new JSONObject() : new JSONObject(args);
        } catch (Exception e) {
            restoreInterrupt(e);
            return ToolResult.of(toolCall.getId(), tool.getName(), "参数解析失败: " + describeException(e), true);
        }
        PreFlightGroundingValidator.ValidationOutcome preFlight =
                PreFlightGroundingValidator.getInstance().validatePreFlight(tool, input, callContext);
        long startTime = System.currentTimeMillis();
        try {
            ToolResult result = diffRecorder != null && diffRecorder.shouldRecordDiff(tool)
                    ? diffRecorder.executeWithDiff(tool, input, callContext)
                    : tool.execute(input, callContext);
            long durationMs = System.currentTimeMillis() - startTime;
            boolean isError = result != null && result.isError();
            String resultContent = result != null ? result.getContent() : "";

            // If error occurred and we had pre-flight advice, append diagnostic hint
            if (isError && preFlight.getDiagnosticAdvice() != null && !resultContent.contains("[Pre-Flight")) {
                resultContent = resultContent + "\n\n" + preFlight.getDiagnosticAdvice();
                result = ToolResult.of(toolCall.getId(), tool.getName(), resultContent, true);
            }

            // Log accuracy telemetry
            String targetPath = input.optString("path", input.optString("file_path", input.optString("target_file", input.optString("command", ""))));
            boolean isGrounded = preFlight.getConfidenceReport().getGroundedFactor() >= 0.90;
            cn.lineai.data.repository.AgentAccuracyLogger.getInstance().logExecution(
                    tool.getName(),
                    targetPath,
                    false,
                    isGrounded,
                    !isError,
                    preFlight.getConfidenceReport().getTotalScore(),
                    resultContent,
                    durationMs
            );

            if (isError) {
                cn.lineai.data.repository.PostMortemLearningEngine.getInstance().recordFailure(
                        callContext.getHomePath(),
                        tool.getName(),
                        input.toString(),
                        resultContent
                );
            } else {
                cn.lineai.data.repository.PostMortemLearningEngine.getInstance().recordSuccess(
                        callContext.getHomePath(),
                        tool.getName(),
                        input.toString(),
                        resultContent
                );
            }
            return result.withCall(toolCall.getId(), tool.getName());
        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - startTime;
            restoreInterrupt(e);
            String err = describeException(e);
            String targetPath = input.optString("path", input.optString("file_path", input.optString("target_file", input.optString("command", ""))));
            cn.lineai.data.repository.AgentAccuracyLogger.getInstance().logExecution(
                    tool.getName(),
                    targetPath,
                    false,
                    false,
                    false,
                    preFlight.getConfidenceReport().getTotalScore(),
                    err,
                    durationMs
            );
            cn.lineai.data.repository.PostMortemLearningEngine.getInstance().recordFailure(
                    callContext.getHomePath(),
                    tool.getName(),
                    input.toString(),
                    err
            );
            return ToolResult.of(toolCall.getId(), tool.getName(), "工具执行失败: " + err, true);
        }
    }

    private boolean needsInjection(ToolContext context) {
        return context.getToolSettingsStore() == null
                || context.getModelRepository() == null
                || context.getModelServiceProvider() == null
                || context.getLearningContextStore() == null;
    }

    private ToolContext injectDependencies(ToolContext context) {
        return ToolContext.builder()
                .homePath(context.getHomePath())
                .extraWriteRoots(context.getExtraWriteRoots())
                .agentRunner(context.getAgentRunner())
                .toolCallId(context.getToolCallId())
                .progressListener(context.getProgressListener())
                .todoStateStore(context.getTodoStateStore())
                .learningContextStore(context.getLearningContextStore() != null ? context.getLearningContextStore() : learningContextStore)
                .toolSettingsStore(context.getToolSettingsStore() != null ? context.getToolSettingsStore() : settingsRepository)
                .modelRepository(context.getModelRepository() != null ? context.getModelRepository() : modelRepository)
                .sshFileTreeRepository(context.getSshFileTreeRepository() != null ? context.getSshFileTreeRepository() : sshFileTreeRepository)
                .modelServiceProvider(context.getModelServiceProvider() != null ? context.getModelServiceProvider() : modelServiceProvider)
                .promptTemplateRepository(context.getPromptTemplateRepository() != null ? context.getPromptTemplateRepository() : promptTemplateRepository)
                .bypassPathProtection(context.isBypassPathProtection())
                .appContext(context.getAndroidContext())
                .stringResolver(context.getStringResolver())
                .agentResultStore(context.getAgentResultStore())
                .build();
    }

    private static void restoreInterrupt(Exception error) {
        ExceptionUtils.restoreInterrupt(error);
    }

    private static String describeException(Exception error) {
        return ExceptionUtils.describeException(error);
    }
}
