package spb;

import picocli.CommandLine;

import java.util.concurrent.Callable;

@CommandLine.Command(name = "spb",
        mixinStandardHelpOptions = true,
        subcommands = {Backup.class, Restore.class, ListFiles.class, Verify.class, GenerateKey.class},
        versionProvider = SpbVersionProvider.class,
        description = "Simple and secure personal backup")
public class Spb implements Callable<Integer> {

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public Integer call() throws Exception {
        spec.commandLine().usage(System.err);
        return 0;
    }
}
