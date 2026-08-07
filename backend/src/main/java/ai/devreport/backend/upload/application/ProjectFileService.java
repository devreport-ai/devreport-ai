package ai.devreport.backend.upload.application;

import ai.devreport.backend.upload.domain.ProjectFileException;
import ai.devreport.backend.upload.domain.UploadedFile;
import ai.devreport.backend.upload.infrastructure.SafeZipExtractor;
import ai.devreport.backend.upload.infrastructure.UploadedFileRepository;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

import ai.devreport.backend.project.application.ProjectService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

@Service
@Transactional
public class ProjectFileService {

	private static final Logger log = LoggerFactory.getLogger(ProjectFileService.class);
	private static final long MAX_FILE_SIZE = 20L * 1024 * 1024;
	private static final byte[] PDF_SIGNATURE = "%PDF-".getBytes(StandardCharsets.US_ASCII);
	private static final byte[] PNG_SIGNATURE = {
		(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
	};
	private static final byte[] JPEG_SIGNATURE = {(byte) 0xff, (byte) 0xd8, (byte) 0xff};
	private static final Set<String> ZIP_MIME_TYPES = Set.of("application/zip", "application/x-zip-compressed");

	private final UploadedFileRepository files;
	private final ProjectService projects;
	private final SafeZipExtractor zipExtractor;
	private final Path uploadRoot;

	ProjectFileService(UploadedFileRepository files, ProjectService projects, SafeZipExtractor zipExtractor,
		@Value("${storage.upload-path}") String uploadPath) {
		this.files = files;
		this.projects = projects;
		this.zipExtractor = zipExtractor;
		this.uploadRoot = Path.of(uploadPath).toAbsolutePath().normalize();
	}

	public UploadedFile upload(UUID ownerId, UUID projectId, MultipartFile multipartFile) {
		projects.requireOwned(ownerId, projectId);
		String originalName = originalName(multipartFile);
		String extension = extension(originalName);
		String contentType = contentType(multipartFile);
		validateMetadata(multipartFile, extension, contentType);

		Path temporary = null;
		Path extractionTemporary = null;
		try {
			Path projectDirectory = projectDirectory(projectId);
			Files.createDirectories(projectDirectory);
			temporary = Files.createTempFile(projectDirectory, ".upload-", ".tmp");
			multipartFile.transferTo(temporary);
			validateContent(temporary, extension);
			long storedSize = Files.size(temporary);
			if (storedSize != multipartFile.getSize()) {
				throw storageError(null);
			}
			UploadedFile uploadedFile = new UploadedFile(projectId, originalName, contentType, storedSize);
			if (extension.equals("zip")) {
				extractionTemporary = projectDirectory.resolve("." + uploadedFile.getStoredName() + ".extracting");
				zipExtractor.extract(temporary, extractionTemporary);
			}
			Path stored = move(temporary, projectDirectory.resolve(uploadedFile.getStoredName()));
			temporary = null;
			deleteOnRollback(stored);
			if (extractionTemporary != null) {
				Path extracted = move(extractionTemporary, extractedPath(uploadedFile));
				extractionTemporary = null;
				deleteDirectoryOnRollback(extracted);
			}
			return files.save(uploadedFile);
		} catch (ProjectFileException exception) {
			throw exception;
		} catch (IOException exception) {
			throw storageError(exception);
		} finally {
			deleteQuietly(temporary);
			deleteDirectoryQuietly(extractionTemporary);
		}
	}

	@Transactional(readOnly = true)
	public Page<UploadedFile> list(UUID ownerId, UUID projectId, int page, int size) {
		projects.requireOwned(ownerId, projectId);
		Sort sort = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
		Page<UploadedFile> uploadedFiles = files.findAllByProjectId(projectId, PageRequest.of(page, size, sort));
		uploadedFiles.getContent().forEach(file -> {
			if (!isStoredFileConsistent(file)) {
				log.error("Stored file is inconsistent with metadata: projectId={}, fileId={}",
					projectId, file.getId());
			}
		});
		return uploadedFiles;
	}

	public void stageProjectPurge(UUID projectId) {
		Path directory = projectDirectory(projectId);
		if (Files.notExists(directory)) {
			if (files.existsByProjectId(projectId)) {
				throw storageError(null);
			}
			return;
		}
		Path staged = uploadRoot.resolve(".purge-" + projectId + "-" + UUID.randomUUID());
		try {
			move(directory, staged);
		} catch (IOException exception) {
			throw storageError(exception);
		}
		deleteDirectoryOnCommit(staged, directory);
	}

	public void delete(UUID ownerId, UUID projectId, UUID fileId) {
		projects.requireOwned(ownerId, projectId);
		UploadedFile uploadedFile = files.findByIdAndProjectId(fileId, projectId)
			.orElseThrow(ProjectFileService::fileNotFound);
		Path stored = storedPath(uploadedFile);
		if (Files.notExists(stored)) {
			log.error("Stored file is missing; deleting metadata: projectId={}, fileId={}", projectId, fileId);
			stageExtractedDeletion(uploadedFile);
			files.delete(uploadedFile);
			return;
		}
		if (!Files.isRegularFile(stored)) {
			throw storageError(null);
		}

		Path staged = stored.resolveSibling("." + stored.getFileName() + ".deleting");
		try {
			move(stored, staged);
		} catch (IOException exception) {
			throw storageError(exception);
		}
		restoreOnRollback(staged, stored);
		stageExtractedDeletion(uploadedFile);
		files.delete(uploadedFile);
	}

	private static String originalName(MultipartFile file) {
		String name = file.getOriginalFilename();
		if (name == null) {
			throw invalidFile("FILE_NAME_INVALID", "파일 이름을 확인할 수 없습니다.");
		}
		name = name.replace('\\', '/');
		name = name.substring(name.lastIndexOf('/') + 1).trim();
		if (name.isEmpty() || name.length() > 255) {
			throw invalidFile("FILE_NAME_INVALID", "파일 이름은 1자 이상 255자 이하여야 합니다.");
		}
		return name;
	}

	private static String extension(String name) {
		int separator = name.lastIndexOf('.');
		return separator < 0 ? "" : name.substring(separator + 1).toLowerCase(Locale.ROOT);
	}

	private static String contentType(MultipartFile file) {
		String contentType = file.getContentType();
		return contentType == null ? "" : contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
	}

	private static void validateMetadata(MultipartFile file, String extension, String contentType) {
		if (file.isEmpty()) {
			throw invalidFile("FILE_EMPTY", "빈 파일은 업로드할 수 없습니다.");
		}
		if (file.getSize() > MAX_FILE_SIZE) {
			throw new ProjectFileException(HttpStatus.CONTENT_TOO_LARGE, "FILE_TOO_LARGE",
				"파일 크기는 20 MiB 이하여야 합니다.");
		}

		boolean allowed = switch (extension) {
			case "pdf" -> contentType.equals("application/pdf");
			case "docx" -> contentType.equals(
				"application/vnd.openxmlformats-officedocument.wordprocessingml.document");
			case "txt" -> contentType.equals("text/plain");
			case "md" -> contentType.equals("text/markdown") || contentType.equals("text/plain");
			case "zip" -> ZIP_MIME_TYPES.contains(contentType);
			case "jpg", "jpeg" -> contentType.equals("image/jpeg");
			case "png" -> contentType.equals("image/png");
			default -> false;
		};
		if (!allowed) {
			throw typeNotAllowed();
		}
	}

	private static void validateContent(Path path, String extension) throws IOException {
		boolean valid = switch (extension) {
			case "pdf" -> startsWith(path, PDF_SIGNATURE);
			case "docx" -> isDocx(path);
			case "txt", "md" -> isUtf8Text(path);
			case "zip" -> isZip(path);
			case "jpg", "jpeg" -> startsWith(path, JPEG_SIGNATURE);
			case "png" -> startsWith(path, PNG_SIGNATURE);
			default -> false;
		};
		if (!valid) {
			throw typeNotAllowed();
		}
	}

	private static boolean startsWith(Path path, byte[] signature) throws IOException {
		try (var input = Files.newInputStream(path)) {
			return Arrays.equals(input.readNBytes(signature.length), signature);
		}
	}

	private static boolean isZip(Path path) throws IOException {
		try (ZipFile ignored = new ZipFile(path.toFile())) {
			return true;
		} catch (ZipException exception) {
			return false;
		}
	}

	private static boolean isDocx(Path path) throws IOException {
		try (ZipFile zip = new ZipFile(path.toFile())) {
			return zip.getEntry("[Content_Types].xml") != null && zip.getEntry("word/document.xml") != null;
		} catch (ZipException exception) {
			return false;
		}
	}

	private static boolean isUtf8Text(Path path) throws IOException {
		var decoder = StandardCharsets.UTF_8.newDecoder()
			.onMalformedInput(CodingErrorAction.REPORT)
			.onUnmappableCharacter(CodingErrorAction.REPORT);
		try (Reader reader = java.nio.channels.Channels.newReader(
			Files.newByteChannel(path, StandardOpenOption.READ), decoder, -1)) {
			char[] buffer = new char[4096];
			int count;
			while ((count = reader.read(buffer)) != -1) {
				for (int index = 0; index < count; index++) {
					if (buffer[index] == '\0') {
						return false;
					}
				}
			}
			return true;
		} catch (java.nio.charset.CharacterCodingException exception) {
			return false;
		}
	}

	private Path projectDirectory(UUID projectId) {
		return uploadRoot.resolve(projectId.toString());
	}

	private Path storedPath(UploadedFile file) {
		return projectDirectory(file.getProjectId()).resolve(file.getStoredName());
	}

	private Path extractedPath(UploadedFile file) {
		return projectDirectory(file.getProjectId()).resolve(file.getStoredName() + ".extracted");
	}

	private void stageExtractedDeletion(UploadedFile file) {
		Path extracted = extractedPath(file);
		if (Files.notExists(extracted)) {
			return;
		}
		if (!Files.isDirectory(extracted)) {
			throw storageError(null);
		}
		Path staged = extracted.resolveSibling("." + extracted.getFileName() + ".deleting");
		try {
			move(extracted, staged);
		} catch (IOException exception) {
			throw storageError(exception);
		}
		deleteDirectoryOnCommit(staged, extracted);
	}

	private boolean isStoredFileConsistent(UploadedFile file) {
		Path path = storedPath(file);
		try {
			return Files.isRegularFile(path) && Files.size(path) == file.getSize();
		} catch (IOException exception) {
			return false;
		}
	}

	private static Path move(Path source, Path target) throws IOException {
		try {
			return Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException exception) {
			return Files.move(source, target);
		}
	}

	private static void deleteOnRollback(Path path) {
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCompletion(int status) {
				if (status != STATUS_COMMITTED) {
					deleteQuietly(path);
				}
			}
		});
	}

	private static void restoreOnRollback(Path staged, Path original) {
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCompletion(int status) {
				try {
					if (status == STATUS_COMMITTED) {
						Files.deleteIfExists(staged);
					} else if (Files.exists(staged)) {
						move(staged, original);
					}
				} catch (IOException exception) {
					log.error("Failed to finalize uploaded file deletion: {}", staged, exception);
				}
			}
		});
	}

	private static void deleteDirectoryOnCommit(Path staged, Path original) {
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCompletion(int status) {
				try {
					if (status == STATUS_COMMITTED) {
						deleteRecursively(staged);
					} else if (Files.exists(staged)) {
						move(staged, original);
					}
				} catch (IOException exception) {
					log.error("Failed to finalize project file purge: {}", staged, exception);
				}
			}
		});
	}

	private static void deleteDirectoryOnRollback(Path directory) {
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCompletion(int status) {
				if (status != STATUS_COMMITTED) {
					deleteDirectoryQuietly(directory);
				}
			}
		});
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

	private static void deleteQuietly(Path path) {
		if (path == null) {
			return;
		}
		try {
			Files.deleteIfExists(path);
		} catch (IOException exception) {
			log.warn("Failed to clean up uploaded file: {}", path, exception);
		}
	}

	private static void deleteDirectoryQuietly(Path directory) {
		if (directory == null) {
			return;
		}
		try {
			deleteRecursively(directory);
		} catch (IOException exception) {
			log.warn("Failed to clean up extracted files: {}", directory, exception);
		}
	}

	private static ProjectFileException invalidFile(String code, String message) {
		return new ProjectFileException(HttpStatus.BAD_REQUEST, code, message);
	}

	private static ProjectFileException typeNotAllowed() {
		return new ProjectFileException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "FILE_TYPE_NOT_ALLOWED",
			"PDF, DOCX, TXT, MD, ZIP, JPG, JPEG, PNG 파일만 업로드할 수 있습니다.");
	}

	private static ProjectFileException fileNotFound() {
		return new ProjectFileException(HttpStatus.NOT_FOUND, "FILE_NOT_FOUND", "파일을 찾을 수 없습니다.");
	}

	private static ProjectFileException storageError(Exception cause) {
		ProjectFileException exception = new ProjectFileException(HttpStatus.INTERNAL_SERVER_ERROR,
			"FILE_STORAGE_ERROR", "파일 저장소를 처리할 수 없습니다.");
		if (cause != null) {
			exception.initCause(cause);
		}
		return exception;
	}
}
