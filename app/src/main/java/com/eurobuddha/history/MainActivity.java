package com.eurobuddha.history;

import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.PopupMenu;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.minimarex.minimaapi.MinimaAPIMessages;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Minima History — a node-only, persistent record of the wallet's relevant transactions. Mirrors the
 * node's `history` into a local SQLite DB (which keeps growing even after the node prunes), and shows it
 * as a searchable list. The node is touched only to SYNC; the list is served entirely from the local DB.
 */
public class MainActivity extends AppCompatActivity {

    public static final String NODE_PKG = "org.minimarex.minimacore";
    private static final int MAX_ROWS = 3000;

    private NodeApi node;
    private HistoryDb db;
    private HistorySync sync;

    private LinearLayout header;
    private View pairingBanner, composer;   // composer = the search bar row (for inset padding reuse)
    private TextView status, syncBtn;
    private EditText search;
    private RecyclerView recycler;
    private TxAdapter adapter;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private BroadcastReceiver notifyReceiver;
    private final Runnable syncTask = this::doSync;
    private ActivityResultLauncher<String> exportLauncher;
    private ActivityResultLauncher<String> csvLauncher;
    private ActivityResultLauncher<String[]> importLauncher;

    private int chainBlock = 0;
    private boolean paired = false;
    private boolean lastSyncOk = true;
    private String currentSearch = "";
    // Wallet ownership sets for the owned/contract/external address labels. Loaded from the meta
    // cache before the first list render (instant + offline), refreshed from the node on pairing and
    // after each sync. Published wholesale on the UI thread; IO reads whichever snapshot it sees.
    private volatile Ownership own = new Ownership();
    private boolean refreshingOwn = false;
    private boolean hideForeign = false;   // menu toggle: hide entries with no owned address/coin

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        header = findViewById(R.id.header);
        status = findViewById(R.id.status);
        syncBtn = findViewById(R.id.syncBtn);
        search = findViewById(R.id.search);
        recycler = findViewById(R.id.recycler);
        pairingBanner = findViewById(R.id.pairingBanner);
        composer = search;

        applyInsets();
        db = new HistoryDb(this);
        own = Ownership.fromMeta(db);   // last-good labels, before the node ever replies
        hideForeign = "true".equals(db.getMeta("hide_foreign", "false"));
        sync = new HistorySync(this, db, syncListener);
        adapter = new TxAdapter();
        recycler.setLayoutManager(new LinearLayoutManager(this));
        recycler.setAdapter(adapter);

