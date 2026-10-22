package io.rikka.starrailresolution.core;

import java.io.*;
import java.security.*;

public final class Hashing {
    public static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) out.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return out.toString();
    }
    public static String sha256(byte[] data) { return hex(digest("SHA-256").digest(data)); }
    public static String md5(File file) throws IOException { return substitutedMd5(file, -1, null); }

    /**
     * Hash a virtual file with one replacement range. This validates the whole
     * container against the game's original manifest without editing it or
     * loading a 128 MB resource pack into the application's heap.
     */
    public static String substitutedMd5(File file, long offset, byte[] replacement) throws IOException {
        MessageDigest digest = digest("MD5");
        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buffer = new byte[131072];
            long position = 0;
            int count;
            while ((count = in.read(buffer)) != -1) {
                if (replacement != null && position < offset + replacement.length && position + count > offset) {
                    int first = (int) Math.max(0, offset - position);
                    int last = (int) Math.min(count, offset + replacement.length - position);
                    System.arraycopy(replacement, (int) (position + first - offset), buffer, first, last - first);
                }
                digest.update(buffer, 0, count); position += count;
            }
            if (replacement != null && (offset < 0 || offset > position - replacement.length)) {
                throw new IOException("替换范围超出资源包边界");
            }
        }
        return hex(digest.digest());
    }
    private static MessageDigest digest(String algorithm) {
        try { return MessageDigest.getInstance(algorithm); }
        catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
    private Hashing() {}
}
