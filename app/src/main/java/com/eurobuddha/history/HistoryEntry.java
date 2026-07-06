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
        e.tokenid = o.optString("tokenid", "0x00"); e.tokenName = o.optString("tokenName", "");
        e.amount = o.optString("amount", "0");
        e.deltas = o.optString("deltas", "{}"); e.counterparty = o.optString("counterparty", "");
        e.inputs = o.optString("inputs", "[]"); e.outputs = o.optString("outputs", "[]");
        e.syncedAt = o.optLong("syncedAt", System.currentTimeMillis());
        return e;
    }

    public static HistoryEntry from(JSONObject txpow, JSONObject detail) {
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
        e.inputs = coins(ins);
        e.outputs = coins(outs);
        // received → show a sender (input) address; sent/self → show a recipient (output) address
        e.counterparty = firstAddr(e.incoming ? ins : outs);

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
        return Util.shorten(tid);
    }

    private static String coins(JSONArray arr) {
        JSONArray out = new JSONArray();
        if (arr != null) for (int i = 0; i < arr.length(); i++) {
            JSONObject c = arr.optJSONObject(i);
            if (c == null) continue;
            try {
                JSONObject o = new JSONObject();
                o.put("addr", c.optString("miniaddress", c.optString("address", "")));
                o.put("amount", c.optString("amount", c.optString("tokenamount", "")));
                o.put("tokenid", c.optString("tokenid", "0x00"));
                out.put(o);
            } catch (Exception ignored) {}
        }
        return out.toString();
    }

    private static String firstAddr(JSONArray arr) {
        if (arr != null && arr.length() > 0) {
            JSONObject c = arr.optJSONObject(0);
            if (c != null) return c.optString("miniaddress", c.optString("address", ""));
        }
        return "";
    }

    private static BigDecimal bd(String s) { try { return new BigDecimal(s); } catch (Exception e) { return BigDecimal.ZERO; } }
}
