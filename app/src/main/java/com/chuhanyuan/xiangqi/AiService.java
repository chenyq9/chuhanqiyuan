package com.chuhanyuan.xiangqi;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** OpenAI兼容 /chat/completions 客户端，带 move_piece 工具。零第三方依赖。 */
public class AiService {

    public static class Move {
        public String from, to, say;
        public int fc, fr, tc, tr;
    }

    private final String base, key, model;
    private final List<String[]> history = new ArrayList<>();

    private static final String SYSTEM_PROMPT =
            "你正和朋友在一张真实的中式木棋盘上下中国象棋。你执红棋（帥仕相俥傌炮兵），坐在棋盘上方（行0一侧）；" +
            "朋友执黑棋（將士象車馬砲卒），坐在下方（行9一侧，玩家手边）。\n" +
            "这张棋盘没有任何规则：马不一定走日、象可以过河、将帅可以照面，任何棋子都能放到任何空格，" +
            "吃子就是走进对方的格子（被吃的子会被拿起来摆到桌边）。你可以遵守传统下法，也可以和朋友商量新玩法，像真人一样自由。\n" +
            "坐标：列a-i从左到右，行0-9从上到下，例如 e4=列e行4，你的帥初始在 e0。\n" +
            "想走棋时，调用工具 move_piece：from=起点坐标，to=终点坐标，say=走棋时想说的话（不想说话就传空字符串）。" +
            "只想聊天、观棋、评价局势时，不要调用工具，直接回复文字。说话像面对面下棋的朋友：简短、口语、有点性情。";

    private static final String TOOL_JSON =
            "{\"type\":\"function\",\"function\":{\"name\":\"move_piece\",\"description\":\"像真人一样拿起自己的一枚红棋放到另一格。终点为空就是普通落子，终点有朋友的黑棋就是吃子。要走棋时调用；只聊天则不调用。\",\"parameters\":{\"type\":\"object\",\"properties\":{\"from\":{\"type\":\"string\",\"description\":\"起点坐标，如 b2\"},\"to\":{\"type\":\"string\",\"description\":\"终点坐标，如 e4\"},\"say\":{\"type\":\"string\",\"description\":\"走棋时想说的一句话，可为空字符串\"}},\"required\":[\"from\",\"to\",\"say\"]}}}";

    public AiService(String base, String key, String model) {
        String b = base.trim();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        this.base = b;
        this.key = key.trim();
        this.model = model.trim();
    }

    public void resetHistory() {
        history.clear();
    }

    /** 记录一次"抓空"（起点无子），下轮提示模型。 */
    public void noteToolMiss(String fromTo) {
        history.add(new String[]{"user", "（系统提示：你上一手想走 " + fromTo + "，但起点上没有棋子，棋盘没有变化。请重新观察棋盘再走。）"});
    }

    /** 追加一条用户消息并请求模型，返回原始响应体。 */
    public String chat(String userMessage) throws Exception {
        history.add(new String[]{"user", userMessage});
        String body = buildBody();
        String resp = post(body);
        history.add(new String[]{"assistant", summarize(resp)});
        while (history.size() > 24) history.remove(0);
        return resp;
    }

    private String summarize(String resp) {
        Move m = firstMove(resp);
        String c = content(resp);
        if (m != null) {
            String s = "[走子 " + m.from + "→" + m.to + "]";
            String say = (m.say != null && !m.say.isEmpty()) ? m.say : (c == null ? "" : c);
            return s + (say == null ? "" : say);
        }
        return c == null ? "" : c;
    }

