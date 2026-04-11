package org.unlaxer.infra.volta;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PaginationTest {
    @Test
    void pageRequestOffset() {
        var req = new Pagination.PageRequest(3, 20, null, null);
        assertEquals(40, req.offset());
    }

    @Test
    void pageRequestOffsetFirstPage() {
        var req = new Pagination.PageRequest(1, 50, null, null);
        assertEquals(0, req.offset());
    }

    @Test
    void pageResponseToJson() {
        var resp = new Pagination.PageResponse<>(List.of("a", "b"), 102L, 2, 50);
        Map<String, Object> json = resp.toJson();
        assertEquals(List.of("a", "b"), json.get("items"));
        assertEquals(102L, json.get("total"));
        assertEquals(2, json.get("page"));
        assertEquals(50, json.get("size"));
        assertEquals(3, json.get("pages")); // ceil(102/50) = 3
    }

    @Test
    void pageResponsePagesEdgeCases() {
        assertEquals(0, new Pagination.PageResponse<>(List.of(), 0L, 1, 20).pages());
        assertEquals(1, new Pagination.PageResponse<>(List.of(), 1L, 1, 20).pages());
        assertEquals(1, new Pagination.PageResponse<>(List.of(), 20L, 1, 20).pages());
        assertEquals(2, new Pagination.PageResponse<>(List.of(), 21L, 1, 20).pages());
    }

    @Test
    void pageResponseZeroSizeDoesNotThrow() {
        assertEquals(0, new Pagination.PageResponse<>(List.of(), 0L, 1, 0).pages());
    }
}
