package spb;

import java.util.List;

public record BackupFolderSummary(String backupName,
                                  String backupFolder,
                                  List<BackedUpFile> backedUpFiles,
                                  List<DeletedFile> deletedFiles) {

    public sealed interface BackedUpFile permits BackedUpFile.UnchangedFile, BackedUpFile.ChangedFile {
        String relativePath();

        record UnchangedFile(String relativePath) implements BackedUpFile {

        }

        record ChangedFile(String relativePath,
                           String sha256Base64,
                           long fileSizeInBytes) implements BackedUpFile {

        }

    }


    public record DeletedFile(String relativePath) {

    }
}

