package spb;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import spb.Impl.FileMetadata;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;

@CommandLine.Command(name = "list", mixinStandardHelpOptions = true, description = "list all backed up files")
public class ListFiles implements Callable<Integer> {

    private final Logger logger = LoggerFactory.getLogger("SPB");

    @CommandLine.Option(names = {"--file-pattern"}, description = "A pattern to describe the file",
            paramLabel = "file-pattern", required = false)
    private String filePattern;

    @Override
    public Integer call() throws Exception {
        Pattern pattern = null;
        if (filePattern == null) {
            logger.info("no pattern specified ... listing all files");
        } else {
            logger.info("using the pattern '{}' to list backed up files", filePattern);
            pattern = Pattern.compile(filePattern);
        }
        Impl impl = new Impl();
        Map<String, List<FileMetadata>> allBackedUpFiles = impl.allBackedUpFiles();
        logger.info("Found {} backups", allBackedUpFiles.size());
        for (String backupName : allBackedUpFiles.keySet()) {
            logger.info("---");
            logger.info("Listing matching files for backup '{}'", backupName);
            List<FileMetadata> fileMetadataList = allBackedUpFiles.get(backupName);
            logger.info("total file count in backup: {}", fileMetadataList.size());
            int matchedCount = 0;
            for (FileMetadata fileMetadata : fileMetadataList) {
                if (pattern != null) {
                    if (pattern.matcher(fileMetadata.fileName()).find()) {
                        logger.info("file: " + fileMetadata.fileName());
                        logger.info("size: " + fileMetadata.originalFileSizeInBytes() + " bytes");
                        logger.info("creation date: " + fileMetadata.creationDate());
                        matchedCount++;
                    }
                } else {
                    logger.info("file: " + fileMetadata.fileName());
                    logger.info("size: " + fileMetadata.originalFileSizeInBytes() + " bytes");
                    logger.info("creation date: " + fileMetadata.creationDate());
                    matchedCount++;
                }
            }
            logger.info("******");
            if (filePattern != null) {
                logger.info("found {} matching files in {} for pattern '{}'", matchedCount, backupName, filePattern);
            } else {
                logger.info("found {} files in {}", matchedCount, backupName);
            }
        }
        impl.shutdown();
        return 0;
    }
}
