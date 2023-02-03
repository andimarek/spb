package spb;

import java.util.List;

public interface ConfigProvider {


    byte[] getRawSecretKeyBytes();

    String getBucketName();

    List<FolderToBackupConfig> getFoldersBackupConfig();

    int getMultiPartUploadLimitInBytes();
}
