package dev.aarchive;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class ArchivePath {
    private ArchivePath() {}

    public static String[] components(String path) throws IOException {
        if (path == null || path.isEmpty() || path.startsWith("/") || path.indexOf('\\') >= 0
                || path.indexOf(':') >= 0 || path.indexOf('\0') >= 0) {
            throw new IOException("Unsafe archive path: " + path);
        }
        List<String> parts = new ArrayList<>();
        for (String part : path.split("/")) {
            if (part.equals("..")) throw new IOException("Parent traversal is blocked: " + path);
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.length() > 255 || parts.size() >= 64) throw new IOException("Archive path is too long.");
            parts.add(part);
        }
        return parts.toArray(new String[0]);
    }
}
