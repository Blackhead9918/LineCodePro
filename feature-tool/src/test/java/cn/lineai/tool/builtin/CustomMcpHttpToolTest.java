package cn.lineai.tool.builtin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import cn.lineai.model.ExtensionMcpConfig;
import cn.lineai.model.McpToolSummary;
import java.util.Collections;
import org.json.JSONObject;
import org.junit.Test;

public final class CustomMcpHttpToolTest {

    @Test
    public void normalizesToolParametersSchema() throws Exception {
        ExtensionMcpConfig mcp = new ExtensionMcpConfig(
                "mcp1", true, "Weather Service", "https://example.com/mcp",
                Collections.emptyList(), Collections.emptyList(), 0, 0);
        
        String inputSchemaJson = "{\"properties\":{\"city\":{\"type\":\"string\"}},\"required\":[\"city\"]}";
        McpToolSummary toolSummary = new McpToolSummary("get_weather", true, "Get weather by city", inputSchemaJson);
        
        CustomMcpHttpTool tool = new CustomMcpHttpTool("weather_get_weather", mcp, toolSummary);
        JSONObject parameters = tool.getParameters();

        assertEquals("object", parameters.optString("type"));
        assertNotNull(parameters.optJSONObject("properties"));
        assertTrue(parameters.optJSONObject("properties").has("city"));
        assertNotNull(parameters.optJSONArray("required"));
        assertEquals("city", parameters.optJSONArray("required").getString(0));
    }

    @Test
    public void fallbackToDefaultSchemaWhenEmpty() throws Exception {
        ExtensionMcpConfig mcp = new ExtensionMcpConfig(
                "mcp1", true, "Test MCP", "https://example.com/mcp",
                Collections.emptyList(), Collections.emptyList(), 0, 0);
        McpToolSummary toolSummary = new McpToolSummary("no_schema_tool", true, "No schema tool", "");

        CustomMcpHttpTool tool = new CustomMcpHttpTool("test_no_schema_tool", mcp, toolSummary);
        JSONObject parameters = tool.getParameters();

        assertEquals("object", parameters.optString("type"));
        assertNotNull(parameters.optJSONObject("properties"));
        assertTrue(parameters.optBoolean("additionalProperties"));
    }
}
