package com.fitnesscoaching.platform.modules.measurement.application.service;

import com.fitnesscoaching.platform.common.exception.ApplicationValidationException;
import com.fitnesscoaching.platform.common.exception.FieldErrorDto;
import com.fitnesscoaching.platform.common.exception.ScannerUnavailableException;
import com.fitnesscoaching.platform.common.exception.StorageUnavailableException;
import com.fitnesscoaching.platform.modules.measurement.adapter.in.web.dto.ProgressPhotoPageResponse;
import com.fitnesscoaching.platform.modules.measurement.adapter.in.web.dto.ProgressPhotoResponse;
import com.fitnesscoaching.platform.modules.measurement.domain.MeasurementError;
import com.fitnesscoaching.platform.modules.measurement.domain.MeasurementFailure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.DataOutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

@Service
public class ProgressPhotoStorageService {

    private static final Logger log = LoggerFactory.getLogger(ProgressPhotoStorageService.class);
    public static final int MAX_FILE_SIZE = 10 * 1024 * 1024; // 10MB

    private final String endpoint;
    private final String bucket;
    private final String accessKey;
    private final String secretKey;
    private final String clamdHost;
    private final int clamdPort;
    private final Supplier<S3Client> s3ClientSupplier;
    private final NamedParameterJdbcTemplate jdbc;
    private final MeasurementAccessPolicy accessPolicy;

    @Autowired
    public ProgressPhotoStorageService(
            @Value("${chat.attachments.s3.endpoint:}") String endpoint,
            @Value("${chat.attachments.s3.bucket:}") String bucket,
            @Value("${chat.attachments.s3.access-key:}") String accessKey,
            @Value("${chat.attachments.s3.secret-key:}") String secretKey,
            @Value("${chat.attachments.clamd.host:}") String clamdHost,
            @Value("${chat.attachments.clamd.port:3310}") int clamdPort,
            NamedParameterJdbcTemplate jdbc,
            MeasurementAccessPolicy accessPolicy
    ) {
        this(endpoint, bucket, accessKey, secretKey, clamdHost, clamdPort, null, jdbc, accessPolicy);
    }

    public ProgressPhotoStorageService(
            String endpoint,
            String bucket,
            String accessKey,
            String secretKey,
            String clamdHost,
            int clamdPort,
            Supplier<S3Client> s3ClientSupplier,
            NamedParameterJdbcTemplate jdbc,
            MeasurementAccessPolicy accessPolicy
    ) {
        this.endpoint = endpoint;
        this.bucket = bucket;
        this.accessKey = accessKey;
        this.secretKey = secretKey;
        this.clamdHost = clamdHost;
        this.clamdPort = clamdPort;
        this.s3ClientSupplier = s3ClientSupplier;
        this.jdbc = jdbc;
        this.accessPolicy = accessPolicy;
    }

