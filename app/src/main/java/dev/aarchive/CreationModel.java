package dev.aarchive;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.text.format.Formatter;
import java.io.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Retains source selection and background creation across configuration changes. */
final class CreationModel {
    static final class Source {
        final Uri uri;
        final String name;
        final boolean directory;
        final long size, modified;
        Source(Uri uri, String name, boolean directory, long size, long modified) {
            this.uri = uri; this.name = name; this.directory = directory; this.size = size; this.modified = modified;
        }
    }
    private static final class Input {
        final Source source;
        final String path;
        Input(Source source, String path) { this.source = source; this.path = path; }
    }

    private final Context context;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    final List<Source> sources = new ArrayList<>();
    String format = "zip", codec = "deflate", status = "";
    int level = 6;
    boolean busy, pickingOutput;
    volatile boolean cancelled;
    private volatile NativeWriter activeWriter;
    private long lastProgress;
    Runnable changed;

    CreationModel(Context context) { this.context = context.getApplicationContext(); }
    void notifyChanged() { if (changed != null) changed.run(); }
    void setFormat(String value) {
        format = value;
        codec = CreationFormat.codecs(format)[0];
        notifyChanged();
    }
    String selectionError() {
        if (sources.isEmpty()) return "Add files or a folder.";
        if (format.equals("raw") && (sources.size() != 1 || sources.get(0).directory))
            return "Standalone compression requires exactly one file. Use an archive format for multiple files or folders.";
        Set<String> names = new HashSet<>();
        for (Source source : sources) if (!names.add(source.name)) return "Two selected sources have the same name: " + source.name;
        return "";
    }
    String suggestedName() {
        String base = sources.size() == 1 ? sources.get(0).name : "archive";
        return base + CreationFormat.suffix(format, codec);
    }
    void remove(int index) { if (!busy && !pickingOutput) { sources.remove(index); status = ""; notifyChanged(); } }
    void cancel() {
        cancelled = true;
        NativeWriter writer = activeWriter;
        if (writer != null) writer.cancel();
    }
    private void checkCancelled() throws IOException { if (cancelled) throw new IOException("Operation cancelled."); }
    private void start(String message) {
        status = message; busy = true; cancelled = false; lastProgress = 0; notifyChanged();
    }
    private void finish(String message) { main.post(() -> { busy = false; status = message; notifyChanged(); }); }
    private void progress(String message) {
        long now = System.nanoTime();
        if (now - lastProgress < 100_000_000L) return;
        lastProgress = now;
        main.post(() -> { status = message; notifyChanged(); });
    }

