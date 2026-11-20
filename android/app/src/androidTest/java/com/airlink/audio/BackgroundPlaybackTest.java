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
public final class BackgroundPlaybackTest {
    private final Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
    private final Context context=instrumentation.getTargetContext();
    private void shell(String command) throws Exception {
        ParcelFileDescriptor descriptor=instrumentation.getUiAutomation().executeShellCommand(command);
        try(java.io.InputStream input=new ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
            byte[] buffer=new byte[1024]; while(input.read(buffer)!=-1) { }
        }
    }
    private boolean foreground() {
        for(ActivityManager.RunningServiceInfo info:((ActivityManager)context.getSystemService(Context.ACTIVITY_SERVICE)).getRunningServices(100))
            if(info.service.getClassName().equals(ReceiverService.class.getName())) return info.foreground;
        return false;
    }
    private void report(String phase,long received,long played) {
        Bundle progress=new Bundle(); progress.putString("stream",phase+": received="+received+", played="+played+", foreground="+foreground()); instrumentation.sendStatus(2,progress);
    }
    @Test public void streamsWithHomeAndLockedScreen() throws Exception {
        assertFalse("Android background running is restricted",BackgroundAccess.backgroundRestricted(context));
        assertTrue("Android battery exemption is required for this screen-off test",BackgroundAccess.batteryExempt(context));
        Activity activity=instrumentation.startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        String code=InstrumentationRegistry.getArguments().getString("pairingCode","");
        if(!code.isEmpty()) {
            assertTrue("A six-digit Windows pairing code is required",code.matches("[0-9]{6}"));
            String ip=InstrumentationRegistry.getArguments().getString("pcAddress","");
            java.util.List<ServerDiscovery.Server> servers=ServerDiscovery.scan(ip);
            assertFalse("No Windows AirLink server found",servers.isEmpty());
            ServerDiscovery.Server server=servers.get(0);
            context.startForegroundService(new Intent(context,ReceiverService.class).putExtra("address",server.address).putExtra("serverId",server.id)
                .putExtra("serverName",server.name).putExtra("port",server.port).putExtra("code",code));
        } else {
            assertTrue("Pair with Windows once before running this test or pass pairingCode",SessionVault.has(context) || SavedConnection.has(context));
            context.startForegroundService(new Intent(context,ReceiverService.class).setAction("RESUME"));
        }
        long deadline=SystemClock.elapsedRealtime()+25000;
        while(ReceiverService.playedPackets.get()<100 && SystemClock.elapsedRealtime()<deadline) Thread.sleep(200);
        assertTrue("Receiver did not start: "+ReceiverService.status,ReceiverService.playedPackets.get()>=100);
        assertTrue("Receiver was not promoted to a foreground service",foreground());
        long received=ReceiverService.receivedPackets.get(), played=ReceiverService.playedPackets.get();
        shell("input keyevent 3"); Thread.sleep(12000);
        assertTrue("Activity exit stopped received audio: "+ReceiverService.status,ReceiverService.receivedPackets.get()>received+500);
        assertTrue("Activity exit stopped AudioTrack writes: "+ReceiverService.status,ReceiverService.playedPackets.get()>played+500);
        assertTrue("Foreground service demoted after Home",foreground()); report("HOME PASSED",ReceiverService.receivedPackets.get(),ReceiverService.playedPackets.get());
        shell("input keyevent 223");
        try {
            for(int step=1;step<=6;step++) {
                received=ReceiverService.receivedPackets.get(); played=ReceiverService.playedPackets.get();
                Thread.sleep(30000);
                assertFalse("Display was woken during the test; state="+ReceiverService.status+", running="+ReceiverService.running,((PowerManager)context.getSystemService(Context.POWER_SERVICE)).isInteractive());
                assertTrue("Receiver stopped at locked-screen interval "+step+": "+ReceiverService.status,ReceiverService.running);
                assertTrue("Foreground status lost while locked",foreground());
                assertTrue("No fresh network audio while locked: "+ReceiverService.status,ReceiverService.receivedPackets.get()>received+2000);
                assertTrue("No fresh AudioTrack writes while locked: "+ReceiverService.status,ReceiverService.playedPackets.get()>played+2000);
                report("LOCKED "+(step*30)+"s PASSED",ReceiverService.receivedPackets.get(),ReceiverService.playedPackets.get());
            }
        } finally { shell("input keyevent 224"); }
        instrumentation.runOnMainSync(activity::finish);
    }
}
