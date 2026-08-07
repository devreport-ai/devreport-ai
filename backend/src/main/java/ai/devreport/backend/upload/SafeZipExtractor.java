package ai.devreport.backend.upload;

import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
class SafeZipExtractor {

	private static final long MAX_EXTRACTED_SIZE = 100L * 1024 * 1024;
	private static final int MAX_FILE_COUNT = 1_000;
	private static final Set<String> EXCLUDED_DIRECTORIES = Set.of(".git", "node_modules", "build", ".gradle");
	private static final Set<String> CERTIFICATE_EXTENSIONS = Set.of(
		"pem", "key", "p12", "pfx", "jks", "keystore", "crt", "cer", "der");
	private static final Set<String> EXECUTABLE_EXTENSIONS = Set.of(
		"exe", "dll", "so", "dylib", "bin", "com", "msi", "apk", "jar", "war", "class",
		"sh", "bat", "cmd", "ps1");
	private static final Set<String> ANALYSIS_EXTENSIONS = Set.of(
		"java", "kt", "py", "js", "jsx", "ts", "tsx", "html", "css", "scss", "sql", "xml",
		"json", "yaml", "yml", "md", "txt", "gradle", "properties", "toml", "go", "rs", "c",
		"h", "cpp", "hpp", "cs", "php", "rb", "swift", "dart", "vue", "svelte");

	void extract(Path zipPath, Path targetDirectory) throws IOException {
		Path target = targetDirectory.toAbsolutePath().normalize();
		Files.createDirectory(target);
		try {
			extractEntries(zipPath, target);
		} catch (ProjectFileException | IOException exception) {
			try {
				deleteRecursively(target);
			} catch (IOException cleanupException) {
				exception.addSuppressed(cleanupException);
			}
			throw exception;
		}
	}

	private static void extractEntries(Path zipPath, Path target) throws IOException {
		long totalSize = 0;
		int fileCount = 0;
		byte[] buffer = new byte[8192];
		Set<Path> entryPaths = new HashSet<>();
		try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(zipPath))) {
			ZipEntry entry;
			while ((entry = zip.getNextEntry()) != null) {
				Path output = safeOutputPath(target, entry.getName());
				if (!entryPaths.add(output)) {
					throw invalidZip(new ZipException("Duplicate ZIP entry path"));
				}
				if (++fileCount > MAX_FILE_COUNT) {
					throw limitExceeded();
				}
				if (entry.isDirectory()) {
					continue;
				}

				String filename = output.getFileName().toString().toLowerCase(Locale.ROOT);
				String extension = extension(filename);
				if (isBlocked(filename, extension)) {
					throw blockedContent();
				}

				boolean selected = !hasExcludedDirectory(target.relativize(output))
					&& ANALYSIS_EXTENSIONS.contains(extension);
				if (selected) {
					Files.createDirectories(output.getParent());
				}
				try (OutputStream destination = selected
					? Files.newOutputStream(output, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
					: OutputStream.nullOutputStream()) {
					byte[] header = new byte[4];
					int headerSize = 0;
					String scanTail = "";
					int read;
					while ((read = zip.read(buffer)) != -1) {
						totalSize += read;
						if (totalSize > MAX_EXTRACTED_SIZE) {
							throw limitExceeded();
						}
						if (headerSize < header.length) {
							int copied = Math.min(read, header.length - headerSize);
							System.arraycopy(buffer, 0, header, headerSize, copied);
							headerSize += copied;
						}
						scanTail = scanPemContent(scanTail, buffer, read);
						destination.write(buffer, 0, read);
					}
					if (isExecutable(header, headerSize)) {
						throw blockedContent();
					}
				}
			}
		} catch (EOFException | ZipException | FileAlreadyExistsException exception) {
			throw invalidZip(exception);
		}
	}

	private static Path safeOutputPath(Path target, String entryName) {
		if (entryName == null || entryName.indexOf('\0') >= 0) {
			throw invalidPath();
		}
		String normalizedName = entryName.replace('\\', '/');
		if (normalizedName.startsWith("/") || normalizedName.matches("^[A-Za-z]:/.*")) {
			throw invalidPath();
		}
		try {
			Path output = target.resolve(normalizedName).normalize();
			if (!output.startsWith(target)) {
				throw invalidPath();
			}
			return output;
		} catch (InvalidPathException exception) {
			throw invalidPath();
		}
	}

	private static boolean hasExcludedDirectory(Path relativePath) {
		for (Path part : relativePath) {
			if (EXCLUDED_DIRECTORIES.contains(part.toString().toLowerCase(Locale.ROOT))) {
				return true;
			}
		}
		return false;
	}

	private static boolean isBlocked(String filename, String extension) {
		return filename.startsWith(".env")
			|| CERTIFICATE_EXTENSIONS.contains(extension) || EXECUTABLE_EXTENSIONS.contains(extension);
	}

	private static String extension(String filename) {
		int separator = filename.lastIndexOf('.');
		return separator < 0 ? "" : filename.substring(separator + 1);
	}

	private static String scanPemContent(String tail, byte[] bytes, int size) {
		String scanned = tail + new String(bytes, 0, size, StandardCharsets.ISO_8859_1);
		if (scanned.contains("-----BEGIN CERTIFICATE-----") || scanned.contains("PRIVATE KEY-----")) {
			throw blockedContent();
		}
		return scanned.substring(Math.max(0, scanned.length() - 32));
	}

	private static boolean isExecutable(byte[] header, int size) {
		if (size >= 2 && header[0] == 'M' && header[1] == 'Z') {
			return true;
		}
		if (size >= 4) {
			int magic = (header[0] & 0xff) << 24 | (header[1] & 0xff) << 16
				| (header[2] & 0xff) << 8 | header[3] & 0xff;
			if (magic == 0x7f454c46 || magic == 0xfeedface || magic == 0xfeedfacf
				|| magic == 0xcefaedfe || magic == 0xcffaedfe
				|| magic == 0xcafebabe || magic == 0xbebafeca
				|| magic == 0xcafebabf || magic == 0xbfbafeca) {
				return true;
			}
		}
		return false;
	}

	private static void deleteRecursively(Path directory) throws IOException {
		if (Files.notExists(directory)) {
			return;
		}
		try (var paths = Files.walk(directory)) {
			for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(path);
			}
		}
	}

	private static ProjectFileException invalidPath() {
		return new ProjectFileException(HttpStatus.BAD_REQUEST, "ZIP_PATH_INVALID",
			"ZIP 내부에 허용되지 않는 파일 경로가 있습니다.");
	}

	private static ProjectFileException limitExceeded() {
		return new ProjectFileException(HttpStatus.CONTENT_TOO_LARGE, "ZIP_LIMIT_EXCEEDED",
			"ZIP 압축 해제 용량은 100 MiB, 내부 엔트리는 1,000개를 초과할 수 없습니다.");
	}

	private static ProjectFileException blockedContent() {
		return new ProjectFileException(HttpStatus.UNPROCESSABLE_CONTENT, "ZIP_BLOCKED_CONTENT",
			"ZIP에 환경변수, 인증서·키 또는 실행 파일이 포함되어 있습니다.");
	}

	private static ProjectFileException invalidZip(IOException cause) {
		ProjectFileException exception = new ProjectFileException(HttpStatus.BAD_REQUEST, "ZIP_INVALID",
			"ZIP 파일을 해제할 수 없습니다.");
		exception.initCause(cause);
		return exception;
	}
}
