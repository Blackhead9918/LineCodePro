package cn.lineai.data.repository;

import cn.lineai.model.grounding.GroundedFileState;
import cn.lineai.model.grounding.GroundedSourceType;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class GroundedStateManager {
    private static final GroundedStateManager INSTANCE = new GroundedStateManager();
    private final Map<String, GroundedFileState> fileStates = new ConcurrentHashMap<>();

    private GroundedStateManager() {
    }

    public static GroundedStateManager getInstance() {
        return INSTANCE;
    }

    public void recordState(String path, String content, GroundedSourceType source, String sessionId) {
        if (path == null || path.trim().isEmpty()) {
            return;
        }
        String normalizedPath = normalizePath(path);
        fileStates.put(normalizedPath, GroundedFileState.create(normalizedPath, content, source, sessionId));
    }

    public GroundedFileState getState(String path) {
        if (path == null) {
            return null;
        }
        return fileStates.get(normalizePath(path));
    }

    public boolean isGrounded(String path) {
        if (path == null) {
            return false;
        }
        return fileStates.containsKey(normalizePath(path));
    }

    public boolean verifyHash(String path, String currentContent) {
        GroundedFileState state = getState(path);
        if (state == null) {
            return false;
        }
        String currentHash = GroundedFileState.computeSha256(currentContent);
        return state.getContentHash().equalsIgnoreCase(currentHash);
    }

    public void remove(String path) {
        if (path != null) {
            fileStates.remove(normalizePath(path));
        }
    }

    public void clear() {
        fileStates.clear();
    }

    public Map<String, GroundedFileState> getAllStates() {
        return Collections.unmodifiableMap(fileStates);
    }

    public static String buildRemoteKey(String host, int port, String remotePath) {
        String safeHost = host == null ? "localhost" : host.trim();
        int safePort = port <= 0 ? 22 : port;
        String safePath = remotePath == null ? "" : remotePath.trim().replace('\\', '/');
        return "ssh://" + safeHost + ":" + safePort + (safePath.startsWith("/") ? safePath : "/" + safePath);
    }

    public void recordRemoteState(String host, int port, String remotePath, String content, GroundedSourceType source, String sessionId) {
        String key = buildRemoteKey(host, port, remotePath);
        fileStates.put(key, GroundedFileState.create(key, content, source, sessionId));
    }

    public boolean isRemoteGrounded(String host, int port, String remotePath) {
        return fileStates.containsKey(buildRemoteKey(host, port, remotePath));
    }

    public GroundedFileState getRemoteState(String host, int port, String remotePath) {
        return fileStates.get(buildRemoteKey(host, port, remotePath));
    }

    private String normalizePath(String path) {
        return path.trim().replace('\\', '/');
    }
}
