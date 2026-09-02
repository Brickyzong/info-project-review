package com.xmps.vector;

import java.util.List;
import java.util.Map;

/**
 * 文档向量化存储抽象接口。
 * 一期使用 NoopVectorStore 空实现；二期接入 ChromaDB/Milvus 实现此接口即可，
 * 业务代码通过 Spring 依赖注入，无需改动。
 */
public interface VectorStore {

    /**
     * 批量添加文档到向量库
     * @param docs 文档列表，Map 包含 id、content、metadata
     */
    void addDocuments(List<Map<String, Object>> docs);

    /**
     * 语义搜索
     * @param query 查询文本
     * @param topK  返回条数
     * @return 搜索结果，每项包含 id、content、score
     */
    List<SearchResult> search(String query, int topK);

    /**
     * 删除指定文档
     * @param docIds 文档 ID 列表
     */
    void delete(List<String> docIds);

    /**
     * 搜索结果
     */
    record SearchResult(String id, String content, double score, Map<String, Object> metadata) {}
}
