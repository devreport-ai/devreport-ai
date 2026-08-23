package ai.devreport.backend.upload.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import ai.devreport.backend.upload.domain.ProjectFileException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PdfFileValidatorTest {

	@TempDir
	Path temporaryDirectory;

	@Test
	void acceptsAValidSinglePagePdf() throws Exception {
		Path pdf = Files.write(temporaryDirectory.resolve("valid.pdf"), pdf());

		PdfFileValidator.validate(pdf);
	}

	@Test
	void rejectsIncompletePdf() throws Exception {
		Path pdf = Files.writeString(temporaryDirectory.resolve("broken.pdf"), "%PDF-1.4\n%%EOF");

		assertThatThrownBy(() -> PdfFileValidator.validate(pdf))
			.isInstanceOfSatisfying(ProjectFileException.class,
				exception -> assertThat(exception.code()).isEqualTo("PDF_INVALID"));
	}

	@Test
	void rejectsEncryptedPdf() throws Exception {
		Path pdf = Files.writeString(temporaryDirectory.resolve("encrypted.pdf"), """
			%PDF-1.4
			1 0 obj << /Type /Catalog /Pages 2 0 R >> endobj
			2 0 obj << /Type /Page /Parent 1 0 R >> endobj
			trailer << /Root 1 0 R /Encrypt 3 0 R >>
			startxref
			1
			%%EOF
			""");

		assertThatThrownBy(() -> PdfFileValidator.validate(pdf))
			.isInstanceOfSatisfying(ProjectFileException.class,
				exception -> assertThat(exception.code()).isEqualTo("PDF_ENCRYPTED"));
	}

	@Test
	void rejectsPdfWithTooManyPages() throws Exception {
		StringBuilder content = new StringBuilder("%PDF-1.4\n1 0 obj << /Type /Catalog /Pages 2 0 R >> endobj\n");
		for (int index = 0; index <= PdfFileValidator.MAX_PAGES; index++) {
			content.append("<< /Type /Page /Parent 2 0 R >>\n");
		}
		int xrefOffset = content.length();
		content.append("xref\ntrailer << /Root 1 0 R >>\nstartxref\n")
			.append(xrefOffset).append("\n%%EOF\n");
		Path pdf = Files.writeString(temporaryDirectory.resolve("large.pdf"), content.toString(),
			StandardCharsets.US_ASCII);

		assertThatThrownBy(() -> PdfFileValidator.validate(pdf))
			.isInstanceOfSatisfying(ProjectFileException.class,
				exception -> assertThat(exception.code()).isEqualTo("PDF_PAGE_LIMIT_EXCEEDED"));
	}

	private static byte[] pdf() {
		String[] objects = {
			"<< /Type /Catalog /Pages 2 0 R >>",
			"<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
			"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] >>"
		};
		StringBuilder content = new StringBuilder("%PDF-1.4\n");
		int[] offsets = new int[objects.length + 1];
		for (int index = 0; index < objects.length; index++) {
			offsets[index + 1] = content.length();
			content.append(index + 1).append(" 0 obj\n").append(objects[index]).append("\nendobj\n");
		}
		int xrefOffset = content.length();
		content.append("xref\n0 ").append(offsets.length).append("\n0000000000 65535 f \n");
		for (int index = 1; index < offsets.length; index++) {
			content.append("%010d 00000 n \n".formatted(offsets[index]));
		}
		content.append("trailer\n<< /Root 1 0 R /Size ").append(offsets.length)
			.append(" >>\nstartxref\n").append(xrefOffset).append("\n%%EOF\n");
		return content.toString().getBytes(StandardCharsets.US_ASCII);
	}
}
