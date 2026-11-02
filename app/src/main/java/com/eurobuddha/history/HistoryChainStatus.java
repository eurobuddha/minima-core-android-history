package com.eurobuddha.history;

import org.json.JSONObject;
import java.math.BigInteger;

/** Read-only inclusion display, adapted from UTXO TransactionEvidence.observedInclusion.
 * A TxPoW header height and a wallet delta are not evidence of settlement. */
final class HistoryChainStatus {
    static String describe(JSONObject reply) {
        try {
            if(reply==null||!Boolean.TRUE.equals(reply.opt("status"))||Boolean.TRUE.equals(reply.opt("pending")))return "Unverified";
            JSONObject r=reply.optJSONObject("response");
            if(r==null)return "Unverified";
            if(Boolean.FALSE.equals(r.opt("found")))return "Not found on this node — unverified";
            if(!Boolean.TRUE.equals(r.opt("found")))return "Unverified";
            Object id=r.opt("blockid");
            if(!(id instanceof String)||!((String)id).matches("(?i)0x[0-9a-f]{64}")||new BigInteger(((String)id).substring(2),16).signum()==0)return "Unverified";
            BigInteger block=whole(r,"block"),tip=whole(r,"tip"),depth=whole(r,"confirmations");
            if(block.signum()<=0||!tip.subtract(block).equals(depth))return "Unverified";
            return (depth.compareTo(BigInteger.valueOf(3))>=0?"Confirmed":"In block")+" #"+block+" · "+depth+" confirmations";
        }catch(RuntimeException invalid){return "Unverified";}
    }
    private static BigInteger whole(JSONObject r,String key){
        Object v=r.opt(key);if(!(v instanceof String)||!((String)v).matches("[0-9]{1,30}"))throw new IllegalArgumentException();
        return new BigInteger((String)v);
    }
    static String direction(String direction){return "sent".equals(direction)?"Outgoing":"received".equals(direction)?"Incoming":"Self / contract";}
}
