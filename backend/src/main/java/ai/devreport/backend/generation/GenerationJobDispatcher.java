package ai.devreport.backend.generation;

import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
class GenerationJobDispatcher {

	private final GenerationJobWorker worker;
	private final GenerationJobService jobs;

	GenerationJobDispatcher(GenerationJobWorker worker, GenerationJobService jobs) {
		this.worker = worker;
		this.jobs = jobs;
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	void dispatch(GenerationQueuedEvent event) {
		try {
			worker.process(event);
		} catch (TaskRejectedException exception) {
			jobs.fail(event.jobId(), "GENERATION_CAPACITY_EXCEEDED",
				"보고서 생성 요청이 많아 작업을 실행하지 못했습니다.");
		}
	}
}
