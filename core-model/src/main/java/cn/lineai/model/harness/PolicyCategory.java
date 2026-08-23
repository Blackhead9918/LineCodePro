package cn.lineai.model.harness;

/**
 * Classification of tool/side-effect categories for Policy Gate (LCP-Harness v1 §10).
 *
 * <p>Maps to the existing permission mode system ({@code PermissionModeController}).
 * Used by {@code PolicyGate} to determine if a decision is allowed.
 */
public enum PolicyCategory {

    /** No side effects — read-only tools (FileRead, Glob, ListDirectory). */
    READ_ONLY,

    /** Mutating but reversible — file write/edit. */
    MUTATING,

    /** Destructive / hard to reverse — file delete, git reset. */
    DESTRUCTIVE,

    /** External system interaction — git push, web fetch. */
    EXTERNAL,

    /** Privileged — phone control, shell execute with elevated access. */
    PRIVILEGED;

    /** Returns true if this category requires user confirmation in standard mode. */
    public boolean requiresConfirmation() {
        return this == MUTATING || this == DESTRUCTIVE || this == EXTERNAL || this == PRIVILEGED;
    }

    /** Returns true if this category is allowed in read-only permission mode. */
    public boolean allowedInReadOnly() {
        return this == READ_ONLY;
    }
}
