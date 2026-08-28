package cn.lineai.mvp;

import cn.lineai.ai.ModelCancellationToken;
import cn.lineai.model.ModelConfig;
import cn.lineai.model.tool.ToolCall;
import cn.lineai.model.tool.ToolResult;
import cn.lineai.mvp.agent.PendingToolExecution;
import java.util.ArrayList;
import java.util.List;
import org.junit.Assert;
import org.junit.Test;

public final class ToolConfirmationControllerTest {

    private static final class FakeCallback implements ToolConfirmationController.Callback {
        String convId = "conv_1";
        boolean activeGen = true;
        ToolResult lastResult;
        boolean persisted;
        boolean rendered;
        PendingToolExecution lastExecutedPending;

        @Override
        public boolean isActiveGeneration(int generationId) {
            return activeGen;
        }

        @Override
        public void addOrReplaceToolResult(ToolResult result) {
            this.lastResult = result;
        }

        @Override
        public void persistCurrentConversation() {
            this.persisted = true;
        }

        @Override
        public void render() {
            this.rendered = true;
        }

        @Override
        public void continueToolExecution(
                int generationId,
                ModelConfig selectedModel,
                List<ToolCall> remainingCalls,
                int usedToolCallCount,
                String homePath,
                ModelCancellationToken cancellationToken
        ) {
        }

        @Override
        public void executeAcceptedPendingTool(PendingToolExecution pending) {
            this.lastExecutedPending = pending;
        }

        @Override
        public String currentConversationId() {
            return convId;
        }
    }

    @Test
    public void gitCommitAndGitPushCanBeAutoConfirmedDirectly() {
        FakeCallback callback = new FakeCallback();
        ToolConfirmationController controller = new ToolConfirmationController(callback);

        ToolCall commit1 = new ToolCall("c1", "git_commit", "{\"message\":\"fix\"}");
        ToolCall commit2 = new ToolCall("c2", "git_commit", "{\"message\":\"feat\"}");
        ToolCall push1 = new ToolCall("p1", "git_push", "{}");
        ToolCall push2 = new ToolCall("p2", "git_push", "{}");

        Assert.assertFalse(controller.isSessionAutoConfirmed(commit1));
        Assert.assertFalse(controller.isSessionAutoConfirmed(push1));

        controller.rememberSessionAutoConfirmation(commit1);
        Assert.assertTrue(controller.isSessionAutoConfirmed(commit2));
        Assert.assertFalse(controller.isSessionAutoConfirmed(push1));

        controller.rememberSessionAutoConfirmation(push1);
        Assert.assertTrue(controller.isSessionAutoConfirmed(push2));
    }

    @Test
    public void shellExecuteAutoRunInheritsToGitTools() {
        FakeCallback callback = new FakeCallback();
        ToolConfirmationController controller = new ToolConfirmationController(callback);

        ToolCall shellCall = new ToolCall("s1", "shell_execute", "{\"command\":\"ls\"}");
        ToolCall commitCall = new ToolCall("c1", "git_commit", "{\"message\":\"update\"}");
        ToolCall pushCall = new ToolCall("p1", "git_push", "{}");
        ToolCall diffCall = new ToolCall("d1", "git_diff", "{}");
        ToolCall deleteCall = new ToolCall("del1", "file_delete", "{}");

        controller.rememberSessionAutoConfirmation(shellCall);

        Assert.assertTrue(controller.isSessionAutoConfirmed(commitCall));
        Assert.assertTrue(controller.isSessionAutoConfirmed(pushCall));
        Assert.assertTrue(controller.isSessionAutoConfirmed(diffCall));
        Assert.assertFalse(controller.isSessionAutoConfirmed(deleteCall));
    }

    @Test
    public void sessionAutoReviewRemembersGitToolInPendingToolExecution() {
        FakeCallback callback = new FakeCallback();
        ToolConfirmationController controller = new ToolConfirmationController(callback);

        ToolCall commitCall = new ToolCall("c1", "git_commit", "{\"message\":\"auto test\"}");
        PendingToolExecution pending = new PendingToolExecution(
                1,
                null,
                commitCall,
                new ArrayList<>(),
                0,
                "/home",
                null
        );
        controller.setPendingToolExecution(pending);

        controller.handleToolReview("session_auto");

        Assert.assertNotNull(callback.lastExecutedPending);
        Assert.assertEquals("c1", callback.lastExecutedPending.getToolCall().getId());
        Assert.assertTrue(controller.isSessionAutoConfirmed(new ToolCall("c2", "git_commit", "{\"message\":\"next\"}")));
    }

    @Test
    public void conversationSwitchClearsAutoConfirmation() {
        FakeCallback callback = new FakeCallback();
        ToolConfirmationController controller = new ToolConfirmationController(callback);

        ToolCall pushCall = new ToolCall("p1", "git_push", "{}");
        controller.rememberSessionAutoConfirmation(pushCall);
        Assert.assertTrue(controller.isSessionAutoConfirmed(pushCall));

        callback.convId = "conv_2";
        Assert.assertFalse(controller.isSessionAutoConfirmed(pushCall));
    }
}
