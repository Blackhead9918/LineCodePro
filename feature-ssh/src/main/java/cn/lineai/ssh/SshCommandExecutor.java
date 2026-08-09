package cn.lineai.ssh;

import android.content.Context;
import cn.lineai.ssh.R;
import cn.lineai.model.SshConfig;
import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.Session;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 负责通过 SSH 连接池执行远程命令。
 * 从 SshService 中提取，遵循 SRP：SshService 管理连接与配置，SshCommandExecutor 专注命令执行。
 */
public final class SshCommandExecutor {

    public interface OutputListener {
        void onOutput(String output);
    }

    private final Context context;
    private final SshConnectionPool connectionPool;

    public SshCommandExecutor(Context context, SshConnectionPool connectionPool) {
        this.context = context.getApplicationContext();
        this.connectionPool = connectionPool;
    }

    public String executeCommand(String command, int timeoutMs, SshConfig config, OutputListener listener) throws Exception {
        String safeCommand = command == null ? "" : command.trim();
        if (safeCommand.length() == 0) {
            throw new IllegalArgumentException(context.getString(R.string.ssh_error_command_empty));
        }
        SshConfig safeConfig = config == null ? throwNotConfigured() : config;
        if (!safeConfig.isConfigured()) {
            throw new IllegalStateException(context.getString(R.string.ssh_error_not_configured));
        }
        int boundedTimeout = Math.max(1000, Math.min(timeoutMs <= 0 ? 30000 : timeoutMs, 300000));
        return executeInternal(safeCommand, boundedTimeout, safeConfig, listener);
    }

    private static SshConfig throwNotConfigured() throws IllegalStateException {
        throw new IllegalStateException("SSH not configured");
    }

    private String executeInternal(String command, int timeoutMs, SshConfig config, OutputListener listener) throws Exception {
        Session session = null;
        ChannelExec channel = null;
        boolean success = false;
        try {
            session = connectionPool.getOrCreate(config, timeoutMs);
            channel = (ChannelExec) session.openChannel("exec");
            channel.setCommand(command);
            InputStream stdout = channel.getInputStream();
            InputStream stderr = channel.getErrStream();
            channel.connect(timeoutMs);
            long startedAt = System.currentTimeMillis();
            StringBuilder output = new StringBuilder();
            StringBuilder error = new StringBuilder();

            CountDownLatch readersDone = new CountDownLatch(2);
            Thread stdoutReader = startDrainThread("linecode-ssh-stdout", stdout, output, readersDone);
            Thread stderrReader = startDrainThread("linecode-ssh-stderr", stderr, error, readersDone);

            long lastEmitAt = 0L;
            boolean interruptedWhileExecuting = false;
            while (!channel.isClosed()) {
                if (System.currentTimeMillis() - startedAt > timeoutMs) {
                    throw new IllegalStateException(context.getString(R.string.ssh_error_command_timeout));
                }
                if (listener != null) {
                    long now = System.currentTimeMillis();
                    if (now - lastEmitAt >= 80L) {
                        lastEmitAt = now;
                        String combined = combineRaw(output.toString(), error.toString());
                        if (combined.length() > 0) {
                            listener.onOutput(combined);
                        }
                    }
                }
                try {
                    channel.getExitStatus();
                } catch (Exception ignored) {
                    // getExitStatus can throw before the channel is connected; keep polling.
                }
                try {
                    Thread.sleep(20L);
                } catch (InterruptedException e) {
                    interruptedWhileExecuting = true;
                    if (!finishSoonAfterInterrupt(channel, Math.min(1000L, timeoutMs))) {
                        throw e;
                    }
                }
            }

            boolean interruptedWhileDraining = interruptedWhileExecuting;
            try {
                readersDone.await(2L, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                interruptedWhileDraining = true;
            }
            if (!interruptedWhileDraining) {
                interruptedWhileDraining = joinReader(stdoutReader) || interruptedWhileDraining;
                interruptedWhileDraining = joinReader(stderrReader) || interruptedWhileDraining;
            }

            if (listener != null) {
                String combined = combineRaw(output.toString(), error.toString());
                if (combined.length() > 0) {
                    listener.onOutput(combined);
                }
            }
            int exitStatus = channel.getExitStatus();
            String combined = combine(output.toString(), error.toString(), "exit status: " + exitStatus);
            success = true;
            if (exitStatus == 0) {
                if (interruptedWhileDraining) {
                    Thread.currentThread().interrupt();
                }
                return combined;
            }
            if (interruptedWhileDraining) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("exit status " + exitStatus + "\n" + combined);
        } catch (Exception e) {
            if (session != null) {
                connectionPool.discard(session, config);
                session = null;
            }
            throw e;
        } finally {
            if (channel != null) {
                try {
                    channel.disconnect();
                } catch (Exception ignored) {
                }
            }
            if (session != null) {
                if (success) {
                    connectionPool.release(session, config);
                } else {
                    connectionPool.discard(session, config);
                }
            }
        }
    }

    private Thread startDrainThread(String name, InputStream stream, StringBuilder target, CountDownLatch done) {
        Thread thread = new Thread(() -> {
            byte[] buffer = new byte[4096];
            try {
                int read;
                while ((read = stream.read(buffer)) != -1) {
                    if (read > 0) {
                        synchronized (target) {
                            target.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
                        }
                    }
                }
            } catch (Exception ignored) {
                // Best-effort: a closed/errored channel ends the read loop; output so far
                // is kept and the exit status check below decides success/failure.
            } finally {
                done.countDown();
            }
        }, name);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private boolean joinReader(Thread reader) {
        try {
            reader.join(500L);
            return false;
        } catch (InterruptedException e) {
            return true;
        }
    }

    private boolean finishSoonAfterInterrupt(ChannelExec channel, long waitMs) {
        long deadline = System.currentTimeMillis() + Math.max(0L, waitMs);
        while (!channel.isClosed() && System.currentTimeMillis() < deadline) {
            try {
                channel.getExitStatus();
            } catch (Exception ignored) {
                // Same poll pattern as above: ignore pre-connection channel state.
            }
            try {
                Thread.sleep(20L);
            } catch (InterruptedException ignored) {
                return channel.isClosed();
            }
        }
        return channel.isClosed();
    }

    static String combine(String output, String error, String fallback) {
        StringBuilder builder = new StringBuilder();
        if (output != null && output.trim().length() > 0) {
            builder.append(output.trim());
        }
        if (error != null && error.trim().length() > 0) {
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(error.trim());
        }
        return builder.length() == 0 ? fallback : builder.toString();
    }

    static String combineRaw(String output, String error) {
        StringBuilder builder = new StringBuilder();
        if (output != null && output.length() > 0) {
            builder.append(output);
        }
        if (error != null && error.length() > 0) {
            if (builder.length() > 0 && builder.charAt(builder.length() - 1) != '\n') {
                builder.append('\n');
            }
            builder.append(error);
        }
        return builder.toString();
    }
}
