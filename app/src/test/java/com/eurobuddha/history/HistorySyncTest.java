package com.eurobuddha.history;

import android.os.Handler;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Synthetic replies only: no node, storage or network is contacted. */
public class HistorySyncTest {
    private static class Fixture {
        final MainActivity screen = mock(MainActivity.class);
        final HistoryDb db = mock(HistoryDb.class);
        final NodeApi node = mock(NodeApi.class);
        final Handler handler = mock(Handler.class);
        final HistorySync.Listener listener = mock(HistorySync.Listener.class);
        final Map<String,String> meta = new HashMap<>();
        final List<String> commands = new ArrayList<>();
        final ArrayDeque<Runnable> queue = new ArrayDeque<>();
        NodeApi.Cb reply;
        final HistorySync sync;
        Fixture() {
            when(screen.node()).thenReturn(node); when(screen.ui()).thenReturn(handler);
            when(screen.io()).thenReturn(Runnable::run);   // DB work runs inline; paging stays on the handler queue
            when(handler.post(any(Runnable.class))).thenAnswer(i->{((Runnable)i.getArgument(0)).run();return true;});
            when(db.getMeta(anyString(),anyString())).thenAnswer(i -> meta.getOrDefault(i.getArgument(0),i.getArgument(1)));
            doAnswer(i->{meta.put(i.getArgument(0),i.getArgument(1));return null;}).when(db).setMeta(anyString(),anyString());
            when(db.insertFromNode(any())).thenReturn(true);
            when(handler.postDelayed(any(Runnable.class),anyLong())).thenAnswer(i->{queue.add(i.getArgument(0));return true;});
            doAnswer(i->{commands.add(i.getArgument(0));reply=i.getArgument(1);return null;}).when(node).cmd(anyString(),any());
            sync = new HistorySync(screen,db,listener);
        }
        void next() { assertFalse(queue.isEmpty()); queue.remove().run(); }
        /** A failed sync records where/why but must never write the success markers. */
        void assertFailedNotSynced(String offset) {
            assertNull(meta.get("last_sync_ts")); assertNull(meta.get(HistorySync.BACKFILL_DONE));
            assertEquals(offset,meta.get("failed_offset")); assertFalse(meta.get("last_sync_error").isEmpty());
        }
    }
    private static JSONObject page(int count) throws Exception {
        JSONArray tx = new JSONArray();
        for(int i=0;i<count;i++) tx.put(new JSONObject().put("txpowid","fixture-"+i));
        return new JSONObject().put("status",true).put("response",new JSONObject().put("txpows",tx));
    }
    @Test public void failedSingleRecordNeverAdvancesOrMarksTheScanComplete() {
        Fixture f=new Fixture(); f.sync.start();
        while(f.sync.isRunning()) { f.reply.onError("TOO_LONG"); if(!f.queue.isEmpty())f.next(); }
        assertEquals(7,f.commands.size());
        for(String c:f.commands)assertTrue(c.endsWith("offset:0"));
        f.assertFailedNotSynced("0"); verify(f.listener).onDone(0,false);
    }
    @Test public void retryCanRecoverTheSameRecordThenComplete() throws Exception {
        Fixture f=new Fixture();f.sync.start();
        for(int i=0;i<4;i++){f.reply.onError("timeout");f.next();}
        assertTrue(f.commands.get(4).endsWith("max:1 offset:0"));
        f.reply.onResult(page(1));f.next();
        assertTrue(f.commands.get(5).endsWith("offset:1"));
        f.reply.onResult(page(0));
        verify(f.listener).onDone(1,true);assertEquals("true",f.meta.get(HistorySync.BACKFILL_DONE));
    }
    @Test public void oldCompletionMarkerCannotHidePreviouslySkippedRows() throws Exception {
        Fixture f=new Fixture();f.meta.put("backfill_done","true");when(f.db.insertFromNode(any())).thenReturn(false);
        f.sync.start();f.reply.onResult(page(8));
        assertTrue(f.sync.isRunning());f.next();assertTrue(f.commands.get(1).endsWith("offset:8"));
        f.reply.onResult(page(0));verify(f.listener).onDone(0,true);
    }
    @Test public void verifiedBackfillStillUsesTheIncrementalKnownRowStop() throws Exception {
        Fixture f=new Fixture();f.meta.put(HistorySync.BACKFILL_DONE,"true");when(f.db.insertFromNode(any())).thenReturn(false);
        f.sync.start();f.reply.onResult(page(8));assertFalse(f.sync.isRunning());assertTrue(f.queue.isEmpty());
        verify(f.listener).onDone(0,true);
    }
    @Test public void nullOrIncompleteStatusCannotBecomeAnEmptySuccessfulHistory() throws Exception {
        for(JSONObject bad:new JSONObject[]{null,page(0).put("status","true"),page(0).put("status",JSONObject.NULL)}) {
            Fixture f=new Fixture();f.sync.start();
            while(f.sync.isRunning()){f.reply.onResult(bad);if(!f.queue.isEmpty())f.next();}
            verify(f.listener).onDone(0,false);f.assertFailedNotSynced("0");
        }
    }
    @Test public void malformedRowsCannotBeSilentlySkippedOnAShortPage() throws Exception {
        Fixture f=new Fixture();JSONObject bad=page(2);
        bad.getJSONObject("response").getJSONArray("txpows").put(1,new JSONObject());f.sync.start();
        while(f.sync.isRunning()){f.reply.onResult(bad);if(!f.queue.isEmpty())f.next();}
        verify(f.db,never()).insertFromNode(any());verify(f.listener).onDone(0,false);f.assertFailedNotSynced("0");
    }
    @Test public void fetchLimitPreservesPriorSuccessfulSyncMetadata() throws Exception {
        Fixture f=new Fixture();f.meta.put("last_sync_ts","123");f.sync.start();
        for(int i=0;i<600;i++){f.reply.onResult(page(8));f.next();}
        assertFalse(f.sync.isRunning());assertEquals(600,f.commands.size());
        assertEquals("123",f.meta.get("last_sync_ts"));assertNull(f.meta.get(HistorySync.BACKFILL_DONE));
        assertEquals("4800",f.meta.get("failed_offset"));assertEquals("4800",f.meta.get(HistorySync.BACKFILL_OFFSET));
        verify(f.listener).onDone(4800,false);
    }
    @Test public void failureIsSurfacedAndBackfillResumesAfterTheLastGoodPage() throws Exception {
        Fixture f=new Fixture();f.sync.start();
        for(int i=0;i<2;i++){f.reply.onResult(page(8));f.next();}          // two good pages → resume point 16
        while(f.sync.isRunning()){f.reply.onError("TOO_LONG");if(!f.queue.isEmpty())f.next();}
        assertEquals(9,f.commands.size());f.assertFailedNotSynced("16");assertEquals("16",f.meta.get(HistorySync.BACKFILL_OFFSET));
        assertTrue(MainActivity.statusLine(16,false,100,"123").contains("sync incomplete"));
        assertFalse(MainActivity.statusLine(16,true,100,"").contains("incomplete"));
        Fixture g=new Fixture();g.meta.putAll(f.meta);g.sync.start();       // next launch: stored offset, not 0
        assertTrue(g.commands.get(0).endsWith("max:8 offset:16"));
        g.reply.onResult(page(0));verify(g.listener).onDone(0,true);
        assertEquals("true",g.meta.get(HistorySync.BACKFILL_DONE));assertEquals("0",g.meta.get(HistorySync.BACKFILL_OFFSET));
        assertEquals("",g.meta.get("last_sync_error"));
    }
    @Test public void completedBackfillIgnoresTheStoredOffset() throws Exception {
        Fixture f=new Fixture();f.meta.put(HistorySync.BACKFILL_DONE,"true");f.meta.put(HistorySync.BACKFILL_OFFSET,"16");
        f.sync.start();assertTrue(f.commands.get(0).endsWith("offset:0"));
    }
    @Test public void closingStopsQueuedRetriesAndLateReplies() throws Exception {
        Fixture f=new Fixture();f.sync.start();f.reply.onError("timeout");f.sync.cancel();f.next();
        f.reply.onResult(page(0));f.sync.start();
        assertEquals(1,f.commands.size());assertTrue(f.meta.isEmpty());verifyNoInteractions(f.listener);
    }
}
