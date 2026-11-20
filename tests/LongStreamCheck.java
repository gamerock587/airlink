package com.airlink.audio;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.LockSupport;
import org.json.JSONObject;

public final class LongStreamCheck {
    public static void main(String[] args) throws Exception {
        int seconds=Integer.parseInt(args[1]);
        ServerDiscovery.Server server=ServerDiscovery.scan("127.0.0.1",Integer.parseInt(args[0])).get(0);
        DatagramSocket socket=new DatagramSocket(); socket.connect(InetAddress.getByName(server.address),server.port); socket.setSoTimeout(1000);
        ClientPairing.Result pair=ClientPairing.connect(socket,"123456",server.id,"Long-run Android client",()->true);
        Protocol.Decoder decoder=new Protocol.Decoder(pair.key,Protocol.unhex(pair.session));
        JitterBuffer jitter=new JitterBuffer(8); AtomicBoolean alive=new AtomicBoolean(true); AtomicLong played=new AtomicLong();
        Thread player=new Thread(()->{
            long next=System.nanoTime();
            try {
                while(alive.get()) {
                    byte[] pcm=jitter.take(); if(pcm==null) { next=System.nanoTime(); continue; }
                    long now=System.nanoTime(); if(now-next>100_000_000L) next=now;
                    long wait=next-now; if(wait>0) LockSupport.parkNanos(wait);
                    played.incrementAndGet(); next+=5_000_000L;
                }
            } catch(InterruptedException ignored) {}
        },"simulated-audio-clock"); player.start();
        long started=System.nanoTime(), lastSequence=-1, resumeCounter=0; int received=0, restores=0, nextReport=30;
        boolean gapRestored=false, socketRestored=false;
        byte[] bytes=new byte[2048];
        try {
            while((System.nanoTime()-started)/1_000_000_000L<seconds) {
                long elapsed=(System.nanoTime()-started)/1_000_000_000L;
                // Drop all packets and heartbeats for 12 s, longer than the old 6 s lease.
                if(elapsed>=45 && elapsed<57) { DatagramPacket drop=new DatagramPacket(bytes,bytes.length); try { socket.receive(drop); } catch(SocketTimeoutException ignored) {} continue; }
                if((elapsed>=57 && !gapRestored) || (elapsed>=90 && !socketRestored)) {
                    boolean afterGap=!gapRestored;
                    socket.close(); socket=new DatagramSocket(); socket.connect(InetAddress.getByName(server.address),server.port);
                    if(!ClientRecovery.resume(socket,pair.session,pair.key,++resumeCounter,()->true)) throw new AssertionError("Authenticated reconnect failed");
                    socket.setSoTimeout(1000); jitter.rebuffer(); restores++;
                    if(afterGap) gapRestored=true; else socketRestored=true;
                    System.out.println("PASS: automatic session restoration at "+elapsed+" s, counter "+resumeCounter);
                    if(resumeCounter==2) {
                        // Replaying an older valid resume from another endpoint must not redirect audio.
                        try(DatagramSocket replay=new DatagramSocket()) {
                            replay.connect(InetAddress.getByName(server.address),server.port);
                            byte[] old=new JSONObject().put("type","resume").put("session",pair.session).put("counter",1)
                                .put("proof",Protocol.proof(pair.key,"resume:"+pair.session+":1")).toString().getBytes(StandardCharsets.UTF_8);
                            replay.send(new DatagramPacket(old,old.length));
                        }
                    }
                }
                DatagramPacket packet=new DatagramPacket(bytes,bytes.length); socket.receive(packet);
                if(packet.getLength()==Protocol.PACKET_BYTES) {
                    byte[] data=Arrays.copyOf(bytes,packet.getLength()); long sequence=Protocol.sequence(data);
                    if(sequence<=lastSequence) throw new AssertionError("Sequence reused after reconnection"); lastSequence=sequence;
                    jitter.offer(sequence,decoder.decrypt(data)); received++;
                } else if(bytes[0]=='{') {
                    JSONObject json=new JSONObject(new String(bytes,0,packet.getLength(),StandardCharsets.UTF_8));
                    if("stop".equals(json.optString("type"))) throw new AssertionError("Windows disconnected during a recoverable gap");
                    if("ping".equals(json.optString("type"))) {
                        if(!Protocol.matches(Protocol.proof(pair.key,"ping:"+pair.session),json.optString("proof"))) throw new AssertionError("Bad ping proof");
                        byte[] reply=new JSONObject().put("type","pong").put("session",pair.session).put("proof",Protocol.proof(pair.key,"pong:"+pair.session)).toString().getBytes(StandardCharsets.UTF_8);
                        socket.send(new DatagramPacket(reply,reply.length));
                    }
                }
                if(elapsed>=nextReport) { System.out.println("RUNNING: "+elapsed+" s · "+received+" decrypted packets · "+played.get()+" playout frames · "+restores+" restores"); nextReport+=30; }
            }
            if(received<seconds*120 || played.get()<seconds*120 || restores!=2) throw new AssertionError("Long stream stalled or recovery was not exercised");
            byte[] stop=new JSONObject().put("type","stop").put("session",pair.session).put("proof",Protocol.proof(pair.key,"stop:"+pair.session)).toString().getBytes(StandardCharsets.UTF_8);
            socket.send(new DatagramPacket(stop,stop.length));
            System.out.println("PASS: "+seconds+" s sustained stream, "+received+" decryptions, "+played.get()+" simulated audio writes, 12-second outage and socket changes recovered without reusing a sequence");
        } finally { alive.set(false); player.interrupt(); player.join(2000); socket.close(); }
    }
}
