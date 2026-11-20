package com.airlink.audio;

import java.nio.file.*;
import java.util.*;

public final class ProtocolCheck {
    private static void check(boolean value,String message) { if(!value) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        Path path=Paths.get(args[0]); List<String> fixture=Files.readAllLines(path.resolve("crypto.txt"));
        byte[] key=Protocol.key(fixture.get(0),fixture.get(1),fixture.get(2));
        check(Protocol.hex(key).equals(fixture.get(3)),"PBKDF2 differs from Windows");
        check(Protocol.proof(key,"pair:"+fixture.get(2)).equals(fixture.get(4)),"Pair HMAC differs from Windows");
        byte[] packet=Files.readAllBytes(path.resolve("packet.bin"));
        check(Protocol.sequence(packet)==42,"Sequence byte order differs");
        byte[] pcm=Protocol.decrypt(key,Protocol.unhex(fixture.get(2)),packet);
        for(int i=0;i<pcm.length;i++) check(pcm[i]==(byte)(i%251),"Decrypted PCM differs at "+i);
        packet[20]^=1; boolean rejected=false;
        try { Protocol.decrypt(key,Protocol.unhex(fixture.get(2)),packet); } catch(Exception e) { rejected=true; }
        check(rejected,"Tampered ciphertext accepted");
        packet[20]^=1; packet[4]^=1; rejected=false;
        try { Protocol.decrypt(key,Protocol.unhex(fixture.get(2)),packet); } catch(Exception e) { rejected=true; }
        check(rejected,"Tampered header accepted");
        System.out.println("PASS: Android Java decrypts Windows packet; PBKDF2, HMAC, nonce and PCM agree; tampering rejected");

        JitterBuffer buffer=new JitterBuffer(4);
        for(int i:new int[]{2,0,3,1}) { byte[] data=new byte[960]; data[0]=(byte)i; check(buffer.offer(i,data),"Packet rejected"); }
        check(!buffer.offer(1,new byte[960]),"Duplicate accepted");
        for(int i=0;i<4;i++) check(buffer.take()[0]==i,"Reorder failed");
        check(!buffer.offer(1,new byte[960]),"Played packet replay accepted");
        check(buffer.take().length==960 && buffer.missing==1,"Lost packet concealment failed");
        buffer.clear();
        long start=System.nanoTime(); check(buffer.take()==null,"Empty buffer should not play");
        check(System.nanoTime()-start<500_000_000L,"Disconnected queue blocks playback thread");
        for(int i=0;i<100;i++) buffer.offer(i,new byte[960]);
        check(buffer.size()<=16 && buffer.dropped>0,"Queue grew without latency bound");
        buffer.clear(); for(int i=0;i<4;i++) buffer.offer(i,new byte[960]);
        for(int i=0;i<14;i++) buffer.take(); // 4 real packets, 10 concealed packets then rebuffer
        check(buffer.take()==null,"Long dropout did not rebuffer");
        for(int i=14;i<18;i++) buffer.offer(i,new byte[960]);
        check(buffer.take()!=null,"Did not recover after dropout");
        check(!buffer.offer(100000,new byte[960]),"Unbounded sequence jump accepted");
        buffer.rebuffer();
        for(int i=5000;i<5004;i++) check(buffer.offer(i,new byte[960]),"Valid live packet rejected after a long gap");
        check(buffer.take()!=null,"No playback after a long gap");
        check(!buffer.offer(14,new byte[960]),"Old packet accepted after resynchronization");
        System.out.println("PASS: jitter reordering, duplicate/replay rejection, loss concealment, bounded queue, dropout recovery");
    }
}
