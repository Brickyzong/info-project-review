package com.xmps.vector;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 向量化存储空实现（一期）。
 * 当 xmps.vector.enabled=false 时自动注入此实现，后续接入真实向量库时只需提供
 * 新的 @Component 实现即可——业务代码通过 VectorStore 接口调用，零改动。
 */
@Component
@ConditionalOnProperty(name = "xmps.vector.enabled", havingValue = "false", matchIfMissing = true)
public class NoopVectorStore implements VectorStore {

    private static final Logger log = LoggerFactory.getLogger(NoopVectorStore.class);

    public NoopVectorStore() {
        log.info("向量化存储已禁用（一期模式）——使用 NoopVectorStore");
    }

    @Override
    public void addDocuments(List<Map<String, Object>> docs) {
        log.debug("NoopVectorStore.addDocuments: 跳过 {} 条文档", docs.size());
    }

    @Override
    public List<SearchResult> search(String query, int topK) {
        log.debug("NoopVectorStore.search: 查询 '{}', topK={}, 返回空结果", query, topK);
        return Collections.emptyList();
    }

    @Override
    public void delete(List<String> docIds) {
        log.debug("NoopVectorStore.delete: 跳过 {} 条文档", docIds.size());
    }
}
