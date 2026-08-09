package cn.lineai.ai;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Pure retry policy for model requests.
 *
 * <p>Rules:</p>
 * <ul>
 *   <li>Permanent HTTP errors (4xx except 408 Request Timeout and 429 Too Many
 *       Requests) are not retried - the request would fail identically.</li>
 *   <li>Retryable failures use exponential backoff with jitter.</li>
 * </ul>
 */
public final class RetryPolicy {

    private static final long BASE_DELAY_MS = 2000L;
    private static final long MAX_DELAY_MS = 16000L;
    private static final long JITTER_MS = 500L;

    private RetryPolicy() {
    }

    /**
     * Whether an HTTP status code should be retried. 4xx errors are permanent
     * (401/403/404/...), except 408 (server timed out) and 429 (rate limited)
     * which are transient and worth retrying.
     */
    public static boolean isRetryableStatusCode(int statusCode) {
        if (statusCode < 400 || statusCode >= 500) {
            return true;
        }
        return statusCode == 408 || statusCode == 429;
    }

    /**
     * Exponential backoff for the given 1-based retry attempt, without jitter:
     * 2s, 4s, 8s, 16s (capped). Deterministic for tests.
     */
    public static long backoffDelayMs(int attempt) {
        int exponent = Math.max(0, attempt - 1);
        long delay = BASE_DELAY_MS;
        for (int i = 0; i < exponent && delay < MAX_DELAY_MS; i++) {
            delay *= 2L;
        }
        return Math.min(delay, MAX_DELAY_MS);
    }

    /** Deterministic base delay plus random jitter in [0, JITTER_MS). */
    public static long delayWithJitterMs(int attempt) {
        return backoffDelayMs(attempt) + ThreadLocalRandom.current().nextLong(JITTER_MS);
    }
}
