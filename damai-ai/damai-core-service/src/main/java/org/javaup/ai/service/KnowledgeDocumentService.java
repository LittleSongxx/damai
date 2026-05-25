package org.javaup.ai.service;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.entity.KnowledgeBaseVersion;
import org.javaup.ai.entity.RagDocument;
import org.javaup.ai.mapper.KnowledgeBaseVersionMapper;
import org.javaup.ai.mapper.RagDocumentMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * 知识库运营管理服务 - 参考 Dify 的 dataset document 管理设计。
 * 支持知识的增删改查、版本管理、审核发布流程。
 */
@Slf4j
@Service
public class KnowledgeDocumentService {

    private final RagDocumentMapper documentMapper;
    private final KnowledgeBaseVersionMapper versionMapper;
    private final DocumentIngestionService ingestionService;

    @Value("${damai.ai.knowledge.datum-dir:./datum}")
    private String datumDir;

    public KnowledgeDocumentService(RagDocumentMapper documentMapper,
                                     KnowledgeBaseVersionMapper versionMapper,
                                     DocumentIngestionService ingestionService) {
        this.documentMapper = documentMapper;
        this.versionMapper = versionMapper;
        this.ingestionService = ingestionService;
    }

    /**
     * 获取所有文档列表 (供运营后台)
     */
    public List<RagDocument> listDocuments(String category, String docStatus) {
        var wrapper = Wrappers.lambdaQuery(RagDocument.class)
                .orderByDesc(RagDocument::getEditTime);
        if (StringUtils.hasText(category)) {
            wrapper.eq(RagDocument::getCategory, category);
        }
        if (StringUtils.hasText(docStatus)) {
            wrapper.eq(RagDocument::getDocStatus, docStatus);
        }
        return documentMapper.selectList(wrapper);
    }

    /**
     * 获取单个文档
     */
    public RagDocument getDocument(String docUid) {
        return documentMapper.selectOne(
                Wrappers.lambdaQuery(RagDocument.class)
                        .eq(RagDocument::getDocUid, docUid));
    }

    /**
     * 创建/更新文档 (暂存为草稿)
     */
    @Transactional
    public RagDocument saveDocument(String docUid, String title, String content,
                                     String category, String tags, Long operatorId) {
        RagDocument doc;
        boolean isNew = false;

        if (StringUtils.hasText(docUid)) {
            doc = documentMapper.selectOne(
                    Wrappers.lambdaQuery(RagDocument.class)
                            .eq(RagDocument::getDocUid, docUid));
            if (doc == null) {
                doc = new RagDocument();
                doc.setDocUid(docUid);
                isNew = true;
            }
        } else {
            doc = new RagDocument();
            doc.setDocUid(UUID.randomUUID().toString().replace("-", ""));
            isNew = true;
        }

        doc.setTitle(title);
        doc.setCategory(category);
        doc.setTags(tags);
        doc.setDocStatus("draft");
        doc.setEditTime(new Date());

        if (isNew) {
            doc.setSource("manual");
            doc.setFileType("md");
            doc.setVersion(1);
            doc.setCreateTime(new Date());
            doc.setStatus(1);
            documentMapper.insert(doc);
        } else {
            doc.setVersion((doc.getVersion() == null ? 0 : doc.getVersion()) + 1);
            documentMapper.updateById(doc);
        }

        // 保存版本快照 (参考 Dify 的 document versioning)
        KnowledgeBaseVersion version = new KnowledgeBaseVersion();
        version.setVersionId(UUID.randomUUID().toString().replace("-", ""));
        version.setDocUid(doc.getDocUid());
        version.setVersionNumber(doc.getVersion());
        version.setContent(content);
        version.setChangeSummary(isNew ? "创建文档" : "更新文档内容");
        version.setChangedBy(operatorId);
        version.setPublishStatus("draft");
        version.setCreateTime(new Date());
        version.setEditTime(new Date());
        version.setStatus(1);
        versionMapper.insert(version);

        return doc;
    }

