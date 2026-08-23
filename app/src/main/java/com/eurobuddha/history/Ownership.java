package com.eurobuddha.history;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * The wallet's own address/key sets, for labeling every address in the history as OWNED (a simple
 * wallet address), CONTRACT (a non-simple script the node knows — e.g. the shared casino covenant),
 * or EXTERNAL (neither). Built from the node's `scripts` (+ `keys`) replies; both the 0x and Mx
 * forms of every address are stored lowercased so either form classifies. Persisted in the app DB's
 * meta table so stored history is labeled instantly and offline; when nothing is cached yet,
 * {@link #classify} returns {@link Kind#UNKNOWN} and the UI renders NO label — never a wrong one.
 *
 * Instances are immutable-in-practice: built once, then published wholesale (the UI thread swaps
 * the field; the IO thread reads whichever snapshot it sees). Never mutate a published instance.
 */
public class Ownership {

    public enum Kind { OWNED, CONTRACT, EXTERNAL, UNKNOWN }

    private static final String META_OWNED    = "own_addrs";
    private static final String META_CONTRACT = "contract_addrs";
    private static final String META_KEYS     = "wallet_keys";

    final Set<String> owned = new HashSet<>();      // simple wallet addresses (0x + Mx, lowercased)
    final Set<String> contracts = new HashSet<>();  // non-simple script addresses (0x + Mx, lowercased)
    final Set<String> keys = new HashSet<>();       // wallet public keys (lowercased)

    public boolean isLoaded() { return !owned.isEmpty(); }

    public Kind classify(String addr) {
        if (!isLoaded()) return Kind.UNKNOWN;
        if (addr == null || addr.isEmpty()) return Kind.UNKNOWN;
        String a = addr.toLowerCase(Locale.ROOT);
        if (owned.contains(a)) return Kind.OWNED;
        if (contracts.contains(a)) return Kind.CONTRACT;
        return Kind.EXTERNAL;
    }

    /** True when this state-var value is one of the wallet's keys/addresses — marks a contract coin
     *  as controlled by this wallet (e.g. the user's own casino bet at the shared script address). */
    public boolean matchesWallet(String value) {
        if (value == null || value.isEmpty()) return false;
        String v = value.toLowerCase(Locale.ROOT);
        return keys.contains(v) || owned.contains(v);
    }

    public static String label(Kind k) {
        switch (k) {
            case OWNED:    return "YOURS";
            case CONTRACT: return "CONTRACT";
            case EXTERNAL: return "EXTERNAL";
            default:       return "";
        }
    }

    public static int color(Kind k) {
        switch (k) {
            case OWNED:    return HistoryDesign.RECEIVED;
            case CONTRACT: return HistoryDesign.ACCENT;
            case EXTERNAL: return HistoryDesign.DIM_2;
            default:       return HistoryDesign.DIM_2;
        }
    }

    // ---- building from node replies ----

    /** Parse a `scripts` reply (response may be a bare array or wrapped) + optional `keys` reply.
     *  Returns null when the scripts list is missing or EMPTY — an empty success is suspect (a live
     *  wallet always has its default simple address), and must never overwrite a good cache. */
    public static Ownership parse(JSONObject scriptsResp, JSONObject keysResp) {
        JSONArray rows = array(scriptsResp);
        if (rows == null || rows.length() == 0) return null;
        Ownership o = new Ownership();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject r = rows.optJSONObject(i);
            if (r == null) continue;
            boolean simple = truthy(r.opt("simple"));
            addAddr(simple ? o.owned : o.contracts, r.optString("address", ""));
            addAddr(simple ? o.owned : o.contracts, r.optString("miniaddress", ""));
            String pk = r.optString("publickey", "");
            if (!pk.isEmpty() && !pk.equals("0x00")) o.keys.add(pk.toLowerCase(Locale.ROOT));
        }
        JSONArray karr = array(keysResp);
        if (karr != null) for (int i = 0; i < karr.length(); i++) {
            JSONObject k = karr.optJSONObject(i);
            if (k == null) continue;
            String pk = k.optString("publickey", "");
            if (!pk.isEmpty()) o.keys.add(pk.toLowerCase(Locale.ROOT));
        }
        return o.isLoaded() ? o : null;
    }

    private static void addAddr(Set<String> into, String a) {
        if (a != null && !a.isEmpty()) into.add(a.toLowerCase(Locale.ROOT));
    }

    private static JSONArray array(JSONObject resp) {
        if (resp == null) return null;
        Object r = resp.opt("response");
        if (r instanceof JSONArray) return (JSONArray) r;
        if (r instanceof JSONObject) {
            JSONObject jo = (JSONObject) r;
            JSONArray a = jo.optJSONArray("scripts");
            if (a == null) a = jo.optJSONArray("keys");
            return a;
        }
        return null;
    }

    private static boolean truthy(Object v) {
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof Number) return ((Number) v).intValue() == 1;
        if (v instanceof String) { String s = ((String) v).trim(); return s.equals("1") || s.equalsIgnoreCase("true"); }
        return false;
    }

    // ---- persistence in the meta table (offline-first labels) ----

    public static Ownership fromMeta(HistoryDb db) {
        Ownership o = new Ownership();
        load(db.getMeta(META_OWNED, "[]"), o.owned);
        load(db.getMeta(META_CONTRACT, "[]"), o.contracts);
        load(db.getMeta(META_KEYS, "[]"), o.keys);
        return o;
    }

    public void saveTo(HistoryDb db) {
        db.setMeta(META_OWNED, toArr(owned));
        db.setMeta(META_CONTRACT, toArr(contracts));
        db.setMeta(META_KEYS, toArr(keys));
    }

    private static void load(String json, Set<String> into) {
        try {
            JSONArray a = new JSONArray(json);
            for (int i = 0; i < a.length(); i++) {
                String s = a.optString(i, "");
                if (!s.isEmpty()) into.add(s);
            }
        } catch (Exception ignored) {}
    }

    private static String toArr(Set<String> s) {
        JSONArray a = new JSONArray();
        for (String v : s) a.put(v);
        return a.toString();
    }
}
