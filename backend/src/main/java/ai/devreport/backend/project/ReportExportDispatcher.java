package ai.devreport.backend.project;

import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
class ReportExportDispatcher {

	private final ReportExportWorker worker;
	private final ReportExportService exports;

	ReportExportDispatcher(ReportExportWorker worker, ReportExportService exports) {
		this.worker = worker;
		this.exports = exports;
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	void dispatch(ReportExportQueuedEvent event) {
		try {
			worker.process(event);
		} catch (TaskRejectedException exception) {
			exports.fail(event.exportId(), "EXPORT_CAPACITY_EXCEEDED",
				"PDF 생성 요청이 많아 작업을 실행하지 못했습니다.");
		}
	}
}
