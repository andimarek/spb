package spb;

import com.amazonaws.encryptionsdk.AwsCrypto;
import com.amazonaws.encryptionsdk.CommitmentPolicy;
import com.amazonaws.encryptionsdk.CryptoAlgorithm;
import com.amazonaws.encryptionsdk.CryptoInputStream;
import com.amazonaws.encryptionsdk.CryptoResult;
import com.amazonaws.encryptionsdk.jce.JceMasterKey;
import org.bouncycastle.crypto.engines.AESEngine;
import org.bouncycastle.crypto.macs.CMac;
import org.bouncycastle.crypto.params.KeyParameter;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ChecksumAlgorithm;
import software.amazon.awssdk.services.s3.model.CommonPrefix;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteMarkerEntry;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.ObjectVersion;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.model.UploadPartResponse;
import software.amazon.awssdk.services.s3.paginators.ListObjectVersionsIterable;
import software.amazon.awssdk.utils.IoUtils;
import spb.BackupFolderSummary.BackedUpFile;
import spb.BackupFolderSummary.BackedUpFile.ChangedFile;
import spb.BackupFolderSummary.BackedUpFile.UnchangedFile;
import spb.BackupFolderSummary.DeletedFile;

import javax.crypto.spec.SecretKeySpec;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static spb.Impl.HistoricalFile.HistoricDeletedFile;
import static spb.Impl.HistoricalFile.HistoricalBackedUpFile;
import static spb.Util.DIVIDER;
import static spb.Util.bytesToHumanReadableFormat;

public class Impl {

    private static final AwsCrypto awsCrypto = AwsCrypto.builder()
            .withCommitmentPolicy(CommitmentPolicy.RequireEncryptRequireDecrypt)
            .withEncryptionAlgorithm(CryptoAlgorithm.ALG_AES_256_GCM_HKDF_SHA512_COMMIT_KEY)
            .build();
    private static final int MAX_FILES_COUNT = 10_000;

    private static final long HUNDRED_MB = 100 * 1024 * 1024L;

    // changing this key will make existing backups fail.
    private static final String MASTER_KEY_ID = "SpbSecretKey";

    // currently we only have version 1
    private static final int METADATA_VERSION_1 = 1;
    private final ConfigProvider configFile;
    private final S3Client s3Client;
    private String bucketName;
    private JceMasterKey masterKey;
    private SecretKeySpec secretKeySpec;

    private final ExecutorService threadPoolExecutor = Executors.newFixedThreadPool(10);
    private final ExecutorService multipartUploadExecutor = Executors.newFixedThreadPool(5);

    private final Logger logger = LoggerFactory.getLogger("spb");


    static final List<Pattern> filePatternsToIgnore = List.of(Pattern.compile("(.*/)?.DS_Store"));


    public record FileMetadata(String fileName,
                               String originalFileSha256Base64,
                               String objectKey,
                               long originalFileSizeInBytes,
                               Instant creationDate,
                               String contentVersionId
    ) implements FileInfo {

    }

    interface FileInfo {

        String fileName();

        String originalFileSha256Base64();

        long originalFileSizeInBytes();

        Instant creationDate();

        String objectKey();

        @Nullable String contentVersionId();

    }

    public sealed interface HistoricalFile
            permits HistoricalBackedUpFile, HistoricDeletedFile {

        String fileName();

        boolean isLatest();

        Instant creationDate();

        record HistoricalBackedUpFile(
                String fileName,
                String originalFileSha256Base64,
                long originalFileSizeInBytes,
                Instant creationDate,
                boolean isLatest,
                String objectKey,
                String contentVersionId,
                String metadataVersionId
        ) implements HistoricalFile, FileInfo {
        }

        record HistoricDeletedFile(
                String fileName,
                Instant creationDate,
                boolean isLatest
        ) implements HistoricalFile {
        }
    }

    record CountFilesResult(long count, long ignoredFiles) {
    }

    public Impl(ConfigProvider configProvider) throws IOException {
        this.configFile = configProvider;
        readConfigFile();
        s3Client = S3Client.create();
    }

    public Impl() throws IOException {
        this(new PropertiesConfigProvider());
    }

    public void shutdown() {
        threadPoolExecutor.shutdown();
        multipartUploadExecutor.shutdown();
    }

    private void readConfigFile() throws IOException {
        bucketName = configFile.getBucketName();

        secretKeySpec = new SecretKeySpec(configFile.getRawSecretKeyBytes(), "AES");
        masterKey = JceMasterKey.getInstance(secretKeySpec, "Spb", MASTER_KEY_ID, "AES/GCM/NoPadding");

    }

    public List<BackupFolderSummary> backupFolders(boolean dryRun) throws IOException, ExecutionException, InterruptedException {
        List<FolderToBackupConfig> foldersBackupConfig = configFile.getFoldersBackupConfig();
        List<BackupFolderSummary> result = new ArrayList<>();
        if (dryRun) {
            logger.info(DIVIDER);
            logger.info("DRY RUN ---- NOTHING will be actually actually backed up ---- DRY RUN");
            logger.info(DIVIDER);
        }
        logger.info("start backup of {} folders", foldersBackupConfig.size());
        for (FolderToBackupConfig folderToBackupConfig : foldersBackupConfig) {
            result.add(backupSingleFolder(folderToBackupConfig.folder(), folderToBackupConfig.backupName(), dryRun));
        }
        result.forEach(backupFolderSummary -> printBackupSummary(backupFolderSummary, dryRun));
        return result;
    }

