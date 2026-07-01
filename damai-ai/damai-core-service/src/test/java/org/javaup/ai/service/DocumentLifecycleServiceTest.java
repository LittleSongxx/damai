package org.javaup.ai.service;

import org.javaup.ai.entity.RagIngestionTask;
import org.javaup.ai.mapper.RagDocumentMapper;
import org.javaup.ai.mapper.RagIngestionTaskMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentLifecycleServiceTest {

    private final RagDocumentMapper documentMapper = mock(RagDocumentMapper.class);
    private final RagIngestionTaskMapper taskMapper = mock(RagIngestionTaskMapper.class);
    private final DocumentLifecycleService service = new DocumentLifecycleService(documentMapper, taskMapper);

    @Test
    void shouldCreateSubmittedIngestionTaskBeforeMqDelivery() {
        when(taskMapper.selectByTaskId("ingest_1")).thenReturn(null);

        RagIngestionTask task = service.createSubmittedTask("ingest_1", "full");

        assertEquals("ingest_1", task.getTaskId());
        assertEquals("full", task.getTaskType());
        assertEquals("submitted", task.getTaskStatus());
        assertEquals(1, task.getStatus());
        verify(taskMapper).insert(task);
    }

    @Test
    void shouldNotDuplicateExistingSubmittedTask() {
        RagIngestionTask existing = new RagIngestionTask();
        existing.setTaskId("ingest_1");
        when(taskMapper.selectByTaskId("ingest_1")).thenReturn(existing);

        RagIngestionTask task = service.createSubmittedTask("ingest_1", "full");

        assertSame(existing, task);
        verify(taskMapper, never()).insert(any(RagIngestionTask.class));
    }
}
