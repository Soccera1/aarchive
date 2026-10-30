package dev.aarchive;

import android.content.Context;
import android.content.pm.*;
import android.os.Build;
import java.io.*;
import java.util.*;

final class UpdateVerifier {
    private UpdateVerifier() {}
    static long version(PackageInfo info) { return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode; }
    private static Set<String> signers(PackageInfo info) throws IOException {
        Signature[] signatures = info.signatures;
        if (Build.VERSION.SDK_INT >= 28 && info.signingInfo != null) {
            Signature[] current = info.signingInfo.getApkContentsSigners();
            if (current != null && current.length > 0) signatures = current;
        }
        if (signatures == null || signatures.length == 0) throw new IOException("The APK has no signing certificate.");
        Set<String> result = new HashSet<>();
        for (Signature signature : signatures) result.add(signature.toCharsString());
        return result;
    }
    static void verify(Context context, File apk, UpdateClient.Candidate candidate) throws Exception {
        PackageManager manager = context.getPackageManager();
        // Early Android 9 archive parsers can omit SigningInfo. Request the
        // legacy certificate array too, while preferring current signers.
        int flags = PackageManager.GET_META_DATA | PackageManager.GET_SIGNATURES;
        if (Build.VERSION.SDK_INT >= 28) flags |= PackageManager.GET_SIGNING_CERTIFICATES;
        PackageInfo installed = manager.getPackageInfo(context.getPackageName(), flags);
        PackageInfo incoming = manager.getPackageArchiveInfo(apk.getAbsolutePath(), flags);
        if (installed.applicationInfo == null || installed.applicationInfo.metaData == null
                || !candidate.channel.equals(installed.applicationInfo.metaData.getString("dev.aarchive.UPDATE_CHANNEL"))
                || !candidate.repository.equals(installed.applicationInfo.metaData.getString("dev.aarchive.UPDATE_REPOSITORY")))
            throw new IOException("The installed app belongs to a different update channel or repository.");
        if (incoming == null || !context.getPackageName().equals(incoming.packageName)) throw new IOException("This APK is not AArchive.");
        if (version(incoming) != candidate.versionCode || version(incoming) <= version(installed)
                || !Objects.equals(incoming.versionName, candidate.versionName)) throw new IOException("The APK version does not match the update.");
        if (incoming.applicationInfo == null || incoming.applicationInfo.metaData == null
                || !candidate.channel.equals(incoming.applicationInfo.metaData.getString("dev.aarchive.UPDATE_CHANNEL"))
                || !candidate.repository.equals(incoming.applicationInfo.metaData.getString("dev.aarchive.UPDATE_REPOSITORY")))
            throw new IOException("The APK belongs to a different update channel or repository.");
        if (!signers(installed).equals(signers(incoming))) throw new IOException("The update uses a different signing certificate.");
    }
}
