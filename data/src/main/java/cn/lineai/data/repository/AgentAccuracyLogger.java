package cn.lineai.data.repository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Structured Telemetry and Audit Trail for Agent Tool Executions & Accuracy Metrics.
 */
public final class AgentAccuracyLogger {
    private static final AgentAccuracyLogger INSTANCE = new AgentAccuracyLogger();

    public static class ToolLogEntry {
        private final long id;
        private final long timestamp;
        private final String toolName;
        private final String targetPath;
        private final boolean isRemote;
        private final boolean isGrounded;
        private final boolean isSuccess;
        private final double confidenceScore;
        private final String summary;
        private final long durationMs;

        public ToolLogEntry(
                long id,
                long timestamp,
                String toolName,
                String targetPath,
                boolean isRemote,
                boolean isGrounded,
                boolean isSuccess,
                double confidenceScore,
                String summary,
                long durationMs
        ) {
            this.id = id;
            this.timestamp = timestamp;
            this.toolName = toolName == null ? "" : toolName;
            this.targetPath = targetPath == null ? "" : targetPath;
            this.isRemote = isRemote;
            this.isGrounded = isGrounded;
            this.isSuccess = isSuccess;
            this.confidenceScore = confidenceScore;
            this.summary = summary == null ? "" : summary;
            this.durationMs = durationMs;
        }

        public long getId() { return id; }
        public long getTimestamp() { return timestamp; }
        public String getToolName() { return toolName; }
        public String getTargetPath() { return targetPath; }
        public boolean isRemote() { return isRemote; }
        public boolean isGrounded() { return isGrounded; }
        public boolean isSuccess() { return isSuccess; }
        public double getConfidenceScore() { return confidenceScore; }
        public String getSummary() { return summary; }
        public long getDurationMs() { return durationMs; }
    }

    public static class AccuracyStats {
        private final int totalActions;
        private final int successfulActions;
        private final int groundedActions;
        private final double successRate;
        private final double groundedRate;
        private final double averageConfidence;

        public AccuracyStats(
                int totalActions,
                int successfulActions,
                int groundedActions,
                double successRate,
                double groundedRate,
                double averageConfidence
        ) {
            this.totalActions = totalActions;
            this.successfulActions = successfulActions;
            this.groundedActions = groundedActions;
            this.successRate = successRate;
            this.groundedRate = groundedRate;
            this.averageConfidence = averageConfidence;
        }

        public int getTotalActions() { return totalActions; }
        public int getSuccessfulActions() { return successfulActions; }
        public int getGroundedActions() { return groundedActions; }
        public double getSuccessRate() { return successRate; }
        public double getGroundedRate() { return groundedRate; }
        public double getAverageConfidence() { return averageConfidence; }
    }

    private final List<ToolLogEntry> logEntries = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, AtomicInteger> targetFailureCounts = new ConcurrentHashMap<>();
    private final AtomicInteger idCounter = new AtomicInteger(1);

    private AgentAccuracyLogger() {
    }

    public static AgentAccuracyLogger getInstance() {
        return INSTANCE;
    }

    public void logExecution(
            String toolName,
            String targetPath,
            boolean isRemote,
            boolean isGrounded,
            boolean isSuccess,
            double confidenceScore,
            String summary,
            long durationMs
    ) {
        long id = idCounter.getAndIncrement();
        ToolLogEntry entry = new ToolLogEntry(
                id,
                System.currentTimeMillis(),
                toolName,
                targetPath,
                isRemote,
                isGrounded,
                isSuccess,
                confidenceScore,
                summary,
                durationMs
        );
        logEntries.add(entry);

        // Keep buffer bounded
        if (logEntries.size() > 500) {
            logEntries.remove(0);
        }

        // Track target failure counts for retry penalty calculation
        if (targetPath != null && !targetPath.trim().isEmpty()) {
            String key = targetPath.trim();
            if (!isSuccess) {
                targetFailureCounts.computeIfAbsent(key, k -> new AtomicInteger(0)).incrementAndGet();
            } else {
                targetFailureCounts.remove(key);
            }
        }
    }

    public int getRecentFailureCount(String targetPath) {
        if (targetPath == null || targetPath.trim().isEmpty()) {
            return 0;
        }
        AtomicInteger count = targetFailureCounts.get(targetPath.trim());
        return count != null ? count.get() : 0;
    }

    public AccuracyStats getStats() {
        synchronized (logEntries) {
            int total = logEntries.size();
            if (total == 0) {
                return new AccuracyStats(0, 0, 0, 1.0, 1.0, 1.0);
            }
            int success = 0;
            int grounded = 0;
            double sumConf = 0.0;
            for (ToolLogEntry entry : logEntries) {
                if (entry.isSuccess()) success++;
                if (entry.isGrounded()) grounded++;
                sumConf += entry.getConfidenceScore();
            }
            return new AccuracyStats(
                    total,
                    success,
                    grounded,
                    (double) success / total,
                    (double) grounded / total,
                    sumConf / total
            );
        }
    }

    public List<ToolLogEntry> getRecentLogs(int limit) {
        synchronized (logEntries) {
            int size = logEntries.size();
            int start = Math.max(0, size - limit);
            return new ArrayList<>(logEntries.subList(start, size));
        }
    }

    public void clear() {
        logEntries.clear();
        targetFailureCounts.clear();
    }
}