    private void printBackupSummary(BackupFolderSummary backupFolderSummary, boolean dryRun) {
        String backupName = backupFolderSummary.backupName();
        List<BackedUpFile> backedUpFiles = backupFolderSummary.backedUpFiles();
        int changedFilesCount = 0;
        int unchangedFilesCount = 0;
        long totalBytesUploaded = 0;
        for (BackedUpFile backedUpFile : backedUpFiles) {
            if (backedUpFile instanceof ChangedFile changedFile) {
                changedFilesCount++;
                totalBytesUploaded += changedFile.fileSizeInBytes();
            } else if (backedUpFile instanceof UnchangedFile) {
                unchangedFilesCount++;
            }
        }

        List<DeletedFile> deletedFiles = backupFolderSummary.deletedFiles();

        logger.info("number of backed up files (changed and unchanged): {}", backedUpFiles.size());
        logger.info("number of deleted files: {}", deletedFiles.size());
        if (dryRun) {
            logger.info(DIVIDER);
            logger.info("DRY RUN ---- NOTHING was actually backed up ---- DRY RUN");
            logger.info("DRY RUN summary for backup '{}' from folder '{}'", backupName, backupFolderSummary.backupFolder());
        } else {
            logger.info(DIVIDER);
            logger.info("Summary for backup '{}' from folder '{}'", backupName, backupFolderSummary.backupFolder());
        }
        logger.info("total files backed up: {} made out of {} changed vs {} unchanged", backedUpFiles.size(), changedFilesCount, unchangedFilesCount);
        logger.info("total data uploaded {} ", bytesToHumanReadableFormat(totalBytesUploaded));
        logger.info("total files deleted {}", deletedFiles.size());
        logger.info(DIVIDER);

        if (backedUpFiles.size() == 0) {
            logger.debug("no files found to backup. This means the folder to backup is empty.");
        } else {
            logger.debug("details of backed up files:");
        }
        for (BackedUpFile backedUpFile : backedUpFiles) {
            if (backedUpFile instanceof ChangedFile) {
                logger.debug("file {} was changed and backed up. ", backedUpFile.relativePath());
            } else if (backedUpFile instanceof UnchangedFile) {
                logger.debug("file {} was not changed and not backed up. ", backedUpFile.relativePath());
            }
        }
        if (deletedFiles.size() == 0) {
            logger.debug("no deleted files");
        } else {
            logger.debug("details of deleted files:");
            for (DeletedFile deletedFile : deletedFiles) {
                logger.debug("file {} was deleted", deletedFile.relativePath());
            }
        }
        if (dryRun) {
            logger.info(DIVIDER);
            logger.info("DRY RUN SUMMARY FINISHED FOR '{}'", backupName);
        } else {
            logger.info(DIVIDER);
            logger.info("SUMMARY FINISHED FOR '{}'", backupName);
        }
        logger.info("total files backed up: {} made out of {} changed vs {} unchanged", backedUpFiles.size(), changedFilesCount, unchangedFilesCount);
        logger.info("total data uploaded {} ", bytesToHumanReadableFormat(totalBytesUploaded));
        logger.info("total files deleted {}", deletedFiles.size());
        logger.info(DIVIDER);

    }


    private BackupFolderSummary backupSingleFolder(
            String folderStr,
            String backupName,
            boolean dryRun
    ) throws IOException, ExecutionException, InterruptedException {
        Path folder = Path.of(folderStr);
        CountFilesResult filesCount;
        try {
            filesCount = countFilesToBackup(folder);
        } catch (IOException e) {
            logger.error("error accessing files to backup ... abort backing up {}.", folder, e);
            throw new RuntimeException(e);
        }

        if (filesCount.count > MAX_FILES_COUNT) {
            logger.error("Abort ... to many files to backup");
            throw new RuntimeException("To many files to backup");
        }
        logger.info("Found {} files to backup ({} ignored files)", filesCount.count, filesCount.ignoredFiles);
        List<FileMetadata> alreadyBackedUpFiles = getBackedUpFiles(backupName);

        if (dryRun) {
            BackupFolderSummary backupFolderSummary = dryRunBackupSingleFolderImpl(folder, backupName, alreadyBackedUpFiles);
            return backupFolderSummary;

        } else {
            BackupFolderSummary backupFolderSummary = backupSingleFolderImpl(folder, backupName, alreadyBackedUpFiles);
            return backupFolderSummary;
        }
    }

