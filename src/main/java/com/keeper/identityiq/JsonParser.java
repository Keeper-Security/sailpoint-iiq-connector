package com.keeper.identityiq;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import sailpoint.connector.ConnectorException;

/**
 * Small JSON parser so the connector does not require an additional
 * Jackson or Gson dependency.
 */
final class JsonParser {
    private final String input;
    private int position;

    private JsonParser(String input) {
        this.input = input;
        this.position = 0;
    }

    static Object parse(String input) throws ConnectorException {
        if (input == null) {
            throw new ConnectorException("Cannot parse an empty JSON response.");
        }
        JsonParser parser = new JsonParser(input);
        Object value = parser.parseValue();
        parser.skipWhitespace();
        if (parser.position != parser.input.length()) {
            throw new ConnectorException("Unexpected characters after JSON response.");
        }
        return value;
    }

    private Object parseValue() throws ConnectorException {
        skipWhitespace();
        if (position >= input.length()) {
            throw error("Unexpected end of JSON input.");
        }
        char c = input.charAt(position);
        if (c == '{') return parseObject();
        if (c == '[') return parseArray();
        if (c == '"') return parseString();
        if (c == 't') { expectLiteral("true"); return Boolean.TRUE; }
        if (c == 'f') { expectLiteral("false"); return Boolean.FALSE; }
        if (c == 'n') { expectLiteral("null"); return null; }
        if (c == '-' || (c >= '0' && c <= '9')) return parseNumber();
        throw error("Unexpected character in JSON input.");
    }

    private Map<String, Object> parseObject() throws ConnectorException {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        expect('{');
        skipWhitespace();
        if (consume('}')) return map;
        while (true) {
            skipWhitespace();
            if (position >= input.length() || input.charAt(position) != '"') {
                throw error("Expected a JSON object key.");
            }
            String key = parseString();
            skipWhitespace();
            expect(':');
            map.put(key, parseValue());
            skipWhitespace();
            if (consume('}')) return map;
            expect(',');
        }
    }

    private List<Object> parseArray() throws ConnectorException {
        List<Object> list = new ArrayList<Object>();
        expect('[');
        skipWhitespace();
        if (consume(']')) return list;
        while (true) {
            list.add(parseValue());
            skipWhitespace();
            if (consume(']')) return list;
            expect(',');
        }
    }

    private String parseString() throws ConnectorException {
        expect('"');
        StringBuilder result = new StringBuilder();
        while (position < input.length()) {
            char c = input.charAt(position++);
            if (c == '"') return result.toString();
            if (c == '\\') {
                if (position >= input.length()) throw error("Incomplete JSON escape sequence.");
                char escaped = input.charAt(position++);
                switch (escaped) {
                    case '"': result.append('"'); break;
                    case '\\': result.append('\\'); break;
                    case '/': result.append('/'); break;
                    case 'b': result.append('\b'); break;
                    case 'f': result.append('\f'); break;
                    case 'n': result.append('\n'); break;
                    case 'r': result.append('\r'); break;
                    case 't': result.append('\t'); break;
                    case 'u':
                        if (position + 4 > input.length()) throw error("Incomplete Unicode escape sequence.");
                        try {
                            result.append((char) Integer.parseInt(input.substring(position, position + 4), 16));
                        } catch (NumberFormatException e) {
                            throw error("Invalid Unicode escape sequence.");
                        }
                        position += 4;
                        break;
                    default:
                        throw error("Invalid JSON escape sequence.");
                }
            } else {
                if (c < 0x20) throw error("Unescaped control character in JSON string.");
                result.append(c);
            }
        }
        throw error("Unterminated JSON string.");
    }

    private Number parseNumber() throws ConnectorException {
        int start = position;
        if (consume('-') && position >= input.length()) throw error("Incomplete JSON number.");
        if (consume('0')) {
            if (position < input.length() && Character.isDigit(input.charAt(position))) {
                throw error("Invalid leading zero in JSON number.");
            }
        } else {
            if (position >= input.length() || !Character.isDigit(input.charAt(position))) {
                throw error("Invalid JSON number.");
            }
            while (position < input.length() && Character.isDigit(input.charAt(position))) position++;
        }
        boolean decimal = false;
        if (consume('.')) {
            decimal = true;
            if (position >= input.length() || !Character.isDigit(input.charAt(position))) {
                throw error("Invalid decimal number.");
            }
            while (position < input.length() && Character.isDigit(input.charAt(position))) position++;
        }
        if (position < input.length() && (input.charAt(position) == 'e' || input.charAt(position) == 'E')) {
            decimal = true;
            position++;
            if (position < input.length() && (input.charAt(position) == '+' || input.charAt(position) == '-')) {
                position++;
            }
            if (position >= input.length() || !Character.isDigit(input.charAt(position))) {
                throw error("Invalid exponent in JSON number.");
            }
            while (position < input.length() && Character.isDigit(input.charAt(position))) position++;
        }
        String number = input.substring(start, position);
        try {
            // Keep large integer IDs exact instead of converting to floating point.
            return decimal ? (Number) Double.valueOf(number) : (Number) Long.valueOf(number);
        } catch (NumberFormatException e) {
            throw error("JSON number is outside the supported range.");
        }
    }

    private void expectLiteral(String literal) throws ConnectorException {
        if (!input.startsWith(literal, position)) throw error("Invalid JSON literal.");
        position += literal.length();
    }

    private void expect(char expected) throws ConnectorException {
        skipWhitespace();
        if (position >= input.length() || input.charAt(position) != expected) {
            throw error("Expected '" + expected + "'.");
        }
        position++;
    }

    private boolean consume(char expected) {
        if (position < input.length() && input.charAt(position) == expected) {
            position++;
            return true;
        }
        return false;
    }

    private void skipWhitespace() {
        while (position < input.length() && Character.isWhitespace(input.charAt(position))) position++;
    }

    private ConnectorException error(String message) {
        return new ConnectorException(message + " Position: " + position);
    }
}
