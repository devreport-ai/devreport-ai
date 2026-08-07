package ai.devreport.backend.generation;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskRejectedException;

class GenerationJobDispatcherTest {

	@Test
	void marksRejectedJobAsFailed() {
		GenerationJobWorker worker = mock(GenerationJobWorker.class);
		GenerationJobService jobs = mock(GenerationJobService.class);
		GenerationJobDispatcher dispatcher = new GenerationJobDispatcher(worker, jobs);
		GenerationQueuedEvent event = new GenerationQueuedEvent(UUID.randomUUID());
		doThrow(new TaskRejectedException("executor is full")).when(worker).process(event);

		dispatcher.dispatch(event);

		verify(jobs).fail(event.jobId(), "GENERATION_CAPACITY_EXCEEDED",
			"보고서 생성 요청이 많아 작업을 실행하지 못했습니다.");
	}
}
