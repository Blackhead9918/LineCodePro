package cn.lineai.data.importer;

import cn.lineai.model.ModelConfig;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

class ArchiveSecretRedactor {
    private static final String REDACTED = "";

    private static final ArchiveSecretRedactor DEFAULT = new ArchiveSecretRedactor();

    private final Map<String, String[]> sensitiveFields;
    private final Set<String> sensitiveNameKeywords;

    ArchiveSecretRedactor() {
        sensitiveFields = new HashMap<>();
        sensitiveNameKeywords = new HashSet<>(Arrays.asList(
                "apikey", "api_key", "api-key",
                "authorization",
                "password", "passwd", "passphrase",
                "privatekey", "private_key", "private-key",
                "secret",
                "token",
                "cookie"
        ));
        registerDefaultSensitiveFields();
    }

    private void registerDefaultSensitiveFields() {
        registerSensitiveFields("@lineai_ssh_config", new String[]{"password", "privateKey", "passphrase"});
        registerSensitiveFields("@lineai_web_search_config", new String[]{"apiKey"});
    }

    void registerSensitiveFields(String settingsKey, String[] fields) {
        if (settingsKey == null || fields == null) {
            return;
        }
        sensitiveFields.put(settingsKey, fields);
    }

    void registerSensitiveNameKeyword(String keyword) {
        if (keyword != null && keyword.length() > 0) {
            sensitiveNameKeywords.add(keyword.toLowerCase(Locale.ROOT));
        }
    }

