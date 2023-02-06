package spb;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.File;
import java.util.concurrent.Callable;

@Command(name = "restore", mixinStandardHelpOptions = true, description = "restore previously backed up files")
public class Restore implements Callable<Integer> {

    @Option(names = {"--target-folder"}, description = "The folder to restore files into",
            paramLabel = "target-folder", required = true)
    private File targetFolder;

    @Option(names = {"--backup-name"}, description = "The name of the backup", paramLabel = "backupName", required = true)
    private String backupName;

    @Option(names = {"--file-name"}, description = "A specific file to restore", paramLabel = "file", required = false)
    private String file;

    @CommandLine.Option(names = {"--version-id"}, description = "The version of a specific file to restore. " +
            "Only allowed in combination with --file-name",
            paramLabel = "versionId", required = false)
    private String versionId;


    @Override
    public Integer call() throws Exception {
        Impl impl = new Impl();
        if (file == null) {
            impl.restoreFullBackup(backupName, targetFolder.toPath());
        } else {
            if (versionId == null) {
                impl.restoreFile(targetFolder.toPath(), backupName, file);
            } else {
                impl.restoreHistoricalFile(backupName, targetFolder.toPath(), file, versionId);
            }
        }
        impl.shutdown();
        return 0;
    }
}
