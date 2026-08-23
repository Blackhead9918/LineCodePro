package cn.lineai.ai.harness;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import cn.lineai.model.harness.ContextItem;
import cn.lineai.model.harness.ContextPriority;

/**
 * SELECT / PRUNE operations over candidate context items
 * (LCP-Harness v1 §6, §6.1).
 *
 * <p><b>SELECT:</b> keep highest-priority items first until the token budget is spent.
 * <b>P0</b> (system/capsule) and <b>P1</b> (latest user request) are protected: they are
 * always included even if they alone exceed the budget (the caller must then grow the
 * budget or compact — protected items are never silently dropped).
 *
 * <p><b>PRUNE:</b> drop lowest-priority items first; within the same priority,
 * oldest-updated items are dropped first (recency wins inside a level).
 *
 * <p>COMPACT remains owned by {@code ContextCompactionService} — this class never
 * summarizes content; it only selects or drops whole items.
 *
 * <p>Thread-safety: stateless — safe for concurrent use.
 */
public final class ContextSelector {

    private ContextSelector() {} // utility class

    /**
     * Select items that fit within {@code tokenBudget}, respecting priorities.
     * Protected items (P0/P1) are always included.
     *
     * @return selection result containing included and pruned items
     */
    public static Selection select(List<ContextItem> candidates, int tokenBudget) {
        if (candidates == null) candidates = Collections.emptyList();
        List<ContextItem> sorted = byPriorityThenRecency(candidates);

        List<ContextItem> included = new ArrayList<>();
        List<ContextItem> pruned = new ArrayList<>();
        int used = 0;

        for (ContextItem item : sorted) {
            int cost = item.estimatedTokens();
            if (item.priority().isProtected()) {
                included.add(item);
                used += cost;
            } else if (used + cost <= tokenBudget) {
                included.add(item);
                used += cost;
            } else {
                pruned.add(item);
            }
        }
        return new Selection(included, pruned, used, tokenBudget);
    }

    /**
     * Prune items to reduce total estimated tokens to at most {@code maxTokens},
     * dropping lowest-priority / oldest items first. Protected items survive unless
     * nothing else remains to prune.
     *
     * @return the pruned list (new list; input not modified)
     */
    public static List<ContextItem> prune(List<ContextItem> items, int maxTokens) {
        if (items == null) return Collections.emptyList();
        List<ContextItem> result = new ArrayList<>(items);
        int total = sumTokens(result);

        // Lowest priority first; within same priority oldest updated first
        Comparator<ContextItem> dropOrder = (a, b) -> {
            int byPriority = Integer.compare(
                    b.priority().ordinal(), a.priority().ordinal()); // higher ordinal = lower priority = drop first
            if (byPriority != 0) return byPriority;
            return Long.compare(a.updatedAt(), b.updatedAt());
        };

        result.sort(dropOrder);
        List<ContextItem> kept = new ArrayList<>();
        List<ContextItem> deferredProtected = new ArrayList<>();

        for (ContextItem item : result) {
            if (!item.priority().isProtected()
                    && total > maxTokens
                    && total - item.estimatedTokens() >= 0) {
                total -= item.estimatedTokens(); // drop
            } else if (item.priority().isProtected()) {
                deferredProtected.add(item);     // decide after non-protected pruning
            } else {
                kept.add(item);
            }
        }

        // Protected items are added back in natural priority order
        deferredProtected.sort(Comparator.comparingInt(a -> a.priority().ordinal()));
        kept.addAll(deferredProtected);
        return kept;
    }

    private static int sumTokens(List<ContextItem> items) {
        int t = 0;
        for (ContextItem i : items) t += i.estimatedTokens();
        return t;
    }

    private static List<ContextItem> byPriorityThenRecency(List<ContextItem> candidates) {
        List<ContextItem> sorted = new ArrayList<>(candidates);
        sorted.sort((a, b) -> {
            int byPriority = Integer.compare(
                    a.priority().ordinal(), b.priority().ordinal());
            if (byPriority != 0) return byPriority;
            return Long.compare(b.updatedAt(), a.updatedAt()); // newer first within level
        });
        return sorted;
    }

    /** Outcome of a SELECT operation. Immutable value object. */
    public static final class Selection {
        private final List<ContextItem> included;
        private final List<ContextItem> pruned;
        private final int usedTokens;
        private final int budgetTokens;

        Selection(List<ContextItem> included, List<ContextItem> pruned,
                  int usedTokens, int budgetTokens) {
            this.included = Collections.unmodifiableList(included);
            this.pruned = Collections.unmodifiableList(pruned);
            this.usedTokens = usedTokens;
            this.budgetTokens = budgetTokens;
        }

        public List<ContextItem> included() { return included; }
        public List<ContextItem> pruned() { return pruned; }
        public int usedTokens() { return usedTokens; }
        public int budgetTokens() { return budgetTokens; }

        /** True if P0/P1 alone exceeded the budget (caller must compact or raise budget). */
        public boolean protectedOverflow() {
            int protectedCost = 0;
            for (ContextItem i : included) {
                if (i.priority().isProtected()) protectedCost += i.estimatedTokens();
            }
            return protectedCost > budgetTokens;
        }
    }
}
