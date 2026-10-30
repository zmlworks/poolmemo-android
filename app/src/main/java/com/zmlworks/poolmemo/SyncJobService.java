package com.zmlworks.poolmemo;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

/**
 * Sends an unsent local draft after the editor was closed (or the process killed while a
 * sync was pending). Scheduled by EditorActivity.onStop when there is unsent text; runs
 * when a network is available, retried by JobScheduler with back-off on network errors.
 * Uses the same {@link MemoSession} as the editor, and only replaces the draft file if
 * the editor has not changed it meanwhile.
 */
public class SyncJobService extends JobService {
    private static final int JOB_ID = 0x504d01;

    static void schedule(Context c) {
        JobScheduler js = c.getSystemService(JobScheduler.class);
        if (js == null) return;
        JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(c, SyncJobService.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setMinimumLatency(10_000L)
                .setBackoffCriteria(30_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                .build();
        try {
            js.schedule(job);
        } catch (RuntimeException ignored) {
            // too many jobs etc.: the editor still syncs on next open
        }
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        final Context app = getApplicationContext();
        Thread t = new Thread(() -> {
            boolean retry = false;
            try {
                retry = runOnce(app);
            } catch (Throwable ignored) {
                // key mismatch / site rejected / too large: shown when the editor is opened
            }
            jobFinished(params, retry);
        }, "pm-job");
        t.start();
        return true;
    }

    /** @return true if it should be retried later (network error / not confirmed). */
    static boolean runOnce(Context app) throws Exception {
        String id = Prefs.getId(app), key = Prefs.getKey(app);
        if (id.isEmpty() || key.isEmpty()) return false;
        MemoSession s = MemoSession.get(app, id, key);
        SyncEngine eng = s.engine();
        LocalStore.Draft d = s.store.loadDraft();
        if (d == null) return false;
        SyncEngine.Snap base = eng.base();
        if (base != null && base.wire.equals(d.baseWire) && base.text.equals(d.text)) return false;
        SyncEngine.Result r;
        try {
            r = eng.sync(d.text, d.baseWire);
        } catch (SyncEngine.KeyMismatchException | SyncEngine.SaveRejectedException
                 | SyncEngine.TooLargeException e) {
            return false;
        } catch (Exception e) {
            return true; // network
        }
        if (r.base != null) s.store.replaceDraftIf(d, r.base.wire, r.base.text);
        return r.unverified;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        // The worker finishes its current request; ask to be rescheduled to be safe.
        return true;
    }
}
