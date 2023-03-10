package spb;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Comparator;

public class Util {

    private static final int TEN_MB = 10 * 1024 * 1024;
    public static String DIVIDER = "***************";

    public static String sha256Base64ForFile(Path path) throws IOException, NoSuchAlgorithmException {
        FileInputStream is = new FileInputStream(path.toFile());

        BufferedInputStream bis = new BufferedInputStream(is);
        MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[TEN_MB];
        int bytesRead;
        while ((bytesRead = bis.read(buffer, 0, buffer.length)) != -1) {
            messageDigest.update(buffer, 0, bytesRead);
        }
        return Base64.getEncoder().encodeToString(messageDigest.digest());
    }

    public static String sha256Base64(ByteBuffer input) throws NoSuchAlgorithmException {
        MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
        messageDigest.update(input);
        input.rewind();
        return Base64.getEncoder().encodeToString(messageDigest.digest());

    }

    public static void deleteFolderRecursively(Path path) throws IOException {
        Files.walk(path)
                .sorted(Comparator.reverseOrder())
                .map(Path::toFile)
                .forEach(File::delete);
    }

    public static String bytesToHumanReadableFormat(long bytes) {
        if (bytes < 0) {
            throw new RuntimeException("should not happen: negative file size");
        }
        long kilobyte = 1024;
        long megabyte = kilobyte * 1024;
        long gigabyte = megabyte * 1024;
        long terabyte = gigabyte * 1024;

        if (bytes < kilobyte) {
            return bytes + "b";
        } else if (bytes < megabyte) {
            return (bytes / kilobyte) + "kb";
        } else if (bytes < gigabyte) {
            return (bytes / megabyte) + "mb";
        } else if (bytes < terabyte) {
            return (bytes / gigabyte) + "gb";
        } else {
            return (bytes / terabyte) + "tb";
        }
    }

}
