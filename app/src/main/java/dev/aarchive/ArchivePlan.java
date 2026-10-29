package dev.aarchive;

import java.io.IOException;
import java.util.*;

final class ArchivePlan {
    private ArchivePlan() {}

    static void validate(List<ArchiveEntry> entries, Set<Integer> selected) throws IOException {
        Map<String, Boolean> paths = new HashMap<>();
        for (int index : selected) {
            ArchiveEntry entry = entries.get(index);
            if (!entry.extractable()) throw new IOException("Links and special files cannot be extracted.");
            String path = String.join("/", ArchivePath.components(entry.path));
            if (path.isEmpty() && !entry.directory()) throw new IOException("An entry has an empty file name.");
            if (!path.isEmpty() && paths.putIfAbsent(path, entry.directory()) != null)
                throw new IOException("Duplicate archive path: " + path);
        }
        for (String path : paths.keySet()) {
            int slash = path.lastIndexOf('/');
            while (slash >= 0) {
                String parent = path.substring(0, slash);
                if (Boolean.FALSE.equals(paths.get(parent)))
                    throw new IOException("A file is also used as a folder: " + parent);
                slash = parent.lastIndexOf('/');
            }
        }
    }
}
