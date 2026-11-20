package com.airlink.audio;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

public final class Protocol {
    public static final int DISCOVERY_PORT = 45832, AUDIO_PORT = 45833, PCM_BYTES = 960, PACKET_BYTES = 988;
    public static byte[] key(String code, String id, String session) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(code.toCharArray(), ("AirLink/1/" + id + "/" + session).getBytes(StandardCharsets.UTF_8), 100000, 256);
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); }
        finally { spec.clearPassword(); }
    }
    public static String proof(byte[] key, String message) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return hex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
    }
    public static boolean matches(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.US_ASCII), b.getBytes(StandardCharsets.US_ASCII));
    }
    public static String hex(byte[] data) {
        StringBuilder out = new StringBuilder(); for (byte b : data) out.append(String.format(java.util.Locale.ROOT, "%02x", b & 255)); return out.toString();
    }
    public static byte[] unhex(String text) {
        byte[] result = new byte[text.length()/2];
        for(int i=0;i<result.length;i++) result[i]=(byte)Integer.parseInt(text.substring(i*2,i*2+2),16);
        return result;
    }
    public static long sequence(byte[] packet) { return ByteBuffer.wrap(packet,4,8).order(ByteOrder.LITTLE_ENDIAN).getLong(); }
    public static byte[] decrypt(byte[] key, byte[] session, byte[] packet) throws Exception {
        return new Decoder(key,session).decrypt(packet);
    }
    public static final class Decoder {
        private final Cipher cipher;
        private final SecretKeySpec secret;
        private final byte[] nonce=new byte[12];
        public Decoder(byte[] key,byte[] session) throws Exception {
            cipher=Cipher.getInstance("AES/GCM/NoPadding"); secret=new SecretKeySpec(key,"AES"); System.arraycopy(session,0,nonce,0,4);
        }
        public byte[] decrypt(byte[] packet) throws Exception {
        if (packet.length != PACKET_BYTES || packet[0]!='A' || packet[1]!='L' || packet[2]!='N' || packet[3]!='1') throw new IllegalArgumentException("Invalid packet");
        System.arraycopy(packet,4,nonce,4,8);
        cipher.init(Cipher.DECRYPT_MODE,secret,new GCMParameterSpec(128,nonce));
        cipher.updateAAD(packet,0,12);
        return cipher.doFinal(packet,12,packet.length-12);
        }
    }
}
