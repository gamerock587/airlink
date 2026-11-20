package com.airlink.audio;

import android.app.*;
import android.content.*;
import android.os.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class AudioSignalTest {
    @Test public void pcToneReachesAndroidPlayback() throws Exception {
        Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation(); Context context=instrumentation.getTargetContext();
        instrumentation.startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        assertTrue("Connect once to save the PC code before this check",SessionVault.has(context)||SavedConnection.has(context));
        context.startForegroundService(new Intent(context,ReceiverService.class).setAction("RESUME"));
        long deadline=SystemClock.elapsedRealtime()+20000;
        while(ReceiverService.playedPackets.get()<100 && SystemClock.elapsedRealtime()<deadline) Thread.sleep(100);
        assertTrue("Audio service did not connect: "+ReceiverService.status,ReceiverService.playedPackets.get()>=100);
        Bundle ready=new Bundle(); ready.putString("stream","READY FOR PC TEST TONE"); instrumentation.sendStatus(2,ready);
        deadline=SystemClock.elapsedRealtime()+30000;
        while(ReceiverService.peakPcm<0.01f && SystemClock.elapsedRealtime()<deadline) Thread.sleep(100);
        Bundle result=new Bundle(); result.putString("stream","PC peak="+ReceiverService.peakPcm+", received="+ReceiverService.receivedPackets.get()+", played="+ReceiverService.playedPackets.get()+", route="+ReceiverService.audioRoute); instrumentation.sendStatus(2,result);
        assertTrue("Windows is delivering silent PCM to Android",ReceiverService.peakPcm>=0.01f);
    }
}
