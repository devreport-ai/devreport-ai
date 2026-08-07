package ai.devreport.backend.export;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
class ReportExportRecovery {

	private final ReportExportService exports;
	private final ApplicationEventPublisher events;

	ReportExportRecovery(ReportExportService exports, ApplicationEventPublisher events) {
		this.exports = exports;
		this.events = events;
	}

	@EventListener(ApplicationReadyEvent.class)
	void recover() {
		exports.recover().forEach(exportId -> events.publishEvent(new ReportExportQueuedEvent(exportId)));
	}
}
