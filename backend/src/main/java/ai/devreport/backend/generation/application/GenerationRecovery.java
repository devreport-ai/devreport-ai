package ai.devreport.backend.generation.application;

import ai.devreport.backend.generation.domain.GenerationQueuedEvent;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
class GenerationRecovery {

	private final GenerationJobService jobs;
	private final ApplicationEventPublisher events;

	GenerationRecovery(GenerationJobService jobs, ApplicationEventPublisher events) {
		this.jobs = jobs;
		this.events = events;
	}

	@EventListener(ApplicationReadyEvent.class)
	void recover() {
		jobs.recover().forEach(jobId -> events.publishEvent(new GenerationQueuedEvent(jobId)));
	}
}