        syncBtn.setOnClickListener(v -> requestSync());
        ((Button) findViewById(R.id.openNodeBtn)).setOnClickListener(v -> openMinimaCore());
        // Export/Import via the Storage Access Framework — back up the collected history to a file you keep.
        exportLauncher = registerForActivityResult(new ActivityResultContracts.CreateDocument("application/json"),
                uri -> { if (uri != null) doExport(uri); });
        csvLauncher = registerForActivityResult(new ActivityResultContracts.CreateDocument("text/csv"),
                uri -> { if (uri != null) doExportCsv(uri); });
        importLauncher = registerForActivityResult(new ActivityResultContracts.OpenDocument(),
                uri -> { if (uri != null) doImport(uri); });
        findViewById(R.id.menuBtn).setOnClickListener(this::showMenu);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { currentSearch = s.toString(); reloadList(); }
        });

        node = new NodeApi(this, this::onPaired);

        notifyReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                String data = i.getStringExtra(MinimaAPIMessages.MINIMA_API_NOTIFY_DATA);
                try {
                    String event = new JSONObject(data).optString("event", "");
                    if ("NEWBLOCK".equals(event) || "NEWBALANCE".equals(event)) { fetchBlock(); requestSync(); }
                } catch (Exception ignored) {}
            }
        };
        ContextCompat.registerReceiver(this, notifyReceiver,
                new IntentFilter(MinimaAPIMessages.MINIMA_API_NOTIFY), ContextCompat.RECEIVER_EXPORTED);

        reloadList();   // show whatever is already stored, instantly + offline
    }

    private void applyInsets() {
        final View root = findViewById(R.id.main);
        final int headerTop = header.getPaddingTop();
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            header.setPadding(header.getPaddingLeft(), headerTop + bars.top, header.getPaddingRight(), header.getPaddingBottom());
            recycler.setPadding(recycler.getPaddingLeft(), recycler.getPaddingTop(), recycler.getPaddingRight(), bars.bottom + dp(8));
            return insets;
        });
        ViewCompat.requestApplyInsets(root);
        new WindowInsetsControllerCompat(getWindow(), root).setAppearanceLightStatusBars(false);
    }

    // ---- pairing + node ----

    private void onPaired(boolean enabled) {
        markPaired(enabled);
        refreshOwnership();
        requestSync();   // try regardless — the sync result is the authoritative "is the node reachable" signal
    }

    /**
     * Refresh the wallet ownership sets (`scripts` + `keys`) that drive the owned/contract/external
     * address labels. Failure-safe: only a successful, non-empty parse replaces (and persists) the
     * current set — an unreachable node or empty reply keeps the last good labels rather than
     * showing wrong or missing ones.
     */
    private void refreshOwnership() {
        if (refreshingOwn || node == null) return;
        refreshingOwn = true;
        node.cmd("scripts", new NodeApi.Cb() {
            @Override public void onResult(JSONObject scripts) {
                node.cmd("keys", new NodeApi.Cb() {
                    @Override public void onResult(JSONObject keys) { adopt(Ownership.parse(scripts, keys)); }
                    @Override public void onError(String m) { adopt(Ownership.parse(scripts, null)); }
                });
            }
            @Override public void onError(String m) { refreshingOwn = false; }
        });
    }

    private void adopt(Ownership parsed) {
        refreshingOwn = false;
        if (parsed == null) return;   // suspect reply — keep the last good set
        own = parsed;
        io.execute(() -> parsed.saveTo(db));
        reloadList();
    }

    /** Reflect node reachability in the pairing banner. Called by the register callback AND by the sync
     *  (so enabling the app in Minima Core *after* opening this one is picked up on the next sync). */
    public void markPaired(boolean enabled) {
        paired = enabled;
        pairingBanner.setVisibility(enabled ? View.GONE : View.VISIBLE);
        if (enabled && chainBlock == 0) fetchBlock();
    }

    private void fetchBlock() {
        node.cmd("block", new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                JSONObject r = j.optJSONObject("response");
                if (r != null) try { chainBlock = Integer.parseInt(r.optString("block", "0")); } catch (Exception ignored) {}
                updateStatus();
            }
            @Override public void onError(String m) {}
        });
    }

    /** Coalesce NEWBLOCK/NEWBALANCE/foreground bursts into a single sync. */
    private void requestSync() { ui.removeCallbacks(syncTask); ui.postDelayed(syncTask, 400); }

    private void doSync() {
        // Don't gate on `paired` — try the command; HistorySync flips the banner from the actual result.
        if (!sync.isRunning()) { syncBtn.setText("⟳ …"); status.setText("Syncing…"); sync.start(); }
    }

    private final HistorySync.Listener syncListener = new HistorySync.Listener() {
        @Override public void onProgress(int totalNew) {
            ui.post(() -> { status.setText("Syncing…  " + totalNew + " captured"); reloadList(); });
        }
        @Override public void onDone(int totalNew, boolean ok) {
            ui.post(() -> { lastSyncOk = ok; syncBtn.setText("⟳ Sync"); reloadList(); refreshOwnership(); });
        }
    };

    // ---- list ----

    private void reloadList() {
        final Ownership o = own;   // snapshot — published wholesale, never mutated
        io.execute(() -> {
            List<HistoryEntry> rows = db.list(MAX_ROWS, 0, currentSearch);
            // Hide-foreign filter: drop entries that touch none of the wallet's own addresses/coins
            // (pure contract/external activity a polluted node adopted). No-op until ownership is
            // known — never blank the list for lack of data.
            if (hideForeign && o.isLoaded()) {
                List<HistoryEntry> kept = new ArrayList<>();
                for (HistoryEntry e : rows) if (e.involvesWallet(o)) kept.add(e);
                rows = kept;
            }
            final List<HistoryEntry> shown = rows;
            final int total = db.count();
            ui.post(() -> { adapter.setData(shown); updateStatus(total); });
        });
    }

    private void updateStatus() { io.execute(() -> { final int t = db.count(); ui.post(() -> updateStatus(t)); }); }

    private void updateStatus(int total) {
        if (total == 0 && !lastSyncOk) {
            status.setText("Couldn't read history — the node may be busy or a page too large. Tap ⟳ to retry.");
            return;
        }
        String s = total + (total == 1 ? " transaction" : " transactions");
        if (chainBlock > 0) s += " · synced to block " + chainBlock;
        String last = db.getMeta("last_sync_ts", "");
        if (!last.isEmpty()) { try { s += " · " + relative(Long.parseLong(last)); } catch (Exception ignored) {} }
        status.setText(s);
    }

    // ---- detail dialog ----

    private void showDetail(HistoryEntry e) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(12), dp(20), dp(12));

        kv(box, "Direction", e.direction);
        kv(box, "Amount", (e.incoming ? "+" : e.direction.equals("sent") ? "−" : "") + Util.tidyAmount(e.amount) + " " + e.tokenName);
        kv(box, "Block", String.valueOf(e.block));
        kv(box, "Time", new SimpleDateFormat("dd MMM yyyy  HH:mm:ss", Locale.ENGLISH).format(new Date(e.timemilli)));
        copyRow(box, "Txpow id", e.txpowid);
        if (!Util.isMinima(e.tokenid)) copyRow(box, "Tokenid", e.tokenid);
        if (!e.counterparty.isEmpty()) {
            badge(box, own.classify(e.counterparty), false, 0);
            copyRow(box, e.incoming ? "From" : "To", e.counterparty);
        }
        // Entry-level ownership: none of the wallet's addresses/coins in this transaction at all —
        // this is exactly what foreign contract activity adopted by a polluted node looks like.
        if (own.isLoaded() && !e.involvesWallet(own)) {
            TextView warn = new TextView(this);
            warn.setText("No wallet address involved — contract/external activity");
            warn.setTextColor(HistoryDesign.ACCENT);
            warn.setTextSize(12f);
            warn.setPadding(0, dp(6), 0, dp(2));
            box.addView(warn);
        }
        addDeltas(box, e.deltas);
        addBreakdown(box, "Inputs", e.inputs);
        addBreakdown(box, "Outputs", e.outputs);

        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        new AlertDialog.Builder(this).setTitle("Transaction").setView(sv).setPositiveButton("Close", null).show();
    }

    private void kv(LinearLayout p, String k, String v) {
        TextView t = new TextView(this);
        t.setText(k + ":  " + v);
        t.setTextColor(HistoryDesign.TEXT);
        t.setTextSize(13f);
        t.setPadding(0, dp(4), 0, dp(4));
        t.setTextIsSelectable(true);
        p.addView(t);
    }

    private void copyRow(LinearLayout p, String k, final String v) { copyRow(p, k, v, 0); }

    /** Full value, never shortened; the whole row taps to copy the complete value. */
    private void copyRow(LinearLayout p, String k, final String v, int indentDp) {
        TextView t = new TextView(this);
        t.setText(k + ":  " + v + "   (tap to copy)");
        t.setTextColor(HistoryDesign.DIM);
        t.setTextSize(12f);
        t.setTypeface(Typeface.MONOSPACE);
        t.setPadding(dp(indentDp), dp(4), 0, dp(4));
        t.setOnClickListener(view -> {
            ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText(k, v));
            Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show();
        });
        p.addView(t);
    }

    private void sectionHeader(LinearLayout p, String title) {
        TextView h = new TextView(this);
        h.setText(title);
        h.setTextColor(HistoryDesign.ACCENT);
        h.setTextSize(12f);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        h.setPadding(0, dp(8), 0, dp(2));
        p.addView(h);
    }

    private void bullet(LinearLayout p, String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(HistoryDesign.TEXT);
        t.setTextSize(12f);
        t.setPadding(dp(6), dp(4), 0, 0);
        t.setTextIsSelectable(true);
        p.addView(t);
    }

    /** Ownership badge line: YOURS / CONTRACT / EXTERNAL (nothing while ownership is unknown).
     *  {@code mine} upgrades a CONTRACT badge to "CONTRACT · YOURS" — a shared-script coin the
     *  wallet controls via its state keys (e.g. the user's own casino bet). */
    private void badge(LinearLayout p, Ownership.Kind kind, boolean mine, int indentDp) {
        if (kind == Ownership.Kind.UNKNOWN) return;
        TextView t = new TextView(this);
        t.setText(Ownership.label(kind) + (mine && kind == Ownership.Kind.CONTRACT ? " · YOURS" : ""));
        t.setTextColor(mine ? HistoryDesign.RECEIVED : Ownership.color(kind));
        t.setTextSize(11f);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(dp(indentDp), dp(4), 0, 0);
        p.addView(t);
    }

    private void addBreakdown(LinearLayout p, String title, String json) {
        try {
            JSONArray a = new JSONArray(json);
            if (a.length() == 0) return;
            sectionHeader(p, title);
            for (int i = 0; i < a.length(); i++) {
                JSONObject c = a.optJSONObject(i);
                if (c == null) continue;
                String tid = c.optString("tokenid", "0x00");
                bullet(p, "• " + Util.tidyAmount(c.optString("amount", "")) + (Util.isMinima(tid) ? "  Minima" : ""));
                badge(p, own.classify(c.optString("addr", "")), c.optBoolean("mine", false), 14);
                if (!Util.isMinima(tid)) copyRow(p, "token", tid, 14);
                copyRow(p, "addr", c.optString("addr", ""), 14);
            }
        } catch (Exception ignored) {}
    }

    /** Per-token net effect — one entry per token, full tokenid shown and copyable. */
    private void addDeltas(LinearLayout p, String json) {
        try {
            JSONObject o = new JSONObject(json);
            if (o.length() == 0) { kv(p, "Per-token effect", "—"); return; }
            sectionHeader(p, "Per-token effect");
            for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) {
                String tid = it.next();
                bullet(p, "• " + Util.tidyAmount(o.optString(tid, "")) + (Util.isMinima(tid) ? "  Minima" : ""));
                if (!Util.isMinima(tid)) copyRow(p, "token", tid, 14);
            }
        } catch (Exception e) { kv(p, "Per-token effect", "—"); }
    }

    // ---- adapter ----

    private class TxAdapter extends RecyclerView.Adapter<TxAdapter.VH> {
        private List<HistoryEntry> data = new ArrayList<>();
        void setData(List<HistoryEntry> d) { data = d; notifyDataSetChanged(); }

        class VH extends RecyclerView.ViewHolder {
            final TextView glyph, line1, line2, right;
            VH(LinearLayout row, TextView g, TextView l1, TextView l2, TextView r) {
                super(row); glyph = g; line1 = l1; line2 = l2; right = r;
            }
        }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LinearLayout row = new LinearLayout(MainActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16), dp(11), dp(16), dp(11));
            row.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            TextView glyph = new TextView(MainActivity.this);
            glyph.setTextSize(18f);
            glyph.setWidth(dp(28));
            row.addView(glyph);

            LinearLayout mid = new LinearLayout(MainActivity.this);
            mid.setOrientation(LinearLayout.VERTICAL);
            mid.setPadding(dp(6), 0, dp(6), 0);
            mid.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView line1 = new TextView(MainActivity.this); line1.setTextSize(15f); line1.setTypeface(Typeface.DEFAULT_BOLD);
            TextView line2 = new TextView(MainActivity.this); line2.setTextSize(12f); line2.setTextColor(HistoryDesign.DIM);
            mid.addView(line1); mid.addView(line2);
            row.addView(mid);

            TextView right = new TextView(MainActivity.this);
            right.setTextSize(11f); right.setTextColor(HistoryDesign.DIM_2); right.setGravity(Gravity.END);
            row.addView(right);

            return new VH(row, glyph, line1, line2, right);
        }

        @Override public void onBindViewHolder(@NonNull VH h, int pos) {
            HistoryEntry e = data.get(pos);
            int color = "received".equals(e.direction) ? HistoryDesign.RECEIVED
                    : "sent".equals(e.direction) ? HistoryDesign.SENT : HistoryDesign.SELF;
            String g = "received".equals(e.direction) ? "↓" : "sent".equals(e.direction) ? "↑" : "⟲";
            String sign = e.incoming ? "+" : "sent".equals(e.direction) ? "−" : "";
            boolean reshuffle = e.isReshuffle();
            h.glyph.setText(g); h.glyph.setTextColor(color);
            h.line1.setText(reshuffle ? e.grossDisplay() : (sign + Util.tidyAmount(e.amount) + "  " + e.tokenName));
            h.line1.setTextColor(color);
            // Counterparty ownership tag (full address is one tap away in the detail view).
            Ownership.Kind ck = own.classify(e.counterparty);
            String tag = ck == Ownership.Kind.UNKNOWN ? "" : Ownership.label(ck) + "  ·  ";
            String cp = e.counterparty.isEmpty() ? "" : Util.shorten(e.counterparty) + "  ·  " + tag;
            h.line2.setText((reshuffle ? e.reshuffleLabel() + "  ·  " : cp) + relative(e.timemilli));
            h.right.setText("#" + e.block);
            h.itemView.setOnClickListener(v -> showDetail(e));
        }

        @Override public int getItemCount() { return data.size(); }
    }

    // ---- helpers / accessors used by HistorySync ----

    public NodeApi node() { return node; }
    public Handler ui() { return ui; }
    public int chainBlock() { return chainBlock; }
    public Ownership ownership() { return own; }

    private void openMinimaCore() {
        Intent launch = getPackageManager().getLaunchIntentForPackage(NODE_PKG);
        if (launch != null) startActivity(launch);
        else Toast.makeText(this, "Minima Core isn't installed.", Toast.LENGTH_LONG).show();
    }

    // ---- export / import: durable backup of the collected history (survives uninstall / new phone) ----

    private void showMenu(View anchor) {
        PopupMenu m = new PopupMenu(this, anchor);
        m.getMenu().add(0, 1, 0, "Export as JSON (backup)");
        m.getMenu().add(0, 3, 1, "Export as CSV (spreadsheet)");
        m.getMenu().add(0, 2, 2, "Import history (restore / merge)");
        m.getMenu().add(0, 4, 3, "Hide foreign contract activity").setCheckable(true).setChecked(hideForeign);
        m.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: exportLauncher.launch("minima-history.json"); return true;
                case 3: csvLauncher.launch("minima-history.csv"); return true;
                case 2: importLauncher.launch(new String[]{"application/json", "*/*"}); return true;
                case 4:
                    hideForeign = !hideForeign;
                    io.execute(() -> db.setMeta("hide_foreign", hideForeign ? "true" : "false"));
                    reloadList();
                    return true;
                default: return false;
            }
        });
        m.show();
    }

    /** Write the whole local DB to a user-chosen JSON file (Downloads, Drive, …). */
    private void doExport(Uri uri) {
        io.execute(() -> {
            try {
                List<HistoryEntry> all = db.all();
                JSONObject root = new JSONObject();
                root.put("app", "minima-history"); root.put("version", 1);
                root.put("exported_at", System.currentTimeMillis()); root.put("count", all.size());
                JSONArray arr = new JSONArray();
                for (HistoryEntry e : all) arr.put(e.toJson());
                root.put("tx", arr);
                try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                    os.write(root.toString().getBytes(StandardCharsets.UTF_8));
                }
                ui.post(() -> Toast.makeText(this, "Exported " + all.size() + " transactions", Toast.LENGTH_SHORT).show());
            } catch (Exception e) {
                ui.post(() -> Toast.makeText(this, "Export failed: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    /** Merge an exported file back in — idempotent (dedup on txpowid), so restoring or combining devices is safe. */
    private void doImport(Uri uri) {
        io.execute(() -> {
            try {
                StringBuilder sb = new StringBuilder();
                try (BufferedReader r = new BufferedReader(new InputStreamReader(getContentResolver().openInputStream(uri), StandardCharsets.UTF_8))) {
                    String line; while ((line = r.readLine()) != null) sb.append(line);
                }
                JSONArray arr = new JSONObject(sb.toString()).optJSONArray("tx");
                int total = 0, neu = 0;
                if (arr != null) for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null) continue;
                    HistoryEntry e = HistoryEntry.fromJson(o);
                    if (e.txpowid.isEmpty()) continue;
                    total++;
                    if (db.insert(e)) neu++;
                }
                final int ft = total, fn = neu;
                ui.post(() -> { reloadList(); Toast.makeText(this, "Imported " + ft + " · " + fn + " new", Toast.LENGTH_SHORT).show(); });
            } catch (Exception e) {
                ui.post(() -> Toast.makeText(this, "Import failed: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    /** Spreadsheet-friendly CSV of the whole history (one row per transaction). Not re-importable — use
     *  the JSON export for backup/restore. */
    private void doExportCsv(Uri uri) {
        io.execute(() -> {
            try {
                List<HistoryEntry> all = db.all();
                SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH);
                StringBuilder sb = new StringBuilder("txpowid,block,datetime,direction,amount,token,counterparty,deltas\n");
                for (HistoryEntry e : all) {
                    String signed = ("received".equals(e.direction) ? "" : "sent".equals(e.direction) ? "-" : "") + e.amount;
                    sb.append(csv(e.txpowid)).append(',')
                      .append(e.block).append(',')
                      .append(csv(fmt.format(new Date(e.timemilli)))).append(',')
                      .append(csv(e.direction)).append(',')
                      .append(csv(signed)).append(',')
                      .append(csv(e.tokenName)).append(',')
                      .append(csv(e.counterparty)).append(',')
                      .append(csv(e.deltas)).append('\n');
                }
                try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                    os.write(sb.toString().getBytes(StandardCharsets.UTF_8));
                }
                ui.post(() -> Toast.makeText(this, "Exported " + all.size() + " rows to CSV", Toast.LENGTH_SHORT).show());
            } catch (Exception e) {
                ui.post(() -> Toast.makeText(this, "CSV export failed: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    /** CSV-escape a field: quote it if it has a comma/quote/newline; double any internal quotes. */
    private static String csv(String s) {
        if (s == null) return "";
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) return "\"" + s.replace("\"", "\"\"") + "\"";
        return s;
    }

    private static String relative(long ms) {
        if (ms <= 0) return "";
        long d = System.currentTimeMillis() - ms;
        if (d < 60000) return "just now";
        if (d < 3600000) return (d / 60000) + "m ago";
        if (d < 86400000) return (d / 3600000) + "h ago";
        if (d < 7 * 86400000L) return (d / 86400000) + "d ago";
        return new SimpleDateFormat("dd MMM yyyy", Locale.ENGLISH).format(new Date(ms));
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }

    @Override
    protected void onResume() { super.onResume(); requestSync(); }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacks(syncTask);
        if (notifyReceiver != null) try { unregisterReceiver(notifyReceiver); } catch (Exception ignored) {}
        if (node != null) node.onDestroy();
        io.shutdownNow();
    }
}
