package com.airlink.audio;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.json.JSONObject;

// Kept independent of Android APIs so the real client handshake can also be
// exercised against the Windows server by the desktop integration checks.
public final class ClientPairing {
    public static final class Result {
        public final String session;
        public final byte[] key;
        Result(String session,byte[] key) { this.session=session; this.key=key; }
    }
    public static Result connect(DatagramSocket socket,String code,String serverId,String name,BooleanSupplier active) throws Exception {
        return connect(socket,code,serverId,name,active,message->{});
    }
    public static Result connect(DatagramSocket socket,String code,String serverId,String name,BooleanSupplier active,Consumer<String> progress) throws Exception {
        socket.setSoTimeout(700);
        progress.accept("Preparing secure pairing…");
        String id=UUID.randomUUID().toString().replace("-","");
        byte[] key=Protocol.key(code,serverId,id);
        byte[] request=new JSONObject().put("type","pair").put("session",id).put("name",name)
            .put("proof",Protocol.proof(key,"pair:"+id)).toString().getBytes(StandardCharsets.UTF_8);
        byte[] bytes=new byte[2048];
        for(int attempt=0;attempt<4 && active.getAsBoolean();attempt++) {
            progress.accept("Pairing with PC · attempt "+(attempt+1)+" of 4");
            socket.send(new DatagramPacket(request,request.length));
            long deadline=System.nanoTime()+1_400_000_000L;
            while(active.getAsBoolean() && System.nanoTime()<deadline) {
                try {
                    DatagramPacket packet=new DatagramPacket(bytes,bytes.length); socket.receive(packet);
                    if(packet.getLength()>1024 || packet.getLength()==0 || bytes[0]!='{') continue;
                    JSONObject json=new JSONObject(new String(bytes,0,packet.getLength(),StandardCharsets.UTF_8));
                    if("error".equals(json.optString("type"))) {
                        String reason=json.optString("reason");
                        throw new IllegalStateException("wrong-code".equals(reason)?"Wrong code. Enter the current code from Windows.":"busy".equals(reason)?"PC already has a connected phone. Disconnect it on Windows first.":"Server rejected connection.");
                    }
                    if("ready".equals(json.optString("type")) && id.equals(json.optString("session")) && Protocol.matches(Protocol.proof(key,"ready:"+id),json.optString("proof"))) {
                        progress.accept("PC accepted code. Starting audio…"); return new Result(id,key);
                    }
                } catch(SocketTimeoutException ignored) {}
                catch(org.json.JSONException ignored) {}
            }
        }
        java.util.Arrays.fill(key,(byte)0);
        throw new IllegalStateException("PC did not answer. Check Start server, the PC address and Windows Firewall.");
    }
}
