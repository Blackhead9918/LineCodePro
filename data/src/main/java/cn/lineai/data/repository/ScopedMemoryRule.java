package cn.lineai.data.repository;

import java.io.Serializable;

public final class ScopedMemoryRule implements Serializable {
    private static final long serialVersionUID = 1L;

    public enum Hierarchy {
        GLOBAL,
        WORKSPACE,
        MODULE,
        FILE_PATTERN
    }

    public enum Category {
        INVARIANT,
        ARCHITECTURE,
        PREFERENCE,
        CONDITIONAL_FAILURE
    }

    public enum Status {
        CANDIDATE,
        VALIDATED,
        DEPRECATED
    }

    private final String id;
    private final Hierarchy hierarchy;
    private final Category category;
    private final String scopeTarget;
    private final String condition;
    private final String observedFailure;
    private final String recommendedRule;
    private final double confidence;
    private final Status status;
    private final long createdAt;
    private final long lastValidatedAt;
    private final int hitCount;

    public ScopedMemoryRule(
            String id,
            Hierarchy hierarchy,
            Category category,
            String scopeTarget,
            String condition,
            String observedFailure,
            String recommendedRule,
            double confidence,
            Status status,
            long createdAt,
            long lastValidatedAt,
            int hitCount
    ) {
        this.id = id == null ? "" : id;
        this.hierarchy = hierarchy == null ? Hierarchy.WORKSPACE : hierarchy;
        this.category = category == null ? Category.INVARIANT : category;
        this.scopeTarget = scopeTarget == null ? "*" : scopeTarget;
        this.condition = condition == null ? "" : condition;
        this.observedFailure = observedFailure == null ? "" : observedFailure;
        this.recommendedRule = recommendedRule == null ? "" : recommendedRule;
        this.confidence = Math.max(0.0, Math.min(1.0, confidence));
        this.status = status == null ? Status.CANDIDATE : status;
        this.createdAt = createdAt;
        this.lastValidatedAt = lastValidatedAt;
        this.hitCount = hitCount;
    }

    public String getId() {
        return id;
    }

    public Hierarchy getHierarchy() {
        return hierarchy;
    }

    public Category getCategory() {
        return category;
    }

    public String getScopeTarget() {
        return scopeTarget;
    }

    public String getCondition() {
        return condition;
    }

    public String getObservedFailure() {
        return observedFailure;
    }

    public String getRecommendedRule() {
        return recommendedRule;
    }

    public double getConfidence() {
        return confidence;
    }

    public Status getStatus() {
        return status;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public long getLastValidatedAt() {
        return lastValidatedAt;
    }

    public int getHitCount() {
        return hitCount;
    }

    public boolean isEligibleForAutoGrounding() {
        return status == Status.VALIDATED && confidence >= 0.70;
    }
}
