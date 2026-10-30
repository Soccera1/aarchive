package dev.aarchive;

import android.content.Context;
import android.os.*;
import android.util.Log;
import java.io.*;
import java.util.UUID;
import java.util.concurrent.*;

/** Retained by the archive model; network work never holds an Activity. */
final class Updater {
    enum State { IDLE, CHECKING, OFFER, DOWNLOADING, READY, PERMISSION, ERROR }
    private final Context context;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final UpdateClient client;
    private boolean started;
    private volatile boolean cancelled, closed;
    State state = State.IDLE;
    UpdateClient.Candidate candidate;
    File ready;
    String status = "";
    int percent;
    boolean dismissed, installRequested;
    Runnable changed;

    Updater(Context context) {
        this.context = context.getApplicationContext();
        client = new UpdateClient(new UpdatePolicy(BuildConfig.UPDATE_CHANNEL, BuildConfig.UPDATE_REPOSITORY, BuildConfig.VERSION_CODE), UpdateClient::https);
    }
    private void publish(Runnable update) { main.post(() -> { if (!closed) { update.run(); if (changed != null) changed.run(); } }); }
    void launch() {
        if (started || BuildConfig.UPDATE_CHANNEL.equals("none")) return;
        started = true;
        state = State.CHECKING;
        worker.execute(() -> {
            try {
                UpdateClient.Candidate found = client.check();
                publish(() -> { candidate = found; state = found == null ? State.IDLE : State.OFFER; });
            } catch (Exception error) {
                Log.i("AArchive", "Update check unavailable", error);
                publish(() -> state = State.IDLE); // Offline launches stay usable and quiet.
            }
        });
    }
    void download() {
        if (state == State.DOWNLOADING || closed) return;
        state = State.DOWNLOADING; status = "Checking the release…"; percent = 0;
        dismissed = false; cancelled = false; installRequested = true;
        if (changed != null) changed.run();
        worker.execute(() -> {
            File partial = null, verified = null;
            try {
                checkCancelled();
                // Resolve immutable asset IDs again: master might have moved since the offer.
                UpdateClient.Candidate latest = client.check();
                if (latest == null) throw new IOException("No newer update is available in this channel.");
                File directory = new File(context.getCacheDir(), "updates");
                if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Could not create update cache.");
                File[] old = directory.listFiles();
                if (old != null) for (File file : old)
                    if (file.lastModified() < System.currentTimeMillis() - 86400000L) file.delete();
                String name = "update-" + UUID.randomUUID();
                partial = new File(directory, name + ".part");
                verified = new File(directory, name + ".apk");
                final int[] last = {-1};
                client.download(latest, partial, (received, total) -> {
                    checkCancelled();
                    int value = (int) (received * 100 / total);
                    if (value != last[0]) {
                        last[0] = value;
                        publish(() -> { percent = value; status = "Downloading update… " + value + "%"; });
                    }
                });
                checkCancelled();
                publish(() -> status = "Verifying update…");
                UpdateVerifier.verify(context, partial, latest);
                checkCancelled();
                if (!partial.renameTo(verified)) throw new IOException("Could not finish the update download.");
                File result = verified;
                if (closed || cancelled) { result.delete(); throw new IOException("Update cancelled."); }
                publish(() -> {
                    if (cancelled) { result.delete(); state = State.OFFER; dismissed = true; installRequested = false; }
                    else { candidate = latest; ready = result; state = State.READY; }
                });
                verified = null;
            } catch (Exception error) {
                boolean wasCancelled = cancelled;
                publish(() -> { state = wasCancelled ? State.OFFER : State.ERROR; dismissed = wasCancelled;
                    installRequested = false; status = error.getMessage() == null ? "Update failed." : error.getMessage(); });
            } finally {
                if (partial != null) partial.delete();
                if (verified != null) verified.delete();
            }
        });
    }
    void cancel() { cancelled = true; installRequested = false; }
    private void checkCancelled() throws IOException { if (cancelled || closed) throw new IOException("Update cancelled."); }
    void close() { closed = true; changed = null; cancel(); worker.shutdown(); }
}
