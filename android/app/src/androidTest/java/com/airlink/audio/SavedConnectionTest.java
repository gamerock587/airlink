package com.airlink.audio;

import android.content.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class SavedConnectionTest {
    @Test public void remembersAddressAndCodeEncrypted() throws Exception {
        Context base=InstrumentationRegistry.getInstrumentation().getTargetContext();
        Context isolated=new ContextWrapper(base) {
            @Override public android.content.SharedPreferences getSharedPreferences(String name,int mode) { return base.getSharedPreferences("test-"+name,mode); }
        };
        var prefs=isolated.getSharedPreferences("saved-pc",Context.MODE_PRIVATE);
        try {
            SavedConnection.save(isolated,"192.0.2.25","test-server","Test PC",45832,"654321");
            var restored=SavedConnection.load(isolated);
            assertEquals("192.0.2.25",restored.getString("address")); assertEquals("654321",restored.getString("code"));
            assertEquals(45832,restored.getInt("port"));
            assertFalse("PIN must not be a plaintext preference",prefs.contains("code"));
            assertFalse("Address must not be a plaintext preference",prefs.contains("address"));
            assertTrue(prefs.contains("data")); assertTrue(prefs.contains("iv"));
        } finally { prefs.edit().clear().commit(); }
    }
}
