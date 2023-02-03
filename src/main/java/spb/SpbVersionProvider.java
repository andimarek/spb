package spb;

import picocli.CommandLine;

public class SpbVersionProvider implements CommandLine.IVersionProvider {
    @Override
    public String[] getVersion() throws Exception {
        Package aPackage = Main.class.getPackage();
        String implementationVersion = aPackage.getImplementationVersion();
        return new String[]{implementationVersion};
    }
}
