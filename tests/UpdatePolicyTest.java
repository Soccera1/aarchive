package dev.aarchive;

public final class UpdatePolicyTest {
    private static int count;
    private static void check(boolean value, String message) { count++; if (!value) throw new AssertionError(message); }
    private interface Action { void run() throws Exception; }
    private static void rejects(Action action) throws Exception {
        try { action.run(); throw new AssertionError("Unexpected update accepted"); }
        catch (java.io.IOException expected) { count++; }
    }
    public static void main(String[] args) throws Exception {
        UpdatePolicy local = new UpdatePolicy("none", "example/app", 10);
        check(!local.enabled() && !local.acceptsRelease("master", false, true), "Local builds opt out");
        UpdatePolicy master = new UpdatePolicy("github-master", "example/app", 10);
        UpdatePolicy release = new UpdatePolicy("github-release", "example/app", 10);
        check(master.endpoint().endsWith("/tags/master"), "Fixed master release endpoint");
        check(release.endpoint().endsWith("/latest"), "Versioned release endpoint");
        check(master.acceptsRelease("master", false, true), "Rolling master prerelease accepted");
        check(!master.acceptsRelease("v2.0", false, false), "Master rejects stable release");
        check(!release.acceptsRelease("master", false, true), "Stable rejects master");
        check(release.acceptsRelease("v2.0.1", false, false), "Stable numeric version accepted");
        check(!release.acceptsRelease("v2.0.1", false, true), "Stable rejects prereleases");
        check(!release.acceptsRelease("v2.0.1", true, false), "Drafts rejected");
        String hash = "a".repeat(64);
        master.validate("github-master", "example/app", 11, master.apkName(), 100, hash);
        rejects(() -> master.validate("github-release", "example/app", 11, master.apkName(), 100, hash));
        rejects(() -> master.validate("github-master", "other/app", 11, master.apkName(), 100, hash));
        rejects(() -> master.validate("github-master", "example/app", 10, master.apkName(), 100, hash));
        rejects(() -> master.validate("github-master", "example/app", 9, master.apkName(), 100, hash));
        rejects(() -> master.validate("github-master", "example/app", 11, "other.apk", 100, hash));
        rejects(() -> master.validate("github-master", "example/app", 11, master.apkName(), UpdatePolicy.MAX_APK_BYTES + 1, hash));
        rejects(() -> master.validate("github-master", "example/app", 11, master.apkName(), 100, "invalid"));
        for (String host : new String[]{"api.github.com", "github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com"})
            check(UpdatePolicy.allowedDownload("https://" + host + "/asset"), "Allowed GitHub HTTPS host");
        for (String url : new String[]{"http://github.com/asset", "https://github.com.evil.test/asset", "https://evil.test/asset",
                "https://evil@github.com/asset", "https://github.com:123/asset", "file:///tmp/asset", "malformed"})
            check(!UpdatePolicy.allowedDownload(url), "Reject untrusted download URL: " + url);
        System.out.println("Update policy passed " + count + " assertions.");
    }
}
