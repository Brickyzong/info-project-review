package com.xmps.model.enums;

/**
 * 项目类型——先判属性，再行审核
 */
public enum ProjectType {
    /**
     * 建设类-新建
     */
    CONSTRUCTION_NEW("建设-新建"),
    /**
     * 建设类-续建
     */
    CONSTRUCTION_CONTINUE("建设-续建"),
    /**
     * 建设类-改建
     */
    CONSTRUCTION_RENOVATE("建设-改建"),
    /**
     * 运维类
     */
    OPERATION("运维");

    private final String label;

    ProjectType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