    void add(List<Uri> uris, boolean folder) {
        if (busy || pickingOutput) return;
        List<Source> updated = new ArrayList<>(sources);
        start("Reading source details…");
        worker.execute(() -> {
            try {
                for (Uri uri : uris) {
                    checkCancelled();
                    if (updated.size() >= ArchiveModel.MAX_ENTRIES) throw new IOException("Too many sources.");
                    Uri document = folder ? DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri)) : uri;
                    Source source = metadata(document);
                    if (folder && !source.directory) throw new IOException("The selected source is not a folder.");
                    boolean duplicate = false;
                    for (Source previous : updated) if (sameDocument(previous.uri, source.uri)) { duplicate = true; break; }
                    if (!duplicate) updated.add(source);
                }
                checkCancelled();
                main.post(() -> { sources.clear(); sources.addAll(updated); busy = false; status = ""; notifyChanged(); });
            } catch (Exception error) { finish(message(error)); }
        });
    }

    private Source metadata(Uri uri) throws IOException {
        try (Cursor cursor = context.getContentResolver().query(uri,
                new String[]{Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED},
                null, null, null)) {
            if (cursor == null || !cursor.moveToFirst() || cursor.isNull(0)) throw new IOException("Could not read source details.");
            return source(uri, cursor.getString(0), !cursor.isNull(1) ? cursor.getString(1) : "",
                    cursor.isNull(2) ? -1 : cursor.getLong(2), cursor.isNull(3) ? 0 : cursor.getLong(3));
        }
    }
    private static Source source(Uri uri, String name, String mime, long size, long modified) throws IOException {
        String[] parts = ArchivePath.components(name);
        if (parts.length != 1 || !parts[0].equals(name)) throw new IOException("Unsupported source name: " + name);
        return new Source(uri, name, Document.MIME_TYPE_DIR.equals(mime), size, Math.max(0, modified));
    }
    private static boolean sameDocument(Uri first, Uri second) {
        if (first.equals(second)) return true;
        if (!Objects.equals(first.getAuthority(), second.getAuthority())) return false;
        try { return DocumentsContract.getDocumentId(first).equals(DocumentsContract.getDocumentId(second)); }
        catch (IllegalArgumentException ignored) { return false; }
    }

    private void enumerate(Source source, String path, Uri output, List<Input> plan, Set<String> visited) throws IOException {
        checkCancelled();
        if (sameDocument(source.uri, output)) return; // Exclude the newly created output if it is inside an input folder.
        ArchivePath.components(path);
        if (plan.size() >= ArchiveModel.MAX_ENTRIES) throw new IOException("Creation limit is 100,000 entries.");
        plan.add(new Input(source, path));
        if (!source.directory) return;
        String id = DocumentsContract.getDocumentId(source.uri);
        if (!visited.add(id)) throw new IOException("A folder contains repeated or cyclic document references.");
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(source.uri, id);
        List<Source> entries = new ArrayList<>();
        try (Cursor cursor = context.getContentResolver().query(children,
                new String[]{Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED},
                null, null, null)) {
            if (cursor == null) throw new IOException("Could not list folder: " + path);
            while (cursor.moveToNext()) {
                checkCancelled();
                if (entries.size() + plan.size() >= ArchiveModel.MAX_ENTRIES) throw new IOException("Creation limit is 100,000 entries.");
                Uri child = DocumentsContract.buildDocumentUriUsingTree(source.uri, cursor.getString(0));
                entries.add(source(child, cursor.getString(1), cursor.getString(2),
                        cursor.isNull(3) ? -1 : cursor.getLong(3), cursor.isNull(4) ? 0 : cursor.getLong(4)));
            }
        }
        entries.sort(Comparator.comparing(entry -> entry.name));
        for (Source entry : entries) enumerate(entry, path + "/" + entry.name, output, plan, visited);
    }

    void create(Uri output) {
        if (busy) return;
        final List<Source> selected = new ArrayList<>(sources);
        final String outputFormat = format, outputCodec = codec, fallbackName = suggestedName();
        final int outputLevel = level;
        pickingOutput = false;
        start("Preparing sources…");
        worker.execute(() -> {
            File archiveFile = null;
            boolean deleteOutputOnFailure = true;
            try {
                if (selected.isEmpty()) throw new IOException("Source selection was lost. Add the files again.");
                if (outputFormat.equals("raw") && (selected.size() != 1 || selected.get(0).directory))
                    throw new IOException("Standalone compression requires one regular file.");
                for (Source source : selected) {
                    if (sameDocument(source.uri, output)) {
                        deleteOutputOnFailure = false;
                        throw new IOException("Output cannot replace a selected input.");
                    }
                }
                List<Input> plan = new ArrayList<>();
                for (Source source : selected) enumerate(source, source.name, output, plan, new HashSet<>());
                List<ArchiveEntry> entries = new ArrayList<>();
                Set<Integer> indices = new HashSet<>();
                for (Input input : plan) {
                    indices.add(entries.size());
                    entries.add(new ArchiveEntry(input.path, input.source.size, input.source.directory ? 0040000 : 0100000, false));
                }
                ArchivePlan.validate(entries, indices);
                checkCancelled();
                archiveFile = File.createTempFile("created-archive-", ".bin", context.getCacheDir());
                long total = 0;
                byte[] buffer = new byte[65536];
                try (NativeWriter writer = new NativeWriter(archiveFile.getAbsolutePath(), outputFormat, outputCodec, outputLevel)) {
                    activeWriter = writer;
                    int completed = 0;
                    for (Input input : plan) {
                        checkCancelled();
                        progress("Creating " + (completed + 1) + " / " + plan.size() + " · " + input.path);
                        if (input.source.directory) {
                            writer.begin(input.path, 0, input.source.modified, true);
                            writer.endEntry();
                        } else {
                            File staged = File.createTempFile("create-input-", ".bin", context.getCacheDir());
                            try {
                                long size = 0;
                                try (InputStream source = context.getContentResolver().openInputStream(input.source.uri);
                                     OutputStream cache = new FileOutputStream(staged)) {
                                    if (source == null) throw new IOException("Could not open " + input.path);
                                    int count;
                                    while ((count = source.read(buffer)) != -1) {
                                        checkCancelled();
                                        if (count > ArchiveModel.MAX_BYTES - total - size) throw new IOException("Creation input limit is 10 GiB.");
                                        cache.write(buffer, 0, count);
                                        size += count;
                                        progress("Reading " + input.path + " · " + Formatter.formatFileSize(context, size));
                                    }
                                }
                                total += size;
                                writer.begin(input.path, size, input.source.modified, false);
                                try (InputStream cached = new FileInputStream(staged)) {
                                    int count;
                                    while ((count = cached.read(buffer)) != -1) {
                                        checkCancelled();
                                        writer.write(buffer, count);
                                    }
                                }
                                writer.endEntry();
                            } finally { staged.delete(); }
                        }
                        completed++;
                    }
                    checkCancelled();
                    progress("Finishing archive…");
                    writer.finish();
                } finally { activeWriter = null; }
                checkCancelled();
                progress("Saving output…");
                try (InputStream cache = new FileInputStream(archiveFile);
                     OutputStream destination = context.getContentResolver().openOutputStream(output, "w")) {
                    if (destination == null) throw new IOException("Could not open the output document.");
                    int count;
                    while ((count = cache.read(buffer)) != -1) {
                        checkCancelled();
                        destination.write(buffer, 0, count);
                    }
                }
                checkCancelled();
                String name = fallbackName;
                try { name = metadata(output).name; } catch (Exception ignored) { /* Saving succeeded even if metadata is unavailable. */ }
                finish("Created " + name + " · " + Formatter.formatFileSize(context, archiveFile.length()));
            } catch (Exception error) {
                String cleanup = "";
                if (deleteOutputOnFailure) {
                    try {
                        if (!DocumentsContract.deleteDocument(context.getContentResolver(), output)) cleanup = " The incomplete output could not be removed.";
                    } catch (Exception ignored) { cleanup = " The incomplete output could not be removed."; }
                }
                finish(message(error) + cleanup);
            } finally {
                activeWriter = null;
                if (archiveFile != null) archiveFile.delete();
            }
        });
    }
    private static String message(Exception error) { return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage(); }
    void close() { changed = null; cancel(); worker.shutdown(); }
}
