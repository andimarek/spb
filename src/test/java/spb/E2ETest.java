package spb;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import spb.BackupFolderSummary.BackedUpFile;
import spb.BackupFolderSummary.BackedUpFile.ChangedFile;
import spb.BackupFolderSummary.BackedUpFile.UnchangedFile;
import spb.Impl.HistoricFile;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutionException;

import static java.nio.file.Files.createDirectory;
import static java.nio.file.Files.createFile;
import static org.assertj.core.api.Assertions.assertThat;

public class E2ETest {

    static Path rootTestDataFolder;

    static final int HUNDRED_BYTE = 100;
    static final int ONE_MB = 1024 * 1024;
    static final int TEN_MB = 10 * 1024 * 1024;

    static ByteBuffer randomData1Mb = ByteBuffer.allocate(ONE_MB);
    static ByteBuffer randomData10Mb = ByteBuffer.allocate(TEN_MB);
    static ByteBuffer randomData100b = ByteBuffer.allocate(HUNDRED_BYTE);

    static byte[] secretKey;
    private static Path backupsOne;
    private static List<Path> backupsOneFiles = new ArrayList<>();
    private static Map<String, String> fileToSha256Base64 = new LinkedHashMap<>();

    private static String bucketName;
    private static String file6Key;
    private static String file1Key;
    private static String largeFileKey;
    private static String file2Key;

    @BeforeAll
    static void init() throws IOException, NoSuchAlgorithmException {
        readBucketName();
        rootTestDataFolder = Files.createTempDirectory("spb-e2e");
        createSecretKey();
        createRandomData();
        createSimpleBackupsFolder();

    }

    private static void readBucketName() {
        bucketName = System.getenv("BUCKET_NAME");
        if (bucketName == null || bucketName.length() == 0) {
            System.err.println("tests requires a BUCKET_NAME env value");
            throw new RuntimeException("tests requires a BUCKET_NAME env value");
        }
        System.out.println("executing test again bucket: " + bucketName);
    }

    private static void createSecretKey() {
        secretKey = new byte[32];
        new SecureRandom().nextBytes(secretKey);
    }

    private static void createRandomData() {
        Random random = new Random();
        random.nextBytes(randomData1Mb.array());
        random.nextBytes(randomData100b.array());
        random.nextBytes(randomData10Mb.array());
    }

    private static void createSimpleBackupsFolder() throws IOException, NoSuchAlgorithmException {
        /**
         * A couple of files in different sizes in different folders.
         */
        backupsOne = rootTestDataFolder.resolve("backups-1");
        createDirectory(backupsOne);
        Path file1 = createFile(backupsOne.resolve("file-1.txt"));
        backupsOneFiles.add(file1);
        writeFile100Bytes(file1, 1);
        file1Key = backupsOne.relativize(file1).toString();
        fileToSha256Base64.put(file1Key, Util.sha256Base64ForFile(file1));

        Path file2 = createFile(backupsOne.resolve("file-2.txt"));
        backupsOneFiles.add(file2);
        writeFileTenMB(file2, 1);
        file2Key = backupsOne.relativize(file2).toString();
        fileToSha256Base64.put(file2Key, Util.sha256Base64ForFile(file2));

        Path largeFile = createFile(backupsOne.resolve("file-large.txt"));
        backupsOneFiles.add(largeFile);
        writeFileTenMB(largeFile, 3);
        largeFileKey = backupsOne.relativize(largeFile).toString();
        fileToSha256Base64.put(largeFileKey, Util.sha256Base64ForFile(largeFile));

        Path folder1 = createDirectory(backupsOne.resolve("folder-1"));

        Path file4 = createFile(folder1.resolve("file-4.txt"));
        backupsOneFiles.add(file4);
        writeFileTenMB(file4, 2);
        fileToSha256Base64.put(backupsOne.relativize(file4).toString(), Util.sha256Base64ForFile(file4));

        Path subFolder1 = createDirectory(folder1.resolve("sub-folder-1"));

        Path file5 = createFile(subFolder1.resolve("file-5.txt"));
        writeFileMB(file5, 3);
        backupsOneFiles.add(file5);
        fileToSha256Base64.put(backupsOne.relativize(file5).toString(), Util.sha256Base64ForFile(file5));

        Path folder2 = createDirectory(backupsOne.resolve("folder-2"));

        Path file6 = createFile(folder2.resolve("file-6.txt"));
        backupsOneFiles.add(file6);
        writeFile100Bytes(file6, 3);
        file6Key = backupsOne.relativize(file6).toString();
        fileToSha256Base64.put(file6Key, Util.sha256Base64ForFile(file6));

    }

