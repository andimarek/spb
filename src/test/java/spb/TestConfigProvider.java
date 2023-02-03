package spb;

import java.util.List;

public class TestConfigProvider implements ConfigProvider {

    private final byte[] rawSecretKeyBytes;
    private final String bucketName;
    private final List<FolderToBackupConfig> foldersBackupConfig;
    private final int multiPartUploadLimitInBytes;

    public TestConfigProvider(byte[] rawSecretKeyBytes,
                              String bucketName,
                              List<FolderToBackupConfig> foldersBackupConfig,
                              int multiPartUploadLimitInBytes
    ) {
        this.rawSecretKeyBytes = rawSecretKeyBytes;
        this.bucketName = bucketName;
        this.foldersBackupConfig = foldersBackupConfig;
        this.multiPartUploadLimitInBytes = multiPartUploadLimitInBytes;
    }

    @Override
    public byte[] getRawSecretKeyBytes() {
        return rawSecretKeyBytes;
    }

    @Override
    public String getBucketName() {
        return bucketName;
    }

    @Override
    public List<FolderToBackupConfig> getFoldersBackupConfig() {
        return foldersBackupConfig;
    }

    @Override
    public int getMultiPartUploadLimitInBytes() {
        return multiPartUploadLimitInBytes;
    }
}
