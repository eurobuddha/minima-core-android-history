package com.eurobuddha.history;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Bounded, ADAPTIVE, IPC-safe sync of the node's relevant history into the local DB.
 *
 * The node refuses any command whose response exceeds 256,000 bytes ("results too long"). Contract-heavy
 * txpows are large, so a fixed page can blow past that — and the over-limit reply comes back empty/dropped,
 * which is why the list was silently empty. So we page SMALL and shrink on demand: start at max:8, and on
 * any over-limit/empty/errored page, halve the page (8→4→2→1) and retry the same offset, keeping the
 * smaller size. On a heavy node it converges to a page that fits; on a light node it stays at 8.
 *
 * Two modes: a one-time BACKFILL (pages to the end of what the node retains, marked done in meta) and the
 * steady-state INCREMENTAL sync (pages only until the first already-stored txpowid). Both stop on a short
 * page (end of history). Gentle: a delay between pages, a hard cap on total fetches.
 */
public class HistorySync {

    public interface Listener {
        void onProgress(int totalNew);
        void onDone(int totalNew, boolean ok);
    }

    private static final int  START_MAX = 8;
    private static final long PAGE_DELAY_MS = 450;
    private static final int  MAX_FETCHES = 600;   // safety cap across pages + retries
    private static final int  MAX_RETRIES = 3;        // retries of the same offset at max:1 before giving up

    /** Bump whenever the stored coin JSON changes shape: forces exactly one rescan of known rows. */
    static final int COIN_FORMAT = 3;
    static final String BACKFILL_DONE   = "backfill_complete_v" + COIN_FORMAT;
    static final String BACKFILL_OFFSET = "backfill_offset_v" + COIN_FORMAT;   // resume point for an interrupted backfill

    private final MainActivity act;
    private final HistoryDb db;
    private final Listener listener;

    private boolean running = false;
    private boolean cancelled;
    public void cancel() { cancelled = true; running = false; }
    private boolean backfill = false;
    private int pageMax = START_MAX;
    private int totalNew = 0;
    private int fetches = 0;
    private int pageFailures = 0;

    public HistorySync(MainActivity act, HistoryDb db, Listener l) { this.act = act; this.db = db; this.listener = l; }

    public boolean isRunning() { return running; }

    public void start() {
        if (running || cancelled) return;
        running = true;
        backfill = !"true".equals(db.getMeta(BACKFILL_DONE, ""));
        pageMax = START_MAX; totalNew = 0; fetches = 0; pageFailures = 0;
        int offset = 0;   // backfill resumes after the last good page instead of re-walking from the top
        if (backfill) try { offset = Integer.parseInt(db.getMeta(BACKFILL_OFFSET, "0")); } catch (Exception ignored) {}
        fetchPage(offset);
    }

    private void fetchPage(final int offset) {
        if (cancelled) return;
        if (++fetches > MAX_FETCHES) { fail(offset, "fetch cap reached"); return; }
        act.node().cmd("history relevant:true max:" + pageMax + " offset:" + offset, new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                if (cancelled) return;
                act.markPaired(true);                     // reached the node — hide the pairing banner
                JSONObject r = j == null ? null : j.optJSONObject("response");
                JSONArray txpows = r != null ? r.optJSONArray("txpows") : null;
                if (r == null || txpows == null || !Boolean.TRUE.equals(j.opt("status"))) {
                    overLimit(offset);                    // dropped/over-256KB reply → shrink + retry
                    return;
                }
                JSONArray details = r.optJSONArray("details");
                int got = txpows.length();
                // Validate the entire page before storing any part of it.
                for (int i = 0; i < got; i++) {
                    JSONObject tx = txpows.optJSONObject(i);
                    Object id = tx == null ? null : tx.opt("txpowid");
                    if (!(id instanceof String) || ((String) id).trim().isEmpty()) {
                        overLimit(offset); return;
                    }
                }
                pageFailures = 0;
                final Ownership own = act.ownership();
                act.io().execute(() -> {                  // parse + store off the main thread
                    boolean hitKnown = false;
                    int pageNew = 0;
                    for (int i = 0; i < got; i++) {
                        JSONObject tx = txpows.optJSONObject(i);
                        JSONObject det = (details != null && i < details.length()) ? details.optJSONObject(i) : null;
                        if (tx == null) continue;
                        HistoryEntry e = HistoryEntry.from(tx, det, own);
                        if (e.txpowid.isEmpty()) continue;
                        if (db.insertFromNode(e)) pageNew++; else hitKnown = true;
                    }
                    if (backfill) db.setMeta(BACKFILL_OFFSET, String.valueOf(offset + got));
                    final int n = pageNew; final boolean known = hitKnown;
                    act.ui().post(() -> stored(offset, got, n, known));
                });
            }
            @Override public void onError(String m) {
                if (cancelled) return;
                if (NodeApi.ERR_NOT_ENABLED.equals(m)) { act.markPaired(false); fail(offset, m); return; }
                overLimit(offset);                        // dropped oversized reply / timeout → shrink + retry
            }
        });
    }

    /** Back on the main thread once a page is in the DB: report, then stop or keep paging. */
    private void stored(int offset, int got, int pageNew, boolean hitKnown) {
        if (cancelled) return;
        totalNew += pageNew;
        if (pageNew > 0 && listener != null) listener.onProgress(totalNew);

        if (got < pageMax) { markBackfillDone(); finish(true); return; }   // short page = end of history
        if (!backfill && hitKnown) { finish(true); return; }                // steady state: caught up
        act.ui().postDelayed(() -> fetchPage(offset + got), PAGE_DELAY_MS); // keep paging
    }

    /** Page was too big (or dropped): halve and retry the same offset; at max:1, retry without advancing past the failed record. */
    private void overLimit(final int offset) {
        if (pageMax > 1) {
            pageMax = Math.max(1, pageMax / 2);
            act.ui().postDelayed(() -> fetchPage(offset), PAGE_DELAY_MS);
        } else if (++pageFailures <= MAX_RETRIES) {
            // A permanently unreadable record leaves the sync incomplete; never hide the gap.
            act.ui().postDelayed(() -> fetchPage(offset), PAGE_DELAY_MS);
        } else {
            fail(offset, "unreadable record");
        }
    }

    private void markBackfillDone() {
        if (backfill) { db.setMeta(BACKFILL_DONE, "true"); db.setMeta(BACKFILL_OFFSET, "0"); }
    }

    /** Record where and why, so the status line says "sync incomplete" now AND on the next launch. */
    private void fail(int offset, String why) {
        db.setMeta("last_sync_error", why);
        db.setMeta("failed_offset", String.valueOf(offset));
        finish(false);
    }

    private void finish(boolean ok) {
        running = false;
        if (ok) {
            if (db.getMeta("first_sync_ts", "").isEmpty())
                db.setMeta("first_sync_ts", String.valueOf(System.currentTimeMillis()));
            db.setMeta("synced_tip_block", String.valueOf(act.chainBlock()));
            db.setMeta("last_sync_ts", String.valueOf(System.currentTimeMillis()));
            db.setMeta("last_sync_error", "");
        }
        if (listener != null) listener.onDone(totalNew, ok);
    }
}
