package com.airlink.audio;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.JSONObject;

public final class JavaClientCheck {
    private static void check(boolean value,String message) { if(!value) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        List<ServerDiscovery.Server> servers=ServerDiscovery.scan("127.0.0.1",Integer.parseInt(args[0]));
        check(servers.size()==1,"Java scanner did not find Windows server");
        ServerDiscovery.Server server=servers.get(0); check(server.id.equals("airlink-test-device"),"Wrong server identity");
        System.out.println("PASS: actual Android ServerDiscovery found running C# Windows server");
        try(DatagramSocket socket=new DatagramSocket()) {
            check(server.port==Integer.parseInt(args[0]),"Pairing should use the discovery port");
            socket.connect(InetAddress.getByName(server.address),server.port); socket.setSoTimeout(1200);
            boolean wrongRejected=false;
            try { ClientPairing.connect(socket,"000000",server.id,"Java test",()->true); } catch(IllegalStateException e) { wrongRejected=e.getMessage().startsWith("Wrong code"); }
            check(wrongRejected,"Wrong code error not shown by real Android pairing code");
            Thread.sleep(1000);
            ClientPairing.Result pair=ClientPairing.connect(socket,"123456",server.id,"Java Android client",()->true);
            byte[] bytes=new byte[2048]; long deadline=System.nanoTime()+2_000_000_000L, previous=-1; int received=0;
            JitterBuffer jitter=new JitterBuffer(4);
            while(System.nanoTime()<deadline) {
                DatagramPacket packet=new DatagramPacket(bytes,bytes.length); socket.receive(packet);
                if(packet.getLength()==Protocol.PACKET_BYTES) {
                    byte[] audio=Arrays.copyOf(bytes,packet.getLength()); long sequence=Protocol.sequence(audio);
                    check(sequence>previous,"Audio sequence did not increase"); previous=sequence;
                    byte[] pcm=Protocol.decrypt(pair.key,Protocol.unhex(pair.session),audio); check(pcm.length==960,"Bad PCM frame count");
                    jitter.offer(sequence,pcm); if(jitter.size()>=4) check(jitter.take()!=null,"Playout not primed"); received++;
                } else {
                    JSONObject json=new JSONObject(new String(bytes,0,packet.getLength(),StandardCharsets.UTF_8));
                    if("ping".equals(json.optString("type"))) {
                        check(Protocol.matches(Protocol.proof(pair.key,"ping:"+pair.session),json.optString("proof")),"Unauthenticated ping");
                        byte[] reply=new JSONObject().put("type","pong").put("session",pair.session).put("proof",Protocol.proof(pair.key,"pong:"+pair.session)).toString().getBytes(StandardCharsets.UTF_8);
                        socket.send(new DatagramPacket(reply,reply.length));
                    }
                }
            }
            check(received>=250,"Too few real Windows audio packets");
            byte[] stop=new JSONObject().put("type","stop").put("session",pair.session).put("proof",Protocol.proof(pair.key,"stop:"+pair.session)).toString().getBytes(StandardCharsets.UTF_8);
            socket.send(new DatagramPacket(stop,stop.length));
            System.out.println("PASS: actual Android pairing rejected wrong code, connected, decrypted "+received+" Windows WASAPI packets, answered heartbeat, and disconnected");
        }
    }
}
