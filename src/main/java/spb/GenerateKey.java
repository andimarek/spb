package spb;

import picocli.CommandLine;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "generate-key", mixinStandardHelpOptions = true, description = "generate a new random key")
public class GenerateKey implements Callable<Integer> {
    @Override
    public Integer call() throws Exception {
        byte[] newKey = new byte[32];
        SecureRandom secureRandom = new SecureRandom();
        secureRandom.nextBytes(newKey);
        // not logging, just printing
        String keyBase64 = Base64.getEncoder().encodeToString(newKey);
        System.out.println("new 256 bit key, Base64 encoded: " + keyBase64);
        return 0;
    }
}
