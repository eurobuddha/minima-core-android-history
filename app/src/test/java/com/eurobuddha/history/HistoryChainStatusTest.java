package com.eurobuddha.history;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;
public class HistoryChainStatusTest {
    private JSONObject proof() throws Exception {return new JSONObject().put("status",true).put("response",new JSONObject().put("found",true).put("blockid","0x"+"ab".repeat(32)).put("block","100").put("tip","103").put("confirmations","3"));}
    @Test public void onlyCoherentInclusionConfirms() throws Exception {
        JSONObject r=proof();assertEquals("Confirmed #100 · 3 confirmations",HistoryChainStatus.describe(r));
        r.getJSONObject("response").put("tip","101").put("confirmations","1");assertTrue(HistoryChainStatus.describe(r).startsWith("In block"));
        r.getJSONObject("response").put("tip","109");assertEquals("Unverified",HistoryChainStatus.describe(r));
    }
    @Test public void headerAndFailureAreNotConfirmation() throws Exception {
        assertEquals("Unverified",HistoryChainStatus.describe(new JSONObject().put("status",true).put("response",new JSONObject().put("header",new JSONObject().put("block","100")))));
        JSONObject r=proof().put("pending",true);assertEquals("Unverified",HistoryChainStatus.describe(r));
        r=proof().put("status",false);assertEquals("Unverified",HistoryChainStatus.describe(r));
        r=proof();r.getJSONObject("response").put("blockid","0x"+"00".repeat(32));assertEquals("Unverified",HistoryChainStatus.describe(r));
        r=proof();r.getJSONObject("response").put("block",100);assertEquals("Unverified",HistoryChainStatus.describe(r));
        assertEquals("Not found on this node — unverified",HistoryChainStatus.describe(new JSONObject().put("status",true).put("response",new JSONObject().put("found",false))));
    }
}
