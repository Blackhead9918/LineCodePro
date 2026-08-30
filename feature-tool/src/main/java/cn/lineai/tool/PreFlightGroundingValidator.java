package cn.lineai.tool;

import cn.lineai.data.repository.AgentAccuracyLogger;
import cn.lineai.data.repository.AgentConfidenceCalculator;
import cn.lineai.data.repository.GroundedStateManager;
import cn.lineai.model.grounding.AgentConfidenceReport;
import cn.lineai.model.tool.ToolResult;
import org.json.JSONObject;

/**
 * Pre-flight validator ensuring empirical grounding, reasoning verification,
 * and safety checks before mutating or critical tool executions.
 */
public final class PreFlightGroundingValidator {
    private static final PreFlightGroundingValidator INSTANCE = new PreFlightGroundingValidator();

    private PreFlightGroundingValidator() {
    }

    public static PreFlightGroundingValidator getInstance() {
        return INSTANCE;
    }

    public static class ValidationOutcome {
        private final boolean allowed;
        private final AgentConfidenceReport confidenceReport;
        private final String diagnosticAdvice;

        public ValidationOutcome(boolean allowed, AgentConfidenceReport confidenceReport, String diagnosticAdvice) {
            this.allowed = allowed;
            this.confidenceReport = confidenceReport;
            this.diagnosticAdvice = diagnosticAdvice;
        }

        public boolean isAllowed() { return allowed; }
        public AgentConfidenceReport getConfidenceReport() { return confidenceReport; }
        public String getDiagnosticAdvice() { return diagnosticAdvice; }
    }

    public ValidationOutcome validatePreFlight(BaseTool tool, JSONObject input, ToolContext context) {
        String toolName = tool.getName();
        String targetPath = extractTargetPath(toolName, input);
        boolean isRemote = false;
        String host = null;
        int port = 22;

        AgentConfidenceReport report = AgentConfidenceCalculator.getInstance().evaluateConfidence(
                targetPath,
                isRemote,
                host,
                port,
                toolName
        );

        // Pre-flight check: If attempting to edit or delete a file that is not grounded
        if ((toolName.equals("file_edit") || toolName.equals("file_multi_edit")) && targetPath != null && !targetPath.isEmpty()) {
            boolean isGrounded = GroundedStateManager.getInstance().isGrounded(targetPath);
            if (!isGrounded) {
                // If confidence is low and ungrounded, provide pre-flight diagnostic advice
                String advice = "[Pre-Flight Reasoning Guardrail]: Target file '" + targetPath +
                        "' is ungrounded (not yet read in this session). Confidence Score: " +
                        String.format("%.2f", report.getTotalScore()) + " (" + report.getLevel() + ").\n" +
                        "Recommended Next Step: Call file_read on '" + targetPath + "' first to ground exact lines.";
                return new ValidationOutcome(true, report, advice);
            }
        }

        return new ValidationOutcome(true, report, null);
    }

    private String extractTargetPath(String toolName, JSONObject input) {
        if (input == null) return null;
        if (input.has("path")) return input.optString("path", "");
        if (input.has("file_path")) return input.optString("file_path", "");
        if (input.has("target_file")) return input.optString("target_file", "");
        if (input.has("targetFile")) return input.optString("targetFile", "");
        if (input.has("command")) return input.optString("command", "");
        return "";
    }
}
