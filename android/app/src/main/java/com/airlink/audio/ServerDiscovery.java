package com.airlink.audio;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.JSONObject;

public final class ServerDiscovery {
    public static final class Server {
        public final String name,id,address;
        public final int port;
        public Server(String name,String id,String address) { this(name,id,address,Protocol.AUDIO_PORT); }
        public Server(String name,String id,String address,int port) { this.name=name; this.id=id; this.address=address; this.port=port; }
        @Override public String toString() { return name+" · "+address; }
    }
    public static List<Server> scan(String manual) throws Exception {
        return scan(manual,Protocol.DISCOVERY_PORT);
    }
    public static List<Server> scan(String manual,int discoveryPort) throws Exception {
        String nonce=UUID.randomUUID().toString().replace("-","");
        byte[] query=("AIRLINK_DISCOVER_V2 "+nonce).getBytes(StandardCharsets.UTF_8);
        Set<InetAddress> targets=new HashSet<>();
        if(!manual.trim().isEmpty()) {
            if(!manual.trim().matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) throw new IllegalArgumentException("Enter the PC's IPv4 address shown in Windows AirLink.");
            InetAddress address=InetAddress.getByName(manual.trim());
            if(!(address instanceof Inet4Address)) throw new IllegalArgumentException("Use an IPv4 address.");
            targets.add(address);
        } else {
            targets.add(InetAddress.getByName("255.255.255.255"));
            for(NetworkInterface nic:Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if(!nic.isUp() || nic.isLoopback()) continue;
                for(InterfaceAddress address:nic.getInterfaceAddresses()) if(address.getBroadcast()!=null) targets.add(address.getBroadcast());
            }
        }
        Map<String,Server> found=new LinkedHashMap<>();
        try(DatagramSocket socket=new DatagramSocket()) {
            socket.setBroadcast(true); socket.setSoTimeout(300);
            long start=System.nanoTime(), nextSend=0;
            byte[] bytes=new byte[2048];
            while((System.nanoTime()-start)/1_000_000L<3000 && !Thread.currentThread().isInterrupted()) {
                long elapsed=(System.nanoTime()-start)/1_000_000L;
                if(elapsed>=nextSend) {
                    for(InetAddress target:targets) try { socket.send(new DatagramPacket(query,query.length,target,discoveryPort)); } catch(SocketException ignored) {}
                    nextSend+=900;
                }
                try {
                    DatagramPacket response=new DatagramPacket(bytes,bytes.length); socket.receive(response);
                    JSONObject json=new JSONObject(new String(bytes,0,response.getLength(),StandardCharsets.UTF_8));
                    if(!"airlink-server".equals(json.optString("type")) || json.optInt("version")!=2 || !nonce.equals(json.optString("nonce"))) continue;
                    String id=json.optString("id"), name=json.optString("name","Windows PC");
                    if(id.isEmpty() || id.length()>128 || !(response.getAddress() instanceof Inet4Address)) continue;
                    int port=json.optInt("port",Protocol.AUDIO_PORT); if(port<1024 || port>65535) continue;
                    Server server=new Server(name,id,response.getAddress().getHostAddress(),port); found.put(id+"/"+server.address,server);
                } catch(SocketTimeoutException ignored) {}
                catch(org.json.JSONException ignored) {}
            }
        }
        return new ArrayList<>(found.values());
    }
}
