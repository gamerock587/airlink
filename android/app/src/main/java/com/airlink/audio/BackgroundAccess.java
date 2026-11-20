package com.airlink.audio;

import android.Manifest;
import android.app.ActivityManager;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.PowerManager;

public final class BackgroundAccess {
    public static boolean backgroundRestricted(Context context) {
        return Build.VERSION.SDK_INT>=28 && ((ActivityManager)context.getSystemService(Context.ACTIVITY_SERVICE)).isBackgroundRestricted();
    }
    public static boolean batteryExempt(Context context) {
        return ((PowerManager)context.getSystemService(Context.POWER_SERVICE)).isIgnoringBatteryOptimizations(context.getPackageName());
    }
    public static boolean notificationsAllowed(Context context) {
        return ((NotificationManager)context.getSystemService(Context.NOTIFICATION_SERVICE)).areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT<33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED);
    }
    public static String summary(Context context) {
        return "Background running: "+(backgroundRestricted(context)?"BLOCKED — set app battery use to Unrestricted":"allowed")+
            "\nScreen-off network: "+(batteryExempt(context)?"allowed":"restricted — tap Allow screen-off playback")+
            "\nPlayback notification: "+(notificationsAllowed(context)?"allowed":"blocked — enable notifications");
    }
}
