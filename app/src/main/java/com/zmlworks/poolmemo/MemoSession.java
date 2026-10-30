package com.zmlworks.poolmemo;

import android.content.Context;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * One memo (ID + key) per process: a single {@link LocalStore} and {@link SyncEngine}
 * shared by every EditorActivity instance (rotation, re-creation) and the background
 * {@link SyncJobService}, so their syncs and file writes are serialised instead of racing.
 * Slow work (PBKDF2, disk, network) runs on the static executors, never on the UI thread.
 */
final class MemoSession {
    /** Draft writes, in order. */
    static final ExecutorService DISK = Executors.newSingleThreadExecutor(r -> new Thread(r, "pm-disk"));
    /** Loading + sync (may block on the network or the engine lock). */
    static final ExecutorService NET = Executors.newSingleThreadExecutor(r -> new Thread(r, "pm-net"));

    private static MemoSession current;

    final String id, key;
    final LocalStore store;
    private SyncEngine engine;
    private LocalStore.Opened firstOpen;

    private MemoSession(Context app, String id, String key) {
        this.id = id; this.key = key;
        this.store = new LocalStore(app.getFilesDir(), id, key);
    }

    static synchronized MemoSession get(Context c, String id, String key) {
        if (current == null || !current.id.equals(id) || !current.key.equals(key)) {
            current = new MemoSession(c.getApplicationContext(), id, key);
        }
        return current;
    }

    /** Opens local files once (slow: decrypts). Call off the UI thread. */
    synchronized SyncEngine engine() {
        if (engine == null) {
            firstOpen = store.open();
            final String fid = id, fkey = key;
            engine = new SyncEngine(new SyncEngine.Remote() {
                @Override public String fetch() throws Exception { return PoolSketch.fetch(fid); }
                @Override public void save(String wire) throws Exception { PoolSketch.save(fid, wire); }
            }, new SyncEngine.Codec() {
                @Override public String encrypt(String plain) throws Exception { return Crypto.encrypt(plain, fkey); }
                @Override public String decrypt(String wire) throws Exception { return Crypto.decrypt(wire, fkey); }
            }, store::saveState, firstOpen.state);
        }
        return engine;
    }

    /** Warnings found when the files were first opened (returned once). */
    synchronized LocalStore.Opened takeOpenReport() {
        LocalStore.Opened o = firstOpen;
        firstOpen = null;
        return o;
    }
}
