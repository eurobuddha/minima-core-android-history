package org.minimarex.history;

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
        requestSync();   // try regardless — the sync result is the authoritative "is the node reachable" signal
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
            ui.post(() -> { lastSyncOk = ok; syncBtn.setText("⟳ Sync"); reloadList(); });
        }
    };

    // ---- list ----

    private void reloadList() {
        io.execute(() -> {
            final List<HistoryEntry> rows = db.list(MAX_ROWS, 0, currentSearch);
            final int total = db.count();
            ui.post(() -> { adapter.setData(rows); updateStatus(total); });
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
        if (!e.counterparty.isEmpty()) copyRow(box, e.incoming ? "From" : "To", e.counterparty);
        kv(box, "Per-token effect", prettyDeltas(e.deltas));
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
        p.addView(t);
    }

    private void copyRow(LinearLayout p, String k, final String v) {
        TextView t = new TextView(this);
        t.setText(k + ":  " + v + "   (tap to copy)");
        t.setTextColor(HistoryDesign.DIM);
        t.setTextSize(12f);
        t.setTypeface(Typeface.MONOSPACE);
        t.setPadding(0, dp(4), 0, dp(4));
        t.setOnClickListener(view -> {
            ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText(k, v));
            Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show();
        });
        p.addView(t);
    }

    private void addBreakdown(LinearLayout p, String title, String json) {
        try {
            JSONArray a = new JSONArray(json);
            if (a.length() == 0) return;
            TextView h = new TextView(this);
            h.setText(title);
            h.setTextColor(HistoryDesign.ACCENT);
            h.setTextSize(12f);
            h.setTypeface(Typeface.DEFAULT_BOLD);
            h.setPadding(0, dp(8), 0, dp(2));
            p.addView(h);
            for (int i = 0; i < a.length(); i++) {
                JSONObject c = a.optJSONObject(i);
                if (c == null) continue;
                TextView t = new TextView(this);
                String tid = c.optString("tokenid", "0x00");
                String tok = Util.isMinima(tid) ? "Minima" : Util.shorten(tid);
                t.setText("• " + Util.tidyAmount(c.optString("amount", "")) + " " + tok + "  →  " + Util.shorten(c.optString("addr", "")));
                t.setTextColor(HistoryDesign.DIM);
                t.setTextSize(12f);
                t.setPadding(dp(6), dp(1), 0, dp(1));
                p.addView(t);
            }
        } catch (Exception ignored) {}
    }

    private String prettyDeltas(String json) {
        try {
            JSONObject o = new JSONObject(json);
            StringBuilder sb = new StringBuilder();
            for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) {
                String tid = it.next();
                if (sb.length() > 0) sb.append(", ");
                sb.append(Util.tidyAmount(o.optString(tid, ""))).append(" ").append(Util.isMinima(tid) ? "Minima" : Util.shorten(tid));
            }
            return sb.length() > 0 ? sb.toString() : "—";
        } catch (Exception e) { return "—"; }
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
            h.glyph.setText(g); h.glyph.setTextColor(color);
            h.line1.setText(sign + Util.tidyAmount(e.amount) + "  " + e.tokenName); h.line1.setTextColor(color);
            String cp = e.counterparty.isEmpty() ? "" : Util.shorten(e.counterparty) + "  ·  ";
            h.line2.setText(cp + relative(e.timemilli));
            h.right.setText("#" + e.block);
            h.itemView.setOnClickListener(v -> showDetail(e));
        }

        @Override public int getItemCount() { return data.size(); }
    }

    // ---- helpers / accessors used by HistorySync ----

    public NodeApi node() { return node; }
    public Handler ui() { return ui; }
    public int chainBlock() { return chainBlock; }

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
        m.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: exportLauncher.launch("minima-history.json"); return true;
                case 3: csvLauncher.launch("minima-history.csv"); return true;
                case 2: importLauncher.launch(new String[]{"application/json", "*/*"}); return true;
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
