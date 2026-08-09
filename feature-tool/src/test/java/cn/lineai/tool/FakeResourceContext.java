package cn.lineai.tool;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal {@link ToolContext.StringResolver} for unit tests. Resolves
 * {@code R.string.*} resource ids by parsing
 * {@code feature-tool/src/main/res/values/strings.xml} (the English source).
 *
 * <p>In Android unit tests the framework {@code Context.getString} is stubbed and
 * throws "not mocked", so the production code path consults this resolver instead
 * of the {@code Context} when one is provided via {@link ToolContext.Builder}.
 */
public final class FakeResourceContext implements ToolContext.StringResolver {

    private final Map<Integer, String> table = new HashMap<>();

    public FakeResourceContext() {
        Map<String, String> nameToText = parseStringsXml();
        Field[] fields = R.string.class.getDeclaredFields();
        for (Field field : fields) {
            if (field.getType() != int.class) {
                continue;
            }
            try {
                int resId = field.getInt(null);
                String text = nameToText.get(field.getName());
                if (text != null) {
                    table.put(resId, text);
                }
            } catch (IllegalAccessException ignored) {
                // skip inaccessible fields
            }
        }
    }

    @Override
    public String getString(int resId) {
        String text = table.get(resId);
        return text != null ? text : "";
    }

    @Override
    public String getString(int resId, Object... formatArgs) {
        String text = table.get(resId);
        if (text == null) {
            return "";
        }
        return String.format(Locale.ROOT, text, formatArgs);
    }

    private static Map<String, String> parseStringsXml() {
        Map<String, String> result = new HashMap<>();
        File file = new File("feature-tool/src/main/res/values/strings.xml");
        if (!file.exists()) {
            // Fallback when run from the module root.
            file = new File("src/main/res/values/strings.xml");
        }
        if (!file.exists()) {
            return result;
        }
        try (InputStream in = new FileInputStream(file)) {
            byte[] bytes = new byte[(int) file.length()];
            int offset = 0;
            while (offset < bytes.length) {
                int read = in.read(bytes, offset, bytes.length - offset);
                if (read < 0) {
                    break;
                }
                offset += read;
            }
            String xml = new String(bytes, StandardCharsets.UTF_8);
            Pattern pattern = Pattern.compile(
                    "<string\\s+name=\"([^\"]+)\"\\s*>([\\s\\S]*?)</string>",
                    Pattern.DOTALL);
            Matcher matcher = pattern.matcher(xml);
            while (matcher.find()) {
                String name = matcher.group(1);
                String value = matcher.group(2);
                result.put(name, unescape(value).trim());
            }
        } catch (Exception ignored) {
            // best-effort: return whatever was parsed so far
        }
        return result;
    }

    private static String unescape(String value) {
        return value
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&nbsp;", " ");
    }
}