    /**
     * 发布文档 (draft -> published，参考 Dify 的 publish 流程)
     */
    @Transactional
    public RagDocument publishDocument(String docUid, Long operatorId) {
        RagDocument doc = getDocument(docUid);
        if (doc == null) {
            throw new IllegalArgumentException("Document not found: " + docUid);
        }

        // 获取最新版本内容并触发重新入库
        KnowledgeBaseVersion latestVersion = versionMapper.selectOne(
                Wrappers.lambdaQuery(KnowledgeBaseVersion.class)
                        .eq(KnowledgeBaseVersion::getDocUid, docUid)
                        .orderByDesc(KnowledgeBaseVersion::getVersionNumber)
                        .last("limit 1"));

        if (latestVersion != null) {
            latestVersion.setPublishStatus("published");
            latestVersion.setPublishedAt(new Date());
            latestVersion.setEditTime(new Date());
            versionMapper.updateById(latestVersion);
        }

        doc.setDocStatus("published");
        doc.setEditTime(new Date());
        documentMapper.updateById(doc);

        // 将内容写入datum目录供RAG检索引擎消费
        if (latestVersion != null && StringUtils.hasText(latestVersion.getContent())) {
            writeDatumFile(doc, latestVersion.getContent());
        }

        // 触发RAG重新入库
        try {
            ingestionService.incrementalReindex();
        } catch (Exception e) {
            log.warn("Reindex after publish failed: {}", e.getMessage());
        }

        return doc;
    }

    private void writeDatumFile(RagDocument doc, String content) {
        try {
            Path datumPath = Paths.get(datumDir);
            Files.createDirectories(datumPath);
            String filename = (StringUtils.hasText(doc.getTitle()) ? doc.getTitle() : doc.getDocUid())
                    .replaceAll("[\\\\/:*?\"<>|]", "_") + ".md";
            Path filePath = datumPath.resolve(filename);
            Files.writeString(filePath, content, StandardCharsets.UTF_8);
            log.info("Knowledge document written to datum: docUid={}, path={}", doc.getDocUid(), filePath);
        } catch (IOException e) {
            log.warn("Failed to write datum file for docUid={}: {}", doc.getDocUid(), e.getMessage());
        }
    }

    /**
     * 归档文档
     */
    @Transactional
    public void archiveDocument(String docUid) {
        RagDocument doc = getDocument(docUid);
        if (doc != null) {
            doc.setDocStatus("archived");
            doc.setEditTime(new Date());
            documentMapper.updateById(doc);
        }
    }

    /**
     * 删除文档
     */
    @Transactional
    public void deleteDocument(String docUid) {
        RagDocument doc = getDocument(docUid);
        if (doc != null) {
            doc.setStatus(0);
            doc.setEditTime(new Date());
            documentMapper.updateById(doc);
        }
    }

    /**
     * 获取文档版本历史
     */
    public List<KnowledgeBaseVersion> getVersionHistory(String docUid) {
        return versionMapper.selectList(
                Wrappers.lambdaQuery(KnowledgeBaseVersion.class)
                        .eq(KnowledgeBaseVersion::getDocUid, docUid)
                        .orderByDesc(KnowledgeBaseVersion::getVersionNumber));
    }

    /**
     * 获取文档分类列表
     */
    public List<String> getCategories() {
        List<RagDocument> docs = documentMapper.selectList(
                Wrappers.lambdaQuery(RagDocument.class)
                        .select(RagDocument::getCategory)
                        .groupBy(RagDocument::getCategory));
        return docs.stream()
                .map(RagDocument::getCategory)
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
    }

    /**
     * 获取待审核文档
     */
    public List<RagDocument> getPendingReviewDocuments() {
        return documentMapper.selectList(
                Wrappers.lambdaQuery(RagDocument.class)
                        .eq(RagDocument::getDocStatus, "draft"));
    }

    /**
     * 审核文档
     */
    @Transactional
    public void reviewDocument(String docUid, boolean approved, String comment, Long reviewerId) {
        RagDocument doc = getDocument(docUid);
        if (doc == null) return;

        KnowledgeBaseVersion latestVersion = versionMapper.selectOne(
                Wrappers.lambdaQuery(KnowledgeBaseVersion.class)
                        .eq(KnowledgeBaseVersion::getDocUid, docUid)
                        .orderByDesc(KnowledgeBaseVersion::getVersionNumber)
                        .last("limit 1"));

        if (latestVersion != null) {
            latestVersion.setReviewedBy(reviewerId);
            latestVersion.setReviewComment(comment);
            latestVersion.setEditTime(new Date());
            if (!approved) {
                latestVersion.setPublishStatus("rejected");
            }
            versionMapper.updateById(latestVersion);
        }

        if (approved) {
            publishDocument(docUid, reviewerId);
        }
    }
}
