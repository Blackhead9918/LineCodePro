package cn.lineai.data.repository;

/**
 * 学习上下文仓库接口，定义 LearningContextRepository 的数据存取契约。
 * 业务编排方法（buildLearningContext、getOverview）已移至 LearningContextService。
 */
public interface LearningContextStore {

    /**
     * 手动保存一条记忆。
     */
    void saveMemory(String id, String scope, String projectId, String content);

    /**
     * 自动抽取并保存记忆，置信度与现有条目合并。
     */
    void saveExtractedMemory(String scope, String projectId, String content, double confidence);

    /**
     * 删除一条记忆。
     */
    void deleteMemory(String id);

    /**
     * 批量删除记忆。
     */
    void deleteMemories(java.util.List<String> ids);

    /**
     * 将指定会话索引到对话索引中。
     */
    void indexConversation(String projectId, ConversationRecord conversation);

    /**
     * 检索长期记忆（供 memory_recall 工具使用）。
     * <p>
     * 仅返回与 {@code query} 关键词真正匹配的记忆（按相关性排序，无匹配时返回空列表），
     * 不会为了“凑数”返回不相关的记忆，避免给模型噪声。命中项会更新使用统计。
     *
     * @param projectId 当前工作区路径，用于过滤 project / environment 作用域的记忆
     * @param query     检索关键词
     * @param scope     all | user | project | environment，未知值按 all 处理
     * @param limit     最多返回条数（调用方负责限制上限）
     */
    java.util.List<MemoryRanker.Candidate> searchMemories(String projectId, String query, String scope, int limit);
}
