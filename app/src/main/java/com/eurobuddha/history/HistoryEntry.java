package com.eurobuddha.history;

import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.util.Iterator;

/**
 * One transaction in the local history, parsed from a node `history` txpow + its matching `details`
 * entry. Direction + amount come straight from `details.difference` (the net per-token effect on the
 * wallet); the primary token is the one with the largest absolute move.
 */
public class HistoryEntry {

    public String txpowid;
    public long block, timemilli, syncedAt;
    public String direction;        // received | sent | self
    public boolean incoming;
    public String tokenid, tokenName, amount;   // primary token moved (amount is the absolute value)
    public String deltas;           // JSON { tokenid: signedAmount } — full per-token effect
    public String counterparty;     // display address of the other side
    public String inputs, outputs;  // JSON arrays [{addr, amount, tokenid}] for the detail view

    /** Full lossless serialization for export. */
    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("txpowid", txpowid); o.put("block", block); o.put("timemilli", timemilli);
            o.put("direction", direction); o.put("incoming", incoming);
            o.put("tokenid", tokenid); o.put("tokenName", tokenName); o.put("amount", amount);
            o.put("deltas", deltas); o.put("counterparty", counterparty);
            o.put("inputs", inputs); o.put("outputs", outputs); o.put("syncedAt", syncedAt);
        } catch (Exception ignored) {}
        return o;
    }

    /** Reconstruct an entry from an export record (re-import is idempotent via the txpowid key). */
    public static HistoryEntry fromJson(JSONObject o) {
        HistoryEntry e = new HistoryEntry();
        e.txpowid = o.optString("txpowid", "");
        e.block = o.optLong("block"); e.timemilli = o.optLong("timemilli");
        e.direction = o.optString("direction", "self"); e.incoming = o.optBoolean("incoming");
        e.tokenid = o.optString("tokenid", "0x00"); e.tokenName = Util.tokenName(o.opt("tokenName"), e.tokenid);
        e.amount = o.optString("amount", "0");
        e.deltas = o.optString("deltas", "{}"); e.counterparty = o.optString("counterparty", "");
        e.inputs = o.optString("inputs", "[]"); e.outputs = o.optString("outputs", "[]");
        e.syncedAt = o.optLong("syncedAt", System.currentTimeMillis());
        return e;
    }

    /** True when this transaction touches the wallet at all: an input/output (or counterparty)
     *  address the wallet OWNS, or a coin stamped {@code mine} at sync time (wallet key in its
     *  state — e.g. the user's own bet at a shared contract address). Entries where this is false
     *  are pure contract/external activity (what a trackall-polluted node adopted). */
    public boolean involvesWallet(Ownership own) {
        if (own.classify(counterparty) == Ownership.Kind.OWNED) return true;
        return anyOwnedOrMine(inputs, own) || anyOwnedOrMine(outputs, own);
    }

    private static boolean anyOwnedOrMine(String json, Ownership own) {
        try {
            JSONArray a = new JSONArray(json);
            for (int i = 0; i < a.length(); i++) {
                JSONObject c = a.optJSONObject(i);
                if (c == null) continue;
                if (c.optBoolean("mine", false)) return true;
                if (own.classify(c.optString("addr", "")) == Ownership.Kind.OWNED) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    public static HistoryEntry from(JSONObject txpow, JSONObject detail) { return from(txpow, detail, null); }

    public static HistoryEntry from(JSONObject txpow, JSONObject detail, Ownership own) {
        HistoryEntry e = new HistoryEntry();
        e.txpowid = txpow.optString("txpowid", "");
        JSONObject hdr = txpow.optJSONObject("header");
        if (hdr != null) { e.block = hdr.optLong("block", 0); e.timemilli = hdr.optLong("timemilli", 0); }

        JSONObject diff = detail != null ? detail.optJSONObject("difference") : null;
        e.deltas = diff != null ? diff.toString() : "{}";

        // primary token = the largest |net amount| in the difference map
        String pTid = "0x00";
        BigDecimal pAmt = BigDecimal.ZERO;
        if (diff != null) {
            for (Iterator<String> it = diff.keys(); it.hasNext(); ) {
                String tid = it.next();
                BigDecimal a = bd(diff.optString(tid, "0"));
                if (a.abs().compareTo(pAmt.abs()) > 0) { pAmt = a; pTid = tid; }
            }
        }
        e.tokenid = pTid;
        e.amount = pAmt.signum() == 0 ? "0" : pAmt.abs().stripTrailingZeros().toPlainString();
        int sign = pAmt.signum();
        e.incoming = sign > 0;
        e.direction = sign > 0 ? "received" : sign < 0 ? "sent" : "self";
        e.tokenName = tokenNameFor(txpow, pTid);

        JSONObject txn = txn(txpow);
        JSONArray ins = txn != null ? txn.optJSONArray("inputs") : null;
        JSONArray outs = txn != null ? txn.optJSONArray("outputs") : null;
        e.inputs = coins(ins, own);
        e.outputs = coins(outs, own);
        // received → show a sender (input) address; sent/self → show a recipient (output) address
        e.counterparty = firstAddr(e.incoming ? ins : outs);

        e.reconcile(own);
        e.syncedAt = System.currentTimeMillis();
        return e;
    }

    private static JSONObject txn(JSONObject txpow) {
        JSONObject body = txpow.optJSONObject("body");
        return body != null ? body.optJSONObject("txn") : null;
    }

    private static String tokenNameFor(JSONObject txpow, String tid) {
        if (Util.isMinima(tid)) return "Minima";
        JSONObject txn = txn(txpow);
        JSONArray outs = txn != null ? txn.optJSONArray("outputs") : null;
        if (outs != null) for (int i = 0; i < outs.length(); i++) {
            JSONObject o = outs.optJSONObject(i);
            if (o != null && tid.equals(o.optString("tokenid"))) {
                String n = Util.tokenName(o.opt("token"), tid);
                if (n != null && !n.isEmpty()) return n;
            }
        }
        return tid;   // full id, never shortened (family rule)
    }

    private static String coins(JSONArray arr, Ownership own) {
        JSONArray out = new JSONArray();
        if (arr != null) for (int i = 0; i < arr.length(); i++) {
            JSONObject c = arr.optJSONObject(i);
            // Deliberate: one unreadable coin voids the whole array so hasCoinData() fails and the
            // node's `difference` stays authoritative — never account a partial coin set.
            if (c == null) return "[]";
            try {
                JSONObject o = new JSONObject();
                o.put("addr", c.optString("miniaddress", c.optString("address", "")));
                o.put("amount", c.optString("tokenamount", c.optString("amount", "")));
                o.put("displayUnits", Util.isMinima(c.optString("tokenid", "0x00")) || c.has("tokenamount"));
                o.put("name", Util.tokenName(c.opt("token"), c.optString("tokenid", "0x00")));
                o.put("tokenid", c.optString("tokenid", "0x00"));
                // Sync-time enrichment: a wallet key/address inside the coin's STATE marks a
                // shared-contract coin the wallet controls (e.g. our own casino bet). Only
                // available here — state isn't stored — so old rows simply lack the flag.
                if (own != null && own.isLoaded() && stateMatchesWallet(c, own)) o.put("mine", true);
                out.put(o);
            } catch (Exception ignored) {}
        }
        return out.toString();
    }

    private static boolean stateMatchesWallet(JSONObject coin, Ownership own) {
        JSONArray st = coin.optJSONArray("state");
        if (st == null) return false;
        for (int i = 0; i < st.length(); i++) {
            JSONObject sv = st.optJSONObject(i);
            if (sv == null) continue;
            if (own.matchesWallet(sv.optString("data", ""))) return true;
        }
        return false;
    }

    private static String firstAddr(JSONArray arr) {
        if (arr != null && arr.length() > 0) {
            JSONObject c = arr.optJSONObject(0);
            if (c != null) return c.optString("miniaddress", c.optString("address", ""));
        }
        return "";
    }

    private static BigDecimal bd(String s) { try { return new BigDecimal(s); } catch (Exception e) { return BigDecimal.ZERO; } }

    private boolean verifiedSelfTransfer;

    /** Reuse minimaWallet HistoryTx: outputs at this wallet minus inputs at this wallet.
     * Tracking another wallet's script is not ownership of its spending keys.
     * Returns true only when a stored field actually CHANGED, so callers can skip the write. */
    public boolean reconcile(Ownership own) {
        verifiedSelfTransfer = false;
        if (own == null || !own.isLoaded() || !hasCoinData()) return false;
        try {
            java.util.LinkedHashMap<String,BigDecimal> net = new java.util.LinkedHashMap<>();
            java.util.Map<String,String> names = new java.util.HashMap<>();
            java.util.Map<String,String> foreignIn = new java.util.HashMap<>(), foreignOut = new java.util.HashMap<>();
            boolean allOwned = true, touched = false;
            for (int pass=0;pass<2;pass++) {
                JSONArray rows = new JSONArray(pass==0 ? inputs : outputs);
                for (int i=0;i<rows.length();i++) {
                    JSONObject coin=rows.getJSONObject(i);
                    String address=coin.getString("addr"), tid=coin.getString("tokenid");
                    if (coin.has("name")) names.put(tid,coin.getString("name"));
                    if (own.classify(address) != Ownership.Kind.OWNED) {
                        allOwned=false;
                        (pass==0 ? foreignIn : foreignOut).putIfAbsent(tid,address);
                        continue;
                    }
                    touched=true;
                    BigDecimal value=new BigDecimal(coin.getString("amount"));
                    net.merge(tid,pass==0 ? value.negate() : value,BigDecimal::add);
                }
            }
            if (!touched) return false; // Preserve foreign activity; never call it our split.
            String primary="0x00"; BigDecimal largest=BigDecimal.ZERO;
            for (java.util.Map.Entry<String,BigDecimal> item:net.entrySet()) {
                if (item.getValue().abs().compareTo(largest.abs())>0) {primary=item.getKey();largest=item.getValue();}
            }
            JSONObject delta=new JSONObject();
            for (java.util.Map.Entry<String,BigDecimal> item:net.entrySet()) delta.put(item.getKey(),item.getValue().stripTrailingZeros().toPlainString());
            String name=Util.isMinima(primary) ? "Minima" : names.getOrDefault(primary,primary.equals(tokenid) ? tokenName : primary);
            String amountStr=largest.abs().stripTrailingZeros().toPlainString();
            String dir=largest.signum()>0 ? "received" : largest.signum()<0 ? "sent" : "self";
            String cp=(largest.signum()>0 ? foreignIn : foreignOut).getOrDefault(primary,"");
            boolean changed=!primary.equals(tokenid) || !amountStr.equals(amount) || !dir.equals(direction)
                    || !delta.toString().equals(deltas) || !cp.equals(counterparty) || !name.equals(tokenName)
                    || (largest.signum()>0)!=incoming;
            tokenid=primary;tokenName=name;amount=amountStr;direction=dir;
            incoming=largest.signum()>0;deltas=delta.toString();counterparty=cp;
            verifiedSelfTransfer=allOwned && "self".equals(direction);
            return changed;
        } catch (Exception malformed) { return false; }
    }

    /** Legacy native rows have exact units. Legacy token rows need a fresh scaled snapshot. */
    boolean hasCoinData() {
        try {
            for (String json:new String[]{inputs,outputs}) {
                JSONArray rows=new JSONArray(json);
                if(rows.length()==0)return false;
                for(int i=0;i<rows.length();i++) {
                    JSONObject coin=rows.getJSONObject(i);
                    if(coin.getString("addr").isEmpty())return false;
                    if(!Util.isMinima(coin.getString("tokenid")) && !coin.optBoolean("displayUnits",false))return false;
                    if(new BigDecimal(coin.getString("amount")).signum()<0)return false;
                }
            }
            return true;
        }catch(Exception malformed){return false;}
    }

    // ----- split / consolidation display (a self-only coin reshuffle) -----
    private static int count(String json) {
        try { return json == null ? 0 : new JSONArray(json).length(); } catch (Exception e) { return 0; }
    }
    public boolean isSplit() { return verifiedSelfTransfer && count(outputs) > count(inputs) && count(outputs) > 1; }
    public boolean isConsolidation() { return verifiedSelfTransfer && count(inputs) > count(outputs) && count(inputs) > 1; }
    public boolean isReshuffle() { return isSplit() || isConsolidation(); }
    public String reshuffleLabel() {
        return isSplit() ? ("Split · " + count(outputs) + " coins") : ("Consolidation · " + count(inputs) + " coins");
    }
    /** For a reshuffle, the GROSS amount + token of the dominant output token (e.g. "500000  Minima") —
     *  more informative than the net "0" a self-only transaction otherwise shows. */
    public String grossDisplay() {
        try {
            JSONArray outs = new JSONArray(outputs);
            java.util.Map<String, BigDecimal> sums = new java.util.HashMap<>();
            for (int i = 0; i < outs.length(); i++) {
                JSONObject o = outs.optJSONObject(i);
                if (o == null) continue;
                sums.merge(o.optString("tokenid", "0x00"), bd(o.optString("amount", "0")), BigDecimal::add);
            }
            String domTid = "0x00"; BigDecimal domSum = BigDecimal.ZERO;
            for (java.util.Map.Entry<String, BigDecimal> en : sums.entrySet())
                if (en.getValue().compareTo(domSum) > 0) { domSum = en.getValue(); domTid = en.getKey(); }
            String name = Util.isMinima(domTid) ? "Minima" : domTid.equals(tokenid) ? tokenName : domTid;
            return Util.tidyAmount(domSum.stripTrailingZeros().toPlainString()) + "  " + name;
        } catch (Exception e) { return Util.tidyAmount(amount) + "  " + tokenName; }
    }
}
