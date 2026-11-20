package com.airlink.audio;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.media.*;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.wifi.WifiManager;
import android.os.*;
import org.json.JSONObject;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

public final class ReceiverService extends Service {
    public static volatile boolean running;
    public static volatile String status = "Scan for your Windows PC to connect", statistics = "";
    public static volatile String lastInterruption="";
    public static final AtomicLong receivedPackets=new AtomicLong(), playedPackets=new AtomicLong();
    public static final AtomicLong incomingPackets=new AtomicLong(), latePackets=new AtomicLong();
    public static volatile float peakPcm;
    public static volatile String audioRoute="Waiting for audio output";
    private volatile boolean alive;
    private volatile Session session;
    private volatile DatagramSocket receiver;
    private Thread receiverThread, playbackThread, heartbeatThread;
    private PowerManager.WakeLock wake;
    private WifiManager.WifiLock wifi;
    private WifiManager.MulticastLock multicast;
    private AudioManager audioManager;
    private AudioFocusRequest focus;
    private MediaSession mediaSession;
    private String serverAddress, serverId, pin, serverName;
    private int serverPort=Protocol.AUDIO_PORT;
    private volatile boolean audioStarted;
    private volatile long acceptedAt;
    private volatile boolean focusPaused, recovering;
    private volatile boolean stopRequested;
    private boolean restoreStartup;
    private int recoveries;
    private int targetPackets;
    private final Handler handler = new Handler(Looper.getMainLooper());
    public static String describe(Throwable e) { return e.getClass().getSimpleName()+(e.getMessage()==null?"":": "+e.getMessage()); }
    private final Runnable connectDeadline=()->{
        if(alive && !audioStarted) {
            status=session==null?"Connection timed out before the PC accepted pairing. Check the Windows server's connection status.":"PC accepted pairing, but no playable audio arrived. Check the Windows audio output.";
            endReceiver();
        }
    };
    private final Runnable renewWake = new Runnable() {
        @Override public void run() {
            if(alive && wake!=null) { wake.acquire(10*60*1000L); handler.postDelayed(this,5*60*1000L); }
        }
    };
    private final BroadcastReceiver noisy = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { status = "Headphones unplugged. Receiver stopped."; endReceiver(); }
    };
    private static final class Session {
        final String id;
        final byte[] bytes, key;
        final InetSocketAddress peer;
        final JitterBuffer buffer;
        final Protocol.Decoder decoder;
        volatile long resumeCounter;
        volatile long lastSeen = SystemClock.elapsedRealtime();
        Session(String id, byte[] key, InetSocketAddress peer, int target) throws Exception {
            this.id=id; this.bytes=Protocol.unhex(id); this.key=key; this.peer=peer; buffer=new JitterBuffer(target); decoder=new Protocol.Decoder(key,bytes);
        }
    }

    @Override public void onCreate() {
        super.onCreate();
        var prefs = getSharedPreferences("airlink", MODE_PRIVATE);
        targetPackets = prefs.getInt("buffer",8);
        if(targetPackets!=4 && targetPackets!=8 && targetPackets!=16) targetPackets=8;
        audioManager=(AudioManager)getSystemService(AUDIO_SERVICE);
        focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes()).setOnAudioFocusChangeListener(change -> {
                if(change==AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                    focusPaused=true; lastInterruption="Temporary audio-focus loss"; status="Paused briefly by Android audio focus; playback resumes when focus returns.";
                    Session current=session; if(current!=null) current.buffer.rebuffer();
                } else if(change==AudioManager.AUDIOFOCUS_GAIN) {
                    focusPaused=false; Session current=session; if(current!=null) { current.buffer.rebuffer(); status="Audio focus restored. Resuming playback…"; }
                } else if(change==AudioManager.AUDIOFOCUS_LOSS) {
                    disconnect("Another app took audio focus. Connect again when it finishes.");
                }
            },handler).build();
        mediaSession=new MediaSession(this,"AirLink");
        mediaSession.setCallback(new MediaSession.Callback() {
            @Override public void onStop() { endReceiver(); }
            @Override public void onPause() { endReceiver(); }
        });
        mediaSession.setPlaybackState(new PlaybackState.Builder().setActions(PlaybackState.ACTION_STOP|PlaybackState.ACTION_PAUSE).setState(PlaybackState.STATE_PAUSED,0,1).build());
        mediaSession.setActive(true);
        NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel("receiver","Audio receiver",NotificationManager.IMPORTANCE_LOW));
        if(Build.VERSION.SDK_INT>=33) registerReceiver(noisy,new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(noisy,new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        if(intent!=null && "STOP".equals(intent.getAction())) { endReceiver(); return START_NOT_STICKY; }
        if(alive) {
            // A phone can demote a restricted service without killing its Java
            // process. Reassert foreground status after the user allows it.
            try { promoteForeground(); }
            catch(Exception e) { lastInterruption="Foreground service could not be restored: "+describe(e); }
            return START_STICKY;
        }
        restoreStartup=intent==null || "RESUME".equals(intent.getAction());
        if(restoreStartup) {
            if(!SessionVault.has(this) && !SavedConnection.has(this)) { endReceiver(); return START_NOT_STICKY; }
        } else {
            if(!intent.hasExtra("address") || !intent.hasExtra("serverId") || !intent.hasExtra("code")) { endReceiver(); return START_NOT_STICKY; }
            serverAddress=intent.getStringExtra("address"); serverId=intent.getStringExtra("serverId"); pin=intent.getStringExtra("code"); serverName=intent.getStringExtra("serverName");
            serverPort=intent.getIntExtra("port",Protocol.AUDIO_PORT);
        }
        status="Starting Android receiver…";
        receivedPackets.set(0); playedPackets.set(0); incomingPackets.set(0); latePackets.set(0); peakPcm=0;
        lastInterruption="";
        try {
            promoteForeground();
            wake=((PowerManager)getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"AirLink:receiver");
            wake.setReferenceCounted(false); wake.acquire(10*60*1000L);
            WifiManager wm=(WifiManager)getApplicationContext().getSystemService(WIFI_SERVICE);
            if(wm!=null) {
                wifi=wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF,"AirLink:stream"); wifi.acquire();
                multicast=wm.createMulticastLock("AirLink:discovery"); multicast.acquire();
            }
            alive=true; running=true;
            handler.postDelayed(renewWake,5*60*1000L);
            handler.postDelayed(connectDeadline,15000);
            receiverThread=new Thread(this::connectAndReceive,"airlink-client"); receiverThread.start();
            playbackThread=new Thread(this::play,"airlink-playback"); playbackThread.start();
            heartbeatThread=new Thread(this::heartbeat,"airlink-heartbeat"); heartbeatThread.start();
        } catch(Exception e) { status="Cannot start receiver: "+describe(e); endReceiver(); }
        return stopRequested?START_NOT_STICKY:START_STICKY;
    }
    private void promoteForeground() {
        if(Build.VERSION.SDK_INT>=29) startForeground(1,notification(),ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE|ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        else startForeground(1,notification());
        if(BackgroundAccess.backgroundRestricted(this)) lastInterruption="Android blocks background running. Set AirLink battery use to Unrestricted.";
    }
    private AudioAttributes attributes() { return new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build(); }
    private Notification notification() {
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,ReceiverService.class).setAction("STOP"),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder builder=new Notification.Builder(this,"receiver").setSmallIcon(com.airlink.audio.R.drawable.ic_airlink)
            .setContentTitle("AirLink · "+(session==null?"Connecting to PC":"Playing Windows audio"))
            .setContentText("Receiver stays active when the screen is off")
            .setContentIntent(open).setOngoing(true).setCategory(Notification.CATEGORY_TRANSPORT)
            .setStyle(new Notification.MediaStyle().setMediaSession(mediaSession.getSessionToken()).setShowActionsInCompactView(0))
            .addAction(new Notification.Action.Builder(android.R.drawable.ic_media_pause,"Stop receiver",stop).build());
        if(Build.VERSION.SDK_INT>=31) builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        return builder.setVisibility(Notification.VISIBILITY_PUBLIC).setShowWhen(false).build();
    }
    private void updateNotification() { if(alive) ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(1,notification()); }
    private void connectAndReceive() {
        try {
            if(restoreStartup && !SessionVault.has(this)) {
                JSONObject saved=SavedConnection.load(this);
                if(saved==null || saved.optString("code").isEmpty()) throw new IllegalStateException("Enter the Windows code once to save this connection.");
                serverAddress=saved.getString("address"); serverId=saved.getString("serverId"); serverName=saved.getString("serverName"); serverPort=saved.getInt("port"); pin=saved.getString("code");
                restoreStartup=false;
            }
            Session pending;
            if(restoreStartup) {
                handler.removeCallbacks(connectDeadline);
                JSONObject saved=SessionVault.load(this);
                if(saved==null) throw new IllegalStateException("Saved connection expired. Connect again from Android.");
                serverAddress=saved.getString("address"); serverId=saved.getString("serverId"); serverName=saved.getString("serverName"); serverPort=saved.getInt("port");
                pending=new Session(saved.getString("session"),Protocol.unhex(saved.getString("key")),new InetSocketAddress(InetAddress.getByName(serverAddress),serverPort),targetPackets);
                pending.resumeCounter=saved.getLong("counter"); session=pending;
                if(!restore(pending,"Android recreated the background receiver")) throw new IllegalStateException("The background connection expired. Connect again.");
                handler.postDelayed(connectDeadline,15000);
            } else {
            try { SavedConnection.save(this,serverAddress,serverId,serverName,serverPort,pin); }
            catch(Exception e) { lastInterruption="Could not remember PC settings: "+describe(e); }
            // Socket setup, address resolution and all network I/O belong to this
            // worker, never Android's service/main callback thread.
            status="Opening connection to "+serverAddress+":"+serverPort+"…";
            DatagramSocket socket=new DatagramSocket(); receiver=socket;
            if(!alive) { socket.close(); return; }
            socket.setReceiveBufferSize(256*1024); socket.connect(InetAddress.getByName(serverAddress),serverPort); socket.setSoTimeout(700);
            ClientPairing.Result result=ClientPairing.connect(socket,pin,serverId,Build.MANUFACTURER+" "+Build.MODEL,()->alive,message->{ if(alive) status=message; }); pin=null;
            pending=new Session(result.session,result.key,(InetSocketAddress)receiver.getRemoteSocketAddress(),targetPackets);
            }
            if(!alive) return;
            session=pending; acceptedAt=SystemClock.elapsedRealtime();
            checkpoint(pending);
            if(audioManager.requestAudioFocus(focus)!=AudioManager.AUDIOFOCUS_REQUEST_GRANTED) throw new IllegalStateException("Another app is using audio. Try connecting again.");
            status="PC accepted code. Waiting for audio from "+serverName+"…";
            mediaSession.setPlaybackState(new PlaybackState.Builder().setActions(PlaybackState.ACTION_STOP|PlaybackState.ACTION_PAUSE).setState(PlaybackState.STATE_PLAYING,0,1).build());
            updateNotification(); reply("ping",pending); receiver.setSoTimeout(1000); receive();
        } catch(Exception e) { if(alive) { status="Connection failed: "+describe(e); handler.post(this::endReceiver); } }
        finally { pin=null; }
    }
    private void receive() {
        byte[] bytes=new byte[2048];
        while(alive) {
            try {
                DatagramPacket packet=new DatagramPacket(bytes,bytes.length); receiver.receive(packet);
                Session current=session;
                if(packet.getLength()==Protocol.PACKET_BYTES && bytes[0]=='A' && bytes[1]=='L' && bytes[2]=='N' && bytes[3]=='1') {
                    if(current==null || !current.peer.equals(packet.getSocketAddress())) continue;
                    long sequence=Protocol.sequence(bytes);
                    byte[] pcm=current.decoder.decrypt(Arrays.copyOf(bytes,packet.getLength()));
                    incomingPackets.incrementAndGet();
                    for(int offset=0;offset<pcm.length;offset+=2) {
                        int value=(short)((pcm[offset]&255)|(pcm[offset+1]<<8)); peakPcm=Math.max(peakPcm,Math.abs(value)/32768f);
                    }
                    current.lastSeen=SystemClock.elapsedRealtime();
                    if(current.buffer.offer(sequence,pcm)) receivedPackets.incrementAndGet(); else latePackets.incrementAndGet();
                } else if(packet.getLength()<=1024 && bytes[0]=='{') {
                    control(new JSONObject(new String(bytes,0,packet.getLength(),StandardCharsets.UTF_8)),(InetSocketAddress)packet.getSocketAddress());
                }
            } catch(SocketTimeoutException ignored) { }
            catch(SocketException e) { if(alive && session!=null && audioStarted && restore(session,"Network socket: "+describe(e))) continue; if(alive) disconnect("Network stopped: "+describe(e)); break; }
            catch(Exception ignored) { /* Invalid proofs, malformed packets and failed GCM tags never reach playback. */ }
            Session current=session;
            if(current!=null && !audioStarted && SystemClock.elapsedRealtime()-acceptedAt>8000) { disconnect("PC accepted code but did not send playable audio. Check Windows' output and connection status."); break; }
            if(current!=null && SystemClock.elapsedRealtime()-current.lastSeen>8000) {
                if(!audioStarted || !restore(current,"No valid Windows packets for 8 seconds")) { if(alive) disconnect("Could not restore the PC connection. Check Windows and connect again."); break; }
            }
        }
    }
    private void heartbeat() {
        long lastCheckpoint=SystemClock.elapsedRealtime();
        while(alive) {
            Session current=session;
            if(current!=null) try { reply("ping",current); } catch(Exception ignored) { }
            if(current!=null && SystemClock.elapsedRealtime()-lastCheckpoint>=30000) { checkpoint(current); lastCheckpoint=SystemClock.elapsedRealtime(); }
            try { Thread.sleep(1000); } catch(InterruptedException e) { return; }
        }
    }
    private void checkpoint(Session current) {
        try {
            JSONObject state=new JSONObject().put("address",serverAddress).put("serverId",serverId).put("serverName",serverName).put("port",serverPort)
                .put("session",current.id).put("key",Protocol.hex(current.key)).put("counter",current.resumeCounter);
            SessionVault.save(this,state,()->alive && !stopRequested);
        } catch(Exception e) { lastInterruption="Background restart recovery unavailable: "+describe(e); }
    }
    private boolean restore(Session current,String reason) {
        lastInterruption=reason; recovering=true; current.buffer.rebuffer();
        long until=SystemClock.elapsedRealtime()+90000;
        int attempt=0;
        while(alive && session==current && SystemClock.elapsedRealtime()<until) {
            status="Reconnecting to Windows automatically · attempt "+(++attempt);
            try {
                DatagramSocket previous=receiver; if(previous!=null) previous.close();
                DatagramSocket next=new DatagramSocket(); receiver=next;
                next.setReceiveBufferSize(256*1024); next.connect(InetAddress.getByName(serverAddress),serverPort);
                ++current.resumeCounter; checkpoint(current);
                if(ClientRecovery.resume(next,current.id,current.key,current.resumeCounter,()->alive)) {
                    current.lastSeen=SystemClock.elapsedRealtime(); current.buffer.rebuffer(); next.setSoTimeout(1000); recoveries++;
                    status="Connection restored. Waiting for fresh Windows audio…"; return true;
                }
            } catch(Exception e) { lastInterruption=reason+" · "+describe(e); }
            try { Thread.sleep(1200); } catch(InterruptedException e) { return false; }
        }
        return false;
    }
    private void control(JSONObject json,InetSocketAddress peer) throws Exception {
        String type=json.optString("type"), id=json.optString("session"), proof=json.optString("proof");
        if(!id.matches("[0-9a-f]{32}")) return;
        Session current=session;
        if(current!=null && current.id.equals(id) && current.peer.equals(peer) &&
                Protocol.matches(Protocol.proof(current.key,type+":"+id),proof)) {
            if(type.equals("ping")) { current.lastSeen=SystemClock.elapsedRealtime(); reply("pong",current); }
            else if(type.equals("pong")) current.lastSeen=SystemClock.elapsedRealtime();
            else if(type.equals("stop")) disconnect("Windows stopped the stream. Connect again from Android.");
        }
    }
    private void reply(String type,Session current) throws Exception {
        byte[] data=new JSONObject().put("type",type).put("session",current.id).put("proof",Protocol.proof(current.key,type+":"+current.id)).toString().getBytes(StandardCharsets.UTF_8);
        receiver.send(new DatagramPacket(data,data.length,current.peer));
    }
    private void disconnect(String message) {
        status=message; statistics="";
        audioManager.abandonAudioFocusRequest(focus);
        mediaSession.setPlaybackState(new PlaybackState.Builder().setActions(PlaybackState.ACTION_STOP).setState(PlaybackState.STATE_PAUSED,0,1).build());
        updateNotification();
        handler.post(this::endReceiver);
    }
    private void play() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);
        AudioTrack track=null; Session playing=null;
        int outputRestarts=0; long lastStats=0, nextFrame=0;
        try {
            while(alive) {
                try {
                Session current=session;
                if(current!=playing) {
                    if(track!=null) { track.pause(); track.flush(); track.release(); track=null; }
                    playing=current;
                    if(current!=null) {
                        int minimum=AudioTrack.getMinBufferSize(48000,AudioFormat.CHANNEL_OUT_STEREO,AudioFormat.ENCODING_PCM_16BIT);
                        track=new AudioTrack.Builder().setAudioAttributes(attributes())
                            .setAudioFormat(new AudioFormat.Builder().setSampleRate(48000).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                            .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(Math.max(minimum,Protocol.PCM_BYTES*4))
                            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY).build();
                        if(track.getState()!=AudioTrack.STATE_INITIALIZED) throw new IllegalStateException("Audio output unavailable");
                        track.setBufferSizeInFrames(Math.max(480,minimum/4));
                    }
                }
                if(current==null) { Thread.sleep(60); continue; }
                if(focusPaused) {
                    if(track.getPlayState()==AudioTrack.PLAYSTATE_PLAYING) { track.pause(); track.flush(); current.buffer.rebuffer(); }
                    nextFrame=0; Thread.sleep(60); continue;
                }
                long nowNanos=System.nanoTime();
                if(nextFrame>nowNanos) java.util.concurrent.locks.LockSupport.parkNanos(nextFrame-nowNanos);
                else if(nowNanos-nextFrame>30_000_000L) nextFrame=nowNanos;
                byte[] data=current.buffer.take();
                if(data==null || session!=current) { nextFrame=0; continue; }
                // Prime the hardware with PCM before starting; otherwise an underrun at
                // startup adds avoidable latency on some Android audio HALs.
                if(track.getPlayState()!=AudioTrack.PLAYSTATE_PLAYING) {
                    int written=track.write(data,0,data.length,AudioTrack.WRITE_NON_BLOCKING);
                    if(written<0) throw new AudioDeviceLost("Audio output error "+written);
                    track.play();
                    if(written<data.length) writeFully(track,data,written);
                } else writeFully(track,data,0);
                outputRestarts=0;
                nextFrame+=5_000_000L;
                if(!focusPaused) playedPackets.incrementAndGet();
                if(!focusPaused && (!audioStarted || recovering || status.startsWith("Audio focus restored") || status.startsWith("Recovering audio"))) {
                    audioStarted=true; recovering=false; handler.removeCallbacks(connectDeadline); status="Playing Windows audio · "+serverName;
                }
                if(SystemClock.elapsedRealtime()-lastStats>=1000) {
                    lastStats=SystemClock.elapsedRealtime(); AudioDeviceInfo routed=track.getRoutedDevice();
                    audioRoute=routed==null?"Android default output":routed.getProductName()+" (type "+routed.getType()+")";
                    statistics="Buffer: "+(targetPackets*5)+" ms · restores: "+recoveries+"\n"+current.buffer.stats()+"\nIncoming: "+incomingPackets.get()+" · late: "+latePackets.get()+"\nPC signal peak: "+Math.round(peakPcm*100)+"% · "+audioRoute;
                }
                } catch(AudioDeviceLost e) {
                    lastInterruption=e.getMessage(); status="Recovering audio output…";
                    if(track!=null) { try { track.pause(); track.flush(); } catch(Exception ignored) {} track.release(); track=null; } playing=null;
                    if(++outputRestarts>5) throw e;
                    Session current=session; if(current!=null) current.buffer.rebuffer(); Thread.sleep(250);
                }
            }
        } catch(InterruptedException ignored) { }
        catch(Exception e) { if(alive) { status="Audio error: "+describe(e); handler.post(this::endReceiver); } }
        finally { if(track!=null) { try { track.pause(); track.flush(); } catch(Exception ignored) {} track.release(); } }
    }
    private static final class AudioDeviceLost extends RuntimeException { AudioDeviceLost(String message) { super(message); } }
    private void writeFully(AudioTrack track,byte[] data,int offset) throws InterruptedException {
        long deadline=SystemClock.elapsedRealtime()+500;
        while(alive && !focusPaused && offset<data.length) {
            int written=track.write(data,offset,data.length-offset,AudioTrack.WRITE_NON_BLOCKING);
            if(written<0) throw new AudioDeviceLost("Audio output error "+written);
            if(written==0) {
                if(SystemClock.elapsedRealtime()>deadline) throw new AudioDeviceLost("Audio output stalled for 500 ms");
                Thread.sleep(2); continue;
            }
            offset+=written;
        }
    }
    private void endReceiver() { stopRequested=true; SessionVault.clear(this); stopSelf(); }
    @Override public void onTaskRemoved(Intent rootIntent) { updateNotification(); super.onTaskRemoved(rootIntent); }
    @Override public void onDestroy() {
        Session current=session;
        alive=false; running=false; session=null;
        handler.removeCallbacks(renewWake);
        handler.removeCallbacks(connectDeadline);
        DatagramSocket closingSocket=receiver;
        if(closingSocket!=null) {
            new Thread(()->{ try { if(current!=null && stopRequested) reply("stop",current); } catch(Exception ignored) {} finally { closingSocket.close(); } },"airlink-close").start();
        }
        if(playbackThread!=null) playbackThread.interrupt();
        if(heartbeatThread!=null) heartbeatThread.interrupt(); if(receiverThread!=null) receiverThread.interrupt();
        for(Thread thread:new Thread[]{receiverThread,playbackThread,heartbeatThread}) {
            if(thread!=null) try { thread.join(1200); } catch(InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }
        if(wake!=null && wake.isHeld()) wake.release(); if(wifi!=null && wifi.isHeld()) wifi.release(); if(multicast!=null && multicast.isHeld()) multicast.release();
        audioManager.abandonAudioFocusRequest(focus); mediaSession.release();
        try { unregisterReceiver(noisy); } catch(Exception ignored) {}
        if(status.startsWith("Connected") || status.startsWith("Connecting") || status.startsWith("Playing") || status.startsWith("Starting") || status.startsWith("Preparing") || status.startsWith("Pairing")) status="Disconnected. Select your PC to connect again.";
        statistics=""; stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