    public String validateAndDetectContentType(byte[] bytes, String filename) {
        if (bytes == null || bytes.length == 0) {
            throw new ApplicationValidationException("Uploaded photo cannot be empty",
                    List.of(new FieldErrorDto("file", "NotEmpty", "Uploaded photo cannot be empty")));
        }
        if (bytes.length > MAX_FILE_SIZE) {
            throw new ApplicationValidationException("File size exceeds 10MB limit",
                    List.of(new FieldErrorDto("file", "Size", "File size exceeds 10MB limit")));
        }
        if (filename == null || filename.isBlank()) {
            throw new ApplicationValidationException("File name cannot be empty",
                    List.of(new FieldErrorDto("file", "NotBlank", "File name cannot be empty")));
        }
        if (filename.contains("..") || filename.contains("/") || filename.contains("\\")) {
            throw new ApplicationValidationException("Invalid file name format",
                    List.of(new FieldErrorDto("file", "Pattern", "Invalid file name format")));
        }

        String detected;
        if (starts(bytes, new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff})) {
            detected = "image/jpeg";
        } else if (starts(bytes, new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a})) {
            detected = "image/png";
        } else {
            throw new ApplicationValidationException("Unsupported photo type. Only PNG and JPEG images are allowed",
                    List.of(new FieldErrorDto("file", "InvalidType", "Unsupported photo type. Only PNG and JPEG images are allowed")));
        }
        return detected;
    }

    public String scanAndVerifyClean(byte[] bytes) {
        if (clamdHost == null || clamdHost.isBlank()) {
            throw new ScannerUnavailableException("Antivirus scanner host is not configured");
        }
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(clamdHost, clamdPort), 3000);
            socket.setSoTimeout(15000);
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            out.write("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII));
            out.writeInt(bytes.length);
            out.write(bytes);
            out.writeInt(0);
            out.flush();
            byte[] reply = socket.getInputStream().readNBytes(512);
            String verdict = new String(reply, StandardCharsets.US_ASCII);
            if (verdict.startsWith("stream: OK")) {
                return "CLEAN";
            }
            if (verdict.contains("FOUND")) {
                throw new ApplicationValidationException("Malware detected in uploaded photo",
                        List.of(new FieldErrorDto("file", "MALWARE_DETECTED", "Malware detected in uploaded photo")));
            }
            log.warn("Unexpected clamd reply: {}", verdict);
            throw new ScannerUnavailableException("Unexpected reply from antivirus scanner: " + verdict);
        } catch (ApplicationValidationException e) {
            throw e;
        } catch (Exception ex) {
            log.warn("Could not connect to clamd host {}:{}: {}", clamdHost, clamdPort, ex.getMessage());
            throw new ScannerUnavailableException("Antivirus scanner is currently unavailable: " + ex.getMessage(), ex);
        }
    }

    public void storeFile(String objectKey, byte[] bytes, String contentType) {
        requireStorageConfigured();
        try (S3Client client = createS3Client()) {
            client.putObject(PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(objectKey)
                    .contentType(contentType)
                    .build(), RequestBody.fromBytes(bytes));
        } catch (Exception e) {
            log.error("S3 upload failed for key {}: {}", objectKey, e.getMessage());
            throw new StorageUnavailableException("Failed to store photo in object storage: " + e.getMessage(), e);
        }
    }

    public byte[] loadFile(String objectKey) {
        requireStorageConfigured();
        try (S3Client client = createS3Client()) {
            return client.getObjectAsBytes(GetObjectRequest.builder()
                    .bucket(bucket)
                    .key(objectKey)
                    .build()).asByteArray();
        } catch (NoSuchKeyException e) {
            throw new MeasurementFailure(MeasurementError.PHOTO_NOT_FOUND, "Photo not found in storage: " + objectKey);
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                throw new MeasurementFailure(MeasurementError.PHOTO_NOT_FOUND, "Photo not found in storage: " + objectKey);
            }
            log.error("S3 load failed for key {}: {}", objectKey, e.getMessage());
            throw new StorageUnavailableException("Failed to retrieve photo from object storage: " + e.getMessage(), e);
        } catch (Exception e) {
            log.error("Failed to load object from S3 for key {}: {}", objectKey, e.getMessage());
            throw new StorageUnavailableException("Failed to retrieve photo from object storage: " + e.getMessage(), e);
        }
    }

    public void deleteFile(String objectKey) {
        if (!isS3Configured()) {
            return;
        }
        try (S3Client client = createS3Client()) {
            client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(bucket)
                    .key(objectKey)
                    .build());
        } catch (Exception e) {
            log.warn("Failed to delete orphaned S3 object {}: {}", objectKey, e.getMessage());
        }
    }

    public String computeSha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (Exception e) {
            return "";
        }
    }

    @Transactional
    public ProgressPhotoResponse uploadProgressPhoto(
            UUID actorId,
            UUID targetStudentId,
            byte[] fileBytes,
            String filename,
            String viewType,
            Instant takenAt,
            BigDecimal weightAtTime,
            String weightUnitCode,
            String notes
    ) {
        if (actorId == null) {
            throw new MeasurementFailure(MeasurementError.VALIDATION_FAILED, "Actor ID must not be null");
        }
        UUID studentId = targetStudentId != null ? targetStudentId : actorId;
        accessPolicy.evaluatePhotoWriteAccess(actorId, studentId);

        String contentType = validateAndDetectContentType(fileBytes, filename);
        scanAndVerifyClean(fileBytes);

        String cleanViewType = viewType != null ? viewType.trim().toUpperCase() : "OTHER";
        List<String> validViewTypes = List.of("FRONT", "SIDE_LEFT", "SIDE_RIGHT", "BACK", "OTHER");
        if (!validViewTypes.contains(cleanViewType)) {
            cleanViewType = "OTHER";
        }

        Instant actualTakenAt = takenAt != null ? takenAt : Instant.now();
        if (actualTakenAt.isAfter(Instant.now().plusSeconds(300))) {
            throw new MeasurementFailure(MeasurementError.VALIDATION_FAILED, "Photo timestamp cannot be in the future");
        }

        Short weightUnitId = null;
        if (weightUnitCode != null && !weightUnitCode.isBlank()) {
            var unitRows = jdbc.query(
                    "SELECT id FROM fitness.measurement_units WHERE UPPER(code) = UPPER(:code)",
                    Map.of("code", weightUnitCode),
                    (rs, rowNum) -> rs.getShort("id")
            );
            if (!unitRows.isEmpty()) {
                weightUnitId = unitRows.get(0);
            }
        }

        UUID mediaId = UUID.randomUUID();
        String extension = "image/png".equals(contentType) ? "png" : "jpg";
        String objectKey = "progress-photos/" + studentId + "/" + mediaId + "." + extension;

        storeFile(objectKey, fileBytes, contentType);

        try {
            String sha256 = computeSha256(fileBytes);
            jdbc.update("""
                    INSERT INTO fitness.media_files(
                        id, owner_user_id, storage_provider, bucket_name, object_key,
                        original_filename, content_type, size_bytes, checksum_sha256,
                        media_purpose, visibility, scan_status
                    ) VALUES (
                        :id, :ownerId, 'S3', :bucket, :objectKey,
                        :filename, :contentType, :sizeBytes, :checksum,
                        'PROGRESS_PHOTO', 'PRIVATE', 'CLEAN'
                    )
                    """, Map.of(
                    "id", mediaId,
                    "ownerId", studentId,
                    "bucket", bucket != null ? bucket : "",
                    "objectKey", objectKey,
                    "filename", filename,
                    "contentType", contentType,
                    "sizeBytes", (long) fileBytes.length,
                    "checksum", sha256
            ));

            UUID photoId = UUID.randomUUID();
            MapSqlParameterSource photoParams = new MapSqlParameterSource()
                    .addValue("id", photoId)
                    .addValue("studentId", studentId)
                    .addValue("mediaId", mediaId)
                    .addValue("takenAt", Timestamp.from(actualTakenAt))
                    .addValue("viewType", cleanViewType)
                    .addValue("weightAtTime", weightAtTime)
                    .addValue("weightUnitId", weightUnitId)
                    .addValue("notes", notes);

            jdbc.update("""
                    INSERT INTO fitness.progress_photos(
                        id, student_id, media_id, taken_at, view_type, weight_at_time, weight_unit_id, notes
                    ) VALUES (
                        :id, :studentId, :mediaId, :takenAt, :viewType, :weightAtTime, :weightUnitId, :notes
                    )
                    """, photoParams);

            return new ProgressPhotoResponse(
                    photoId,
                    studentId,
                    mediaId,
                    actualTakenAt,
                    cleanViewType,
                    weightAtTime,
                    weightUnitId,
                    weightUnitCode,
                    notes,
                    contentType,
                    fileBytes.length,
                    Instant.now()
            );
        } catch (Exception ex) {
            log.error("Failed to persist progress photo metadata; rolling back S3 object: {}", objectKey, ex);
            deleteFile(objectKey);
            throw ex;
        }
    }

    @Transactional
    public ProgressPhotoPageResponse listProgressPhotos(
            UUID actorId,
            UUID targetStudentId,
            String viewType,
            LocalDate fromDate,
            LocalDate toDate,
            ZoneId timezone,
            int page,
            int size
    ) {
        if (actorId == null) {
            throw new MeasurementFailure(MeasurementError.VALIDATION_FAILED, "Actor ID must not be null");
        }
        UUID studentId = targetStudentId != null ? targetStudentId : actorId;
        var access = accessPolicy.evaluatePhotoAccess(actorId, studentId);

        ZoneId zone = timezone != null ? timezone : ZoneId.of("Asia/Ho_Chi_Minh");
        LocalDate start = fromDate != null ? fromDate : LocalDate.now(zone).minusYears(1);
        LocalDate end = toDate != null ? toDate : LocalDate.now(zone);

        Instant windowStart = start.atStartOfDay(zone).toInstant();
        Instant windowEnd = end.plusDays(1).atStartOfDay(zone).toInstant();

        Instant historyFrom = access.historyFrom();
        Instant historyUntil = access.historyUntil();

        Instant effectiveStart = (historyFrom != null && historyFrom.isAfter(windowStart)) ? historyFrom : windowStart;
        Instant effectiveEnd = (historyUntil != null && historyUntil.isBefore(windowEnd)) ? historyUntil : windowEnd;

        int safePage = Math.max(0, page);
        int safeSize = (size <= 0 || size > 100) ? 20 : size;
        int offset = safePage * safeSize;

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("studentId", studentId)
                .addValue("startTime", Timestamp.from(effectiveStart))
                .addValue("endTime", Timestamp.from(effectiveEnd))
                .addValue("offset", offset)
                .addValue("limit", safeSize);

        String viewFilter = "";
        if (viewType != null && !viewType.isBlank() && !"ALL".equalsIgnoreCase(viewType)) {
            viewFilter = " AND UPPER(p.view_type) = UPPER(:viewType)";
            params.addValue("viewType", viewType.trim());
        }

        String countSql = "SELECT COUNT(*) FROM fitness.progress_photos p "
                + "WHERE p.student_id = :studentId AND p.deleted_at IS NULL "
                + "AND p.taken_at >= :startTime AND p.taken_at < :endTime"
                + viewFilter;

        Long total = jdbc.queryForObject(countSql, params, Long.class);
        long totalElements = total != null ? total : 0;
        int totalPages = (int) Math.ceil((double) totalElements / safeSize);

        if (totalElements == 0 || effectiveStart.isAfter(effectiveEnd)) {
            return new ProgressPhotoPageResponse(List.of(), safePage, safeSize, totalElements, totalPages, false);
        }

        String dataSql = """
                SELECT p.id, p.student_id, p.media_id, p.taken_at, p.view_type, p.weight_at_time, p.weight_unit_id,
                       mu.code AS weight_unit_code, p.notes, mf.content_type, mf.size_bytes, p.created_at
                FROM fitness.progress_photos p
                JOIN fitness.media_files mf ON mf.id = p.media_id
                LEFT JOIN fitness.measurement_units mu ON mu.id = p.weight_unit_id
                WHERE p.student_id = :studentId AND p.deleted_at IS NULL
                  AND p.taken_at >= :startTime AND p.taken_at < :endTime
                """ + viewFilter + """
                ORDER BY p.taken_at DESC, p.created_at DESC
                OFFSET :offset LIMIT :limit
                """;

        List<ProgressPhotoResponse> content = jdbc.query(dataSql, params, (rs, rowNum) -> new ProgressPhotoResponse(
                (UUID) rs.getObject("id"),
                (UUID) rs.getObject("student_id"),
                (UUID) rs.getObject("media_id"),
                rs.getTimestamp("taken_at").toInstant(),
                rs.getString("view_type"),
                rs.getBigDecimal("weight_at_time"),
                rs.getObject("weight_unit_id") instanceof Number unitId ? unitId.shortValue() : null,
                rs.getString("weight_unit_code"),
                rs.getString("notes"),
                rs.getString("content_type"),
                rs.getLong("size_bytes"),
                rs.getTimestamp("created_at").toInstant()
        ));

        boolean hasMore = (offset + content.size()) < totalElements;
        return new ProgressPhotoPageResponse(content, safePage, safeSize, totalElements, totalPages, hasMore);
    }

    @Transactional
    public PhotoFileDownload loadPhotoFile(UUID actorId, UUID photoId) {
        if (actorId == null || photoId == null) {
            throw new MeasurementFailure(MeasurementError.VALIDATION_FAILED, "Actor ID and Photo ID must not be null");
        }

        var rows = jdbc.query("""
                SELECT p.id, p.student_id, p.taken_at, mf.object_key, mf.content_type
                FROM fitness.progress_photos p
                JOIN fitness.media_files mf ON mf.id = p.media_id
                WHERE p.id = :id AND p.deleted_at IS NULL
                """, Map.of("id", photoId), (rs, rowNum) -> Map.of(
                "studentId", (UUID) rs.getObject("student_id"),
                "takenAt", rs.getTimestamp("taken_at").toInstant(),
                "objectKey", rs.getString("object_key"),
                "contentType", rs.getString("content_type")
        ));

        if (rows.isEmpty()) {
            throw new MeasurementFailure(MeasurementError.PHOTO_NOT_FOUND, "Photo not found: " + photoId);
        }

        UUID studentId = (UUID) rows.get(0).get("studentId");
        var access = accessPolicy.evaluatePhotoAccess(actorId, studentId);

        if (!access.isSelf()) {
            Instant takenAt = (Instant) rows.get(0).get("takenAt");
            if (access.historyFrom() != null && takenAt.isBefore(access.historyFrom())) {
                throw new MeasurementFailure(MeasurementError.MEASUREMENT_ACCESS_DENIED,
                        "Progress photo is before the shared coaching period");
            }
            if (access.historyUntil() != null && takenAt.isAfter(access.historyUntil())) {
                throw new MeasurementFailure(MeasurementError.MEASUREMENT_ACCESS_DENIED,
                        "Progress photo is after the shared coaching period");
            }
        }

        String objectKey = (String) rows.get(0).get("objectKey");
        String contentType = (String) rows.get(0).get("contentType");

        byte[] data = loadFile(objectKey);
        return new PhotoFileDownload(data, contentType);
    }

    @Transactional
    public void deletePhoto(UUID actorId, UUID photoId) {
        if (actorId == null || photoId == null) {
            throw new MeasurementFailure(MeasurementError.VALIDATION_FAILED, "Actor ID and Photo ID must not be null");
        }

        var rows = jdbc.query(
                "SELECT student_id, media_id FROM fitness.progress_photos WHERE id = :id AND deleted_at IS NULL",
                Map.of("id", photoId),
                (rs, rowNum) -> Map.of("studentId", (UUID) rs.getObject("student_id"), "mediaId", (UUID) rs.getObject("media_id"))
        );

        if (rows.isEmpty()) {
            throw new MeasurementFailure(MeasurementError.PHOTO_NOT_FOUND, "Photo not found: " + photoId);
        }

        UUID studentId = (UUID) rows.get(0).get("studentId");
        UUID mediaId = (UUID) rows.get(0).get("mediaId");

        // Only the student owner can delete their photos
        if (!actorId.equals(studentId)) {
            throw new MeasurementFailure(MeasurementError.MEASUREMENT_ACCESS_DENIED, "Only the owner can delete progress photos");
        }

        jdbc.update("UPDATE fitness.progress_photos SET deleted_at = now() WHERE id = :id", Map.of("id", photoId));
        jdbc.update("UPDATE fitness.media_files SET deleted_at = now() WHERE id = :mediaId", Map.of("mediaId", mediaId));
    }

    private boolean starts(byte[] target, byte[] prefix) {
        if (target == null || target.length < prefix.length) {
            return false;
        }
        return Arrays.equals(Arrays.copyOf(target, prefix.length), prefix);
    }

    private boolean isS3Configured() {
        return endpoint != null && !endpoint.isBlank()
                && bucket != null && !bucket.isBlank()
                && accessKey != null && !accessKey.isBlank()
                && secretKey != null && !secretKey.isBlank();
    }

    private void requireStorageConfigured() {
        if (s3ClientSupplier != null) {
            return;
        }
        if (!isS3Configured()) {
            throw new StorageUnavailableException("Object storage is not configured for progress photos");
        }
    }

    private S3Client createS3Client() {
        if (s3ClientSupplier != null) {
            return s3ClientSupplier.get();
        }
        return S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)))
                .region(Region.of("us-east-1"))
                .forcePathStyle(true)
                .build();
    }

    public record PhotoFileDownload(byte[] data, String contentType) {}
}
