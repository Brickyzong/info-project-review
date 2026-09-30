package com.xmps.service;

import com.xmps.model.ReviewItem;
import com.xmps.model.entity.ReviewTask;
import com.xmps.model.enums.ProjectType;
import com.xmps.model.enums.ReviewVerdict;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 独立规则引擎——在 LLM 审查之外做确定性二次校验（不调用大模型）。
 *
 * <p>规则确定性源自 knowledge_base：
 * <ul>
 *   <li>KB-04 方案完整性：建设八章 / 运维五章结构</li>
 *   <li>KB-06 信创合规：四类核心软硬件国产化白名单 + 国外标记</li>
 *   <li>KB-08 准入门槛：禁止类资源建设、新建政务App</li>
 * </ul>
 *
 * <p>设计原则（与降级策略一致）：
 * <ol>
 *   <li>仅做「加法」——把确定性发现作为额外 ReviewItem 追加，绝不覆盖 LLM 结论；</li>
 *   <li>引擎自身异常时整体降级为空列表，绝不阻断主流程；</li>
 *   <li>不引入任何 LLM 调用，纯文本规则扫描，耗时与成本可忽略。</li>
 * </ol>
 */
@Service
public class RulesEngine {

    private static final Logger log = LoggerFactory.getLogger(RulesEngine.class);

    // ============================================================
    // 方案完整性：建设八章 / 运维五章（KB-04）
    // key=标准章节名, value=文档中可能出现的别名（降低漏判）
    // ============================================================
    private static final Map<String, List<String>> CONSTRUCTION_CHAPTERS = Map.of(
            "背景和依据", List.of("背景和依据", "建设背景", "项目背景"),
            "预期目标", List.of("预期目标", "建设目标", "预期成效"),
            "技术需求", List.of("技术需求", "需求分析", "功能需求"),
            "技术方案", List.of("技术方案", "技术路线", "架构设计"),
            "数据资源设计", List.of("数据资源设计", "数据资源", "数据共享"),
            "信息安全", List.of("信息安全", "安全保障", "等级保护"),
            "实施计划", List.of("实施计划", "进度安排", "里程碑"),
            "项目概算", List.of("项目概算", "经费预算", "概算", "预算"));
    private static final Map<String, List<String>> OPERATION_CHAPTERS = Map.of(
            "项目背景及依据", List.of("项目背景及依据", "运维背景", "项目背景"),
            "总体架构", List.of("总体架构", "总体架构设计"),
            "业务现状及需求", List.of("业务现状及需求", "业务现状", "运维需求"),
            "运维服务内容", List.of("运维服务内容", "服务内容", "服务范围"),
            "实施计划", List.of("实施计划", "进度安排", "保障措施"));

    // ============================================================
    // 信创合规（KB-06）：国产化白名单 + 国外标记
    // ============================================================
    private static final Map<String, List<String>> XC_ALLOW = Map.of(
            "芯片", List.of("鲲鹏", "飞腾", "龙芯", "申威", "兆芯", "海光"),
            "操作系统", List.of("麒麟", "统信UOS", "中科方德", "UOS"),
            "数据库", List.of("达梦", "人大金仓", "南大通用", "神通", "瀚高", "OceanBase", "TiDB", "openGauss", "高斯"),
            "中间件", List.of("东方通", "中创", "金蝶", "普元"));
    private static final Map<String, List<String>> XC_FOREIGN = Map.of(
            "芯片", List.of("Intel", "AMD", "英特尔", "超威"),
            "操作系统", List.of("Windows", "CentOS", "Ubuntu", "Red Hat", "RHEL", "红帽"),
            "数据库", List.of("Oracle", "MySQL", "SQL Server", "PostgreSQL", "DB2"),
            "中间件", List.of("WebLogic", "WebSphere"));

    // ============================================================
    // 准入门槛禁止项（KB-08）：建设类三重审查中的硬禁令
    // ============================================================
    private static final List<String> FORBIDDEN_RESOURCE = List.of(
            "新建数据中心", "自建机房", "采购大型服务器", "新建云平台", "新建云平");
    private static final List<String> FORBIDDEN_APP = List.of(
            "新建政务App", "新建政务 APP", "新建政务应用", "新建政务小程序", "新建小程序");

