package ai.devreport.backend.export;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
class ReportExportWorker {

	private static final Logger log = LoggerFactory.getLogger(ReportExportWorker.class);

	private final ReportExportService exports;
	private final PdfReportRenderer renderer;

	ReportExportWorker(ReportExportService exports, PdfReportRenderer renderer) {
		this.exports = exports;
		this.renderer = renderer;
	}

	@Async("generationExecutor")
	void process(ReportExportQueuedEvent event) {
		Optional<ReportExportService.ExportInput> input = exports.start(event.exportId());
		if (input.isEmpty()) {
			return;
		}
		try {
			Path path = renderer.render(event.exportId(), input.get().document());
			if (!exports.complete(event.exportId(), Files.size(path))) {
				renderer.delete(event.exportId());
			}
		} catch (Exception exception) {
			log.error("PDF export failed: exportId={}", event.exportId(), exception);
			renderer.delete(event.exportId());
			exports.fail(event.exportId(), "PDF_GENERATION_FAILED", "PDF 생성에 실패했습니다.");
		}
	}
}
