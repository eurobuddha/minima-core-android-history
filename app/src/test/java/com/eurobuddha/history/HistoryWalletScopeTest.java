package com.eurobuddha.history;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;
public class HistoryWalletScopeTest {
 static Ownership own(){Ownership o=new Ownership();o.owned.add("core");o.owned.add("change");o.contracts.add("tracked-wallet");return o;}
 static JSONObject coin(String address,String amount)throws Exception{return new JSONObject().put("address",address).put("amount",amount).put("tokenid","0x00");}
 static JSONObject tx(JSONArray ins,JSONArray outs)throws Exception{return new JSONObject().put("txpowid","public-fixture").put("body",new JSONObject().put("txn",new JSONObject().put("inputs",ins).put("outputs",outs)));}
 static JSONObject detail()throws Exception{return new JSONObject().put("difference",new JSONObject().put("0x00","0"));}
 @Test public void transferToTrackedWalletIsSentNotSplitEvenWhenNodeNetIsZero()throws Exception{
  HistoryEntry e=HistoryEntry.from(tx(new JSONArray().put(coin("core","1060.00000213467")),new JSONArray().put(coin("tracked-wallet","1000")).put(coin("change","60.00000213467"))),detail(),own());
  assertEquals("sent",e.direction);assertEquals("1000",e.amount);assertEquals("tracked-wallet",e.counterparty);assertFalse(e.isSplit());
 }
 @Test public void returnedSweepIsReceivedEvenWhenBothAddressesAreTracked()throws Exception{
  HistoryEntry e=HistoryEntry.from(tx(new JSONArray().put(coin("tracked-wallet","1000")),new JSONArray().put(coin("core","1000"))),detail(),own());
  assertEquals("received",e.direction);assertEquals("1000",e.amount);assertEquals("tracked-wallet",e.counterparty);
 }
 @Test public void changeFirstDoesNotReplaceTheRecipient()throws Exception{
  HistoryEntry e=HistoryEntry.from(tx(new JSONArray().put(coin("core","1060")),new JSONArray().put(coin("change","60")).put(coin("tracked-wallet","1000"))),detail(),own());
  assertEquals("tracked-wallet",e.counterparty);assertEquals("1000",e.amount);
 }
 @Test public void actualCoreSplitStillShowsAsSplit()throws Exception{
  HistoryEntry e=HistoryEntry.from(tx(new JSONArray().put(coin("core","1000")),new JSONArray().put(coin("core","500")).put(coin("change","500"))),detail(),own());
  assertEquals("self",e.direction);assertTrue(e.isSplit());
 }
 @Test public void foreignTrackedSplitIsNotPresentedAsCoreSplit()throws Exception{
  HistoryEntry e=HistoryEntry.from(tx(new JSONArray().put(coin("tracked-wallet","1000")),new JSONArray().put(coin("tracked-wallet","500")).put(coin("tracked-wallet","500"))),detail(),own());
  assertFalse(e.isSplit());
 }
 @Test public void scaledTokenUsesDisplayUnitsAndLegacyUnitsAreNotGuessed()throws Exception{
  JSONObject input=coin("core","1000000").put("tokenid","0xtoken").put("tokenamount","10");
  JSONObject change=coin("change","200000").put("tokenid","0xtoken").put("tokenamount","2");
  JSONObject other=coin("tracked-wallet","800000").put("tokenid","0xtoken").put("tokenamount","8");
  HistoryEntry e=HistoryEntry.from(tx(new JSONArray().put(input),new JSONArray().put(change).put(other)),detail(),own());
  assertEquals("sent",e.direction);assertEquals("8",e.amount);assertEquals("0xtoken",e.tokenid);
  JSONArray old=new JSONArray(e.inputs);old.getJSONObject(0).remove("displayUnits");e.inputs=old.toString();
  e.direction="self";e.amount="0";assertFalse(e.reconcile(own()));assertFalse(e.isSplit());assertEquals("0",e.amount);
 }
 @Test public void missingOwnershipOrMalformedAmountsCannotClaimSplit()throws Exception{
  JSONObject tx=tx(new JSONArray().put(coin("core","1000")),new JSONArray().put(coin("core","500")).put(coin("change","500")));
  assertFalse(HistoryEntry.from(tx,detail()).isSplit());
  tx.getJSONObject("body").getJSONObject("txn").getJSONArray("outputs").getJSONObject(0).put("amount","bad");
  HistoryEntry e=HistoryEntry.from(tx,detail(),own());assertFalse(e.isSplit());assertFalse(e.reconcile(own()));
 }
 @Test public void settledEntryReportsNoChangeAndOwnershipComparesByValue()throws Exception{
  HistoryEntry e=HistoryEntry.from(tx(new JSONArray().put(coin("core","1060")),new JSONArray().put(coin("tracked-wallet","1000")).put(coin("change","60"))),detail(),own());
  assertEquals("sent",e.direction);assertFalse(e.reconcile(own()));assertEquals("tracked-wallet",e.counterparty); // settled: nothing to write
  assertEquals(own(),own());assertEquals(own().hashCode(),own().hashCode());
  Ownership other=own();other.keys.add("0xkey");assertNotEquals(own(),other);
 }

}
