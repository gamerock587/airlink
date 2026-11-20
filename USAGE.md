# AirLink — Android 1.4.1 / Windows 1.3.3

Hear your Windows PC through headphones plugged into your Android phone, over your local network. **Windows is the server. Android scans for PCs and starts the connection.** No account or cloud service.

## Windows 11 capture correction

Windows 1.3.3 captures standard VB-CABLE through its CABLE Output recording endpoint. On this PC, render loopback returned silent PCM or a device-in-use error while the recording endpoint contained audio. Both CABLE Input and CABLE In 16ch now use the same verified recording endpoint. Physical outputs continue to use loopback. The saved pairing code is unchanged.

## Install the updated apps

Use Android 1.4.1 with Windows 1.3.3. Windows remembers your selected output and pairing code. On first launch it selects the current Windows output. Keep Windows Sound and AirLink on the same working endpoint; CABLE In 16ch is confirmed working on this PC. The server shows captured signal level and warns when it is sending silence. Audio startup failures retain the phone pairing and retry capture every two seconds. Android detects background execution and screen-off battery restrictions separately. The **Hide app — keep audio playing** button and Back while connected put the screen away while keeping the receiver active.

1. Close the older Windows AirLink window and exit any older AirLink tray icons. Extract **dist/AirLink-Windows-1.3.3-x64.zip** and run **AirLink.exe** inside, or run **dist/AirLink-Windows-1.3.3/AirLink.exe**. It includes its runtime and works on Windows 10/11 x64.
2. Transfer **dist/AirLink-Android-1.4.1.apk** to your phone and install it over the previous AirLink app. This is a local test APK signed with the same local debug key, not a Play Store release. Minimum Android version: 8.0/API 26.
3. On Windows, select the audio output to send, then click **Start server**. Windows displays its **six-digit code**, computer name and IPv4 addresses. Keep the server running.
4. Plug headphones into the phone and open Android AirLink. Tap **Scan for PCs**, select your PC, and enter the **code shown on Windows**. Choose **40 ms · balanced** and tap **Connect to PC**.
5. Allow Android notifications. Check the three background-access lines near the top of Android AirLink. **Background running** must show allowed: use **Allow background running / Unrestricted** and select Unrestricted/Allow background activity in app battery settings if blocked. Separately tap **Allow screen-off playback** and approve its battery-exemption prompt. Play something on Windows and adjust the phone's media volume.
6. Minimize Windows AirLink to keep it in the system tray. Closing/exiting the Windows app stops the server. Android playback continues separately from its screen after connection.

Android now remembers the selected PC address and the entered pairing code in encrypted storage and reloads them on app launch. Enter the code once after upgrading; older versions did not save it. Windows 1.3.3 keeps the same pairing code after restarts. Enter its saved code once when upgrading from an older version.

## If scanning or connecting fails

If connected but silent while using **scrcpy**, start the mirror with `--no-audio`. Scrcpy's default audio forwarding can redirect Android media to a remote submix instead of the phone's wired headphones. This was confirmed on the connected phone: the PC test signal reached Android, but output was type 25 (remote submix). Restarting the existing mirror with audio disabled restored type 3 (wired headset), and the user confirmed hearing the test tone. Screen mirroring can remain enabled.

Android playback is explicitly paced at one 5 ms frame at a time. This prevents the hardware's writable buffer from consuming the jitter queue too early and marking arriving packets as late. The device check after this fix showed 1,611 incoming/accepted packets with zero late packets and zero playback gaps.

Version 1.2 uses the **same UDP port (45832) for discovery, pairing and audio**, eliminating a separate reachable-discovery/unreachable-pairing port requirement. Windows retains 45833 for older 1.1 clients. Android moves socket setup into its worker, displays connection progress near the top of its screen and imposes a 15-second startup deadline. Windows displays whether it received a pairing request and whether the code matched.

If Android stops at a phase, note the exact message and the Windows status. **Starting Android receiver** concerns service startup; **Preparing secure pairing** derives the key; **Pairing · attempt N of 4** waits for the PC; **PC accepted code / waiting for audio** means pairing succeeded; **Playing Windows audio** means PCM reached playback. Failures display the exception class even when Android provides no message. Finding a PC alone does not prove its stream has started.

