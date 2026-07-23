package com.differentrockets.util;

import java.util.ArrayList;
import java.util.List;

/**
 * Minimal JSON: a pretty-printing writer and a small recursive-descent parser.
 * Avoids the gdx-json dependency quirks on Android; only what the save system needs.
 */
public final class Json {

    // ---------------- Writer ----------------
    public static class Writer {
        private final StringBuilder sb = new StringBuilder();
        private int indent = 0;
        private boolean needComma = false;
        private boolean pretty = true;

        public Writer() { this(true); }
        public Writer(boolean pretty) { this.pretty = pretty; }

        private void newline() {
            if (!pretty) return;
            sb.append('\n');
            for (int i = 0; i < indent; i++) sb.append("  ");
        }

        private void beforeValue() {
            if (needComma) sb.append(',');
            newline();
            needComma = false;
        }

        public Writer obj() { beforeValue(); sb.append('{'); indent++; needComma = false; return this; }
        public Writer endObj() { indent--; boolean had = needComma; needComma = false; if (had) newline(); sb.append('}'); needComma = true; return this; }
        public Writer arr() { beforeValue(); sb.append('['); indent++; needComma = false; return this; }
        public Writer endArr() { indent--; boolean had = needComma; needComma = false; if (had) newline(); sb.append(']'); needComma = true; return this; }

        public Writer key(String k) {
            if (needComma) sb.append(',');
            newline();
            quote(k);
            sb.append(pretty ? ": " : ":");
            needComma = false;
            return this;
        }

        public Writer val(String v) { beforeValue(); if (v == null) sb.append("null"); else quote(v); needComma = true; return this; }
        public Writer val(double v) { beforeValue(); if (Double.isNaN(v) || Double.isInfinite(v)) sb.append('0'); else sb.append(Double.toString(v)); needComma = true; return this; }
        public Writer val(long v) { beforeValue(); sb.append(v); needComma = true; return this; }
        public Writer val(int v) { beforeValue(); sb.append(v); needComma = true; return this; }
        public Writer val(boolean v) { beforeValue(); sb.append(v); needComma = true; return this; }

        public Writer set(String k, String v) { key(k); val(v); return this; }
        public Writer set(String k, double v) { key(k); val(v); return this; }
        public Writer set(String k, int v) { key(k); val(v); return this; }
        public Writer set(String k, long v) { key(k); val(v); return this; }
        public Writer set(String k, boolean v) { key(k); val(v); return this; }

