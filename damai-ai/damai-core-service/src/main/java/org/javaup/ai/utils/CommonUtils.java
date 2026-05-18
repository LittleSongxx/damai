package org.javaup.ai.utils;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CommonUtils {

    private CommonUtils() {
    }

    public static boolean containsAny(String value, String... keywords) {
        for (String keyword : keywords) {
            if (value.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    public static String extract(String prompt, Pattern pattern) {
        Matcher matcher = pattern.matcher(prompt);
        return matcher.find() ? matcher.group(1) : null;
    }

    public static Map<String, Object> mapOf(Object... values) {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            if (values[index + 1] != null) {
                map.put(String.valueOf(values[index]), values[index + 1]);
            }
        }
        return map;
    }

    public static String formatBytes(double bytes) {
        if (bytes < 1024) {
            return String.format("%.0f B", bytes);
        }
        if (bytes < 1024 * 1024) {
            return String.format("%.2f KB", bytes / 1024);
        }
        if (bytes < 1024 * 1024 * 1024) {
            return String.format("%.2f MB", bytes / (1024 * 1024));
        }
        return String.format("%.2f GB", bytes / (1024 * 1024 * 1024));
    }
}