    private String buildBody() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"model\":\"").append(esc(model)).append("\",\"temperature\":0.8,");
        sb.append("\"tools\":[").append(TOOL_JSON).append("],\"tool_choice\":\"auto\",\"messages\":[");
        sb.append("{\"role\":\"system\",\"content\":\"").append(esc(SYSTEM_PROMPT)).append("\"}");
        for (String[] m : history) {
            sb.append(",{\"role\":\"").append(m[0]).append("\",\"content\":\"").append(esc(m[1])).append("\"}");
        }
        sb.append("]}");
        return sb.toString();
    }

    private String post(String body) throws Exception {
        // 兼容两种填法：base 填 https://host/v1 或完整 https://host/v1/chat/completions 均可
        String endpoint = base;
        if (endpoint == null || endpoint.trim().isEmpty()) {
            endpoint = "https://api.openai.com/v1";
        }
        if (!endpoint.endsWith("/chat/completions")) {
            if (!endpoint.endsWith("/")) endpoint = endpoint + "/";
            endpoint = endpoint + "chat/completions";
        }
        URL url = new URL(endpoint);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(20000);
        conn.setReadTimeout(90000);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Authorization", "Bearer " + key);
        OutputStream os = conn.getOutputStream();
        os.write(body.getBytes(StandardCharsets.UTF_8));
        os.close();
        int code = conn.getResponseCode();
        java.io.InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        if (is == null) is = conn.getInputStream();
        BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
        StringBuilder out = new StringBuilder();
        String ln;
        while ((ln = br.readLine()) != null) out.append(ln);
        br.close();
        String resp = out.toString();
        if (code >= 400) throw new Exception("HTTP " + code + " " + resp.substring(0, Math.min(resp.length(), 200)));
        return resp;
    }

    // ---------- 解析 ----------

    /** 提取 assistant 的 content 文本（可为null）。 */
    public static String content(String body) {
        int i = indexOfKey(body, "content");
        if (i < 0) return null;
        return readStringAt(body, i);
    }

    /** 取第一个 move_piece 工具调用；没有或坐标非法则返回null。 */
    public static Move firstMove(String body) {
        int tc = body.indexOf("\"tool_calls\"");
        if (tc < 0) return null;
        int fn = body.indexOf("\"function\"", tc);
        if (fn < 0) return null;
        int name = body.indexOf("\"name\"", fn);
        int args = body.indexOf("\"arguments\"", fn);
        if (args < 0) return null;
        if (name >= 0 && name < args) {
            String functionName = readStringAt(body, indexOfKey(body, "name"));
            if (!"move_piece".equals(functionName)) return null;
        }
        int colon = body.indexOf(':', args);
        int p = colon + 1;
        while (p < body.length() && Character.isWhitespace(body.charAt(p))) p++;
        String argsJson;
        if (p < body.length() && body.charAt(p) == '{') {
            int end = braceEnd(body, p);
            argsJson = body.substring(p, end + 1);
        } else if (p < body.length() && body.charAt(p) == '"') {
            int[] r = readJsonString(body, p);
            argsJson = r == null ? null : r[0] == 0 ? unescape(body.substring(p + 1, r[1])) : null;
        } else {
            argsJson = null;
        }
        if (argsJson == null) return null;
        String from = extract(argsJson, "from");
        String to = extract(argsJson, "to");
        String say = extract(argsJson, "say");
        if (say == null) say = "";
        if (from == null || to == null) return null;
        if (!from.matches("(?i)[a-i][0-9]") || !to.matches("(?i)[a-i][0-9]")) return null;
        Move m = new Move();
        m.from = from.toLowerCase();
        m.to = to.toLowerCase();
        m.fc = m.from.charAt(0) - 'a';
        m.fr = m.from.charAt(1) - '0';
        m.tc = m.to.charAt(0) - 'a';
        m.tr = m.to.charAt(1) - '0';
        m.say = say;
        return m;
    }

    private static String extract(String json, String key) {
        int i = indexOfKey(json, key);
        if (i < 0) return null;
        return readStringAt(json, i);
    }

    /** 找 "key" 后的字符串值起点，返回读到的未转义内容；非字符串返回null。 */
    private static String readStringAt(String s, int keyEnd) {
        int p = keyEnd;
        while (p < s.length() && Character.isWhitespace(s.charAt(p))) p++;
        if (p >= s.length() || s.charAt(p) != '"') return null;
        int[] r = readJsonString(s, p);
        if (r == null || r[0] != 0) return null;
        return unescape(s.substring(p + 1, r[1]));
    }

    /** 定位形如 "key"（带引号的键）下一次出现位置，返回其后第一个字符的下标。 */
    private static int indexOfKey(String s, String key) {
        String pat = "\"" + key + "\"";
        int i = s.indexOf(pat);
        if (i < 0) return -1;
        int p = i + pat.length();
        while (p < s.length() && Character.isWhitespace(s.charAt(p))) p++;
        if (p < s.length() && s.charAt(p) == ':') p++;
        return p;
    }

    /** 从 quotePos('"')开始读JSON字符串。返回 [status, endIndex]；status 0=成功。 */
    private static int[] readJsonString(String s, int quotePos) {
        boolean esc = false;
        for (int i = quotePos + 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (esc) { esc = false; continue; }
            if (c == '\\') { esc = true; continue; }
            if (c == '"') return new int[]{0, i};
        }
        return null;
    }

    private static int braceEnd(String s, int open) {
        int depth = 0;
        boolean esc = false, inStr = false;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inStr) {
                if (esc) esc = false;
                else if (c == '\\') esc = true;
                else if (c == '"') inStr = false;
                continue;
            }
            if (c == '"') inStr = true;
            else if (c == '{') depth++;
            else if (c == '}') { depth--; if (depth == 0) return i; }
        }
        return s.length() - 1;
    }

    private static String unescape(String raw) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\\' && i + 1 < raw.length()) {
                char n = raw.charAt(++i);
                switch (n) {
                    case 'n': sb.append('\n'); break;
                    case 't': sb.append('\t'); break;
                    case 'r': break;
                    case 'u':
                        if (i + 4 < raw.length()) {
                            try {
                                sb.append((char) Integer.parseInt(raw.substring(i + 1, i + 5), 16));
                                i += 4;
                            } catch (NumberFormatException e) { sb.append(n); }
                        } else sb.append(n);
                        break;
                    default: sb.append(n);
                }
            } else sb.append(c);
        }
        return sb.toString();
    }

    private static String esc(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.toString();
    }
}