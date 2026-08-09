package cn.lineai.ai;

public final class ModelCompletionException extends Exception {
    /** HTTP status code of the failed request, or -1 when not an HTTP error. */
    private final int statusCode;

    public ModelCompletionException(String message) {
        this(message, -1, null);
    }

    public ModelCompletionException(String message, Throwable cause) {
        this(message, -1, cause);
    }

    public ModelCompletionException(String message, int statusCode) {
        this(message, statusCode, null);
    }

    private ModelCompletionException(String message, int statusCode, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    /**
     * HTTP status code of the failed request, or -1 when the failure was not
     * an HTTP error (timeout, parse failure, stream interruption, ...).
     */
    public int getStatusCode() {
        return statusCode;
    }
}
