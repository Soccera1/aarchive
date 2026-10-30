package dev.aarchive;

import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.net.Uri;
import android.os.Bundle;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;

/** Real Android JSON, APK parsing/signatures and content-provider checks; no network. */
public final class UpdateInstrumentation extends Instrumentation {
    private int assertions;
    private Bundle arguments;
    private interface Action { void run() throws Exception; }
    private void check(boolean value, String message) { assertions++; if (!value) throw new AssertionError(message); }
    private void rejected(Action action, String text) throws Exception {
        try { action.run(); throw new AssertionError("Accepted invalid update: " + text); }
        catch (IOException expected) { check(expected.getMessage() != null, text); }
    }
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); this.arguments = arguments; start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            tests();
            result.putString("stream", "Updater passed " + assertions + " Android assertions.\n");
            if ("true".equals(arguments.getString("ui"))) {
                sendStatus(0, result);
                installerUi();
                return; // The external UI test completes installation, replacing this process.
            }
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("stream", android.util.Log.getStackTraceString(error));
            finish(Activity.RESULT_CANCELED, result);
        }
    }
    private byte[] asset(String name) throws IOException {
        try (InputStream input = getContext().getAssets().open(name); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[65536];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toByteArray();
        }
    }
    private String hash(byte[] data) throws Exception {
        StringBuilder value = new StringBuilder();
        for (byte part : MessageDigest.getInstance("SHA-256").digest(data)) value.append(String.format(Locale.ROOT, "%02x", part & 255));
        return value.toString();
    }
    private String repository() throws Exception {
        return getTargetContext().getPackageManager().getApplicationInfo(getTargetContext().getPackageName(), PackageManager.GET_META_DATA)
                .metaData.getString("dev.aarchive.UPDATE_REPOSITORY");
    }
    private UpdateClient.Candidate candidate(byte[] apk) throws Exception {
        File file = new File(getTargetContext().getCacheDir(), "test-version.apk");
        Files.write(file.toPath(), apk);
        PackageInfo info = getTargetContext().getPackageManager().getPackageArchiveInfo(file.toString(), 0);
        check(info != null, "Parse actual candidate APK");
        file.delete();
        return new UpdateClient.Candidate("github-master", repository(), info.versionName, UpdateVerifier.version(info), apk.length, hash(apk), "https://api.github.com/test-apk");
    }
    private static final class Server implements UpdateClient.Transport {
        final Map<String, byte[]> responses = new HashMap<>();
        int calls;
        @Override public InputStream open(String url, boolean asset) throws IOException {
            calls++;
            byte[] data = responses.get(url);
            if (data == null) throw new IOException("Test endpoint unavailable: " + url);
            return new ByteArrayInputStream(data);
        }
        void json(String url, JSONObject data) { responses.put(url, data.toString().getBytes(StandardCharsets.UTF_8)); }
    }
    private JSONObject manifest(UpdateClient.Candidate candidate) throws Exception {
        return new JSONObject().put("schemaVersion", 1).put("channel", candidate.channel).put("repository", candidate.repository)
                .put("versionCode", candidate.versionCode).put("versionName", candidate.versionName)
                .put("apk", "aarchive-master-universal.apk").put("size", candidate.size).put("sha256", candidate.sha256);
    }
    private JSONObject release(String tag, boolean prerelease, long size) throws Exception {
        JSONArray assets = new JSONArray()
                .put(new JSONObject().put("name", "update.json").put("id", 11).put("state", "uploaded"))
                .put(new JSONObject().put("name", "aarchive-master-universal.apk").put("id", 12).put("state", "uploaded").put("size", size));
        return new JSONObject().put("tag_name", tag).put("draft", false).put("prerelease", prerelease).put("assets", assets);
    }
    private void tests() throws Exception {
        Context context = getTargetContext();
        byte[] apk = asset("candidate.apk");
        UpdateClient.Candidate expected = candidate(apk);
        long installed = UpdateVerifier.version(context.getPackageManager().getPackageInfo(context.getPackageName(), 0));
        check(expected.versionCode > installed, "Test APK is newer than installed APK");
        UpdatePolicy policy = new UpdatePolicy("github-master", repository(), installed);
        Server server = new Server();
        String assetBase = "https://api.github.com/repos/" + repository() + "/releases/assets/";
        server.json(policy.endpoint(), release("master", true, apk.length));
        server.json(assetBase + "11", manifest(expected));
        server.responses.put(assetBase + "12", apk);
        UpdateClient client = new UpdateClient(policy, server);
        UpdateClient.Candidate found = client.check();
        check(found != null && found.versionCode == expected.versionCode, "Find same-channel newer master release");
        check(found.apkUrl.equals(assetBase + "12"), "Pin immutable asset ID");
        File output = new File(context.getCacheDir(), "test-download.part");
        client.download(found, output, (received, total) -> {});
        check(Arrays.equals(Files.readAllBytes(output.toPath()), apk), "Download exact APK bytes");
        UpdateVerifier.verify(context, output, found);
        check(true, "Real APK passes package, channel, repository, version and certificate verification");

        int before = server.calls;
        check(new UpdateClient(new UpdatePolicy("none", repository(), installed), server).check() == null, "Local builds have no updates");
        check(server.calls == before, "Local build makes no network calls");
        server.json(policy.endpoint(), release("v2.0", false, apk.length));
        check(client.check() == null, "Master cannot take stable release");
        server.json(policy.endpoint(), release("master", true, apk.length).put("draft", true));
        check(client.check() == null, "Reject draft release");
        server.json(policy.endpoint(), release("master", true, apk.length));
        server.json(assetBase + "11", manifest(expected).put("versionCode", installed));
        check(client.check() == null, "No reinstall of current version");
        server.json(assetBase + "11", manifest(expected).put("channel", "github-release"));
        rejected(client::check, "Reject cross-channel metadata");
        server.json(assetBase + "11", manifest(expected).put("repository", "other/repository"));
        rejected(client::check, "Reject cross-repository metadata");
        server.json(assetBase + "11", manifest(expected).put("size", apk.length + 1));
        rejected(client::check, "Reject release/metadata size mismatch");
        server.json(assetBase + "11", manifest(expected));
        server.responses.put(assetBase + "11", new byte[1024 * 1024 + 1]);
        rejected(client::check, "Bound metadata download");
        server.json(assetBase + "11", manifest(expected));

        UpdatePolicy stable = new UpdatePolicy("github-release", repository(), installed);
        UpdateClient stableClient = new UpdateClient(stable, server);
        server.json(stable.endpoint(), release("master", true, apk.length));
        check(stableClient.check() == null, "Stable cannot take master");
        JSONObject stableRelease = release("v2.0", false, apk.length);
        stableRelease.getJSONArray("assets").getJSONObject(1).put("name", "aarchive-universal.apk");
        server.json(stable.endpoint(), stableRelease);
        server.json(assetBase + "11", manifest(expected).put("channel", "github-release").put("apk", "aarchive-universal.apk"));
        check(stableClient.check() != null, "Stable discovers stable update");
        server.json(stable.endpoint(), stableRelease.put("prerelease", true));
        check(stableClient.check() == null, "Stable ignores prereleases");

        byte[] corrupt = apk.clone(); corrupt[corrupt.length / 2] ^= 1;
        server.responses.put(found.apkUrl, corrupt);
        rejected(() -> client.download(found, output, (received, total) -> {}), "Reject checksum mismatch");
        check(!output.exists(), "Checksum failure removes partial APK");
        server.responses.put(found.apkUrl, Arrays.copyOf(apk, apk.length - 1));
        rejected(() -> client.download(found, output, (received, total) -> {}), "Reject truncated download");
        check(!output.exists(), "Truncated download removes partial APK");
        server.responses.put(found.apkUrl, Arrays.copyOf(apk, apk.length + 1));
        rejected(() -> client.download(found, output, (received, total) -> {}), "Reject oversized download");
        server.responses.put(found.apkUrl, apk);
        rejected(() -> client.download(found, output, (received, total) -> { throw new IOException("Cancelled"); }), "Cancel download");
        check(!output.exists(), "Cancelled download removes partial APK");

        Files.write(output.toPath(), apk);
        UpdateClient.Candidate crossChannel = new UpdateClient.Candidate("github-release", repository(), found.versionName, found.versionCode, found.size, found.sha256, found.apkUrl);
        rejected(() -> UpdateVerifier.verify(context, output, crossChannel), "Reject installed/candidate channel mismatch");
        UpdateClient.Candidate wrongVersion = new UpdateClient.Candidate(found.channel, repository(), found.versionName, found.versionCode + 1, found.size, found.sha256, found.apkUrl);
        rejected(() -> UpdateVerifier.verify(context, output, wrongVersion), "Reject APK version mismatch");
        Files.write(output.toPath(), asset("wrong-signer.apk"));
        rejected(() -> UpdateVerifier.verify(context, output, found), "Reject differently signed APK with otherwise identical metadata");
        Files.write(output.toPath(), asset("wrong-channel.apk"));
        PackageInfo otherChannel = context.getPackageManager().getPackageArchiveInfo(output.toString(), 0);
        UpdateClient.Candidate wrongChannel = new UpdateClient.Candidate(found.channel, repository(), otherChannel.versionName,
                UpdateVerifier.version(otherChannel), found.size, found.sha256, found.apkUrl);
        rejected(() -> UpdateVerifier.verify(context, output, wrongChannel), "Reject APK metadata from another channel");
        Files.write(output.toPath(), Files.readAllBytes(new File(getContext().getApplicationInfo().sourceDir).toPath()));
        rejected(() -> UpdateVerifier.verify(context, output, found), "Reject another package");
        output.delete();

        File directory = new File(context.getCacheDir(), "updates"); directory.mkdirs();
        File ready = new File(directory, "update-00000000-0000-0000-0000-000000000001.apk");
        Files.write(ready.toPath(), apk);
        Uri uri = Uri.parse("content://" + context.getPackageName() + ".updates/" + ready.getName());
        try (InputStream input = context.getContentResolver().openInputStream(uri)) { check(input != null && input.read() == (apk[0] & 255), "Installer provider can read verified APK"); }
        rejected(() -> context.getContentResolver().openFileDescriptor(uri, "rw"), "Installer provider rejects writes");
        rejected(() -> context.getContentResolver().openInputStream(Uri.parse("content://" + context.getPackageName() + ".updates/not-an-update.part")), "Provider rejects unverified partial files");
        ready.delete();
    }

    private void installerUi() throws Exception {
        Context context = getTargetContext();
        byte[] apk = asset("candidate.apk");
        UpdateClient.Candidate candidate = candidate(apk);
        MainActivity activity = (MainActivity) startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        java.lang.reflect.Field field = MainActivity.class.getDeclaredField("model"); field.setAccessible(true);
        Updater updater = ((ArchiveModel) field.get(activity)).updater;
        long deadline = System.currentTimeMillis() + 35000;
        while (updater.state == Updater.State.CHECKING && System.currentTimeMillis() < deadline) Thread.sleep(50);
        if (updater.state == Updater.State.CHECKING) throw new IOException("Initial update check did not finish.");
        long installed = UpdateVerifier.version(context.getPackageManager().getPackageInfo(context.getPackageName(), 0));
        UpdatePolicy policy = new UpdatePolicy("github-master", repository(), installed);
        Server server = new Server();
        String assetBase = "https://api.github.com/repos/" + repository() + "/releases/assets/";
        server.json(policy.endpoint(), release("master", true, apk.length));
        server.json(assetBase + "11", manifest(candidate));
        server.responses.put(assetBase + "12", apk);
        java.lang.reflect.Field client = Updater.class.getDeclaredField("client"); client.setAccessible(true);
        client.set(updater, new UpdateClient(policy, server));
        runOnMainSync(() -> {
            updater.candidate = candidate; updater.state = Updater.State.OFFER;
            updater.dismissed = false;
            updater.changed.run();
        });
    }
}
