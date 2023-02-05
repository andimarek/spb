package spb;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

public class PropertiesConfigProvider implements ConfigProvider {

    static int HUNDRED_MB = 100 * 1024 * 1024;
    private static final String SPB_CONFIG_FILE = "spb.config";
    private static final String CONFIG_BUCKET_NAME = "bucket.name";
    private static final String CONFIG_SECRET_KEY = "secret.key";
    private final Logger logger = LoggerFactory.getLogger("spb");

    private byte[] rawSecretKeyBytes;
    private String bucketName;
    private List<FolderToBackupConfig> foldersBackupConfig;

    public PropertiesConfigProvider() throws IOException {
        readConfigFile();
    }

    private void readConfigFile() throws IOException {
        Properties properties = new Properties();
        FileInputStream fileInputStream;
        File configFile = new File(System.getProperty("user.home"), SPB_CONFIG_FILE);
        try {
            fileInputStream = new FileInputStream(configFile);
        } catch (FileNotFoundException e) {
            logger.error("~/{} not found or can't be read", SPB_CONFIG_FILE, e);
            throw new RuntimeException("Invalid config");
        }
        logger.info("Start reading config file {}", configFile);
        properties.load(fileInputStream);
        bucketName = properties.getProperty(CONFIG_BUCKET_NAME);
        if (bucketName == null || bucketName.length() == 0) {
            logger.error("Invalid config: {} expected to specify the S3 bucket name", CONFIG_BUCKET_NAME);
            throw new RuntimeException("Invalid config");
        }
        logger.info("Using S3 bucket '{}'", bucketName);
        String secretKey = properties.getProperty(CONFIG_SECRET_KEY);
        if (secretKey == null || secretKey.length() == 0) {
            logger.error("Invalid config: {} expected to specify the secret key", CONFIG_SECRET_KEY);
            throw new RuntimeException("Invalid config");
        }
        try {
            rawSecretKeyBytes = Base64.getDecoder().decode(secretKey);
        } catch (IllegalArgumentException e) {
            logger.error("Invalid config: {} expected to be Base64 encoded", CONFIG_SECRET_KEY);
            throw new RuntimeException("Invalid config");
        }
        if (rawSecretKeyBytes.length != 32) {
            logger.error("expected Base64 encoded 256 bits/32 bytes long secret key, but found {} bits/{} bytes", rawSecretKeyBytes.length * 8, rawSecretKeyBytes.length);
            throw new RuntimeException("invalid config");
        }
        foldersBackupConfig = readFilesToBackupConfig(properties);
        logger.info("{} backup folders", foldersBackupConfig.size());
    }


    private List<FolderToBackupConfig> readFilesToBackupConfig(Properties properties) {
        List<FolderToBackupConfig> result = new ArrayList<>();
        Set<Object> keys = properties.keySet();
        Set<String> backupPropertyPrefixes = new LinkedHashSet<>();
        for (Object o : keys) {
            String key = (String) o;
            if (key.matches("backup\\.\\d+\\..+")) {
                backupPropertyPrefixes.add(key.substring(0, key.indexOf(".", key.indexOf(".") + 1)));
            }
        }

        for (String backupPropertyPrefix : backupPropertyPrefixes) {
            String backupFolder = properties.getProperty(backupPropertyPrefix + ".folder");
            if (backupFolder == null || backupFolder.length() == 0) {
                logger.error("invalid config for {}. Expected {}.folder", backupPropertyPrefix, backupFolder);
                continue;
            }
            String backupName = properties.getProperty(backupPropertyPrefix + ".name");
            if (backupName == null || backupName.length() == 0) {
                logger.error("invalid config for {}. Expected {}.name", backupPropertyPrefix, backupFolder);
                continue;
            }
            result.add(new FolderToBackupConfig(backupFolder, backupName));
        }
        return result;
    }

    public byte[] getRawSecretKeyBytes() {
        return rawSecretKeyBytes;
    }

    public String getBucketName() {
        return bucketName;
    }

    public List<FolderToBackupConfig> getFoldersBackupConfig() {
        return foldersBackupConfig;
    }

    @Override
    public int getMultiPartUploadLimitInBytes() {
        return HUNDRED_MB;
    }
}