    private static void writeFile100Bytes(Path path, int hundredBytesCount) throws IOException {
        try (OutputStream outputStream = Files.newOutputStream(path)) {
            for (int i = 0; i < hundredBytesCount; i++) {
                outputStream.write(randomData100b.array(), 0, randomData100b.array().length);
            }
        }

    }

    private static void writeFileMB(Path path, int mbCount) throws IOException {
        try (OutputStream outputStream = Files.newOutputStream(path)) {
            for (int i = 0; i < mbCount; i++) {
                outputStream.write(randomData1Mb.array(), 0, randomData1Mb.array().length);
            }
        }

    }

    private static void writeFileTenMB(Path path, int tenMbCount) throws IOException {
        try (OutputStream outputStream = Files.newOutputStream(path)) {
            for (int i = 0; i < tenMbCount; i++) {
                outputStream.write(randomData10Mb.array(), 0, randomData10Mb.array().length);
            }
        }

    }

    @AfterAll
    static void deleteTestData() throws IOException {
        Util.deleteFolderRecursively(rootTestDataFolder);
    }


    @Test
    void backupVerifyAndChangeFolder() throws NoSuchAlgorithmException, NoSuchProviderException, InvalidKeyException, IOException, ExecutionException, InterruptedException {
        String backupName = createRandomBackupName();

        FolderToBackupConfig folderToBackupConfig = new FolderToBackupConfig(backupsOne.toString(), backupName);

        TestConfigProvider testConfigProvider = new TestConfigProvider(
                secretKey,
                bucketName,
                List.of(folderToBackupConfig),
                TEN_MB);
        Impl impl = new Impl(testConfigProvider);
        List<BackupFolderSummary> backupFolderSummaries = impl.backupFolders(false);
        assertThat(backupFolderSummaries).hasSize(1);
        BackupFolderSummary backupFolderSummary = backupFolderSummaries.get(0);
        assertThat(backupFolderSummary.backedUpFiles()).hasSize(6);
        assertThat(backupFolderSummary.deletedFiles()).isEmpty();

        // all files are changed files
        for (BackedUpFile backedUpFile : backupFolderSummary.backedUpFiles()) {
            assertThat(backedUpFile).isInstanceOf(ChangedFile.class);
            ChangedFile changedFile = (ChangedFile) backedUpFile;
            String expectedSha256 = fileToSha256Base64.get(changedFile.relativePath());
            assertThat(expectedSha256).isNotNull();
            assertThat(changedFile.sha256Base64()).isEqualTo(expectedSha256);
        }
        impl.verifyAllBackup();

        // now delete two files and change another one
        backupsOneFiles.get(0).toFile().delete();
        backupsOneFiles.get(2).toFile().delete();

        Files.writeString(backupsOneFiles.get(backupsOneFiles.size() - 1),
                "hello",
                StandardOpenOption.APPEND);

        List<BackupFolderSummary> backupFolderSummaries1 = impl.backupFolders(false);
        assertThat(backupFolderSummaries1).hasSize(1);
        BackupFolderSummary backupFolderSummary1 = backupFolderSummaries1.get(0);
        assertThat(backupFolderSummary1.backedUpFiles()).hasSize(4);
        assertThat(backupFolderSummary1.backedUpFiles()
                .stream()
                .filter(backedUpFile -> backedUpFile instanceof UnchangedFile)).hasSize(3);
        assertThat(backupFolderSummary1.backedUpFiles()
                .stream()
                .filter(backedUpFile -> backedUpFile instanceof ChangedFile)).hasSize(1);
        assertThat(backupFolderSummary1.deletedFiles()).hasSize(2);

        impl.verifyAllBackup();

        Map<String, Map<String, List<HistoricFile>>> allBackedUpFilesIncludingHistory = impl.allBackedUpFilesIncludingHistory();
        assertThat(allBackedUpFilesIncludingHistory).hasSize(1);
        assertThat(allBackedUpFilesIncludingHistory.keySet().iterator().next()).isEqualTo(backupName);

        Map<String, List<HistoricFile>> history = allBackedUpFilesIncludingHistory.get(backupName);
        assertThat(history).hasSize(6);
        assertThat(history.get(file1Key)).hasSize(2);
        assertThat(history.get(file1Key).get(0)).isInstanceOf(HistoricFile.HistoricBackedUpFile.class);
        assertThat(history.get(file1Key).get(1)).isInstanceOf(HistoricFile.HistoricDeletedFile.class);

        assertThat(history.get(file2Key)).hasSize(1);
        assertThat(history.get(file2Key).get(0)).isInstanceOf(HistoricFile.HistoricBackedUpFile.class);

        assertThat(history.get(largeFileKey)).hasSize(2);
        assertThat(history.get(largeFileKey).get(0)).isInstanceOf(HistoricFile.HistoricBackedUpFile.class);
        assertThat(history.get(largeFileKey).get(1)).isInstanceOf(HistoricFile.HistoricDeletedFile.class);

        assertThat(history.get(file6Key)).hasSize(2);
        assertThat(history.get(file6Key).get(0)).isInstanceOf(HistoricFile.HistoricBackedUpFile.class);
        assertThat(history.get(file6Key).get(1)).isInstanceOf(HistoricFile.HistoricBackedUpFile.class);

        /**
         * Restore historic versions of file-large
         */
        Path tempDirectory = Files.createTempDirectory("spb-e2e");
        impl.restoreHistoricFile(backupName, tempDirectory, (HistoricFile.HistoricBackedUpFile) history.get("file-large.txt").get(0));

        Path restoredFileLarge = tempDirectory.resolve("file-large.txt");
        String restoredFileSha256Base64FileLarge = Util.sha256Base64ForFile(restoredFileLarge);
        assertThat(restoredFileSha256Base64FileLarge).isEqualTo(fileToSha256Base64.get("file-large.txt"));

        /**
         * Restore historic versions of file-6
         */
        impl.restoreHistoricFile(backupName, tempDirectory, (HistoricFile.HistoricBackedUpFile) history.get(file6Key).get(0));

        Path restoredFile6 = tempDirectory.resolve(file6Key);
        String restoredFileSha256Base64File6 = Util.sha256Base64ForFile(restoredFile6);
        assertThat(restoredFileSha256Base64File6).isEqualTo(fileToSha256Base64.get(file6Key));


    }

