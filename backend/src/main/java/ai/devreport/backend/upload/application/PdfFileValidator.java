package ai.devreport.backend.upload.application;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import ai.devreport.backend.upload.domain.ProjectFileException;
import org.springframework.http.HttpStatus;

final class PdfFileValidator {

	static final int MAX_PAGES = 200;
	private static final byte[] PDF_HEADER = "%PDF-".getBytes(StandardCharsets.US_ASCII);
	private static final String EOF_MARKER = "%%EOF";
	private static final Pattern CATALOG = Pattern.compile("/Type\\s*/Catalog(?:\\s|/|>)");
	private static final Pattern PAGE = Pattern.compile("/Type\\s*/Page(?:\\s|/|>)");
	private static final Pattern XREF_STREAM = Pattern.compile("/Type\\s*/XRef(?:\\s|/|>)");

	private PdfFileValidator() {
	}

	static void validate(Path path) throws IOException {
		// ponytail: lightweight structure check; add a full PDF parser if semantic validation is needed.
		byte[] bytes = Files.readAllBytes(path);
		String content = new String(bytes, StandardCharsets.ISO_8859_1);
		if (content.contains("/Encrypt")) {
			throw invalid(HttpStatus.UNPROCESSABLE_ENTITY, "PDF_ENCRYPTED", "암호화된 PDF는 업로드할 수 없습니다.");
		}
		if (!startsWith(bytes, PDF_HEADER)) {
			throw invalid(HttpStatus.BAD_REQUEST, "PDF_INVALID", "손상되었거나 올바르지 않은 PDF입니다.");
		}

		int eof = content.lastIndexOf(EOF_MARKER);
		if (eof < 0 || !content.substring(eof + EOF_MARKER.length()).trim().isEmpty()
			|| !CATALOG.matcher(content).find() || !hasCrossReference(content, eof)) {
			throw invalid(HttpStatus.BAD_REQUEST, "PDF_INVALID", "손상되었거나 올바르지 않은 PDF입니다.");
		}

		int pages = count(PAGE, content);
		if (pages == 0) {
			throw invalid(HttpStatus.BAD_REQUEST, "PDF_INVALID", "페이지가 없는 PDF는 업로드할 수 없습니다.");
		}
		if (pages > MAX_PAGES) {
			throw invalid(HttpStatus.CONTENT_TOO_LARGE, "PDF_PAGE_LIMIT_EXCEEDED",
				"PDF는 최대 " + MAX_PAGES + "페이지까지 업로드할 수 있습니다.");
		}
	}

	private static boolean hasCrossReference(String content, int eof) {
		int startxref = content.lastIndexOf("startxref");
		if (startxref < 0 || startxref >= eof) {
			return false;
		}
		String offset = content.substring(startxref + "startxref".length(), eof).trim();
		int end = 0;
		while (end < offset.length() && Character.isDigit(offset.charAt(end))) {
			end++;
		}
		if (end == 0) {
			return false;
		}
		try {
			long value = Long.parseLong(offset.substring(0, end));
			if (value < 0 || value >= eof) {
				return false;
			}
			int index = Math.toIntExact(value);
			boolean classicXref = content.startsWith("xref", index);
			boolean streamXref = XREF_STREAM.matcher(content.substring(index, eof)).find();
			return classicXref || streamXref;
		} catch (NumberFormatException ignored) {
			return false;
		}
	}

	private static int count(Pattern pattern, String content) {
		var matcher = pattern.matcher(content);
		int count = 0;
		while (matcher.find()) {
			count++;
		}
		return count;
	}

	private static boolean startsWith(byte[] value, byte[] prefix) {
		if (value.length < prefix.length) {
			return false;
		}
		for (int index = 0; index < prefix.length; index++) {
			if (value[index] != prefix[index]) {
				return false;
			}
		}
		return true;
	}

	private static ProjectFileException invalid(HttpStatus status, String code, String message) {
		return new ProjectFileException(status, code, message);
	}
}
