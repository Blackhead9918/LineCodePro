package cn.lineai.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

public final class ModelConfigTest {

    @Test
    public void builderAndDefaultValues() {
        ModelConfig config = ModelConfig.builder(
                "config_1",
                "Gemini Pro",
                ModelProtocolType.OPENAI_COMPATIBLE,
                "OpenAI",
                "https://api.openai.com/v1",
                "sk-test",
                "gpt-4o"
        )
                .toolCallLimit(100)
                .contextSize(128000)
                .build();

        assertEquals("config_1", config.getId());
        assertEquals("Gemini Pro", config.getName());
        assertEquals(ModelProtocolType.OPENAI_COMPATIBLE, config.getProtocolType());
        assertEquals("https://api.openai.com/v1", config.getBaseUrl());
        assertEquals("sk-test", config.getApiKey());
        assertEquals("gpt-4o", config.getModelId());
        assertEquals(100, config.getToolCallLimit());
        assertEquals(128000, config.getContextSize());
    }

    @Test
    public void jsonSerializationAndDeserialization() throws Exception {
        ModelConfig config = ModelConfig.builder(
                "cfg_json",
                "Claude Sonnet",
                ModelProtocolType.ANTHROPIC_MESSAGES,
                "Anthropic",
                "https://api.anthropic.com",
                "sk-ant-test",
                "claude-3-5-sonnet"
        )
                .toolCallLimit(50)
                .contextSize(200000)
                .build();

        JSONObject json = config.toJson();
        assertNotNull(json);

        ModelConfig parsed = ModelConfig.fromJson(json);
        assertNotNull(parsed);
        assertEquals(config.getId(), parsed.getId());
        assertEquals(config.getName(), parsed.getName());
        assertEquals(config.getProtocolType(), parsed.getProtocolType());
        assertEquals(config.getBaseUrl(), parsed.getBaseUrl());
        assertEquals(config.getApiKey(), parsed.getApiKey());
        assertEquals(config.getModelId(), parsed.getModelId());
        assertEquals(config.getToolCallLimit(), parsed.getToolCallLimit());
        assertEquals(config.getContextSize(), parsed.getContextSize());
    }

    @Test
    public void toolCallLimitNormalization() {
        assertEquals(ModelConfig.UNLIMITED_TOOL_CALLS, ModelConfig.normalizeToolCallLimit(ModelConfig.UNLIMITED_TOOL_CALLS));
        assertEquals(0, ModelConfig.normalizeToolCallLimit(-10));
        assertEquals(50, ModelConfig.normalizeToolCallLimit(50));
    }
}
