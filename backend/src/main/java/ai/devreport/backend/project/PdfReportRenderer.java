package ai.devreport.backend.project;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component
class PdfReportRenderer {

	private static final Logger log = LoggerFactory.getLogger(PdfReportRenderer.class);

	private final Path exportRoot;

	PdfReportRenderer(@Value("${storage.export-path}") String exportPath) {
		this.exportRoot = Path.of(exportPath).toAbsolutePath().normalize();
	}

	Path render(UUID exportId, Map<String, Object> document) throws IOException {
		Files.createDirectories(exportRoot);
		Path target = path(exportId);
		Path temporary = Files.createTempFile(exportRoot, ".export-", ".tmp");
		try (PDDocument pdf = new PDDocument();
			InputStream fontStream = new ClassPathResource("fonts/NanumGothic-Regular.ttf").getInputStream()) {
			PDType0Font font = PDType0Font.load(pdf, fontStream);
			Map<String, Object> metadata = map(document.get("metadata"));
			pdf.getDocumentInformation().setTitle(text(metadata.get("title")));
			try (Writer writer = new Writer(pdf, font)) {
				writer.write(text(metadata.get("title")), 22, 30);
				writeMetadata(writer, metadata);
				for (Object sectionValue : list(document.get("sections"))) {
					Map<String, Object> section = map(sectionValue);
					writer.write(text(section.get("title")), 16, 24);
					for (Object blockValue : list(section.get("blocks"))) {
						writeBlock(writer, map(blockValue));
					}
				}
			}
			pdf.save(temporary.toFile());
			move(temporary, target);
			return target;
		} finally {
			Files.deleteIfExists(temporary);
		}
	}

	Path path(UUID exportId) {
		return exportRoot.resolve(exportId + ".pdf");
	}

	boolean delete(UUID exportId) {
		try {
			Files.deleteIfExists(path(exportId));
			return true;
		} catch (IOException exception) {
			log.error("PDF deletion failed: exportId={}", exportId, exception);
			return false;
		}
	}

	private static void writeMetadata(Writer writer, Map<String, Object> metadata) throws IOException {
		List<String> values = new ArrayList<>();
		for (String key : List.of("author", "course", "date")) {
			String value = text(metadata.get(key));
			if (!value.isBlank()) {
				values.add(value);
			}
		}
		if (!values.isEmpty()) {
			writer.write(String.join(" · ", values), 10, 18);
		}
	}

	private static void writeBlock(Writer writer, Map<String, Object> block) throws IOException {
		String type = text(block.get("type"));
		switch (type) {
			case "pageBreak" -> writer.pageBreak();
			case "code" -> writer.write(text(block.get("text")), 9, 14);
			case "image" -> writer.write("[이미지] " + text(block.get("caption")), 10, 16);
			case "paragraph" -> writer.write(text(block.get("text")), 11, 18);
			default -> writer.write(collectText(block), 11, 18);
		}
	}

	private static String collectText(Object value) {
		if (value instanceof String string) {
			return string;
		}
		if (value instanceof List<?> values) {
			return String.join("\n", values.stream().map(PdfReportRenderer::collectText)
				.filter(text -> !text.isBlank()).toList());
		}
		if (value instanceof Map<?, ?> values) {
			return String.join("\n", values.entrySet().stream()
				.filter(entry -> !entry.getKey().equals("type") && !entry.getKey().equals("fileId"))
				.map(Map.Entry::getValue).map(PdfReportRenderer::collectText)
				.filter(text -> !text.isBlank()).toList());
		}
		return value == null ? "" : value.toString();
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> map(Object value) {
		return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
	}

	private static List<?> list(Object value) {
		return value instanceof List<?> list ? list : List.of();
	}

	private static String text(Object value) {
		return value == null ? "" : value.toString();
	}

	private static void move(Path source, Path target) throws IOException {
		try {
			Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException exception) {
			Files.move(source, target);
		}
	}

	private static final class Writer implements AutoCloseable {

		private static final float MARGIN = 50;
		private static final float WIDTH = PDRectangle.A4.getWidth() - 2 * MARGIN;

		private final PDDocument document;
		private final PDType0Font font;
		private PDPageContentStream content;
		private float y;

		private Writer(PDDocument document, PDType0Font font) throws IOException {
			this.document = document;
			this.font = font;
			pageBreak();
		}

		private void write(String value, float fontSize, float leading) throws IOException {
			for (String paragraph : value.split("\\R", -1)) {
				for (String line : wrap(paragraph, fontSize)) {
					if (y < MARGIN + leading) {
						pageBreak();
					}
					content.beginText();
					content.setFont(font, fontSize);
					content.newLineAtOffset(MARGIN, y);
					content.showText(line);
					content.endText();
					y -= leading;
				}
			}
			y -= 4;
		}

		private List<String> wrap(String value, float fontSize) throws IOException {
			if (value.isEmpty()) {
				return List.of("");
			}
			List<String> lines = new ArrayList<>();
			StringBuilder line = new StringBuilder();
			for (int offset = 0; offset < value.length();) {
				int codePoint = value.codePointAt(offset);
				String character = new String(Character.toChars(codePoint));
				if (!line.isEmpty() && width(line + character, fontSize) > WIDTH) {
					lines.add(line.toString());
					line.setLength(0);
				}
				line.append(character);
				offset += Character.charCount(codePoint);
			}
			lines.add(line.toString());
			return lines;
		}

		private float width(CharSequence value, float fontSize) throws IOException {
			return font.getStringWidth(value.toString()) / 1000 * fontSize;
		}

		private void pageBreak() throws IOException {
			if (content != null) {
				content.close();
			}
			PDPage page = new PDPage(PDRectangle.A4);
			document.addPage(page);
			content = new PDPageContentStream(document, page);
			y = PDRectangle.A4.getHeight() - MARGIN;
		}

		@Override
		public void close() throws IOException {
			content.close();
		}
	}
}
