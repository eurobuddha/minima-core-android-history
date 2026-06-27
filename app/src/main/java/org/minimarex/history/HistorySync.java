package org.minimarex.history;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Bounded, incremental, IPC-safe sync of the node's relevant history into the local DB.
 *
 * `history` is the heavy command that can overwhelm an un-hardened node (a large response is delivered
 * synchronously on the broadcast thread). So this NEVER asks for a big page: it walks
 * `history relevant:true max:25 offset:N` newest-first, and stops as soon as a page contains a txpowid we
 * already stored (caught up) or returns a short/empty page (end of what the node still retains). On first
 * run nothing is known, so it pages gently to the end (one-time backfill). A small delay separates pages.
 */
public class HistorySync {

    public interface Listener {
        void onProgress(int totalNew);
        void onDone(int totalNew, boolean ok);
    }

    private static final int  PAGE = 25;            // ≈190 KB/page — comfortably under the IPC limit
    private static final long PAGE_DELAY_MS = 500;  // gentle: don't hammer the node during backfill
    private static final int  MAX_PAGES = 240;      // safety cap (240×25 = 6000 txns per run)

    private final MainActivity act;
    private final HistoryDb db;
    private final Listener listener;
    private boolean running = false;
    private int totalNew = 0;

    public HistorySync(MainActivity act, HistoryDb db, Listener l) { this.act = act; this.db = db; this.listener = l; }

    public boolean isRunning() { return running; }

    public void start() {
        if (running) return;
        running = true; totalNew = 0;
        fetchPage(0, 0);
    }

    private void fetchPage(final int offset, final int pageNo) {
        act.node().cmd("history relevant:true max:" + PAGE + " offset:" + offset, new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                act.markPaired(true);                 // we reached the node — hide the pairing banner
                JSONObject r = j.optJSONObject("response");
                JSONArray txpows = r != null ? r.optJSONArray("txpows") : null;
                JSONArray details = r != null ? r.optJSONArray("details") : null;
                int got = txpows != null ? txpows.length() : 0;
                boolean hitKnown = false;
                int pageNew = 0;
                for (int i = 0; i < got; i++) {
                    JSONObject tx = txpows.optJSONObject(i);
                    JSONObject det = (details != null && i < details.length()) ? details.optJSONObject(i) : null;
                    if (tx == null) continue;
                    HistoryEntry e = HistoryEntry.from(tx, det);
                    if (e.txpowid.isEmpty()) continue;
                    if (db.insert(e)) pageNew++; else hitKnown = true;
                }
                totalNew += pageNew;
                if (pageNew > 0 && listener != null) listener.onProgress(totalNew);
                // Keep paging only while the page is full AND entirely new (more unseen history below it).
                if (got >= PAGE && !hitKnown && pageNo + 1 < MAX_PAGES) {
                    act.ui().postDelayed(() -> fetchPage(offset + PAGE, pageNo + 1), PAGE_DELAY_MS);
                } else {
                    finish(true);
                }
            }
            @Override public void onError(String m) {
                if (NodeApi.ERR_NOT_ENABLED.equals(m)) act.markPaired(false);   // not enabled yet → show banner
                finish(false);
            }
        });
    }

    private void finish(boolean ok) {
        running = false;
        if (db.getMeta("first_sync_ts", "").isEmpty())
            db.setMeta("first_sync_ts", String.valueOf(System.currentTimeMillis()));
        db.setMeta("synced_tip_block", String.valueOf(act.chainBlock()));
        db.setMeta("last_sync_ts", String.valueOf(System.currentTimeMillis()));
        if (listener != null) listener.onDone(totalNew, ok);
    }
}
