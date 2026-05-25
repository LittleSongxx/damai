package org.javaup.ai.controller;

import lombok.RequiredArgsConstructor;
import org.javaup.ai.common.ApiResponse;
import org.javaup.ai.context.AiRequestContextHolder;
import org.javaup.ai.entity.KnowledgeBaseVersion;
import org.javaup.ai.entity.RagDocument;
import org.javaup.ai.service.KnowledgeDocumentService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/admin/knowledge")
@RequiredArgsConstructor
public class KnowledgeDocumentController {

    private final KnowledgeDocumentService knowledgeDocumentService;

    @GetMapping("/documents")
    public ApiResponse<List<RagDocument>> listDocuments(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String status) {
        return ApiResponse.ok(knowledgeDocumentService.listDocuments(category, status));
    }

    @GetMapping("/documents/{docUid}")
    public ApiResponse<RagDocument> getDocument(@PathVariable String docUid) {
        RagDocument doc = knowledgeDocumentService.getDocument(docUid);
        return doc != null ? ApiResponse.ok(doc) : ApiResponse.error("Document not found");
    }

    @PostMapping("/documents")
    public ApiResponse<RagDocument> saveDocument(@RequestBody Map<String, Object> body) {
        Long operatorId = AiRequestContextHolder.getRequiredUser().getUserId();
        RagDocument doc = knowledgeDocumentService.saveDocument(
                (String) body.get("docUid"),
                (String) body.get("title"),
                (String) body.get("content"),
                (String) body.get("category"),
                (String) body.get("tags"),
                operatorId);
        return ApiResponse.ok(doc);
    }

    @PostMapping("/documents/{docUid}/publish")
    public ApiResponse<RagDocument> publishDocument(@PathVariable String docUid) {
        Long operatorId = AiRequestContextHolder.getRequiredUser().getUserId();
        return ApiResponse.ok(knowledgeDocumentService.publishDocument(docUid, operatorId));
    }

    @PostMapping("/documents/{docUid}/archive")
    public ApiResponse<Void> archiveDocument(@PathVariable String docUid) {
        knowledgeDocumentService.archiveDocument(docUid);
        return ApiResponse.ok(null);
    }

    @DeleteMapping("/documents/{docUid}")
    public ApiResponse<Void> deleteDocument(@PathVariable String docUid) {
        knowledgeDocumentService.deleteDocument(docUid);
        return ApiResponse.ok(null);
    }

    @GetMapping("/documents/{docUid}/versions")
    public ApiResponse<List<KnowledgeBaseVersion>> getVersions(@PathVariable String docUid) {
        return ApiResponse.ok(knowledgeDocumentService.getVersionHistory(docUid));
    }

    @GetMapping("/categories")
    public ApiResponse<List<String>> getCategories() {
        return ApiResponse.ok(knowledgeDocumentService.getCategories());
    }

    @GetMapping("/documents/pending-review")
    public ApiResponse<List<RagDocument>> getPendingReview() {
        return ApiResponse.ok(knowledgeDocumentService.getPendingReviewDocuments());
    }

    @PostMapping("/documents/{docUid}/review")
    public ApiResponse<Void> reviewDocument(@PathVariable String docUid, @RequestBody Map<String, Object> body) {
        Long reviewerId = AiRequestContextHolder.getRequiredUser().getUserId();
        boolean approved = Boolean.TRUE.equals(body.get("approved"));
        String comment = (String) body.get("comment");
        knowledgeDocumentService.reviewDocument(docUid, approved, comment, reviewerId);
        return ApiResponse.ok(null);
    }
}
