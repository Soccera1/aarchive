package dev.aarchive;

import java.io.IOException;
import java.net.URI;
import java.util.Locale;

/** Platform-independent rules shared by update discovery and APK verification. */
final class UpdatePolicy {
    static final long MAX_APK_BYTES = 512L * 1024 * 1024;
    final String channel, repository;
    final long installedVersion;

    UpdatePolicy(String channel, String repository, long installedVersion) {
        this.channel = channel;
        this.repository = repository;
        this.installedVersion = installedVersion;
    }
    boolean enabled() { return channel.equals("github-master") || channel.equals("github-release"); }
    String endpoint() {
        if (!enabled()) throw new IllegalStateException("This build has no GitHub update channel.");
        return "https://api.github.com/repos/" + repository + "/releases/"
                + (channel.equals("github-master") ? "tags/master" : "latest");
    }
    boolean acceptsRelease(String tag, boolean draft, boolean prerelease) {
        if (!enabled() || draft) return false;
        if (channel.equals("github-master")) return tag.equals("master");
        return !prerelease && !tag.equals("master") && tag.matches("v?[0-9]+(\\.[0-9]+){1,3}");
    }
    String apkName() { return channel.equals("github-master") ? "aarchive-master-universal.apk" : "aarchive-universal.apk"; }
    void validate(String sourceChannel, String sourceRepository, long version, String apk, long size, String sha256) throws IOException {
        if (!enabled() || !channel.equals(sourceChannel) || !repository.equals(sourceRepository))
            throw new IOException("The update belongs to a different build channel or repository.");
        if (version <= installedVersion || version > Integer.MAX_VALUE) throw new IOException("The update is not a newer Android version.");
        if (!apkName().equals(apk) || size <= 0 || size > MAX_APK_BYTES || !sha256.matches("[0-9a-fA-F]{64}"))
            throw new IOException("Invalid update metadata.");
    }
    static boolean allowedDownload(String value) {
        try {
            URI uri = URI.create(value);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            return "https".equals(uri.getScheme()) && uri.getUserInfo() == null && (uri.getPort() == -1 || uri.getPort() == 443)
                    && (host.equals("api.github.com") || host.equals("github.com") || host.equals("release-assets.githubusercontent.com")
                        || host.equals("objects.githubusercontent.com"));
        } catch (IllegalArgumentException error) { return false; }
    }
}
