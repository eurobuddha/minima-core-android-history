package com.eurobuddha.history;
import org.json.JSONObject;
import org.json.JSONArray;
import org.junit.Test;
import static org.junit.Assert.*;
public class TokenMetadataTest {
    private JSONObject metadata() throws Exception {
        return new JSONObject().put("name","USDT").put("ticker","USDT").put("url","https://example.test/token.svg");
    }
    @Test public void rawAndCachedSerializedNamesAreDecoded() throws Exception {
        assertEquals("USDT",Util.tokenName(metadata().toString(),"0x1234"));
        assertEquals("USDT",Util.tokenName(new JSONObject().put("name",metadata().toString()),"0x1234"));
        assertEquals("Plain",Util.tokenName("Plain","0x1234"));
        assertEquals("Minima",Util.tokenName(metadata().toString(),"0x00"));
        HistoryEntry imported=HistoryEntry.fromJson(new JSONObject().put("tokenid","0x1234").put("tokenName",metadata().toString()).put("amount","12.34"));
        assertEquals("USDT",imported.tokenName);assertEquals("12.34",imported.amount);
    }
    @Test public void nodeHistoryResolvesSerializedCoinName() throws Exception {
        JSONObject output=new JSONObject().put("tokenid","0x1234").put("token",new JSONObject().put("name",metadata().toString()));
        JSONObject txpow=new JSONObject().put("txpowid","fixture").put("body",new JSONObject().put("txn",new JSONObject().put("outputs",new JSONArray().put(output))));
        HistoryEntry entry=HistoryEntry.from(txpow,new JSONObject().put("difference",new JSONObject().put("0x1234","-5.25")));
        assertEquals("USDT",entry.tokenName);assertEquals("5.25",entry.amount);assertEquals("sent",entry.direction);
    }
}
