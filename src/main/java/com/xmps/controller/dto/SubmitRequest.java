package com.xmps.controller.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 提交评审请求
 */
public record SubmitRequest(
        /**
         * 回调 URL——我方评审完成后 POST 结果到此地址
         */
        @NotBlank(message = "callbackUrl 不能为空")
        String callbackUrl
) {}
