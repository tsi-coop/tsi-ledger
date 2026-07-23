package org.tsicoop.ledger.framework;

import jakarta.servlet.http.HttpServletResponse;
import org.json.simple.JSONObject;

import java.io.IOException;
import java.io.PrintWriter;
import java.time.Instant;
import java.util.List;

public class OutputProcessor {

    /**
     * Streams a CSV file download (RFC 4180 quoting): header row + one row per
     * `rows` entry, each cell converted via String.valueOf and quoted only when
     * it contains a comma, quote, or newline. Used by Reports.export_entries —
     * a raw dump, not JSON, so this bypasses send()'s content type entirely.
     */
    public static void sendCsv(HttpServletResponse res, String filename, List<String> header, List<List<Object>> rows) {
        res.setStatus(200);
        res.setCharacterEncoding("UTF-8");
        res.setContentType("text/csv; charset=UTF-8");
        res.setHeader("Content-Disposition", "attachment; filename=\"" + filename + "\"");

        try {
            PrintWriter out = res.getWriter();
            out.print(csvRow(header));
            for (List<Object> row : rows) {
                out.print(csvRow(row));
            }
            out.flush();
        } catch (IOException e) {
            System.err.println("Failed to write CSV response: " + e.getMessage());
        }
    }

    private static String csvRow(List<?> cells) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(csvCell(cells.get(i)));
        }
        sb.append("\r\n");
        return sb.toString();
    }

    private static String csvCell(Object value) {
        String s = value == null ? "" : String.valueOf(value);
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    /**
     * Sends a successful or data-bearing JSON response serialized by Jackson.
     * Accepts JSONObject, JSONArray, or any Jackson-serializable type.
     */
    public static void send(HttpServletResponse res, int status, Object data) {
        res.setStatus(status);
        res.setCharacterEncoding("UTF-8");
        res.setContentType("application/json; charset=UTF-8");

        try {
            PrintWriter out = res.getWriter();
            if (data == null) {
                out.print("{}");
            } else {
                out.print(JacksonUtil.toJson(data));
            }
            out.flush();
        } catch (IOException e) {
            System.err.println("Failed to write response: " + e.getMessage());
        }
    }

    public static void errorResponse(HttpServletResponse res, int code, String error, String message, String path) {
        res.setStatus(code);
        res.setCharacterEncoding("UTF-8");
        res.setContentType("application/json; charset=UTF-8");

        JSONObject errorJson = new JSONObject();
        errorJson.put("timestamp", Instant.now().toString());
        errorJson.put("status", code);
        errorJson.put("error", error);
        errorJson.put("message", message);
        errorJson.put("path", path);

        try {
            PrintWriter out = res.getWriter();
            out.print(JacksonUtil.toJson(errorJson));
            out.flush();
        } catch (IOException e) {
            System.err.println("Critical error sending error response: " + e.getMessage());
        }
    }

    public static void sendError(HttpServletResponse res, int code, String error) {
        res.setStatus(code);
        res.setCharacterEncoding("UTF-8");
        res.setContentType("application/json; charset=UTF-8");

        JSONObject errorJson = new JSONObject();
        errorJson.put("timestamp", Instant.now().toString());
        errorJson.put("status", code);
        errorJson.put("error", error);

        try {
            PrintWriter out = res.getWriter();
            out.print(JacksonUtil.toJson(errorJson));
            out.flush();
        } catch (IOException e) {
            System.err.println("Critical error sending error response: " + e.getMessage());
        }
    }
}
