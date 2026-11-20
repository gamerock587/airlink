package com.airlink.audio;

import java.net.*;
import java.util.*;

public final class PairingFailureCheck {
    public static void main(String[] args) throws Exception {
        try(DatagramSocket silentServer=new DatagramSocket(0,InetAddress.getLoopbackAddress()); DatagramSocket client=new DatagramSocket()) {
            client.connect(InetAddress.getLoopbackAddress(),silentServer.getLocalPort());
            List<String> progress=new ArrayList<>(); boolean timedOut=false;
            long start=System.nanoTime();
            try { ClientPairing.connect(client,"123456","test-pc","Test phone",()->true,progress::add); }
            catch(IllegalStateException e) { timedOut=e.getMessage().startsWith("PC did not answer"); }
            long elapsed=(System.nanoTime()-start)/1_000_000L;
            if(!timedOut || elapsed>10000) throw new AssertionError("Unresponsive server did not fail within 10s: "+elapsed);
            if(progress.stream().filter(message->message.startsWith("Pairing with PC")).count()!=4) throw new AssertionError("Pairing attempts did not report progress");
            System.out.println("PASS: unresponsive PC produces 4 progress updates and an error in "+elapsed+" ms");
        }
    }
}
