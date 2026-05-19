package org.javaup.ai.assistant.mq;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RagIngestionMessage implements Serializable {
    private String taskId;
    private String taskType;
    private String sourceFile;
}
