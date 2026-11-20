package com.airlink.audio;

import android.content.Context;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONObject;

public final class SavedConnection {
    public static boolean has(Context context) { return context.getSharedPreferences("saved-pc",Context.MODE_PRIVATE).contains("data"); }
    public static synchronized void save(Context context,String address,String id,String name,int port,String code) throws Exception {
        JSONObject state=new JSONObject().put("address",address).put("serverId",id).put("serverName",name).put("port",port).put("code",code);
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE,SessionVault.key());
        byte[] data=cipher.doFinal(state.toString().getBytes(StandardCharsets.UTF_8));
        if(!context.getSharedPreferences("saved-pc",Context.MODE_PRIVATE).edit().putString("iv",Base64.encodeToString(cipher.getIV(),Base64.NO_WRAP))
            .putString("data",Base64.encodeToString(data,Base64.NO_WRAP)).commit()) throw new IllegalStateException("Could not remember PC connection");
    }
    public static synchronized JSONObject load(Context context) throws Exception {
        var prefs=context.getSharedPreferences("saved-pc",Context.MODE_PRIVATE);
        if(!prefs.contains("data")) return SessionVault.lastServer(context);
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE,SessionVault.key(),new GCMParameterSpec(128,Base64.decode(prefs.getString("iv",""),Base64.NO_WRAP)));
        return new JSONObject(new String(cipher.doFinal(Base64.decode(prefs.getString("data",""),Base64.NO_WRAP)),StandardCharsets.UTF_8));
    }
}