    boolean isSensitiveName(String name) {
        String value = name == null ? "" : name.toLowerCase(Locale.ROOT);
        for (String keyword : sensitiveNameKeywords) {
            if (value.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    ImportedLineCodeData performRedactData(ImportedLineCodeData data) {
        ImportedLineCodeData safeData = data == null
                ? new ImportedLineCodeData(null, "", null, "", null)
                : data;
        ArrayList<ModelConfig> models = new ArrayList<>();
        for (ModelConfig model : safeData.getModels()) {
            models.add(redactModel(model));
        }
        return new ImportedLineCodeData(
                models,
                safeData.getSelectedModelId(),
                safeData.getConversations(),
                safeData.getCurrentConversationId(),
                performRedactSettings(safeData.getSettings())
        );
    }

    JSONObject performRedactDatabaseSnapshot(JSONObject snapshot) {
        if (snapshot == null) {
            return null;
        }
        JSONObject redacted;
        try {
            redacted = new JSONObject(snapshot.toString());
        } catch (Exception ignored) {
            // Practically unreachable: JSONObject.toString() output always reparses.
            // Returning the original avoids data loss in that pathological case.
            return snapshot;
        }
        JSONObject tables = redacted.optJSONObject("tables");
        if (tables == null) {
            return redacted;
        }
        redactSettingsTable(tables.optJSONObject("settings"));
        redactModelTable(tables.optJSONObject("model_configs"));
        redactMcpTable(tables.optJSONObject("extension_mcps"));
        return redacted;
    }

    Map<String, String> performRedactSettings(Map<String, String> settings) {
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        if (settings == null) {
            return values;
        }
        for (Map.Entry<String, String> entry : settings.entrySet()) {
            String key = entry.getKey();
            if (key == null || key.length() == 0) {
                continue;
            }
            values.put(key, redactSettingValue(key, entry.getValue()));
        }
        return values;
    }

    private ModelConfig redactModel(ModelConfig model) {
        if (model == null) {
            return ModelConfig.builder("", "", null, "", "", "", "").build();
        }
        return new ModelConfig(
                model.getId(),
                model.getName(),
                model.getProtocolType(),
                model.getProviderLabel(),
                model.getBaseUrl(),
                REDACTED,
                model.getModelId(),
                model.getToolCallLimit(),
                model.isCompressionModelEnabled(),
                model.isCompressionModelAuto(),
                model.getCompressionModelId(),
                model.getContextSize()
        );
    }

    private void redactSettingsTable(JSONObject table) {
        JSONArray rows = rows(table);
        if (rows == null) {
            return;
        }
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null) {
                continue;
            }
            String key = cellString(row, "key");
            String value = cellString(row, "value");
            setStringCell(row, "value", redactSettingValue(key, value));
        }
    }

    private void redactModelTable(JSONObject table) {
        JSONArray rows = rows(table);
        if (rows == null) {
            return;
        }
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null) {
                continue;
            }
            setStringCell(row, "api_key", REDACTED);
            setStringCell(row, "raw_json", redactModelJson(cellString(row, "raw_json")));
        }
    }

    private void redactMcpTable(JSONObject table) {
        JSONArray rows = rows(table);
        if (rows == null) {
            return;
        }
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null) {
                continue;
            }
            setStringCell(row, "request_headers_json", redactHeaderArray(cellString(row, "request_headers_json")));
            setStringCell(row, "raw_json", redactRecursiveJson(cellString(row, "raw_json")));
        }
    }

    private String redactSettingValue(String key, String value) {
        String safeKey = key == null ? "" : key;
        String[] fields = sensitiveFields.get(safeKey);
        if (fields != null) {
            return redactJsonFields(value, fields);
        }
        if (isSensitiveName(safeKey)) {
            return REDACTED;
        }
        return value == null ? "" : value;
    }

    private String redactModelJson(String raw) {
        return redactJsonFields(raw, "apiKey", "api_key");
    }

    private String redactHeaderArray(String raw) {
        if (raw == null || raw.trim().length() == 0) {
            return "[]";
        }
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                if (isSensitiveName(item.optString("name"))) {
                    item.put("value", REDACTED);
                }
            }
            return array.toString();
        } catch (Exception ignored) {
            // Fail-closed: unparseable header JSON is exported redacted, never with secrets.
            return REDACTED;
        }
    }

    private String redactRecursiveJson(String raw) {
        if (raw == null || raw.trim().length() == 0) {
            return "";
        }
        try {
            Object parsed = raw.trim().startsWith("[") ? new JSONArray(raw) : new JSONObject(raw);
            redactRecursive(parsed);
            return parsed.toString();
        } catch (Exception ignored) {
            // Unparseable raw JSON: redact only when the content itself looks sensitive;
            // the keyword check also covers "Authorization: Bearer ..." style values.
            return isSensitiveName(raw) ? REDACTED : raw;
        }
    }

    private String redactJsonFields(String raw, String... fields) {
        if (raw == null || raw.trim().length() == 0) {
            return "";
        }
        try {
            JSONObject object = new JSONObject(raw);
            for (String field : fields) {
                if (object.has(field)) {
                    object.put(field, REDACTED);
                }
            }
            return object.toString();
        } catch (Exception ignored) {
            // Fail-closed: unparseable value is exported empty rather than risking a secret.
            return REDACTED;
        }
    }

    private void redactRecursive(Object value) throws Exception {
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            JSONArray names = object.names();
            if (names == null) {
                return;
            }
            for (int i = 0; i < names.length(); i++) {
                String key = names.optString(i);
                Object child = object.opt(key);
                if (isSensitiveName(key)) {
                    object.put(key, REDACTED);
                } else {
                    redactRecursive(child);
                }
            }
        } else if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) {
                redactRecursive(array.opt(i));
            }
        }
    }

    private JSONArray rows(JSONObject table) {
        return table == null ? null : table.optJSONArray("rows");
    }

    private String cellString(JSONObject row, String column) {
        JSONObject cell = row.optJSONObject(column);
        return cell == null ? "" : cell.optString("value", "");
    }

    private void setStringCell(JSONObject row, String column, String value) {
        JSONObject cell = row.optJSONObject(column);
        if (cell == null || !"string".equals(cell.optString("type", "string"))) {
            return;
        }
        try {
            cell.put("value", value == null ? "" : value);
        } catch (Exception ignored) {
            // Defensive: cell values are Strings; put() cannot fail in practice.
        }
    }

    // ---- Static convenience API ----

    static void register(String settingsKey, String[] fields) {
        DEFAULT.registerSensitiveFields(settingsKey, fields);
    }

    static ImportedLineCodeData redactData(ImportedLineCodeData data) {
        return DEFAULT.performRedactData(data);
    }

    static JSONObject redactDatabaseSnapshot(JSONObject snapshot) {
        return DEFAULT.performRedactDatabaseSnapshot(snapshot);
    }

    static Map<String, String> redactSettings(Map<String, String> settings) {
        return DEFAULT.performRedactSettings(settings);
    }
}
