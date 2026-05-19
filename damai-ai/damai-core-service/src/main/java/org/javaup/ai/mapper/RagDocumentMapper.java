package org.javaup.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.javaup.ai.entity.RagDocument;

import java.util.List;

@Mapper
public interface RagDocumentMapper extends BaseMapper<RagDocument> {

    @Select("SELECT * FROM d_ai_rag_document WHERE doc_uid = #{docUid} AND status = 1")
    RagDocument selectByDocUid(@Param("docUid") String docUid);

    @Select("SELECT * FROM d_ai_rag_document WHERE source_file = #{sourceFile} AND status = 1 ORDER BY version DESC LIMIT 1")
    RagDocument selectLatestBySourceFile(@Param("sourceFile") String sourceFile);

    @Select("SELECT * FROM d_ai_rag_document WHERE doc_status = #{docStatus} AND status = 1")
    List<RagDocument> selectByDocStatus(@Param("docStatus") String docStatus);

    @Select("SELECT * FROM d_ai_rag_document WHERE doc_status = 'published' AND status = 1 AND (valid_until IS NULL OR valid_until > NOW())")
    List<RagDocument> selectActivePublished();
}
