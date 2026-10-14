package org.retrobridge.galaxyidentity;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.IBinder;
import java.io.File;
import java.lang.reflect.Method;

/** Fixed-purpose, root-launched package check for the captured FL24 framework. */
public final class IdentityMain {
    private static final class Snapshot {
        int uid, version;
        long updated;
        String path, signer;
    }
    private static Snapshot snapshot(Object pm, Class<?> api) throws Exception {
        Method getInfo = api.getMethod("getPackageInfo", String.class, Integer.TYPE);
        PackageInfo info = (PackageInfo)getInfo.invoke(pm, IdentityChecks.PACKAGE, PackageManager.GET_SIGNATURES);
        IdentityChecks.require(info != null && IdentityChecks.PACKAGE.equals(info.packageName));
        IdentityChecks.require(info.sharedUserId == null && info.applicationInfo != null);
        ApplicationInfo app = info.applicationInfo;
        IdentityChecks.require(IdentityChecks.PACKAGE.equals(app.packageName) && app.enabled && app.uid >= 10000);
        IdentityChecks.require(info.signatures != null && info.signatures.length == 1 && info.signatures[0] != null);
        byte[] certificate = info.signatures[0].toByteArray();
        IdentityChecks.require(certificate != null && certificate.length > 0 && certificate.length <= 65536);
        String[] packages = (String[])api.getMethod("getPackagesForUid", Integer.TYPE).invoke(pm, app.uid);
        IdentityChecks.require(IdentityChecks.solePackage(packages));
        int resolvedUid = (Integer)api.getMethod("getPackageUid", String.class).invoke(pm, IdentityChecks.PACKAGE);
        IdentityChecks.require(resolvedUid == app.uid && IdentityChecks.safeApkPath(app.sourceDir));
        File file = new File(app.sourceDir);
        IdentityChecks.require(app.sourceDir.equals(file.getCanonicalPath()) && file.isFile());
        Snapshot result = new Snapshot();
        result.uid = app.uid; result.version = info.versionCode; result.updated = info.lastUpdateTime;
        result.path = app.sourceDir; result.signer = IdentityChecks.hex(IdentityChecks.digest(certificate));
        return result;
    }
    public static void main(String[] args) {
        try {
            IdentityChecks.require(args != null && args.length == 1 && IdentityChecks.PACKAGE.equals(args[0]));
            IdentityChecks.require(android.os.Process.myUid() == 0);
            Class<?> services = Class.forName("android.os.ServiceManager");
            IBinder binder = (IBinder)services.getMethod("getService", String.class).invoke(null, "package");
            IdentityChecks.require(binder != null);
            Class<?> api = Class.forName("android.content.pm.IPackageManager");
            Class<?> stub = Class.forName("android.content.pm.IPackageManager$Stub");
            Object pm = stub.getMethod("asInterface", IBinder.class).invoke(null, binder);
            IdentityChecks.require(pm != null);
            Snapshot before = snapshot(pm, api);
            String apk = IdentityChecks.hashStableFile(new File(before.path));
            Snapshot after = snapshot(pm, api);
            IdentityChecks.require(before.uid == after.uid && before.version == after.version && before.updated == after.updated &&
                    before.path.equals(after.path) && before.signer.equals(after.signer));
            System.out.print(IdentityChecks.record(before.uid, before.path, apk, before.signer));
            System.out.flush();
            if (System.out.checkError()) System.exit(2);
        } catch (Throwable failure) {
            // No paths, certificates, package metadata, or exception messages on failure.
            System.err.println("IDENTITY_ERROR");
            System.exit(1);
        }
    }
}
