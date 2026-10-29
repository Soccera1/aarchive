package dev.aarchive;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import java.io.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Retained across rotation. All native and provider I/O runs on a single worker. */
final class ArchiveModel {
    static final int MAX_ENTRIES = 100_000;
    static final long MAX_BYTES = 10L * 1024 * 1024 * 1024;
    private final Context context;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile File source;
    private String password = "";
    private String sourceName = "Archive";
    volatile boolean cancelled;
    boolean busy;
    boolean ready;
    String name = "No file open";
    String format = "";
    String status = "";
    List<ArchiveEntry> entries = Collections.emptyList();
    final Set<Integer> selected = new HashSet<>();
    Runnable changed;

    ArchiveModel(Context context) { this.context = context.getApplicationContext(); }
    boolean hasSource() { return source != null; }
    private void notifyChanged() { if (changed != null) changed.run(); }
    private void progress(String value) { main.post(() -> { status = value; notifyChanged(); }); }
    private void checkCancelled() throws IOException {
        if (cancelled) throw new IOException("Operation cancelled.");
    }
    private void start() { busy = true; cancelled = false; notifyChanged(); }
    private void finish(String message) {
        main.post(() -> { busy = false; status = message; notifyChanged(); });
    }

    void open(Uri uri) {
        name = "Opening file…";
        ready = false;
        entries = Collections.emptyList();
        selected.clear();
        status = "Importing file…";
        start();
        worker.execute(() -> {
            File imported = null;
            try {
                if (source != null) source.delete();
                source = null;
                String displayName = "Archive";
                try (Cursor cursor = context.getContentResolver().query(uri,
                        new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                    if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) displayName = cursor.getString(0);
                } catch (Exception ignored) { /* Providers may omit display metadata. */ }
                final String resolvedName = displayName;
                sourceName = resolvedName;
                main.post(() -> { name = resolvedName; notifyChanged(); });
                checkCancelled();
                imported = File.createTempFile("archive-", ".bin", context.getCacheDir());
                try (InputStream input = context.getContentResolver().openInputStream(uri);
                     OutputStream output = new FileOutputStream(imported)) {
                    if (input == null) throw new IOException("The document provider could not open this archive.");
                    byte[] buffer = new byte[65536];
                    long total = 0;
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        checkCancelled();
                        total += count;
                        if (total > MAX_BYTES) throw new IOException("Archive import limit is 10 GiB.");
                        output.write(buffer, 0, count);
                    }
                }
                source = imported;
                imported = null;
                password = "";
                scan();
            } catch (Exception error) {
                finish(message(error));
            } finally {
                if (imported != null) imported.delete();
            }
        });
    }

    void password(String value) {
        if (source == null || busy) return;
        password = value;
        ready = false;
        selected.clear();
        entries = Collections.emptyList();
        status = "Reading archive…";
        start();
        worker.execute(() -> {
            try { scan(); } catch (Exception error) { finish(message(error)); }
        });
    }

    private void scan() throws IOException {
        List<ArchiveEntry> result = new ArrayList<>();
        String detected;
        try (NativeArchive archive = new NativeArchive(source.getAbsolutePath(), password, sourceName)) {
            ArchiveEntry entry;
            while ((entry = archive.next()) != null) {
                checkCancelled();
                if (result.size() >= MAX_ENTRIES) throw new IOException("Archive contains more than 100,000 entries.");
                result.add(entry);
            }
            detected = archive.format();
        }
        checkCancelled();
        main.post(() -> {
            entries = result;
            format = detected;
            ready = true;
            busy = false;
            status = "";
            notifyChanged();
        });
    }

    void extract(Uri tree) {
        final Set<Integer> chosen = new HashSet<>(selected);
        if (chosen.isEmpty()) {
            for (int i = 0; i < entries.size(); i++) if (entries.get(i).extractable()) chosen.add(i);
        }
        final List<ArchiveEntry> snapshot = entries;
        status = "Preparing extraction…";
        start();
        worker.execute(() -> {
            ContentResolver resolver = context.getContentResolver();
            Uri root = null;
            try {
                ArchivePlan.validate(snapshot, chosen);
                checkCancelled();
                Uri parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
                String folder = "AArchive-" + UUID.randomUUID().toString().substring(0, 8);
                root = create(resolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, folder);
                Map<String, Uri> directories = new HashMap<>();
                directories.put("", root);
                byte[] buffer = new byte[65536];
                long total = 0;
                int completed = 0;
                int index = 0;
                try (NativeArchive archive = new NativeArchive(source.getAbsolutePath(), password, sourceName)) {
                    ArchiveEntry entry;
                    while ((entry = archive.next()) != null) {
                        checkCancelled();
                        if (!chosen.contains(index++)) continue;
                        String[] parts = ArchivePath.components(entry.path);
                        Uri directory = root;
                        String key = "";
                        int directoryCount = entry.directory() ? parts.length : parts.length - 1;
                        for (int part = 0; part < directoryCount; part++) {
                            key = key.isEmpty() ? parts[part] : key + "/" + parts[part];
                            Uri next = directories.get(key);
                            if (next == null) {
                                next = create(resolver, directory, DocumentsContract.Document.MIME_TYPE_DIR, parts[part]);
                                directories.put(key, next);
                            }
                            directory = next;
                        }
                        if (!entry.directory()) {
                            if (entry.size > MAX_BYTES - total) throw new IOException("Extraction limit is 10 GiB.");
                            Uri document = create(resolver, directory, "application/octet-stream", parts[parts.length - 1]);
                            try (OutputStream output = resolver.openOutputStream(document, "w")) {
                                if (output == null) throw new IOException("Could not write output file.");
                                int count;
                                while ((count = archive.read(buffer)) > 0) {
                                    checkCancelled();
                                    total += count;
                                    if (total > MAX_BYTES) throw new IOException("Extraction limit is 10 GiB.");
                                    output.write(buffer, 0, count);
                                }
                            }
                        }
                        completed++;
                        if (completed % 16 == 0 || completed == chosen.size())
                            progress("Extracting " + completed + " / " + chosen.size() + " entries…");
                        if (completed == chosen.size()) break;
                    }
                }
                checkCancelled();
                finish("Extracted " + context.getResources().getQuantityString(R.plurals.entry_count, completed, completed)
                        + " into " + folder + ".");
            } catch (Exception error) {
                String cleanup = "";
                if (root != null) {
                    try {
                        if (!DocumentsContract.deleteDocument(resolver, root)) cleanup = " Partial output remains in the destination.";
                    } catch (Exception ignored) { cleanup = " Partial output remains in the destination."; }
                }
                finish(message(error) + cleanup);
            }
        });
    }

    private static Uri create(ContentResolver resolver, Uri parent, String mime, String name) throws IOException {
        Uri result = DocumentsContract.createDocument(resolver, parent, mime, name);
        if (result == null) throw new IOException("The document provider could not create " + name);
        return result;
    }

    private static String message(Exception error) {
        String message = error.getMessage();
        return (message == null ? error.getClass().getSimpleName() : message)
                + (message != null && message.toLowerCase(Locale.ROOT).contains("passphrase")
                ? " Use Password to retry." : "");
    }

    void close() {
        cancelled = true;
        changed = null;
        worker.execute(() -> { if (source != null) source.delete(); password = ""; });
        worker.shutdown();
    }
}
