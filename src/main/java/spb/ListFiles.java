package spb;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import spb.Impl.FileMetadata;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;

import static spb.Util.bytesToHumanReadableFormat;

@CommandLine.Command(name = "list", mixinStandardHelpOptions = true, description = "list all backed up files")
public class ListFiles implements Callable<Integer> {

    private final Logger logger = LoggerFactory.getLogger("SPB");

    @CommandLine.Option(names = {"--file-pattern"}, description = "A pattern to describe the file",
            paramLabel = "file-pattern", required = false)
    private String filePattern;

    @CommandLine.Option(names = {"--backup"}, description = "Restrict the search to this backup",
            paramLabel = "backup", required = false)
    private String backupName;

    @CommandLine.Option(names = {"--historical"}, description = "Includes all past versions in the search",
            paramLabel = "historical", required = false)
    private boolean historical;

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

        if (historical) {
            logger.info("listing files including history");
            Map<String, Map<String, List<Impl.HistoricFile>>> allBackedUpFiles = impl.allBackedUpFilesIncludingHistory();
            logger.info("Found {} backups", allBackedUpFiles.size());
            logger.info("*******");
            int matchedCount = 0;
            for (String backupName : allBackedUpFiles.keySet()) {
                Map<String, List<Impl.HistoricFile>> filesForOneBackup = allBackedUpFiles.get(backupName);
                logger.info("Listing matching files for backup '{}' with {} total files", backupName, filesForOneBackup.size());
                for (String file : filesForOneBackup.keySet()) {
                    List<Impl.HistoricFile> allFileVersions = filesForOneBackup.get(file);
                    if (pattern != null) {
                        if (pattern.matcher(file).matches()) {
                            printAllVersions(file, allFileVersions);
                            matchedCount++;
                        }
                    } else {
                        printAllVersions(file, allFileVersions);
                        matchedCount++;
                    }
                }
            }
            logger.info("******");
            if (filePattern != null) {
                logger.info("found {} matching files in {} for pattern '{}'", matchedCount, backupName, filePattern);
            } else {
                logger.info("found {} files in {}", matchedCount, backupName);
            }
            logger.info("******");
        } else {
            Map<String, List<FileMetadata>> allBackedUpFiles = impl.allBackedUpFiles();
            logger.info("Found {} backups", allBackedUpFiles.size());
            for (String backupName : allBackedUpFiles.keySet()) {
                logger.info("*******");
                List<FileMetadata> fileMetadataList = allBackedUpFiles.get(backupName);
                logger.info("Listing matching files for backup '{}' with {} total files", backupName, fileMetadataList);
                int matchedCount = 0;
                for (FileMetadata fileMetadata : fileMetadataList) {
                    if (pattern != null) {
                        if (pattern.matcher(fileMetadata.fileName()).matches()) {
                            logger.info("file: " + fileMetadata.fileName());
                            logger.info("size: " + bytesToHumanReadableFormat(fileMetadata.originalFileSizeInBytes()));
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
                logger.info("******");
            }
        }
        impl.shutdown();
        return 0;
    }

    private void printAllVersions(String file, List<Impl.HistoricFile> allFileVersions) {
        logger.info("All {} versions of file: {}", allFileVersions.size(), file);
        for (Impl.HistoricFile historicFile : allFileVersions) {
            if (historicFile instanceof Impl.HistoricFile.HistoricBackedUpFile historicBackedUpFile) {
                logger.info("backup date:{}, ", historicFile.date());
                logger.info("size: {}", bytesToHumanReadableFormat(historicBackedUpFile.originalFileSizeInBytes()));
                logger.info("version-id: {}", historicBackedUpFile.metadataVersionId());
            } else if (historicFile instanceof Impl.HistoricFile.HistoricDeletedFile historicDeletedFile) {
                logger.info("file was deleted on {}", historicDeletedFile.date());
            }
        }
    }
}