- Start the server on Windows before scanning from Android. Use both new 1.3 apps for automatic recovery; the old Windows screen with **Find phones** is the wrong connection direction.
- Both devices must be on the same reachable LAN. Guest Wi-Fi, router client isolation and VPNs can prevent them from communicating.
- Enter the **PC IPv4 address shown on Windows** into Android's optional PC address field, then tap **Scan for PCs** again. This sends a direct request and bypasses broadcast discovery. If Windows lists several addresses, use the Ethernet/Wi-Fi adapter on the same LAN as the phone.
- Allow the Windows app through **Windows Firewall on Private networks**. The Windows app has an **Allow LAN access in Windows Firewall (admin)** link that launches its included setup script with the normal Windows administrator prompt. It only permits AirLink's incoming UDP ports 45832/45833 from the local subnet on the Private profile. You can also run **Allow-LanAudio.ps1** from the final Windows folder in administrator PowerShell. Move/reinstall the app before creating the rule, since the rule includes its executable path.
- If your trusted home LAN is marked Public, change its network profile to Private in Windows network settings, then retry. Do not turn off the firewall. No router port forwarding is needed.
- **Wrong code** means enter the current code shown in Windows. Restarting the Windows server changes its code. **PC already has a connected phone** means disconnect the other phone using Windows' Disconnect phone button first. A missing server produces a timeout message instead of silently remaining in Connecting.
- A brief Wi-Fi interruption now triggers **Reconnecting automatically** instead of immediately ending the service. The phone retries restoration for up to 90 seconds; Windows retains the session for up to two minutes without a valid reply. Restored sockets are authenticated and the audio sequence continues. If Windows was restarted or the session expired, connect again with its current code.

Windows listens on UDP **45832** for discovery, pairing, control and outgoing audio. It also accepts pairing on **45833** for compatibility with 1.1 clients. Discovery advertises the port to use; audio replies come from the same Windows socket to Android's initiating temporary UDP socket. Android has no fixed listening server port.

## A selectable Windows speaker/output

A Windows sound output requires an audio driver. AirLink supports the separately supplied **VB-CABLE virtual audio driver**; a custom signed AirLink driver is not included.

1. Use the driver link in Windows AirLink or visit **https://vb-audio.com/Cable/**. Download, extract and install its Windows driver as administrator, and reboot as directed. VB-CABLE is third-party donationware with its own terms; it is not bundled or installed automatically.
2. In **Windows Settings → System → Sound → Output**, select **CABLE Input (VB-Audio Virtual Cable)**. Windows' volume mixer can select it for one application instead.
3. Select that same **CABLE Input** in Windows AirLink, then Start server and connect from Android. Click Refresh outputs after driver installation if needed.

Windows audio now goes to the virtual output and reaches the phone without needing the PC's physical speakers. The output remains named CABLE Input. For an immediate test without a driver, choose your current speakers in AirLink; their sound is captured, and the physical speakers can also play. Muting the source can affect captured audio, so use the virtual output for headphone-only listening. Stop the server before changing the source output.

## Android background playback

Once connected, the receiver runs in a foreground service separate from the activity. It declares `connectedDevice` and `mediaPlayback`, immediately shows a media notification and holds renewable CPU/Wi-Fi locks. `stopWithTask=false` leaves the service running after ordinary activity dismissal. `START_STICKY` allows Android to recreate the receiver after reclaiming its process; a short-lived encrypted checkpoint can restore the active Windows session.

- Use **Hide app — keep audio playing**, Back while connected, Home, switching apps, or screen lock to keep listening. Home and three minutes of locked-screen playback passed on the connected TECNO phone.
- On the tested TECNO CK8n, manually swiping AirLink from Recents makes the launcher fully stop the app, including its foreground service. Android records `USER REQUESTED / STOP APP`. Use Hide/Home instead. Lock AirLink in Recents using its arrow/menu → Lock so Clear all should skip it; this lock is enabled on the connected phone. Global Clear all was not tested because it would close unrelated apps. Even with that lock, manually swiping AirLink still fully stops it.
- Tap **Allow screen-off playback** and approve the Android system prompt. Without this exemption, Android/phone power management can restrict Wi-Fi and CPU access when locked, despite the receiver's foreground service. If your phone still restricts it, also select **Settings → Apps → AirLink → Battery → Unrestricted** or the manufacturer's equivalent and allow autostart where available. The separate app-settings button opens this page.
- **Background running** is a separate Android restriction. On the connected TECNO Android 14 phone, the screen-off exemption was already enabled, but `RUN_ANY_IN_BACKGROUND` was set to ignore. Android removed foreground-service status and recorded background-restricted kills. Android 1.4 now detects this and links to the relevant app settings; after settings are changed, reopening AirLink reasserts foreground-service status without restarting its audio connection. For a USB-authorized development phone, `scripts/Allow-AndroidBackground.ps1` applies only AirLink's background settings.
- **Disconnect** and the notification Stop receiver action erase the recovery checkpoint and stop the stream. Android's **Active apps → Stop** and **Force Stop** stop it too; those user/system choices are not bypassed. Unplugging a routed headset stops playback to prevent unexpected speaker output.
- Temporary audio-focus loss pauses local playback while keeping the connection, then resumes on focus gain. Permanent focus loss ends playback rather than competing with another media app. Android audio-output stalls are detected and the output is recreated rather than waiting indefinitely.
- If Android recreates a killed foreground service while its session is still valid, it restores the encrypted checkpoint and authenticates a new socket automatically. **Resume previous connection** is also available while a saved session is valid. Deliberate stop clears that state. Reboot, long process-restart delays and vendor restrictions remain outside the app's guarantee; reconnect manually if recovery expires.
- Wired/USB headphones follow Android media routing. Bluetooth adds delay. If headphones are absent, Android may use its speaker.

