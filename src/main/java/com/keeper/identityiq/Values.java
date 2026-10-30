package com.keeper.identityiq;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import sailpoint.connector.ConnectorException;

/** Null-safe value, list and Commander argument helpers shared by the connector classes. */
final class Values {

    private Values() {
    }

    /** Extracts a stable id from a scalar or an object such as {team_uid, team_name}. */
    static String stableId(Object value, String idKey) {
        if (value instanceof Map) {
            return trimToNull(str(((Map<?, ?>) value).get(idKey)));
        }
        return trimToNull(str(value));
    }

    static List<String> stableIdList(Object value, String idKey) {
        List<String> out = new ArrayList<String>();
        if (value instanceof List) {
            for (Object entry : (List<?>) value) {
                String id = stableId(entry, idKey);
                if (id != null) out.add(id);
            }
        }
        return out;
    }

    /** Non-empty trimmed strings from a scalar or a list. */
    static List<String> toStringList(Object value) {
        List<String> out = new ArrayList<String>();
        if (value instanceof List) {
            for (Object item : (List<?>) value) {
                String s = trimToNull(str(item));
                if (s != null) out.add(s);
            }
        } else {
            String s = trimToNull(str(value));
            if (s != null) out.add(s);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    static List<String> asStringList(Object value) {
        return value instanceof List ? (List<String>) value : null;
    }

    static String firstValue(Object value) {
        List<String> values = toStringList(value);
        return values.isEmpty() ? null : values.get(0);
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> childrenOf(Object node) {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        if (node instanceof Map && ((Map<?, ?>) node).get("children") instanceof List) {
            for (Object child : (List<?>) ((Map<?, ?>) node).get("children")) {
                if (child instanceof Map) out.add((Map<String, Object>) child);
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> shareEntries(Map<String, Object> node, String key) {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        Object permissions = node.get("share_permissions");
        if (permissions instanceof Map && ((Map<?, ?>) permissions).get(key) instanceof List) {
            for (Object entry : (List<?>) ((Map<?, ?>) permissions).get(key)) {
                if (entry instanceof Map) out.add((Map<String, Object>) entry);
            }
        }
        return out;
    }

    static void addToMap(Map<String, List<String>> map, String key, String value) {
        if (key == null || value == null) return;
        List<String> list = map.get(key);
        if (list == null) {
            list = new ArrayList<String>();
            map.put(key, list);
        }
        if (!list.contains(value)) list.add(value);
    }

    static List<String> listOrEmpty(List<String> list) {
        return list == null ? new ArrayList<String>() : list;
    }

    static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    static String nvl(String value) {
        return value == null ? "" : value;
    }

    static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    static String trimToNull(String value) {
        return isBlank(value) ? null : value.trim();
    }

    static String firstNonBlank(String... values) {
        for (String value : values) {
            if (!isBlank(value)) return value;
        }
        return "";
    }

    /**
     * Escapes a value wrapped in "..." in a Commander command. Backslashes
     * are escaped before quotes; NUL/CR/LF are rejected to prevent command
     * injection through Commander's line-based parser.
     */
    static String escapeArg(String value) throws ConnectorException {
        if (value.indexOf('\0') >= 0 || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new ConnectorException("Refusing to build Commander command: value contains a "
                    + "control character (NUL, CR, or LF)");
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** escapeArg for values already validated elsewhere (the user's own email). */
    static String escapeArgSafe(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    static String escapeJson(String value) {
        StringBuilder sb = new StringBuilder();
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.toString();
    }
}
