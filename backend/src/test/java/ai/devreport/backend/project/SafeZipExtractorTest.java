package ai.devreport.backend.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SafeZipExtractorTest {

	@TempDir
	Path temporaryDirectory;

	private final SafeZipExtractor extractor = new SafeZipExtractor();

	@Test
	void extractsOnlyAnalysisFilesAndExcludedDirectoriesAreSkipped() throws Exception {
		Path zip = zip("allowed.zip", Map.of(
			"src/Main.java", "class Main {}".getBytes(StandardCharsets.UTF_8),
			"README.md", "# Project".getBytes(StandardCharsets.UTF_8),
			".git/config", "ignored".getBytes(StandardCharsets.UTF_8),
			"node_modules/lib.js", "ignored".getBytes(StandardCharsets.UTF_8),
			"build/Generated.java", "ignored".getBytes(StandardCharsets.UTF_8),
			".gradle/cache.txt", "ignored".getBytes(StandardCharsets.UTF_8),
			"assets/logo.png", new byte[] {1, 2, 3}
		));
		Path target = temporaryDirectory.resolve("extracted");

		extractor.extract(zip, target);

		assertThat(target.resolve("src/Main.java")).hasContent("class Main {}");
		assertThat(target.resolve("README.md")).hasContent("# Project");
		assertThat(target.resolve(".git/config")).doesNotExist();
		assertThat(target.resolve("node_modules/lib.js")).doesNotExist();
		assertThat(target.resolve("build/Generated.java")).doesNotExist();
		assertThat(target.resolve(".gradle/cache.txt")).doesNotExist();
		assertThat(target.resolve("assets/logo.png")).doesNotExist();
	}

	@Test
	void rejectsZipSlipAndCleansExtractionDirectory() throws Exception {
		Path zip = zip("zip-slip.zip", Map.of("../outside.java", new byte[] {1}));
		Path target = temporaryDirectory.resolve("zip-slip-target");

		assertThatThrownBy(() -> extractor.extract(zip, target))
			.isInstanceOfSatisfying(ProjectFileException.class,
				exception -> assertThat(exception.code()).isEqualTo("ZIP_PATH_INVALID"));
		assertThat(target).doesNotExist();
		assertThat(temporaryDirectory.resolve("outside.java")).doesNotExist();
	}

	@Test
	void doesNotDeletePreexistingTargetWhenExtractionCannotStart() throws Exception {
		Path zip = zip("existing-target.zip", Map.of("Main.java", new byte[] {1}));
		Path target = Files.createDirectory(temporaryDirectory.resolve("existing-target"));
		Path marker = Files.writeString(target.resolve("marker.txt"), "keep");

		assertThatThrownBy(() -> extractor.extract(zip, target))
			.isInstanceOf(IOException.class);
		assertThat(marker).hasContent("keep");
	}

	@Test
	void rejectsBlockedFilesAndExecutableContent() throws Exception {
		Map<String, byte[]> blockedFiles = new LinkedHashMap<>();
		blockedFiles.put(".env.local", "SECRET=value".getBytes(StandardCharsets.UTF_8));
		blockedFiles.put("certificate.pem", "certificate".getBytes(StandardCharsets.UTF_8));
		blockedFiles.put("run.exe", new byte[] {'M', 'Z', 0, 0});
		blockedFiles.put("renamed.txt", new byte[] {0x7f, 'E', 'L', 'F'});
		blockedFiles.put("secret.txt", (" ".repeat(10_000) + "-----BEGIN PRIVATE KEY-----")
			.getBytes(StandardCharsets.US_ASCII));

		int index = 0;
		for (Map.Entry<String, byte[]> blocked : blockedFiles.entrySet()) {
			Path zip = zip("blocked-" + index + ".zip", Map.of(blocked.getKey(), blocked.getValue()));
			Path target = temporaryDirectory.resolve("blocked-target-" + index++);
			assertThatThrownBy(() -> extractor.extract(zip, target))
				.isInstanceOfSatisfying(ProjectFileException.class,
					exception -> assertThat(exception.code()).isEqualTo("ZIP_BLOCKED_CONTENT"));
			assertThat(target).doesNotExist();
		}
	}

	@Test
	void rejectsTooManyFilesAndExpandedZipBomb() throws Exception {
		Path tooManyFiles = temporaryDirectory.resolve("too-many-files.zip");
		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(tooManyFiles))) {
			for (int index = 0; index <= 1_000; index++) {
				zip.putNextEntry(new ZipEntry("src/File" + index + ".java"));
				zip.closeEntry();
			}
		}
		assertLimitExceeded(tooManyFiles, temporaryDirectory.resolve("too-many-target"));

		Path zipBomb = temporaryDirectory.resolve("zip-bomb.zip");
		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(zipBomb))) {
			zip.putNextEntry(new ZipEntry("large.dat"));
			byte[] zeros = new byte[8192];
			long remaining = 100L * 1024 * 1024 + 1;
			while (remaining > 0) {
				int size = (int) Math.min(zeros.length, remaining);
				zip.write(zeros, 0, size);
				remaining -= size;
			}
			zip.closeEntry();
		}
		assertLimitExceeded(zipBomb, temporaryDirectory.resolve("zip-bomb-target"));
	}

	private void assertLimitExceeded(Path zip, Path target) {
		assertThatThrownBy(() -> extractor.extract(zip, target))
			.isInstanceOfSatisfying(ProjectFileException.class,
				exception -> assertThat(exception.code()).isEqualTo("ZIP_LIMIT_EXCEEDED"));
		assertThat(target).doesNotExist();
	}

	private Path zip(String filename, Map<String, byte[]> entries) throws IOException {
		Path path = temporaryDirectory.resolve(filename);
		try (OutputStream output = Files.newOutputStream(path); ZipOutputStream zip = new ZipOutputStream(output)) {
			for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
				zip.putNextEntry(new ZipEntry(entry.getKey()));
				zip.write(entry.getValue());
				zip.closeEntry();
			}
		}
		return path;
	}
}
