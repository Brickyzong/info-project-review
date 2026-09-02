package com.xmps.model.enums;

/**
 * 审查结论
 */
public enum ReviewVerdict {
    PASS("通过"),
    FAIL("不通过"),
    UNCERTAIN("存疑"),
    NOT_APPLICABLE("不适用");

    private final String label;

    ReviewVerdict(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
