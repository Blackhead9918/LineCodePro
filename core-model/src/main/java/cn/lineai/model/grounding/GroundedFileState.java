package cn.lineai.model.grounding;

import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class GroundedFileState implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String path;
    private final String contentHash;
    private final GroundedSourceType source;
    private final long groundedAt;
    private final String sessionId;
    private final int lineCount;

    public GroundedFileState(String path, String contentHash, GroundedSourceType source, long groundedAt, String sessionId, int lineCount) {
        this.path = path == null ? "" : path;
        this.contentHash = contentHash == null ? "" : contentHash;
        this.source = source == null ? GroundedSourceType.READ : source;
        this.groundedAt = groundedAt;
        this.sessionId = sessionId == null ? "" : sessionId;
        this.lineCount = lineCount;
    }

    public static GroundedFileState create(String path, String content, GroundedSourceType source, String sessionId) {
        String hash = computeSha256(content);
        int lines = content == null || content.isEmpty() ? 0 : content.split("\r\n|\r|\n").length;
        return new GroundedFileState(path, hash, source, System.currentTimeMillis(), sessionId, lines);
    }

    public String getPath() {
        return path;
    }

    public String getContentHash() {
        return contentHash;
    }

    public GroundedSourceType getSource() {
        return source;
    }

    public long getGroundedAt() {
        return groundedAt;
    }

    public String getSessionId() {
        return sessionId;
    }

    public int getLineCount() {
        return lineCount;
    }

    public boolean isExpired(long maxAgeMs) {
        if (maxAgeMs <= 0) {
            return false;
        }
        return (System.currentTimeMillis() - groundedAt) > maxAgeMs;
    }

    public static String computeSha256(String text) {
        if (text == null) {
            text = "";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(text.hashCode());
        }
    }
}
