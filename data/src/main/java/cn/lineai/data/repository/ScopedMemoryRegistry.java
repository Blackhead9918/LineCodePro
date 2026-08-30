package cn.lineai.data.repository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class ScopedMemoryRegistry {
    private static final ScopedMemoryRegistry INSTANCE = new ScopedMemoryRegistry();
    private final Map<String, ScopedMemoryRule> rules = new ConcurrentHashMap<>();

    private ScopedMemoryRegistry() {
    }

    public static ScopedMemoryRegistry getInstance() {
        return INSTANCE;
    }

    public void registerRule(ScopedMemoryRule rule) {
        if (rule == null || rule.getId().isEmpty()) {
            return;
        }
        rules.put(rule.getId(), rule);
    }

    public ScopedMemoryRule proposeCandidate(
            ScopedMemoryRule.Hierarchy hierarchy,
            ScopedMemoryRule.Category category,
            String scopeTarget,
            String condition,
            String observedFailure,
            String recommendedRule
    ) {
        if (recommendedRule == null || recommendedRule.trim().isEmpty()) {
            return null;
        }
        String safeScope = scopeTarget == null || scopeTarget.trim().isEmpty() ? "*" : scopeTarget.trim();
        String safeFailure = observedFailure == null ? "" : observedFailure.trim();
        String safeRec = recommendedRule.trim();

        // Check if an existing rule matches
        for (ScopedMemoryRule existing : rules.values()) {
            if (existing.getScopeTarget().equals(safeScope)
                    && (existing.getRecommendedRule().equalsIgnoreCase(safeRec)
                    || (!safeFailure.isEmpty() && existing.getObservedFailure().equalsIgnoreCase(safeFailure)))) {
                return reinforceRule(existing.getId());
            }
        }

        String id = "rule_" + System.currentTimeMillis() + "_" + Math.abs(safeRec.hashCode() % 10000);
        ScopedMemoryRule newRule = new ScopedMemoryRule(
                id,
                hierarchy,
                category,
                safeScope,
                condition,
                safeFailure,
                safeRec,
                0.70,
                ScopedMemoryRule.Status.CANDIDATE,
                System.currentTimeMillis(),
                System.currentTimeMillis(),
                1
        );
        registerRule(newRule);
        return newRule;
    }

    public ScopedMemoryRule reinforceRule(String ruleId) {
        if (ruleId == null) {
            return null;
        }
        ScopedMemoryRule existing = rules.get(ruleId);
        if (existing == null) {
            return null;
        }
        double newConfidence = Math.min(1.0, existing.getConfidence() + 0.15);
        int newHitCount = existing.getHitCount() + 1;
        ScopedMemoryRule.Status newStatus = (newConfidence >= 0.85 || newHitCount >= 2)
                ? ScopedMemoryRule.Status.VALIDATED
                : existing.getStatus();

        ScopedMemoryRule updated = new ScopedMemoryRule(
                existing.getId(),
                existing.getHierarchy(),
                existing.getCategory(),
                existing.getScopeTarget(),
                existing.getCondition(),
                existing.getObservedFailure(),
                existing.getRecommendedRule(),
                newConfidence,
                newStatus,
                existing.getCreatedAt(),
                System.currentTimeMillis(),
                newHitCount
        );
        rules.put(updated.getId(), updated);
        return updated;
    }

    public List<ScopedMemoryRule> getAllRules() {
        return new ArrayList<>(rules.values());
    }

    public List<ScopedMemoryRule> getRulesByStatus(ScopedMemoryRule.Status status) {
        List<ScopedMemoryRule> list = new ArrayList<>();
        for (ScopedMemoryRule rule : rules.values()) {
            if (rule.getStatus() == status) {
                list.add(rule);
            }
        }
        return list;
    }

    public void removeRule(String id) {
        if (id != null) {
            rules.remove(id);
        }
    }

    public List<ScopedMemoryRule> selectEligibleRules(String workspacePath, String targetFilePath) {
        List<ScopedMemoryRule> eligible = new ArrayList<>();
        for (ScopedMemoryRule rule : rules.values()) {
            if (!rule.isEligibleForAutoGrounding()) {
                continue;
            }
            if (matchesScope(rule, workspacePath, targetFilePath)) {
                eligible.add(rule);
            }
        }
        return eligible;
    }

    public List<ScopedMemoryRule> searchCandidates(String query) {
        if (query == null || query.trim().isEmpty()) {
            return Collections.emptyList();
        }
        String q = query.toLowerCase();
        List<ScopedMemoryRule> matches = new ArrayList<>();
        for (ScopedMemoryRule rule : rules.values()) {
            if (rule.getRecommendedRule().toLowerCase().contains(q)
                    || rule.getObservedFailure().toLowerCase().contains(q)
                    || rule.getCondition().toLowerCase().contains(q)) {
                matches.add(rule);
            }
        }
        return matches;
    }

    private boolean matchesScope(ScopedMemoryRule rule, String workspacePath, String targetFilePath) {
        switch (rule.getHierarchy()) {
            case GLOBAL:
                return true;
            case WORKSPACE:
                return workspacePath != null && workspacePath.contains(rule.getScopeTarget());
            case MODULE:
            case FILE_PATTERN:
                return targetFilePath != null && (targetFilePath.endsWith(rule.getScopeTarget())
                        || targetFilePath.contains(rule.getScopeTarget())
                        || "*".equals(rule.getScopeTarget()));
            default:
                return false;
        }
    }
}
