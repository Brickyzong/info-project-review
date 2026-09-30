package com.xmps.model;

import com.xmps.model.enums.ReviewVerdict;

/**
 * 审查项结果——建设类 / 运维类审查智能体共用。
 *
 * <p>verdict / problem 为内部字段名；对外报告由 ReportService 映射为 conclusion / detail
 * （第一档契约要求），本类保持内部命名不变。</p>
 */
public record ReviewItem(
        String item,
        ReviewVerdict verdict,
        String problem,
        String suggestion,
        String reference
) {
}
