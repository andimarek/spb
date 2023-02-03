package spb;

import picocli.CommandLine.Command;

import java.util.concurrent.Callable;

@Command(name = "verify", mixinStandardHelpOptions = true, description = "verify backups")
public class Verify implements Callable<Integer> {

    @Override
    public Integer call() throws Exception {
        Impl impl = new Impl();
        impl.verifyAllBackup();
        impl.shutdown();
        return 0;
    }
}