    /**
     * 对单份文档做确定性二次校验，返回追加的审查项（可能为空）。
     */
    public List<ReviewItem> check(String documentText, ReviewTask task) {
        List<ReviewItem> items = new ArrayList<>();
        try {
            boolean operation = task.getProjectType() == ProjectType.OPERATION;
            items.addAll(checkChapters(documentText, operation));
            items.addAll(checkXinChuang(documentText));
            // 资源合规 / 政务App 整合仅对建设类（运维类无此三重审查）
            if (!operation) {
                items.addAll(checkAdmission(documentText));
            }
        } catch (Exception e) {
            // 降级：规则引擎异常不得影响主流程
            log.error("确定性规则引擎异常，跳过二次校验 — taskId={}, error={}",
                    task.getId(), e.getMessage(), e);
            return List.of();
        }
        return items;
    }

    // ---- 方案完整性（确定性结构校验）----
    private List<ReviewItem> checkChapters(String text, boolean operation) {
        Map<String, List<String>> required = operation ? OPERATION_CHAPTERS : CONSTRUCTION_CHAPTERS;
        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : required.entrySet()) {
            if (e.getValue().stream().noneMatch(text::contains)) {
                missing.add(e.getKey());
            }
        }
        if (missing.isEmpty()) {
            return List.of(new ReviewItem(
                    "确定性校验-方案完整性",
                    ReviewVerdict.PASS,
                    "方案文档包含全部" + (operation ? "五" : "八") + "章标准结构，章节齐备。",
                    "保持现有结构即可。",
                    "KB-04"));
        }
        return List.of(new ReviewItem(
                "确定性校验-方案完整性",
                ReviewVerdict.FAIL,
                "确定性扫描发现以下标准章节缺失：" + String.join("、", missing) + "。",
                "请补充缺失章节内容，对照 KB-04 " + (operation ? "五" : "八") + "章模板完善方案。",
                "KB-04"));
    }

    // ---- 信创合规（国产选型硬校验）----
    private List<ReviewItem> checkXinChuang(String text) {
        List<ReviewItem> out = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : XC_ALLOW.entrySet()) {
            String category = e.getKey();
            List<String> allow = e.getValue();
            List<String> foreign = XC_FOREIGN.get(category);
            boolean hasDomestic = allow.stream().anyMatch(text::contains);
            boolean hasForeign = foreign != null && foreign.stream().anyMatch(text::contains);
            if (hasForeign && !hasDomestic) {
                out.add(new ReviewItem(
                        "确定性校验-信创合规(" + category + ")",
                        ReviewVerdict.FAIL,
                        "方案在「" + category + "」类提到国外产品（如 " + String.join("/", foreign) + "），但未声明国产替代。",
                        "请明确采用国产" + category + "（" + String.join("、", allow) + "），或补充说明不采用的理由。",
                        "KB-06"));
            }
        }
        return out;
    }

    // ---- 准入门槛（资源合规 + 政务App 整合）----
    private List<ReviewItem> checkAdmission(String text) {
        List<ReviewItem> out = new ArrayList<>();
        List<String> hitResource = new ArrayList<>();
        for (String f : FORBIDDEN_RESOURCE) {
            if (text.contains(f)) hitResource.add(f);
        }
        if (!hitResource.isEmpty()) {
            out.add(new ReviewItem(
                    "确定性校验-准入门槛(资源合规)",
                    ReviewVerdict.FAIL,
                    "方案涉及禁止类资源建设：" + String.join("、", hitResource) + "。",
                    "政务信息化项目禁止新建数据中心/自建机房/采购大型服务器/新建云平台，应统一使用政务云。",
                    "KB-08"));
        }
        for (String f : FORBIDDEN_APP) {
            if (text.contains(f)) {
                out.add(new ReviewItem(
                        "确定性校验-准入门槛(政务App)",
                        ReviewVerdict.UNCERTAIN,
                        "方案拟新建政务App/小程序（命中：" + f + "）。",
                        "原则上不新建政务App，已建应整合至「苏服办」，请评估整合可行性。",
                        "KB-08"));
                break;
            }
        }
        return out;
    }
}
