package spb;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;

@Command(name = "backup", mixinStandardHelpOptions = true, description = "initiate backup of a folder")
public class Backup implements Callable<String> {

    @Option(names = {"--dry-run"}, description = "Show what an actual backup would mean", paramLabel = "dry-run")
    private boolean dryRun;

    @Override
    public String call() throws Exception {
        Impl impl = new Impl();
        impl.backupFolders(dryRun);
        impl.shutdown();
        return "success";
    }
}
