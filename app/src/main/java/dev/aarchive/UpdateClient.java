package dev.aarchive;

import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** GitHub release metadata and bounded, HTTPS-only asset downloads. */
final class UpdateClient {
    interface Transport { InputStream open(String url, boolean asset) throws IOException; }
    interface Progress { void update(long received, long total) throws IOException; }
    static final class Candidate {
        final String channel, repository, versionName, sha256, apkUrl;
        final long versionCode, size;
        Candidate(String channel, String repository, String versionName, long versionCode, long size, String sha256, String apkUrl) {
            this.channel = channel; this.repository = repository; this.versionName = versionName;
            this.versionCode = versionCode; this.size = size; this.sha256 = sha256; this.apkUrl = apkUrl;
        }
    }
    private final UpdatePolicy policy;
    private final Transport transport;
    UpdateClient(UpdatePolicy policy, Transport transport) { this.policy = policy; this.transport = transport; }

    Candidate check() throws IOException {
        if (!policy.enabled()) return null;
        try {
            JSONObject release = json(policy.endpoint(), false);
            if (!policy.acceptsRelease(release.getString("tag_name"), release.getBoolean("draft"), release.getBoolean("prerelease"))) return null;
            JSONArray assets = release.getJSONArray("assets");
            JSONObject metadata = asset(assets, "update.json"), apk = asset(assets, policy.apkName());
            if (metadata == null || apk == null) return null; // Older releases have no update metadata.
            JSONObject manifest = json(assetUrl(metadata), true);
            if (manifest.getInt("schemaVersion") != 1) throw new IOException("Unsupported update metadata version.");
            long version = manifest.getLong("versionCode");
            if (!policy.channel.equals(manifest.getString("channel")) || !policy.repository.equals(manifest.getString("repository")))
                throw new IOException("The release metadata belongs to a different channel.");
            if (version <= policy.installedVersion) return null;
            long size = manifest.getLong("size");
            String hash = manifest.getString("sha256");
            policy.validate(manifest.getString("channel"), manifest.getString("repository"), version, manifest.getString("apk"), size, hash);
            if (apk.getLong("size") != size) throw new IOException("The release changed while checking for updates. Try again.");
            String name = manifest.getString("versionName");
            if (name.isEmpty() || name.length() > 100) throw new IOException("Invalid update version name.");
            return new Candidate(policy.channel, policy.repository, name, version, size, hash, assetUrl(apk));
        } catch (JSONException error) { throw new IOException("Invalid GitHub update metadata.", error); }
    }
    private JSONObject asset(JSONArray assets, String name) throws JSONException, IOException {
        JSONObject found = null;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.getJSONObject(i);
            if (name.equals(asset.getString("name")) && "uploaded".equals(asset.getString("state"))) {
                if (found != null) throw new IOException("Duplicate release asset.");
                found = asset;
            }
        }
        return found;
    }
    private String assetUrl(JSONObject asset) throws JSONException, IOException {
        long id = asset.getLong("id");
        if (id <= 0) throw new IOException("Invalid release asset ID.");
        // Immutable asset IDs prevent a moving master tag swapping downloads.
        return "https://api.github.com/repos/" + policy.repository + "/releases/assets/" + id;
    }
    private JSONObject json(String url, boolean asset) throws IOException, JSONException {
        try (InputStream input = transport.open(url, asset); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > 1024 * 1024) throw new IOException("Update metadata is too large.");
                output.write(buffer, 0, count);
            }
            return new JSONObject(output.toString(StandardCharsets.UTF_8.name()));
        }
    }
    void download(Candidate candidate, File destination, Progress progress) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        boolean complete = false;
        try {
            try (InputStream input = transport.open(candidate.apkUrl, true); OutputStream output = new FileOutputStream(destination)) {
                byte[] buffer = new byte[65536];
                long total = 0;
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (count > candidate.size - total) throw new IOException("Update size does not match its metadata.");
                    progress.update(total, candidate.size);
                    output.write(buffer, 0, count);
                    digest.update(buffer, 0, count);
                    total += count;
                }
                if (total != candidate.size) throw new IOException("The APK download was incomplete.");
                progress.update(total, candidate.size);
            }
            StringBuilder actual = new StringBuilder();
            for (byte value : digest.digest()) actual.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
            if (!actual.toString().equalsIgnoreCase(candidate.sha256)) throw new IOException("The APK checksum does not match. Download it again.");
            complete = true;
        } finally { if (!complete) destination.delete(); }
    }

    static InputStream https(String url, boolean asset) throws IOException {
        String current = url;
        for (int redirects = 0; redirects <= 5; redirects++) {
            if (!UpdatePolicy.allowedDownload(current)) throw new IOException("Unexpected update download host.");
            HttpURLConnection connection = (HttpURLConnection) new URL(current).openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(15000); connection.setReadTimeout(15000);
            connection.setRequestProperty("Accept", asset ? "application/octet-stream" : "application/vnd.github+json");
            connection.setRequestProperty("User-Agent", "AArchive updater");
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
            int status;
            try { status = connection.getResponseCode(); }
            catch (IOException error) { connection.disconnect(); throw error; }
            if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                String location = connection.getHeaderField("Location");
                connection.disconnect();
                if (location == null) throw new IOException("Missing update redirect.");
                current = new URL(new URL(current), location).toString();
                continue;
            }
            if (status != 200) { connection.disconnect(); throw new IOException("GitHub returned HTTP " + status + "."); }
            try {
                return new FilterInputStream(connection.getInputStream()) {
                    @Override public void close() throws IOException { try { super.close(); } finally { connection.disconnect(); } }
                };
            } catch (IOException error) { connection.disconnect(); throw error; }
        }
        throw new IOException("Too many update redirects.");
    }
}
