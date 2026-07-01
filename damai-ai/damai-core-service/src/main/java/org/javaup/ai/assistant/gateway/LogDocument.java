package org.javaup.ai.assistant.gateway;

import lombok.Data;
import org.dromara.easyes.annotation.IndexField;
import org.dromara.easyes.annotation.IndexId;
import org.dromara.easyes.annotation.IndexName;
import org.dromara.easyes.annotation.rely.FieldType;
import org.dromara.easyes.annotation.rely.IdType;

@Data
@IndexName(value = "damai-logs-*", keepGlobalPrefix = false)
public class LogDocument {

    @IndexId(type = IdType.CUSTOMIZE)
    private String id;

    @IndexField(value = "@timestamp", fieldType = FieldType.DATE)
    private String timestamp;

    @IndexField(value = "traceId", fieldType = FieldType.KEYWORD)
    private String traceId;

    @IndexField(value = "spanId", fieldType = FieldType.KEYWORD)
    private String spanId;

    @IndexField(value = "projectName", fieldType = FieldType.KEYWORD)
    private String projectName;

    @IndexField(value = "level", fieldType = FieldType.KEYWORD)
    private String level;

    @IndexField(value = "message", fieldType = FieldType.TEXT)
    private String message;

    @IndexField(value = "sourceClass", fieldType = FieldType.KEYWORD)
    private String sourceClass;

    @IndexField(value = "sourceMethod", fieldType = FieldType.KEYWORD)
    private String sourceMethod;

    @IndexField(value = "sourceLine", fieldType = FieldType.KEYWORD)
    private String sourceLine;

    @IndexField(value = "thread", fieldType = FieldType.KEYWORD)
    private String thread;

    @IndexField(value = "timeMillis", fieldType = FieldType.LONG)
    private Long timeMillis;
}