        private void quote(String s) {
            sb.append('"');
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '"': sb.append("\\\""); break;
                    case '\\': sb.append("\\\\"); break;
                    case '\n': sb.append("\\n"); break;
                    case '\r': sb.append("\\r"); break;
                    case '\t': sb.append("\\t"); break;
                    default:
                        if (c < 32) sb.append(String.format("\\u%04x", (int) c));
                        else sb.append(c);
                }
            }
            sb.append('"');
        }

        @Override public String toString() { return sb.toString(); }
    }

    // ---------------- Value ----------------
    public static final class Value {
        public Object o; // String, Double, Boolean, List<Value>, JObj, or null

        public Value(Object o) { this.o = o; }

        public boolean isObj() { return o instanceof JObj; }
        public JObj asObj() { return (JObj) o; }
        public boolean isArr() { return o instanceof List; }
        @SuppressWarnings("unchecked")
        public List<Value> asArr() { return (List<Value>) o; }
        public String asStr() { return o == null ? null : (o instanceof String ? (String) o : String.valueOf(o)); }
        public double asNum(double def) {
            if (o instanceof Number) return ((Number) o).doubleValue();
            if (o instanceof String) { try { return Double.parseDouble((String) o); } catch (Exception e) { return def; } }
            return def;
        }
        public int asInt(int def) { return (int) asNum(def); }
        public boolean asBool(boolean def) {
            if (o instanceof Boolean) return (Boolean) o;
            if (o != null) return Boolean.parseBoolean(String.valueOf(o));
            return def;
        }
    }

    public static final class JObj {
        public final List<String> keys = new ArrayList<>();
        public final List<Value> vals = new ArrayList<>();

        public void put(String k, Value v) { keys.add(k); vals.add(v); }

        public Value get(String k) {
            int i = keys.indexOf(k);
            return i >= 0 ? vals.get(i) : null;
        }
        public boolean has(String k) { return keys.contains(k); }
        public String getStr(String k, String def) { Value v = get(k); return v == null ? def : v.asStr(); }
        public double getNum(String k, double def) { Value v = get(k); return v == null ? def : v.asNum(def); }
        public int getInt(String k, int def) { Value v = get(k); return v == null ? def : v.asInt(def); }
        public boolean getBool(String k, boolean def) { Value v = get(k); return v == null ? def : v.asBool(def); }
        public JObj getObj(String k) { Value v = get(k); return v != null && v.isObj() ? v.asObj() : null; }
        public List<Value> getArr(String k) { Value v = get(k); return v != null && v.isArr() ? v.asArr() : null; }
    }

    public static JObj parse(String text) {
        Parser p = new Parser(text);
        Value v = p.parseValue();
        p.ws();
        if (p.pos != p.len()) throw new RuntimeException("JSON trailing content at " + p.pos);
        if (!v.isObj()) throw new RuntimeException("JSON root must be object");
        return v.asObj();
    }

    private static final class Parser {
        final String s; int pos = 0;
        Parser(String s) { this.s = s; }
        int len() { return s.length(); }
        void ws() { while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++; }
        char peek() { ws(); return pos < s.length() ? s.charAt(pos) : '\0'; }
        char next() { return s.charAt(pos++); }
        void expect(char c) { ws(); if (next() != c) throw new RuntimeException("JSON expected '" + c + "' at " + pos); }

        Value parseValue() {
            char c = peek();
            switch (c) {
                case '{': return parseObj();
                case '[': return parseArr();
                case '"': return new Value(parseStr());
                case 't': case 'f': return new Value(parseBool());
                case 'n': pos += 4; return new Value(null);
                default: return new Value(parseNum());
            }
        }

        Value parseObj() {
            expect('{');
            JObj o = new JObj();
            if (peek() == '}') { next(); return new Value(o); }
            while (true) {
                String k = parseStr();
                expect(':');
                Value v = parseValue();
                o.put(k, v);
                char c = peek();
                if (c == ',') { next(); continue; }
                if (c == '}') { next(); break; }
                throw new RuntimeException("JSON expected , or } at " + pos);
            }
            return new Value(o);
        }

        Value parseArr() {
            expect('[');
            List<Value> a = new ArrayList<>();
            if (peek() == ']') { next(); return new Value(a); }
            while (true) {
                a.add(parseValue());
                char c = peek();
                if (c == ',') { next(); continue; }
                if (c == ']') { next(); break; }
                throw new RuntimeException("JSON expected , or ] at " + pos);
            }
            return new Value(a);
        }

        String parseStr() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = next();
                if (c == '"') break;
                if (c == '\\') {
                    char e = next();
                    switch (e) {
                        case 'n': sb.append('\n'); break;
                        case 'r': sb.append('\r'); break;
                        case 't': sb.append('\t'); break;
                        case '"': sb.append('"'); break;
                        case '\\': sb.append('\\'); break;
                        case '/': sb.append('/'); break;
                        case 'u': sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16)); pos += 4; break;
                        default: sb.append(e);
                    }
                } else sb.append(c);
            }
            return sb.toString();
        }

        Object parseBool() {
            if (s.startsWith("true", pos)) { pos += 4; return Boolean.TRUE; }
            pos += 5;
            return Boolean.FALSE;
        }

        Double parseNum() {
            ws();
            int start = pos;
            if (pos < s.length() && (s.charAt(pos) == '-' || s.charAt(pos) == '+')) pos++;
            while (pos < s.length() && "0123456789.eE+-".indexOf(s.charAt(pos)) >= 0) pos++;
            return Double.parseDouble(s.substring(start, pos));
        }
    }
}