    private BackupFolderSummary dryRunBackupSingleFolderImpl(Path folder, String backupName, List<FileMetadata> alreadyBackedUpFiles) throws IOException {
        logger.info("Start backup '{}' from folder '{}'", backupName, folder);
        if (!folder.toFile().isDirectory()) {
            throw new RuntimeException("Must be a folder " + folder);
        }

        Map<String, FileMetadata> fileNamesMap = alreadyBackedUpFiles
                .stream()
                .collect(Collectors.toMap(FileMetadata::fileName, fileMetadata -> fileMetadata));

        List<BackedUpFile> newlyBackedUpFiles = new ArrayList<>();
        Set<String> existingFiles = new LinkedHashSet<>();

        try (Stream<Path> walk = Files.walk(folder)) {
            walk.forEach(file -> {
                if (file.equals(folder)) {
                    return;
                }
                if (file.toFile().isDirectory()) {
                    return;
                }
                if (shouldIgnoreFile(file.toString())) {
                    logger.debug("file {} is ignored", file);
                    return;
                }
                Path fileRelativePath = folder.relativize(file);
                existingFiles.add(fileRelativePath.toString());

                String originalFileSha256Base64;
                try {
                    long originalFileSize = Files.size(file);
                    if (originalFileSize > HUNDRED_MB) {
                        logger.debug("calculating sha256 for larger file {} with {}", file, bytesToHumanReadableFormat(originalFileSize));
                    }
                    originalFileSha256Base64 = Util.sha256Base64ForFile(file);
                    if (doesFileNeedBackup(folder, fileRelativePath, originalFileSha256Base64, fileNamesMap)) {
                        newlyBackedUpFiles.add(new BackedUpFile.ChangedFile(fileRelativePath.toString(), originalFileSha256Base64, originalFileSize));
                    } else {
                        newlyBackedUpFiles.add(new UnchangedFile(fileRelativePath.toString()));
                    }
                } catch (Exception e) {
                    logger.error("error: ", e);
                    throw new RuntimeException(e);
                }
            });
        }

        // files to deleted
        List<DeletedFile> filesToDelete = alreadyBackedUpFiles.stream()
                .filter(fileMetadata -> !existingFiles.contains(fileMetadata.fileName()))
                .map(fileMetadata -> new DeletedFile(fileMetadata.fileName))
                .toList();
        return new BackupFolderSummary(backupName, folder.toString(), newlyBackedUpFiles, filesToDelete);
    }

