package cn.lineai.model.grounding;

import java.io.Serializable;

public final class HarnessExecutionResult implements Serializable {
    private static final long serialVersionUID = 1L;

    private final boolean success;
    private final String operation;
    private final String targetPath;
    private final String beforeHash;
    private final String afterHash;
    private final boolean contentChanged;
    private final String errorCode;
    private final String message;
    private final String diagnosticHint;

    private HarnessExecutionResult(
            boolean success,
            String operation,
            String targetPath,
            String beforeHash,
            String afterHash,
            boolean contentChanged,
            String errorCode,
            String message,
            String diagnosticHint
    ) {
        this.success = success;
        this.operation = operation == null ? "" : operation;
        this.targetPath = targetPath == null ? "" : targetPath;
        this.beforeHash = beforeHash;
        this.afterHash = afterHash;
        this.contentChanged = contentChanged;
        this.errorCode = errorCode;
        this.message = message == null ? "" : message;
        this.diagnosticHint = diagnosticHint;
    }

    public static HarnessExecutionResult success(
            String operation,
            String targetPath,
            String beforeHash,
            String afterHash,
            String message
    ) {
        boolean changed = beforeHash != null && afterHash != null && !beforeHash.equals(afterHash);
        return new HarnessExecutionResult(
                true, operation, targetPath, beforeHash, afterHash, changed, null, message, null
        );
    }

    public static HarnessExecutionResult failure(
            String operation,
            String targetPath,
            String errorCode,
            String message,
            String diagnosticHint
    ) {
        return new HarnessExecutionResult(
                false, operation, targetPath, null, null, false, errorCode, message, diagnosticHint
        );
    }

    public boolean isSuccess() {
        return success;
    }

    public String getOperation() {
        return operation;
    }

    public String getTargetPath() {
        return targetPath;
    }

    public String getBeforeHash() {
        return beforeHash;
    }

    public String getAfterHash() {
        return afterHash;
    }

    public boolean isContentChanged() {
        return contentChanged;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getMessage() {
        return message;
    }

    public String getDiagnosticHint() {
        return diagnosticHint;
    }
}
