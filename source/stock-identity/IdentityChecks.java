package org.retrobridge.galaxyidentity;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.MessageDigest;

/** Pure checks shared by the device entry point and local synthetic tests. */
final class IdentityChecks {
    static final String PACKAGE = "org.retrobridge.callprobe";
    static final long MAX_APK = 64L * 1024L * 1024L;
    static void require(boolean value) throws IOException {
        if (!value) throw new IOException("Identity check failed");
    }
    static boolean safeApkPath(String value) {
        if (value == null || value.length() > 512) return false;
        String prefix = value.startsWith("/data/app/") ? "/data/app/" :
                value.startsWith("/data/app-private/") ? "/data/app-private/" : null;
        if (prefix == null) return false;
        String name = value.substring(prefix.length());
        if (name.length() < 5 || !name.endsWith(".apk")) return false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ||
                    (c >= '0' && c <= '9') || c == '.' || c == '-' || c == '_')) return false;
        }
        return !name.equals("..apk");
    }
    static boolean solePackage(String[] packages) {
        return packages != null && packages.length == 1 && PACKAGE.equals(packages[0]);
    }
    static String hex(byte[] value) {
        final char[] digits = "0123456789abcdef".toCharArray();
        char[] result = new char[value.length * 2];
        for (int i = 0; i < value.length; i++) {
            result[2 * i] = digits[(value[i] & 255) >>> 4];
            result[2 * i + 1] = digits[value[i] & 15];
        }
        return new String(result);
    }
    static byte[] digest(byte[] bytes) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(bytes);
    }
    static String hashStableFile(File file) throws Exception {
        require(file.isFile());
        long length = file.length(), modified = file.lastModified();
        require(length > 0 && length <= MAX_APK);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        FileInputStream input = new FileInputStream(file);
        long total = 0;
        try {
            byte[] buffer = new byte[16384];
            int count;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                require(total <= MAX_APK && total <= length);
                digest.update(buffer, 0, count);
            }
        } finally { input.close(); }
        require(total == length && file.length() == length && file.lastModified() == modified);
        return hex(digest.digest());
    }
    static String record(int uid, String path, String apk, String signer) throws IOException {
        require(uid >= 10000 && safeApkPath(path) && lowerHash(apk) && lowerHash(signer));
        return "IDENTITY\t1\t" + uid + "\t" + PACKAGE + "\t" + path + "\t" + apk + "\t" + signer + "\n";
    }
    private static boolean lowerHash(String value) {
        if (value == null || value.length() != 64) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) return false;
        }
        return true;
    }
}