    private BackupFolderSummary backupSingleFolderImpl(Path folder, String backupName, List<FileMetadata> alreadyBackedUpFiles) throws
            IOException, ExecutionException, InterruptedException {
        logger.info("Start backup '{}' from folder '{}'", backupName, folder);
        if (!folder.toFile().isDirectory()) {
            throw new RuntimeException("Must be a folder " + folder);
        }

        Map<String, FileMetadata> fileNamesMap = alreadyBackedUpFiles
                .stream()
                .collect(Collectors.toMap(FileMetadata::fileName, fileMetadata -> fileMetadata));


        Path tempDirectory = Files.createTempDirectory(backupName);
        tempDirectory.toFile().deleteOnExit();

        List<CompletableFuture<?>> futures = new ArrayList<>();
        List<BackedUpFile> newlyBackedUpFiles = Collections.synchronizedList(new ArrayList<>());
        Set<String> existingFiles = Collections.synchronizedSet(new LinkedHashSet<>());
        try (Stream<Path> walk = Files.walk(folder)) {
            walk.forEach(file -> {
                if (file.equals(folder)) {
                    return;
                }
                if (file.toFile().isDirectory()) {
                    return;
                }
                if (shouldIgnoreFile(file.toString())) {
                    logger.debug("file {} is ignored", file);
                    return;
                }
                futures.add(CompletableFuture.runAsync((() -> {
                    try {
                        Path fileRelativePath = folder.relativize(file);
                        existingFiles.add(fileRelativePath.toString());
                        BackedUpFile backedUpFile = encryptAndUploadFile(folder, fileRelativePath, tempDirectory, backupName, fileNamesMap);
                        newlyBackedUpFiles.add(backedUpFile);
                    } catch (Exception e) {
                        logger.info("upload failed", e);
                        throw new RuntimeException(e);
                    }
                }), threadPoolExecutor));
            });

        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get();
        logger.info("finished uploading new or changed files");

        List<DeletedFile> deletedFiles = deleteFiles(alreadyBackedUpFiles, existingFiles);
        return new BackupFolderSummary(backupName, folder.toString(), newlyBackedUpFiles, deletedFiles);
    }


    public void verifyAllBackup() throws IOException, NoSuchAlgorithmException, ExecutionException, InterruptedException {
        logger.info("start verifying all {} backups", configFile.getFoldersBackupConfig().size());
        for (FolderToBackupConfig folderToBackupConfig : configFile.getFoldersBackupConfig()) {
            verifyBackup(folderToBackupConfig.backupName());
        }

    }

    public void verifyBackup(String backupName) throws IOException, NoSuchAlgorithmException, ExecutionException, InterruptedException {
        logger.info("Start verifying backup {}", backupName);
        Path tempDirectory = Files.createTempDirectory(backupName);
        logger.debug("Created tmp directory {}", tempDirectory);
        restoreFullBackup(backupName, tempDirectory);
        Util.deleteFolderRecursively(tempDirectory);
        logger.info("Backup {} successfully verified", backupName);
    }

    public void restoreFullBackup(String backupName, Path targetFolder) throws IOException, ExecutionException, InterruptedException, NoSuchAlgorithmException {
        List<FileMetadata> backedUpFiles = getBackedUpFiles(backupName);

        List<CompletableFuture<?>> completableFutures = new ArrayList<>();
        for (final FileMetadata fileMetadata : backedUpFiles) {
            completableFutures.add(CompletableFuture.runAsync(() -> {
                try {
                    restoreFile(fileMetadata, targetFolder);
                } catch (Exception e) {
                    logger.error("error restoring file {}", fileMetadata.fileName, e);
                    throw new RuntimeException(e);
                }
            }, threadPoolExecutor));
        }
        logger.info("Waiting for restoring of all files finished");
        CompletableFuture.allOf(completableFutures.toArray(new CompletableFuture[0])).get();

    }


    public void restoreHistoricalFile(String backupName,
                                      Path targetFolder,
                                      String fileToRestore,
                                      String metadataVersionId) throws IOException, NoSuchAlgorithmException {
        String objectHash = createObjectKey(Path.of(fileToRestore));
        FileMetadata fileMetadata = readFileMetadata(objectHash + "/", metadataVersionId);
        restoreFile(fileMetadata, targetFolder);
    }


    public void restoreFile(Path targetFolder,
                            String backupName,
                            String fileToRestore) throws
            IOException, ExecutionException, InterruptedException, NoSuchAlgorithmException {
        List<FileMetadata> alreadyBackedUpFiles = getBackedUpFiles(backupName);
        Optional<FileMetadata> backedUpFileOptional = alreadyBackedUpFiles.stream().filter(fileMetadata -> fileMetadata.fileName.equals(fileToRestore)).findFirst();
        if (backedUpFileOptional.isEmpty()) {
            logger.info("file {} not found in backup {} ... nothing to restore", fileToRestore, backupName);
            return;
        }
        restoreFile(backedUpFileOptional.get(), targetFolder);
    }


    public void restoreFile(FileInfo fileInfo,
                            Path targetFolder) throws IOException, NoSuchAlgorithmException {
        logger.debug("restoring file {}", fileInfo);
        /**
         * Download
         */
        GetObjectRequest getObjectRequest = GetObjectRequest
                .builder()
                .bucket(bucketName)
                .key(contentObjectKey(fileInfo.objectKey()))
                .versionId(fileInfo.contentVersionId())
                .build();
        Path encryptedFiled = targetFolder.resolve(fileInfo.fileName() + ".encrypted");
        encryptedFiled.toFile().getParentFile().mkdirs();
        long time = System.currentTimeMillis();
        logger.debug("Start downloading file {}. Original file size: {} bytes", fileInfo.fileName(), fileInfo.originalFileSizeInBytes());
        GetObjectResponse getObjectResponse = s3Client.getObject(getObjectRequest, encryptedFiled);
        logger.debug("Finished downloading file {} after {}ms ", fileInfo.fileName(), System.currentTimeMillis() - time);
        Path decryptedFile = targetFolder.resolve(fileInfo.fileName());
        decryptFile(decryptedFile, encryptedFiled.toFile());
        encryptedFiled.toFile().deleteOnExit();

        /**
         * Verify
         */
        String sha256 = Util.sha256Base64ForFile(decryptedFile);
        if (sha256.equals(fileInfo.originalFileSha256Base64())) {
            logger.debug("Verified SHA256 successfully for restored file {}", fileInfo.fileName());
        } else {
            logger.error("invalid SHA256: {} vs expected {}", sha256, fileInfo.originalFileSha256Base64());
            throw new RuntimeException("Could not verify restored file");
        }

        logger.info("file {} restored at {}", fileInfo.fileName(), targetFolder);
    }


    /**
     * all backed up files across all backups configured in the config file.
     * This doesn't include historical versions, only the latest ones.
     */
    public Map<String, List<FileMetadata>> allBackedUpFiles() throws
            IOException, ExecutionException, InterruptedException {
        Map<String, List<FileMetadata>> result = new LinkedHashMap<>();
        List<FolderToBackupConfig> foldersBackupConfig = configFile.getFoldersBackupConfig();
        for (FolderToBackupConfig folderToBackupConfig : foldersBackupConfig) {
            List<FileMetadata> fileMetadata = getBackedUpFiles(folderToBackupConfig.backupName());
            result.put(folderToBackupConfig.backupName(), fileMetadata);
        }
        return result;
    }

    public Map<String, Map<String, List<HistoricalFile>>> allBackedUpFilesIncludingHistory() throws ExecutionException, InterruptedException {
        Map<String, Map<String, List<HistoricalFile>>> result = new LinkedHashMap<>();
        List<FolderToBackupConfig> foldersBackupConfig = configFile.getFoldersBackupConfig();
        for (FolderToBackupConfig folderToBackupConfig : foldersBackupConfig) {
            Map<String, List<HistoricalFile>> fileMetadata = getBackedUpFilesIncludingHistory(folderToBackupConfig.backupName());
            result.put(folderToBackupConfig.backupName(), fileMetadata);
        }
        return result;

    }


    /**
     * The result is ordered by date, from oldest to newest.
     */
    private Map<String, List<HistoricalFile>> getBackedUpFilesIncludingHistory(String backupName) throws ExecutionException, InterruptedException {
        List<CommonPrefix> allKeys = getAllObjectKeysInBackup(backupName, true);

        Map<String, List<Object>> objectKeyToVersionAndDeleteMarker = new LinkedHashMap<>();
        for (CommonPrefix commonPrefix : allKeys) {
            ListObjectVersionsRequest req = ListObjectVersionsRequest.builder()
                    .bucket(bucketName)
                    .prefix(commonPrefix.prefix() + "metadata")
                    .delimiter("/")
                    .maxKeys(1_000)
                    .build();
            ListObjectVersionsIterable iterable = s3Client.listObjectVersionsPaginator(req);
            /**
             Unfortunately in SDK v2 versions and delete markers are parsed separately
             which means we need to create again one list by combining and sorting both lists.
             "LastModified" has a 1 seconds resolution, which means in theory we can't separate events
             happened in less than 1 second, which should be fine for our use case here.
             See https://github.com/aws/aws-sdk-cpp/issues/1649
             and https://github.com/aws/aws-sdk-java-v2/issues/1620
             */
            List<Object> versionsAndDeleteMarkers = new ArrayList<>();
            iterable.stream().forEach(singleResp -> {
                versionsAndDeleteMarkers.addAll(singleResp.versions());
                versionsAndDeleteMarkers.addAll(singleResp.deleteMarkers());
            });
            versionsAndDeleteMarkers.sort((o1, o2) -> {
                Instant i1 = null;
                if (o1 instanceof ObjectVersion objectVersion) {
                    i1 = objectVersion.lastModified();
                } else if (o1 instanceof DeleteMarkerEntry deleteMarkerEntry) {
                    i1 = deleteMarkerEntry.lastModified();
                }
                Instant i2 = null;
                if (o2 instanceof ObjectVersion objectVersion) {
                    i2 = objectVersion.lastModified();
                } else if (o2 instanceof DeleteMarkerEntry deleteMarkerEntry) {
                    i2 = deleteMarkerEntry.lastModified();
                }
                return i1.compareTo(i2);
            });
            objectKeyToVersionAndDeleteMarker.put(commonPrefix.prefix(), versionsAndDeleteMarkers);
        }

        Map<String, List<HistoricalFile>> result = Collections.synchronizedMap(new LinkedHashMap<>());
        List<CompletableFuture<?>> futures = new ArrayList<>();
        for (String objectKey : objectKeyToVersionAndDeleteMarker.keySet()) {
            futures.add(CompletableFuture.runAsync(() -> {
                // This is sorted
                String fileName = null;
                List<HistoricalFile> fileMetadataForOneKey = new ArrayList<>();
                for (Object versionOrDeleteMarker : objectKeyToVersionAndDeleteMarker.get(objectKey)) {
                    if (versionOrDeleteMarker instanceof ObjectVersion metadataObjectVersion) {
                        try {
                            FileMetadata fileMetadata = readFileMetadata(objectKey, metadataObjectVersion.versionId());
                            fileMetadataForOneKey.add(new HistoricalBackedUpFile(
                                    fileMetadata.fileName,
                                    fileMetadata.originalFileSha256Base64,
                                    fileMetadata.originalFileSizeInBytes,
                                    fileMetadata.creationDate,
                                    metadataObjectVersion.isLatest(),
                                    objectKey,
                                    fileMetadata.contentVersionId,
                                    metadataObjectVersion.versionId()
                            ));
                            if (fileName == null) {
                                fileName = fileMetadata.fileName;
                            }
                        } catch (Exception e) {
                            logger.error("error reading metadata object ", e);
                            throw new RuntimeException(e);
                        }
                    } else if (versionOrDeleteMarker instanceof DeleteMarkerEntry deleteMarkerEntry) {
                        fileMetadataForOneKey.add(new HistoricDeletedFile(
                                fileName,
                                deleteMarkerEntry.lastModified(),
                                deleteMarkerEntry.isLatest()
                        ));
                    }
                }
                result.put(fileName, fileMetadataForOneKey);
            }, threadPoolExecutor));
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get();
        return result;
    }

    private List<FileMetadata> getBackedUpFiles(String backupName) throws
            ExecutionException, InterruptedException {
        List<CommonPrefix> allPrefixes = getAllObjectKeysInBackup(backupName, false);

        List<FileMetadata> result = Collections.synchronizedList(new ArrayList<>());
        List<CompletableFuture<?>> futures = new ArrayList<>();
        for (CommonPrefix commonPrefix : allPrefixes) {
            futures.add(CompletableFuture.runAsync(() -> {
                try {
                    result.add(readFileMetadata(commonPrefix.prefix(), null));
                } catch (Exception e) {
                    logger.error("error reading metadata object ", e);
                    throw new RuntimeException(e);
                }
            }, threadPoolExecutor));
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get();
        return result;
    }

    private FileMetadata readFileMetadata(String keyWithEndingSlash, @Nullable String versionId) throws IOException {
        GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                .bucket(bucketName)
                .key(keyWithEndingSlash + "metadata")
                .versionId(versionId)
                .build();
        ResponseInputStream<GetObjectResponse> responseResponseInputStream = s3Client.getObject(getObjectRequest);
        GetObjectResponse getObjectResponse = responseResponseInputStream.response();
        Instant creationDate = getObjectResponse.lastModified();
        byte[] metaDataEncrypted = IoUtils.toByteArray(responseResponseInputStream);
        CryptoResult<byte[], JceMasterKey> decrypted = awsCrypto.decryptData(masterKey, metaDataEncrypted);

        String metadata = new String(decrypted.getResult(), StandardCharsets.UTF_8);
        // we saved it as comma separated
        String[] metadataEntries = metadata.split(",");

        int metadataVersion = Integer.parseInt(metadataEntries[0]);
        if (metadataVersion != METADATA_VERSION_1) {
            logger.error("unexpected metadata version {}", metadataVersion);
            throw new RuntimeException("unexpected metadata version");
        }
        String fileName = new String(Base64.getDecoder().decode(metadataEntries[1]), StandardCharsets.UTF_8);
        String originalFileSha256Base64 = metadataEntries[2];
        long originalFileSizeInBytes = Long.parseLong(metadataEntries[3]);
        String contentVersionId = metadataEntries[4];

        return new FileMetadata(fileName,
                originalFileSha256Base64,
                keyWithEndingSlash,
                originalFileSizeInBytes,
                creationDate,
                contentVersionId
        );
    }

    private List<CommonPrefix> getAllObjectKeysInBackup(String backupName, boolean includeDeleted) {
        if (includeDeleted) {
            ListObjectVersionsRequest req = ListObjectVersionsRequest.builder()
                    .bucket(bucketName)
                    .prefix(backupName + "/")
                    .delimiter("/")
                    .build();
            ListObjectVersionsIterable iterable = s3Client.listObjectVersionsPaginator(req);
            return iterable.commonPrefixes().stream().toList();
        } else {
            ListObjectsV2Response listObjectsV2Response;
            String continuationToken = null;
            List<CommonPrefix> allPrefixes = new ArrayList<>();
            do {
                ListObjectsV2Request listObjectsV2Request = ListObjectsV2Request.builder()
                        .bucket(bucketName)
                        .delimiter("/")
                        .prefix(backupName + "/")
                        .maxKeys(1_000)
                        .continuationToken(continuationToken)
                        .build();
                listObjectsV2Response = s3Client.listObjectsV2(listObjectsV2Request);
                allPrefixes.addAll(listObjectsV2Response.commonPrefixes());
                continuationToken = listObjectsV2Response.nextContinuationToken();
            } while (listObjectsV2Response.isTruncated());
            logger.info("Total files backed up: {}", allPrefixes.size());
            return allPrefixes;
        }
    }


    private List<DeletedFile> deleteFiles(List<FileMetadata> backedUpFiles, Set<String> existingFiles) {
        List<DeletedFile> result = new ArrayList<>();
        List<FileMetadata> filesToDelete = backedUpFiles.stream().filter(fileMetadata -> !existingFiles.contains(fileMetadata.fileName)).toList();
        logger.info("Found {} deleted files", filesToDelete.size());

        for (int i = 0; i < filesToDelete.size(); i += 500) {
            List<ObjectIdentifier> keys = new ArrayList<>();
            for (int j = i; j < i + 500 && j < filesToDelete.size(); j++) {
                logger.debug("Deleting {} ", filesToDelete.get(j).fileName);
                result.add(new DeletedFile(filesToDelete.get(j).fileName));
                String contentObjectKey = contentObjectKey(filesToDelete.get(j).objectKey());
                String metadataObjectKey = metadataObjectKey(filesToDelete.get(j).objectKey());
                keys.add(ObjectIdentifier.builder().key(contentObjectKey).build());
                keys.add(ObjectIdentifier.builder().key(metadataObjectKey).build());
            }
            DeleteObjectsRequest deleteObjectRequest = DeleteObjectsRequest.builder()
                    .bucket(bucketName)
                    .delete(Delete.builder().objects(keys).build())
                    .build();
            DeleteObjectsResponse deleteObjectsResponse = s3Client.deleteObjects(deleteObjectRequest);
            logger.debug("deleteObjectResponse {}", deleteObjectsResponse);
        }
        logger.info("Finished deleting {} files", filesToDelete.size());
        return result;
    }


    private static CountFilesResult countFilesToBackup(Path folder) throws IOException {

        try (Stream<Path> walk = Files.walk(folder)) {
            AtomicLong ignoredFiles = new AtomicLong();
            AtomicLong count = new AtomicLong();
            walk.forEach(path -> {
                if (path.toFile().isDirectory()) {
                    return;
                }
                if (shouldIgnoreFile(path.toString())) {
                    ignoredFiles.getAndIncrement();
                    return;
                }
                count.getAndIncrement();
            });
            return new CountFilesResult(count.get(), ignoredFiles.get());
        }
    }

    private boolean doesFileNeedBackup(Path root,
                                       Path originalFileRelative,
                                       String originalFileSha256Base64,
                                       Map<String, FileMetadata> fileMap
    ) {
        if (fileMap.containsKey(originalFileRelative.toString())) {
            logger.debug("found file {} checking if it changed", originalFileRelative);
            String backedUpSha256Base64 = fileMap.get(originalFileRelative.toString()).originalFileSha256Base64;
            if (backedUpSha256Base64.equals(originalFileSha256Base64)) {
                logger.debug("file {} not changed. Not being backed up again.", originalFileRelative);
                return false;
            } else {
                logger.debug("file {} changed and will be backed up.", originalFileRelative);
            }
        } else {
            logger.debug("file {} is new and will be backed up", originalFileRelative);
        }
        return true;


    }

    private BackedUpFile encryptAndUploadFile(Path root,
                                              Path originalFileRelative,
                                              Path tempDirectory,
                                              String backupName,
                                              Map<String, FileMetadata> fileMap
    ) throws
            NoSuchAlgorithmException, IOException, ExecutionException, InterruptedException, NoSuchProviderException, InvalidKeyException {

        Path originalFileResolved = root.resolve(originalFileRelative);
        logger.debug("start processing {}", originalFileRelative);
        String originalFileSha256Base64 = Util.sha256Base64ForFile(originalFileResolved);
        if (!doesFileNeedBackup(root, originalFileRelative, originalFileSha256Base64, fileMap)) {
            return new UnchangedFile(originalFileRelative.toString());
        }
        String s3ObjectKey = createObjectKey(originalFileRelative);
        Path encryptedFile = encryptFile(tempDirectory, originalFileResolved.toFile(), s3ObjectKey);

        encryptedFile.toFile().deleteOnExit();

        long originalFileSize = Files.size(originalFileResolved);
        String contentVersionId = createContentObject(backupName, s3ObjectKey, originalFileRelative, originalFileResolved, encryptedFile, originalFileSize);
        createMetadataObject(backupName, s3ObjectKey, originalFileRelative, originalFileResolved, originalFileSha256Base64, contentVersionId);

        logger.debug("finished file {}", originalFileRelative);
        return new ChangedFile(originalFileRelative.toString(), originalFileSha256Base64, originalFileSize);

    }

    private Path encryptFile(Path tempDirectory, File originalFile, String s3ObjectKey) throws IOException {
        logger.debug("encrypt file {}", originalFile);
        Map<String, String> context = Collections.singletonMap("nameHash", s3ObjectKey);
        CryptoInputStream<JceMasterKey> encryptingStream = awsCrypto
                .createEncryptingStream(masterKey, new FileInputStream(originalFile), context);
        Path encryptedFile = tempDirectory.resolve(s3ObjectKey);
        FileOutputStream out = new FileOutputStream(encryptedFile.toFile());
        IoUtils.copy(encryptingStream, out);
        encryptingStream.close();
        out.close();
        return encryptedFile;
    }

    private void decryptFile(Path decryptedFile, File encryptedFile) throws IOException {

        CryptoInputStream<JceMasterKey> encryptingStream = awsCrypto
                .createDecryptingStream(masterKey, new FileInputStream(encryptedFile));

        FileOutputStream out = new FileOutputStream(decryptedFile.toFile());
        IoUtils.copy(encryptingStream, out);
        encryptingStream.close();
        out.close();
    }

    private String createObjectKey(Path relativeFileName) {

        /**
         * We are using here BouncyCastle directly to calculate AESCMAC hash instead via
         * JCE because this works in native images.
         */
        CMac cMac = new CMac(new AESEngine());
        cMac.init(new KeyParameter(secretKeySpec.getEncoded()));

        byte[] filenameBytes = relativeFileName.toString().getBytes(StandardCharsets.UTF_8);
        cMac.update(filenameBytes, 0, filenameBytes.length);
        byte[] keyBytes = new byte[cMac.getMacSize()];
        cMac.doFinal(keyBytes, 0);
        return Base64.getUrlEncoder().encodeToString(keyBytes);
    }


    private void createMetadataObject(String backupName,
                                      String nameHash,
                                      Path originalFileRelative,
                                      Path originalFileResolved,
                                      String originalFileSha256Base64,
                                      String contentVersionId) throws IOException {
        PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(backupName + "/" + nameHash + "/metadata")
                .build();
        long originalFileSizeBytes = Files.size(originalFileResolved);
        // comma separated list
        String metadata = METADATA_VERSION_1 +
                "," + Base64.getEncoder().encodeToString(originalFileRelative.toString().getBytes(StandardCharsets.UTF_8)) +
                "," + originalFileSha256Base64 +
                "," + originalFileSizeBytes +
                "," + contentVersionId;


        CryptoResult<byte[], JceMasterKey> encryptResult = awsCrypto.encryptData(masterKey, metadata.getBytes(StandardCharsets.UTF_8));
        byte[] encryptedFileName = encryptResult.getResult();
        RequestBody requestBody = RequestBody.fromBytes(encryptedFileName);
        PutObjectResponse putObjectResponse = s3Client.putObject(putObjectRequest, requestBody);
        logger.debug("uploaded metadata object for {}: {}", originalFileRelative, putObjectResponse);
    }

    private String createContentObject(String backupName,
                                       String nameHash,
                                       Path originalFileRelative,
                                       Path originalFileResolved,
                                       Path encryptedFile,
                                       long originalFileSizeByte) throws IOException, ExecutionException, InterruptedException, NoSuchAlgorithmException {
        String objectKey = backupName + "/" + nameHash + "/content";
        if (originalFileSizeByte >= configFile.getMultiPartUploadLimitInBytes()) {
            logger.debug("file {} is bigger than {} with {} ... using multipart upload",
                    originalFileRelative,
                    bytesToHumanReadableFormat(configFile.getMultiPartUploadLimitInBytes()),
                    bytesToHumanReadableFormat(originalFileSizeByte));
            return multipartUpload(objectKey, originalFileRelative, encryptedFile);
        } else {
            return putObject(originalFileRelative, encryptedFile, objectKey);
        }

    }

    private String putObject(Path originalFileRelative, Path encryptedFile, String objectKey) throws
            IOException, NoSuchAlgorithmException {
        String sha256 = Util.sha256Base64ForFile(encryptedFile);
        logger.info("uploading file {}", originalFileRelative);
        PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(objectKey)
                .checksumAlgorithm(ChecksumAlgorithm.SHA256)
                .checksumSHA256(sha256)
                .build();
        PutObjectResponse putObjectResponse = s3Client.putObject(putObjectRequest, encryptedFile);
        logger.debug("uploaded content object for {} response {} ", originalFileRelative, putObjectResponse);
        return putObjectResponse.versionId();
    }

    private String multipartUpload(String objectKey,
                                   Path originalFileRelative,
                                   Path encryptedFile
    ) throws
            IOException, ExecutionException, InterruptedException, NoSuchAlgorithmException {
        long encryptedFileSize = Files.size(encryptedFile);
        int multiPartUploadLimitInBytes = configFile.getMultiPartUploadLimitInBytes();
        int partCount = (int) Math.ceil((double) encryptedFileSize / multiPartUploadLimitInBytes);
        logger.info("start multipart upload for {}. Expected to upload {} parts", originalFileRelative, partCount);
        CreateMultipartUploadRequest createMultipartUploadRequest = CreateMultipartUploadRequest.builder()
                .bucket(bucketName)
                .key(objectKey)
                .checksumAlgorithm(ChecksumAlgorithm.SHA256)
                .build();

        CreateMultipartUploadResponse response = s3Client.createMultipartUpload(createMultipartUploadRequest);
        String uploadId = response.uploadId();

        ByteBuffer buffer = ByteBuffer.allocate(multiPartUploadLimitInBytes);
        int partNumber = 0;
        List<CompletedPart> completedParts = Collections.synchronizedList(new ArrayList<>());
        List<CompletableFuture<?>> completableFutures = new ArrayList<>();
        try (FileInputStream fileInputStream = new FileInputStream(encryptedFile.toFile());
             FileChannel fileChannel = fileInputStream.getChannel()) {

            int read;
            while ((read = fileChannel.read(buffer)) > 0) {
                logger.debug("body read {} bytes for encrypted file of {}", read, originalFileRelative);
                buffer.flip();
                UploadPartRequest uploadPartRequest = UploadPartRequest.builder()
                        .bucket(bucketName)
                        .key(objectKey)
                        .uploadId(uploadId)
                        .partNumber(++partNumber)
                        .checksumAlgorithm(ChecksumAlgorithm.SHA256)
                        .checksumSHA256(Util.sha256Base64(buffer))
                        .build();

                RequestBody requestBody = RequestBody.fromByteBuffer(buffer);
                buffer.clear();
                logger.debug("loading part {}/{} for file {} into memory finished", partNumber, partCount, originalFileRelative);
                int finalPartNumber = partNumber;
                completableFutures.add(CompletableFuture.runAsync(() -> {
                    try {
                        logger.debug("start uploading part {}/{} for file {}", finalPartNumber, partCount, originalFileRelative);
                        UploadPartResponse uploadPartResponse = s3Client.uploadPart(uploadPartRequest,
                                requestBody);
                        completedParts.add(CompletedPart.builder().partNumber(finalPartNumber).checksumSHA256(uploadPartResponse.checksumSHA256()).eTag(uploadPartResponse.eTag()).build());
                        logger.debug("uploaded part {}/{} for file {}", finalPartNumber, partCount, originalFileRelative);
                    } catch (Exception e) {
                        logger.error("error uploading part ", e);
                    }
                }, multipartUploadExecutor));
            }
        } catch (IOException e) {
            logger.info("error", e);
            throw e;
        }
        logger.debug("waiting for uploading parts finished for {}", originalFileRelative);
        CompletableFuture.allOf(completableFutures.toArray(new CompletableFuture[0])).get();
        logger.debug("uploading parts finished for {}", originalFileRelative);

        completedParts.sort(Comparator.comparingInt(CompletedPart::partNumber));
        CompletedMultipartUpload completedMultipartUpload = CompletedMultipartUpload.builder()
                .parts(completedParts)
                .build();

        CompleteMultipartUploadRequest completeMultipartUploadRequest =
                CompleteMultipartUploadRequest.builder()
                        .bucket(bucketName)
                        .key(objectKey)
                        .uploadId(uploadId)
                        .multipartUpload(completedMultipartUpload)
                        .build();
        CompleteMultipartUploadResponse completeMultipartUploadResponse = s3Client.completeMultipartUpload(completeMultipartUploadRequest);

        logger.info("completed multi part {} of total {}", completeMultipartUploadResponse, bytesToHumanReadableFormat(encryptedFileSize));
        return completeMultipartUploadResponse.versionId();

    }


    static boolean shouldIgnoreFile(String file) {
        return filePatternsToIgnore.stream().anyMatch(pattern -> pattern.matcher(file).matches());
    }

    private static String contentObjectKey(String objectKeyEndingWithSlash) {
        return objectKeyEndingWithSlash + "content";
    }

    private static String metadataObjectKey(String objectKeyEndingWithSlash) {
        return objectKeyEndingWithSlash + "metadata";
    }


}
