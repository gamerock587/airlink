package com.airlink.audio;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.function.BooleanSupplier;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONObject;

// Store only an active session, never the pairing PIN. The wrapping key stays
// in Android Keystore and does not require the screen to remain unlocked.
public final class SessionVault {
    private static final String ALIAS="AirLinkSessionV1";
    private static android.content.SharedPreferences prefs(Context c) { return c.getSharedPreferences("active-session",Context.MODE_PRIVATE); }
    public static synchronized boolean has(Context c) { return prefs(c).contains("ciphertext") && prefs(c).getLong("until",0)>System.currentTimeMillis(); }
    static SecretKey key() throws Exception {
        KeyStore store=KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        if(store.containsAlias(ALIAS)) return (SecretKey)store.getKey(ALIAS,null);
        KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setUserAuthenticationRequired(false).build());
        return generator.generateKey();
    }
    public static synchronized void save(Context c,JSONObject state,BooleanSupplier allowed) throws Exception {
        if(!allowed.getAsBoolean()) return;
        var preferences=prefs(c);
        if(state.getString("session").equals(preferences.getString("session","")) && state.getLong("counter")<preferences.getLong("counter",0)) return;
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE,key());
        byte[] encrypted=cipher.doFinal(state.toString().getBytes(StandardCharsets.UTF_8));
        if(allowed.getAsBoolean() && !preferences.edit().putString("iv",Base64.encodeToString(cipher.getIV(),Base64.NO_WRAP))
            .putString("ciphertext",Base64.encodeToString(encrypted,Base64.NO_WRAP)).putString("session",state.getString("session")).putLong("counter",state.getLong("counter"))
            .putLong("until",System.currentTimeMillis()+120000).commit()) throw new IllegalStateException("Cannot save background recovery state");
    }
    public static synchronized JSONObject load(Context c) throws Exception {
        if(!has(c)) return null;
        return decrypt(c);
    }
    private static JSONObject decrypt(Context c) throws Exception {
        var prefs=prefs(c); Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Base64.decode(prefs.getString("iv",""),Base64.NO_WRAP)));
        return new JSONObject(new String(cipher.doFinal(Base64.decode(prefs.getString("ciphertext",""),Base64.NO_WRAP)),StandardCharsets.UTF_8));
    }
    public static synchronized JSONObject lastServer(Context c) throws Exception {
        if(!prefs(c).contains("ciphertext")) return null;
        JSONObject state=decrypt(c);
        return new JSONObject().put("address",state.getString("address")).put("serverId",state.getString("serverId"))
            .put("serverName",state.getString("serverName")).put("port",state.getInt("port"));
    }
    public static synchronized void clear(Context c) { prefs(c).edit().clear().commit(); }
}
