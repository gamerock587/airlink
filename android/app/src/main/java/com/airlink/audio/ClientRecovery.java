package com.airlink.audio;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.function.BooleanSupplier;
import org.json.JSONObject;

public final class ClientRecovery {
    public static boolean resume(DatagramSocket socket,String session,byte[] key,long counter,BooleanSupplier active) throws Exception {
        socket.setSoTimeout(600);
        byte[] request=new JSONObject().put("type","resume").put("session",session).put("counter",counter)
            .put("proof",Protocol.proof(key,"resume:"+session+":"+counter)).toString().getBytes(StandardCharsets.UTF_8);
        byte[] bytes=new byte[2048];
        for(int attempt=0;attempt<3 && active.getAsBoolean();attempt++) {
            socket.send(new DatagramPacket(request,request.length));
            long deadline=System.nanoTime()+800_000_000L;
            while(active.getAsBoolean() && System.nanoTime()<deadline) {
                try {
                    DatagramPacket packet=new DatagramPacket(bytes,bytes.length); socket.receive(packet);
                    if(packet.getLength()==0 || bytes[0]!='{') continue;
                    JSONObject json=new JSONObject(new String(bytes,0,packet.getLength(),StandardCharsets.UTF_8));
                    if("stop".equals(json.optString("type")) && session.equals(json.optString("session")) && Protocol.matches(Protocol.proof(key,"stop:"+session),json.optString("proof"))) throw new IllegalStateException("Windows ended the stream.");
                    if("resumed".equals(json.optString("type")) && session.equals(json.optString("session")) && json.optLong("counter")==counter &&
                        Protocol.matches(Protocol.proof(key,"resumed:"+session+":"+counter),json.optString("proof"))) return true;
                } catch(SocketTimeoutException ignored) {}
                catch(org.json.JSONException ignored) {}
            }
        }
        return false;
    }
}
