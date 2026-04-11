package org.unlaxer.infra.volta;

import io.javalin.http.Context;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Pagination {
    private Pagination() {
    }

    public record PageRequest(int page, int size, String sort, String query) {
        public int offset() {
            return (page - 1) * size;
        }

        public static PageRequest from(Context ctx) {
            int page = Math.max(1, parseIntOrDefault(ctx.queryParam("page"), 1));
            int size = Math.min(100, Math.max(1, parseIntOrDefault(ctx.queryParam("size"), 20)));
            String sort = ctx.queryParam("sort");
            String query = ctx.queryParam("q");
            return new PageRequest(page, size, sort, query);
        }

        private static int parseIntOrDefault(String value, int defaultValue) {
            if (value == null) return defaultValue;
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }
    }

    public record PageResponse<T>(List<T> items, long total, int page, int size) {
        public int pages() {
            return size == 0 ? 0 : (int) Math.ceil((double) total / size);
        }

        public Map<String, Object> toJson() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("items", items);
            map.put("total", total);
            map.put("page", page);
            map.put("size", size);
            map.put("pages", pages());
            return map;
        }
    }
}
