package ai.devreport.backend.export.infrastructure;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.UUID;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class PdfReportRenderer {

	private static final Logger log = LoggerFactory.getLogger(PdfReportRenderer.class);
	private static final String EXPORT_ID_PLACEHOLDER = "{exportId}";
	private static final String READY_SELECTOR = "html[data-print-state=\"ready\"]";

	private final Path exportRoot;
	private final String printUrl;
	private final Duration renderTimeout;

	PdfReportRenderer(@Value("${storage.export-path}") String exportPath,
		@Value("${export.print-url}") String printUrl,
		@Value("${export.render-timeout}") Duration renderTimeout) {
		this.exportRoot = Path.of(exportPath).toAbsolutePath().normalize();
		this.printUrl = printUrl;
		this.renderTimeout = renderTimeout;
	}

	public Path render(UUID exportId, String renderToken) throws IOException {
		Files.createDirectories(exportRoot);
		Path target = path(exportId);
		Path temporary = Files.createTempFile(exportRoot, ".export-", ".tmp");
		try {
			renderPage(temporary, exportId, renderToken);
			move(temporary, target);
			return target;
		} finally {
			Files.deleteIfExists(temporary);
		}
	}

	public Path path(UUID exportId) {
		return exportRoot.resolve(exportId + ".pdf");
	}

	public boolean delete(UUID exportId) {
		try {
			Files.deleteIfExists(path(exportId));
			return true;
		} catch (IOException exception) {
			log.error("PDF deletion failed: exportId={}", exportId, exception);
			return false;
		}
	}

	private void renderPage(Path output, UUID exportId, String renderToken) throws IOException {
		try (Playwright playwright = Playwright.create()) {
			Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
			try {
				BrowserContext context = browser.newContext();
				try {
					Page page = context.newPage();
					if (!printUrl.contains(EXPORT_ID_PLACEHOLDER)) {
						throw new IOException("EXPORT_PRINT_URL에 {exportId}가 필요합니다.");
					}
					String url = printUrl.replace(EXPORT_ID_PLACEHOLDER, exportId.toString());
					page.navigate(withRenderToken(url, renderToken),
						new Page.NavigateOptions().setTimeout(renderTimeout.toMillis()));
					page.waitForSelector(READY_SELECTOR,
						new Page.WaitForSelectorOptions().setTimeout(renderTimeout.toMillis()));
					page.pdf(new Page.PdfOptions()
						.setPath(output)
						.setFormat("A4")
						.setPrintBackground(true)
						.setPreferCSSPageSize(true));
				} finally {
					context.close();
				}
			} finally {
				browser.close();
			}
		} catch (PlaywrightException exception) {
			throw new IOException("Chromium PDF 렌더링에 실패했습니다.", exception);
		}
	}

	private static String withRenderToken(String url, String renderToken) {
		int fragmentStart = url.indexOf('#');
		if (fragmentStart < 0) {
			return url + "#token=" + renderToken;
		}
		return url.substring(0, fragmentStart) + "#token=" + renderToken + "&" + url.substring(fragmentStart + 1);
	}

	private static void move(Path source, Path target) throws IOException {
		try {
			Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		} catch (AtomicMoveNotSupportedException exception) {
			Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
		}
	}
}
