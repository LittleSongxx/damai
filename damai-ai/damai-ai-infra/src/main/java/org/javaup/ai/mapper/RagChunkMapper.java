package org.javaup.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.javaup.ai.entity.RagChunk;

import java.util.List;

@Mapper
public interface RagChunkMapper extends BaseMapper<RagChunk> {

    @Select("SELECT * FROM d_ai_rag_chunk WHERE chunk_uid = #{chunkUid} AND status = 1")
    RagChunk selectByChunkUid(@Param("chunkUid") String chunkUid);

    @Select("SELECT * FROM d_ai_rag_chunk WHERE doc_id = #{docId} AND status = 1 ORDER BY chunk_index")
    List<RagChunk> selectByDocId(@Param("docId") Long docId);

    @Select("SELECT * FROM d_ai_rag_chunk WHERE parent_chunk_id = #{parentChunkId} AND status = 1 ORDER BY chunk_index")
    List<RagChunk> selectByParentChunkId(@Param("parentChunkId") Long parentChunkId);

    @Select("SELECT * FROM d_ai_rag_chunk WHERE doc_id = #{docId} AND chunk_type = 'summary' AND status = 1")
    List<RagChunk> selectSummariesByDocId(@Param("docId") Long docId);

    @Select("SELECT * FROM d_ai_rag_chunk WHERE status = 1 AND (embedding_cached IS NULL OR embedding_cached = 0)")
    List<RagChunk> selectChunksWithoutEmbedding();

    @Select("SELECT * FROM d_ai_rag_chunk WHERE doc_id IN (SELECT id FROM d_ai_rag_document WHERE doc_status = 'published' AND status = 1) AND chunk_type IN ('faq','faq_part') AND status = 1")
    List<RagChunk> selectAllActiveChunks();
}
