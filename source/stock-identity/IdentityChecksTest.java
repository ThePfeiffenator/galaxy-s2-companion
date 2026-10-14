package org.retrobridge.galaxyidentity;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

public final class IdentityChecksTest {
    private static int checks;
    private static void check(boolean ok) { checks++; if (!ok) throw new AssertionError("Check " + checks); }
    public static void main(String[] args) throws Exception {
        check(IdentityChecks.safeApkPath("/data/app/org.retrobridge.callprobe-1.apk"));
        check(IdentityChecks.safeApkPath("/data/app-private/org.retrobridge.callprobe-2.apk"));
        check(!IdentityChecks.safeApkPath("/data/app/../other.apk"));
        check(!IdentityChecks.safeApkPath("/data/app/a.apk\nIDENTITY"));
        check(!IdentityChecks.safeApkPath("/data/app/a\tb.apk"));
        check(!IdentityChecks.safeApkPath("/data/app/a b.apk"));
        check(!IdentityChecks.safeApkPath("/data/app/a/b.apk"));
        check(!IdentityChecks.safeApkPath("/system/app/a.apk"));
        check(IdentityChecks.solePackage(new String[]{IdentityChecks.PACKAGE}));
        check(!IdentityChecks.solePackage(new String[]{IdentityChecks.PACKAGE,"other"}));
        check(!IdentityChecks.solePackage(new String[]{"other"}));
        check(!IdentityChecks.solePackage(null));
        String expected="ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
        check(IdentityChecks.hex(IdentityChecks.digest("abc".getBytes("US-ASCII"))).equals(expected));
        File file=new File(args[0],"synthetic-apk.bin");
        FileOutputStream out=new FileOutputStream(file);
        try { out.write("abc".getBytes("US-ASCII")); } finally { out.close(); }
        check(IdentityChecks.hashStableFile(file).equals(expected));
        String record=IdentityChecks.record(10042,"/data/app/org.retrobridge.callprobe-1.apk",expected,expected);
        check(record.split("\t",-1).length==7 && record.endsWith("\n") && record.indexOf('\n')==record.length()-1);
        try { IdentityChecks.record(0,"/data/app/a.apk",expected,expected); throw new AssertionError(); }
        catch(IOException expectedFailure) { checks++; }
        try { IdentityChecks.record(10042,"/data/app/a.apk",expected.toUpperCase(),expected); throw new AssertionError(); }
        catch(IOException expectedFailure) { checks++; }
        System.out.println("Identity checks passed: " + checks);
    }
}
