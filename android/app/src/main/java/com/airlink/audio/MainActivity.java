package com.airlink.audio;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.*;
import android.provider.Settings;
import android.text.InputType;
import android.widget.*;
import java.util.*;
import java.util.concurrent.*;

public final class MainActivity extends Activity {
    private final int ink=Color.rgb(229,236,245), muted=Color.rgb(157,174,191), accent=Color.rgb(91,231,185);
    private TextView status,stats;
    private TextView powerStatus;
    private EditText manual,code;
    private Button connect,scan;
    private Spinner servers,latency;
    private boolean scanning;
    private boolean launching;
    private boolean pendingResume;
    private String lastServiceStatus="";
    private ServerDiscovery.Server pendingServer;
    private String pendingCode;
    private final List<ServerDiscovery.Server> found=new ArrayList<>();
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Runnable refresh=new Runnable() {
        @Override public void run() {
            if(!ReceiverService.status.equals(lastServiceStatus)) { status.setText(ReceiverService.status); lastServiceStatus=ReceiverService.status; }
            stats.setText(ReceiverService.statistics+(ReceiverService.lastInterruption.isEmpty()?"":"\nLast interruption: "+ReceiverService.lastInterruption));
            if(ReceiverService.running) launching=false;
            connect.setText(ReceiverService.running?"Disconnect":launching?"Starting receiver…":SessionVault.has(MainActivity.this)?"Resume previous connection":"Connect to PC");
            powerStatus.setText(BackgroundAccess.summary(MainActivity.this));
            powerStatus.setTextColor(BackgroundAccess.backgroundRestricted(MainActivity.this)?Color.rgb(255,185,105):muted);
            connect.setEnabled(!launching);
            latency.setEnabled(!ReceiverService.running); servers.setEnabled(!ReceiverService.running && !scanning);
            code.setEnabled(!ReceiverService.running); manual.setEnabled(!ReceiverService.running && !scanning);
            scan.setEnabled(!ReceiverService.running && !scanning);
            handler.postDelayed(this,500);
        }
    };
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved); setVolumeControlStream(AudioManager.STREAM_MUSIC);
        ScrollView scroll=new ScrollView(this); scroll.setFillViewport(true); scroll.setBackgroundColor(Color.rgb(13,21,31));
        scroll.setOnApplyWindowInsetsListener((view,insets)->{ view.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom()); return insets; });
        LinearLayout body=new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL); body.setPadding(dp(26),dp(28),dp(26),dp(28)); scroll.addView(body); setContentView(scroll);
        add(body,"◉  AIRLINK / ANDROID",14,accent,true,20);
        add(body,"Find your PC.\nBring its sound.",32,ink,true,12);
        add(body,"On Windows, open AirLink and click Start server. Then scan and connect here.",15,muted,false,20);
        LinearLayout card=new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL); card.setPadding(dp(18),dp(18),dp(18),dp(18));
        GradientDrawable bg=new GradientDrawable(); bg.setColor(Color.rgb(23,37,51)); bg.setCornerRadius(dp(18)); card.setBackground(bg); body.addView(card);
        status=add(card,ReceiverService.status,15,ink,false,10); stats=add(card,"",12,muted,false,0);
        powerStatus=add(body,"",13,muted,false,6);
        Button background=button(body,"Allow screen-off playback"); background.setOnClickListener(v->allowScreenOff());
        Button unrestricted=button(body,"Allow background running / Unrestricted"); unrestricted.setOnClickListener(v->openBackgroundSettings());
        add(body,"01  FIND YOUR WINDOWS PC",12,muted,true,8);
        manual=new EditText(this); manual.setTextColor(ink); manual.setHintTextColor(muted); manual.setHint("Optional PC IPv4 address"); manual.setSingleLine(true); manual.setInputType(InputType.TYPE_CLASS_PHONE); body.addView(manual);
        scan=button(body,"Scan for PCs"); scan.setOnClickListener(v->scanServers());
        servers=new Spinner(this); body.addView(servers,new LinearLayout.LayoutParams(-1,dp(56))); updateServers();
        add(body,"02  ENTER THE CODE SHOWN ON WINDOWS",12,muted,true,8).setPadding(0,dp(12),0,0);
        code=new EditText(this); code.setTextColor(accent); code.setHintTextColor(muted); code.setHint("6-digit Windows code"); code.setSingleLine(true); code.setInputType(InputType.TYPE_CLASS_NUMBER); code.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(6)}); body.addView(code);
        add(body,"PLAYBACK BUFFER",12,muted,true,8).setPadding(0,dp(16),0,0);
        latency=new Spinner(this); latency.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"20 ms · strong Wi-Fi","40 ms · balanced","80 ms · more resilient"}));
        int buffer=getSharedPreferences("airlink",MODE_PRIVATE).getInt("buffer",8); latency.setSelection(buffer==4?0:buffer==16?2:1); body.addView(latency,new LinearLayout.LayoutParams(-1,dp(52)));
        connect=button(body,"Connect to PC"); connect.setOnClickListener(v->connect());
        Button hide=button(body,"Hide app — keep audio playing");
        hide.setOnClickListener(v->{
            if(ReceiverService.running) moveTaskToBack(true);
            else status.setText("Connect to your PC first, then use Hide app to keep listening in the background.");
        });
        add(body,"Keep listening in the background",18,ink,true,8).setPadding(0,dp(24),0,0);
        add(body,"Use Hide app or Home to keep listening. On TECNO/Infinix phones, lock AirLink in Recents so Clear all skips it: open Recents → AirLink arrow/menu → Lock.\n\nBattery use must be Unrestricted. Disconnect, Android Stop app, Force Stop, and unplugging headphones stop playback.",14,muted,false,14);
        Button battery=button(body,"Open app battery settings"); battery.setOnClickListener(v->{ try { startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName()))); } catch(Exception e) { Toast.makeText(this,"Settings → Apps → AirLink → Battery",Toast.LENGTH_LONG).show(); } });
        add(body,"Use Hide app, Home, or lock the screen to leave the receiver playing. Background running and screen-off network access must both show allowed.\n\nEncrypted audio · no cloud · Android version 1.4.1",12,muted,false,0);
        worker.execute(()->{
            try {
                org.json.JSONObject savedPc=SavedConnection.load(this);
                if(savedPc!=null) handler.post(()->{
                    if(isDestroyed() || ReceiverService.running || launching || !found.isEmpty()) return;
                    String address=savedPc.optString("address"), pin=savedPc.optString("code");
                    manual.setText(address); if(code.getText().length()==0) code.setText(pin);
                    found.add(new ServerDiscovery.Server(savedPc.optString("serverName","Saved Windows PC"),savedPc.optString("serverId"),address,savedPc.optInt("port",Protocol.DISCOVERY_PORT)));
                    updateServers(); status.setText(pin.isEmpty()?"Saved PC loaded. Enter its Windows code once; it will be remembered securely.":"Saved PC and pairing code loaded. Tap Connect to PC.");
                });
            } catch(Exception e) { handler.post(()->{ if(!isDestroyed()) status.setText("Could not load saved PC: "+ReceiverService.describe(e)); }); }
        });
    }
    private void scanServers() {
        if(scanning || ReceiverService.running) return;
        scanning=true; scan.setEnabled(false); scan.setText("Scanning…"); final String ip=manual.getText().toString();
        worker.execute(()->{
            WifiManager.MulticastLock lock=null;
            try {
                WifiManager wifi=(WifiManager)getApplicationContext().getSystemService(WIFI_SERVICE);
                if(wifi!=null) { lock=wifi.createMulticastLock("AirLink:scan"); lock.acquire(); }
                List<ServerDiscovery.Server> result=ServerDiscovery.scan(ip);
                handler.post(()->{
                    if(isDestroyed()) return;
                    found.clear(); found.addAll(result); updateServers();
                    status.setText(found.isEmpty()?"No PCs found. Start the Windows server, allow its firewall access, or enter its IPv4 address.":"Found "+found.size()+" PC(s). Select yours and enter its Windows code.");
                });
            } catch(Exception e) { handler.post(()->{ if(!isDestroyed()) status.setText("Scan failed: "+e.getMessage()); }); }
            finally {
                if(lock!=null && lock.isHeld()) lock.release();
                handler.post(()->{ scanning=false; if(!isDestroyed()) { scan.setText("Scan for PCs"); scan.setEnabled(!ReceiverService.running); } });
            }
        });
    }
    private void updateServers() {
        List<String> items=new ArrayList<>(); if(found.isEmpty()) items.add("Scan to find your Windows PC"); else for(var pc:found) items.add(pc.toString());
        servers.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,items));
    }
    private void connect() {
        if(launching) return;
        if(ReceiverService.running) { startService(new Intent(this,ReceiverService.class).setAction("STOP")); return; }
        if(SessionVault.has(this)) {
            pendingResume=true;
            if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},20);
            else startConnection();
            return;
        }
        if(scanning || found.isEmpty()) { status.setText("Scan and select your PC first."); return; }
        String pin=code.getText().toString().trim(); if(!pin.matches("[0-9]{6}")) { status.setText("Enter the six-digit code shown in Windows AirLink."); return; }
        pendingServer=found.get(servers.getSelectedItemPosition()); pendingCode=pin;
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},20);
        else startConnection();
    }
    private void startConnection() {
        if(pendingServer==null && !pendingResume) return;
        int[] values={4,8,16}; getSharedPreferences("airlink",MODE_PRIVATE).edit().putInt("buffer",values[latency.getSelectedItemPosition()]).apply();
        Intent intent=pendingResume?new Intent(this,ReceiverService.class).setAction("RESUME"):
            new Intent(this,ReceiverService.class).putExtra("address",pendingServer.address).putExtra("port",pendingServer.port).putExtra("serverId",pendingServer.id).putExtra("code",pendingCode).putExtra("serverName",pendingServer.name);
        launching=true; ReceiverService.status="Starting Android receiver…"; lastServiceStatus="";
        try {
            startForegroundService(intent);
            if(BackgroundAccess.backgroundRestricted(this)) handler.postDelayed(this::openBackgroundSettings,500);
            var prefs=getSharedPreferences("airlink",MODE_PRIVATE);
            if(!BackgroundAccess.backgroundRestricted(this) && !prefs.getBoolean("screenOffPrompted",false)) { prefs.edit().putBoolean("screenOffPrompted",true).apply(); handler.postDelayed(this::allowScreenOff,700); }
            handler.postDelayed(()->{ if(!ReceiverService.running && launching) { launching=false; if(ReceiverService.status.equals("Starting Android receiver…")) ReceiverService.status="Android did not start the receiver. Tap Connect again with this screen open."; } },4000);
        }
        catch(Exception e) { launching=false; ReceiverService.status="Cannot start receiver: "+ReceiverService.describe(e); status.setText(ReceiverService.status); }
        pendingCode=null; pendingServer=null; pendingResume=false;
    }
    private void allowScreenOff() {
        if(isFinishing() || isDestroyed()) return;
        try {
            PowerManager power=(PowerManager)getSystemService(POWER_SERVICE);
            if(!power.isIgnoringBatteryOptimizations(getPackageName())) startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,Uri.parse("package:"+getPackageName())));
            else if(BackgroundAccess.backgroundRestricted(this)) openBackgroundSettings();
            else Toast.makeText(this,"Both Android background running and screen-off access are allowed.",Toast.LENGTH_LONG).show();
        } catch(Exception e) { startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName()))); }
    }
    private void openBackgroundSettings() {
        if(isFinishing() || isDestroyed()) return;
        Toast.makeText(this,"Choose Battery usage → Unrestricted / Allow background activity for AirLink. This is separate from screen-off access.",Toast.LENGTH_LONG).show();
        startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName())));
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results) { super.onRequestPermissionsResult(request,permissions,results); if(request==20) startConnection(); }
    @Override protected void onResume() {
        super.onResume(); handler.post(refresh);
        if(ReceiverService.running) startService(new Intent(this,ReceiverService.class).setAction("UI_VISIBLE"));
    }
    @Override protected void onPause() { handler.removeCallbacks(refresh); super.onPause(); }
    @Override public void onBackPressed() {
        if(ReceiverService.running) moveTaskToBack(true); else super.onBackPressed();
    }
    @Override protected void onDestroy() { worker.shutdownNow(); super.onDestroy(); }
    private Button button(LinearLayout body,String text) { Button b=new Button(this); b.setText(text); b.setTextColor(Color.rgb(13,21,31)); b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(accent)); LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(58)); p.topMargin=dp(10); p.bottomMargin=dp(10); body.addView(b,p); return b; }
    private TextView add(LinearLayout p,String text,int size,int color,boolean bold,int bottom) { TextView v=new TextView(this); v.setText(text); v.setTextSize(size); v.setTextColor(color); if(bold) v.setTypeface(Typeface.create("sans-serif",Typeface.BOLD)); LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2); lp.bottomMargin=dp(bottom); p.addView(v,lp); return v; }
    private int dp(int value) { return Math.round(value*getResources().getDisplayMetrics().density); }
}
