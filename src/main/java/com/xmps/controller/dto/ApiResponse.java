package com.xmps.controller.dto;

import java.util.Map;

/**
 * 通用 API 响应
 */
public record ApiResponse<T>(
        int code,
        String message,
        T data
) {
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(200, "ok", data);
    }

    public static ApiResponse<Map<String, String>> ok(String key, String value) {
        return new ApiResponse<>(200, "ok", Map.of(key, value));
    }

    public static <T> ApiResponse<T> fail(int code, String message) {
        return new ApiResponse<>(code, message, null);
    }
}