    private static String createRandomBackupName() {
        byte[] randomBytes = new byte[10];
        new Random().nextBytes(randomBytes);
        String suffix = Base64.getUrlEncoder().encodeToString(randomBytes);
        return "spb-e2e-test-" + suffix;
    }

    @Test
    void dryRun() throws IOException, ExecutionException, InterruptedException {
        String backupName = createRandomBackupName();

        FolderToBackupConfig folderToBackupConfig = new FolderToBackupConfig(backupsOne.toString(), backupName);

        TestConfigProvider testConfigProvider = new TestConfigProvider(
                secretKey,
                bucketName,
                List.of(folderToBackupConfig),
                TEN_MB);
        Impl impl = new Impl(testConfigProvider);
        List<BackupFolderSummary> backupFolderSummaries = impl.backupFolders(true);
        assertThat(backupFolderSummaries).hasSize(1);
        BackupFolderSummary backupFolderSummary = backupFolderSummaries.get(0);
        assertThat(backupFolderSummary.backedUpFiles()).hasSize(6);
        assertThat(backupFolderSummary.deletedFiles()).isEmpty();

        // all files are changed files
        for (BackedUpFile backedUpFile : backupFolderSummary.backedUpFiles()) {
            assertThat(backedUpFile).isInstanceOf(ChangedFile.class);
            ChangedFile changedFile = (ChangedFile) backedUpFile;
            String expectedSha256 = fileToSha256Base64.get(changedFile.relativePath());
            assertThat(expectedSha256).isNotNull();
            assertThat(changedFile.sha256Base64()).isEqualTo(expectedSha256);
        }


    }
}
