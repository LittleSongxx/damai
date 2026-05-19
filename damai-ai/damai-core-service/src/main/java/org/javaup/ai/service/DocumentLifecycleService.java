package org.javaup.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.entity.RagDocument;
import org.javaup.ai.entity.RagIngestionTask;
import org.javaup.ai.mapper.RagDocumentMapper;
import org.javaup.ai.mapper.RagIngestionTaskMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * Manages document lifecycle: status transitions, expiration, and cleanup.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentLifecycleService {

    private final RagDocumentMapper documentMapper;
    private final RagIngestionTaskMapper taskMapper;

    /**
     * Publish a document (draft → published).
     */
    public RagDocument publishDocument(Long docId) {
        RagDocument doc = documentMapper.selectById(docId);
        if (doc == null) return null;
        doc.setDocStatus("published");
        doc.setEditTime(new Date());
        documentMapper.updateById(doc);
        log.info("Document {} published", doc.getDocUid());
        return doc;
    }

    /**
     * Archive a document (any status → archived).
     */
    public RagDocument archiveDocument(Long docId) {
        RagDocument doc = documentMapper.selectById(docId);
        if (doc == null) return null;
        doc.setDocStatus("archived");
        doc.setEditTime(new Date());
        documentMapper.updateById(doc);
        log.info("Document {} archived", doc.getDocUid());
        return doc;
    }

    /**
     * Manually expire a document (published → expired).
     */
    public RagDocument expireDocument(Long docId) {
        RagDocument doc = documentMapper.selectById(docId);
        if (doc == null) return null;
        doc.setDocStatus("expired");
        doc.setEditTime(new Date());
        documentMapper.updateById(doc);
        log.info("Document {} expired", doc.getDocUid());
        return doc;
    }

    /**
     * Scheduled job: auto-expire documents that have passed their valid_until date.
     * Runs daily at 3 AM.
     */
    @Scheduled(cron = "${damai.ai.rag.expiration-cron:0 0 3 * * ?}")
    public void autoExpireDocuments() {
        List<RagDocument> published = documentMapper.selectByDocStatus("published");
        int expiredCount = 0;
        Date now = new Date();
        for (RagDocument doc : published) {
            if (doc.getValidUntil() != null && doc.getValidUntil().before(now)) {
                doc.setDocStatus("expired");
                doc.setEditTime(now);
                documentMapper.updateById(doc);
                expiredCount++;
            }
        }
        if (expiredCount > 0) {
            log.info("Auto-expired {} documents", expiredCount);
        }
    }

    /**
     * Get ingestion task status for monitoring.
     */
    public List<RagIngestionTask> getRecentTasks() {
        LambdaQueryWrapper<RagIngestionTask> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(RagIngestionTask::getStatus, 1)
                .orderByDesc(RagIngestionTask::getId)
                .last("LIMIT 20");
        return taskMapper.selectList(wrapper);
    }

    /**
     * Get document statistics.
     */
    public Map<String, Object> getDocumentStats() {
        List<RagDocument> all = documentMapper.selectActivePublished();
        Map<String, Long> byStatus = all.stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        RagDocument::getDocStatus, java.util.stream.Collectors.counting()));
        Map<String, Long> byCategory = all.stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        d -> d.getCategory() != null ? d.getCategory() : "unknown",
                        java.util.stream.Collectors.counting()));
        return Map.of(
                "totalDocuments", all.size(),
                "byStatus", byStatus,
                "byCategory", byCategory
        );
    }

    /**
     * Clean up stale ingestion tasks older than 30 days.
     */
    @Scheduled(cron = "0 0 4 * * ?")
    public void cleanupStaleTasks() {
        LambdaQueryWrapper<RagIngestionTask> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(RagIngestionTask::getStatus, 1)
                .lt(RagIngestionTask::getCreateTime,
                        java.sql.Timestamp.valueOf(LocalDateTime.now().minusDays(30)));
        List<RagIngestionTask> tasks = taskMapper.selectList(wrapper);
        for (RagIngestionTask task : tasks) {
            task.setStatus(0);
            taskMapper.updateById(task);
        }
        if (!tasks.isEmpty()) {
            log.info("Cleaned up {} stale ingestion tasks", tasks.size());
        }
    }
}
