package cn.lineai.ai;

/**
 * Provides skill-related prompts.
 * Implemented by the data layer so the AI layer never depends on it directly.
 */
public interface SkillPromptProvider {
    String buildExtensionPrompt(String skillName, String skillContent, String workDirectory);
}