## Delay and reliability

Audio is 48 kHz stereo PCM16 in 5 ms AES-GCM-encrypted UDP packets: about **1.6 Mbit/s** plus link overhead. The 20/40/80 ms selection is the phone's network jitter-buffer target, **not measured end-to-end latency**. Capture, resampling, network and phone hardware add delay. Zero latency and zero packet loss cannot be guaranteed over Wi-Fi.

Use Ethernet for Windows and strong 5 GHz/6 GHz Wi-Fi for the phone. Start with 40 ms; try 20 ms on a strong network or 80 ms if playback crackles. Disconnect before changing the buffer. Brief loss produces silence, and a longer gap triggers rebuffering. The queue drops old backlog instead of accumulating seconds of delay. Android displays received packets and gaps while connected.

## Privacy and pairing

Windows generates a six-digit pairing code once and saves it in %LOCALAPPDATA%/AirLink/pairing-code.txt. The code and server identity remain the same across server stops, app restarts, and upgrades. The chosen output is saved in output-id.txt. Android generates a fresh session, derives its AES-256 key with PBKDF2-HMAC-SHA256 (100,000 iterations, server/session salt), and initiates authenticated pairing. Ready/control messages use HMAC-SHA256; audio uses AES-GCM with authenticated sequence headers and unique per-session nonces. Old/duplicate audio packets are discarded. Windows accepts one phone at a time.

Discovery exposes the Windows computer name, local address and random persistent server identifier on the LAN. Pairing errors are diagnostic messages; only a valid authenticated ready message establishes the session. Use a trusted private LAN: a six-digit code is intended for convenient local pairing, not resistance to exhaustive offline guessing by a determined traffic-recording attacker. Neither app uses the microphone, stores audio or contacts a cloud service during operation. At the user's request, Android 1.4 remembers the PC address and pairing code in AES-GCM encrypted storage wrapped by Android Keystore. A separate encrypted checkpoint contains the active session key/address/counter for background recovery. The checkpoint expires, is refreshed only while active, and is erased on explicit disconnect; the remembered PC/code remain available for the next connection. No session key or PIN is sent in a recovery request.

## Build and verify

Requirements: .NET SDK 8+ with Windows desktop support, JDK 17/21, Android SDK platform 35/build tools, and dependency download access. Set `ANDROID_HOME` and `JAVA_HOME` and run:

```powershell
.\scripts\Build.ps1
```

This runs real UDP Windows-server tests, a live WASAPI smoke check and the actual Android Java scanner/pairing code against a C# server. A checksum-pinned `org.json` jar is downloaded for JVM checks only; the Android app uses Android's built-in JSON implementation. Tests use OS-assigned loopback ports so they can run while the user's server is open. An active Windows audio output is needed for capture checks. `-SkipChecks` skips those desktop checks. Android build/lint still runs. Packaging writes Android 1.4 and Windows 1.3 artifacts without replacing a running older executable.

For a three-minute stream with a simulated 12-second outage, socket replacement, heartbeat recovery and paced jitter-buffer playback, run `scripts/Test-JavaClient.ps1 -SoakSeconds 180`. This exercises real Windows capture/transport and the Java recovery protocol. It does not simulate Android's hardware, Keystore or phone power management.

Sources: `windows/` (C# WinForms/NAudio.Wasapi), `android/` (Java/AudioTrack), `tests/`, and `docs/PROTOCOL.md`. See `RELEASE_REVIEW.md` for evidence and untested hardware behavior. Executable checks cover wrong code, duplicate pairing retry, a second phone, forged stop, encrypted audio, disconnection and reconnection. They do not replace testing the Android service on a physical phone.

## Check on your phone

Connect, play music, turn the screen off for ten minutes, switch apps and dismiss the activity from Recents. Confirm audio continues. Also check unplugging, calls, notification stop, Wi-Fi loss and reconnect. These device-specific checks and acoustic latency measurements have not been performed here.
