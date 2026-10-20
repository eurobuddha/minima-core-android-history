package com.eurobuddha.history;
import android.app.Application;
import android.database.Cursor;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class)
@Config(sdk=34,application=Application.class)
public class HistoryCachedScopeTest {
 static Ownership own(){Ownership o=new Ownership();o.owned.add("core");o.owned.add("change");o.contracts.add("tracked-wallet");return o;}
 static HistoryEntry legacy()throws Exception{
  HistoryEntry e=new HistoryEntry();e.txpowid="fixture-transfer";e.block=2374784;e.timemilli=100;e.syncedAt=200;
  e.direction="self";e.incoming=false;e.tokenid="0x00";e.tokenName="Minima";e.amount="0";e.deltas="{\"0x00\":\"0\"}";e.counterparty="tracked-wallet";
  e.inputs="[{\"addr\":\"core\",\"amount\":\"1060.00000213467\",\"tokenid\":\"0x00\"}]";
  e.outputs="[{\"addr\":\"tracked-wallet\",\"amount\":\"1000\",\"tokenid\":\"0x00\"},{\"addr\":\"change\",\"amount\":\"60.00000213467\",\"tokenid\":\"0x00\"}]";return e;
 }
 @Test public void cachedZeroEntryIsRepairedDurablyAndSearchExportSeeSentAmount()throws Exception{
  android.content.Context app=RuntimeEnvironment.getApplication();app.deleteDatabase("minima_history.db");
  HistoryDb db=new HistoryDb(app);HistoryEntry old=legacy();assertTrue(db.insert(old));
  db.reconcile(own());assertEquals(1,db.count());assertEquals(1,db.list(50,0,"sent").size());
  HistoryEntry fixed=db.all().get(0);assertEquals("sent",fixed.direction);assertEquals("1000",fixed.amount);assertFalse(fixed.isSplit());
  assertEquals(old.inputs,fixed.inputs);assertEquals(old.outputs,fixed.outputs);assertEquals(200,fixed.syncedAt);assertEquals(2374784,fixed.block);
  assertEquals("1000",db.all().get(0).toJson().getString("amount"));
  assertFalse(db.insert(old)); // Import remains idempotent and cannot replace a repaired row.
  db.close();db=new HistoryDb(app);
  try(Cursor c=db.getReadableDatabase().rawQuery("SELECT direction,amount FROM tx",null)){assertTrue(c.moveToFirst());assertEquals("sent",c.getString(0));assertEquals("1000",c.getString(1));}
  db.close();
 }
 @Test public void freshKnownRowRefreshesWithoutCountingItAsANewTransaction()throws Exception{
  android.content.Context app=RuntimeEnvironment.getApplication();app.deleteDatabase("minima_history.db");HistoryDb db=new HistoryDb(app);
  HistoryEntry e=legacy();db.insert(e);e.reconcile(own());e.syncedAt=300;
  assertFalse(db.insertFromNode(e));assertEquals(1,db.count());db.reconcile(own());assertEquals("1000",db.all().get(0).amount);
  assertEquals(200,db.all().get(0).syncedAt); // refresh keeps the first-capture time
  HistoryEntry malformed=legacy();malformed.inputs="[]";malformed.outputs="[]";
  assertFalse(db.insertFromNode(malformed));assertEquals("1000",db.all().get(0).amount);db.close();
 }
 @Test public void savedRealSplitRemainsSplitAfterReopeningDatabase()throws Exception{
  android.content.Context app=RuntimeEnvironment.getApplication();app.deleteDatabase("minima_history.db");HistoryDb db=new HistoryDb(app);
  HistoryEntry e=legacy();e.inputs="[{\"addr\":\"core\",\"amount\":\"1000\",\"tokenid\":\"0x00\"}]";
  e.outputs="[{\"addr\":\"core\",\"amount\":\"500\",\"tokenid\":\"0x00\"},{\"addr\":\"change\",\"amount\":\"500\",\"tokenid\":\"0x00\"}]";
  db.insert(e);db.reconcile(own());assertTrue(db.all().get(0).isSplit());db.close();db=new HistoryDb(app);db.reconcile(own());assertTrue(db.all().get(0).isSplit());db.close();
 }
}
