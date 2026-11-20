using System.Net;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using AirLink;
using NAudio.CoreAudioApi;
using NAudio.Wave;
using NAudio.Wave.SampleProviders;

static void Check(bool condition,string message) { if(!condition) throw new Exception(message); }
const string pin="123456", deviceId="airlink-test-device", session="00112233445566778899aabbccddeeff";
if(args.Contains("--settings"))
{
    string folder=Path.Combine(Path.GetTempPath(),"AirLink-settings-"+Guid.NewGuid().ToString("N"));
    try
    {
        var first=new ServerSettings(folder);
        first.SaveOutput("working-cable-endpoint");
        var restarted=new ServerSettings(folder);
        Check(first.Id==restarted.Id && first.PairingCode==restarted.PairingCode,"Restart changed server identity or pairing code");
        Check(restarted.OutputId=="working-cable-endpoint","Restart lost selected output");
        for(int i=0;i<3;i++)
        {
            using var restartedServer=new NetworkServer(restarted.Id,"Restart test",IPAddress.Loopback,restarted.PairingCode,0,0);
            Check(restartedServer.Code==first.PairingCode,"Server restart changed saved code");
        }
        restarted.SaveOutput("replacement-endpoint");
        Check(new ServerSettings(folder).OutputId=="replacement-endpoint","Output update was not saved");
        File.WriteAllText(Path.Combine(folder,"pairing-code.txt"),"005017");
        Check(new ServerSettings(folder).PairingCode=="005017","Leading zeros were lost");
        File.WriteAllText(Path.Combine(folder,"pairing-code.txt"),"invalid");
        bool invalidCodeRejected=false;
        try { _=new ServerSettings(folder); } catch(InvalidDataException) { invalidCodeRejected=true; }
        Check(invalidCodeRejected && File.ReadAllText(Path.Combine(folder,"pairing-code.txt"))=="invalid","Invalid code was silently replaced");
        Console.WriteLine("PASS: pairing code and server ID survive restarts, output selection persists, leading zeros preserved, invalid code is not regenerated");
    }
    finally
    {
        foreach(string name in new[]{"server-id.txt","pairing-code.txt","output-id.txt"}) File.Delete(Path.Combine(folder,name));
        Directory.Delete(folder);
    }
    return;
}
if(args.Contains("--capture-recovery")) { await CaptureRecoveryCheck.Run(); return; }
if(args.Contains("--cable-signal")) { await CableSignalCheck.Run(); return; }
if(args.Contains("--recording-outputs"))
{
    using var enumerator=new MMDeviceEnumerator();
    foreach(var device in enumerator.EnumerateAudioEndPoints(DataFlow.Capture,DeviceState.Active))
    {
        using(device)
        {
            if(!device.FriendlyName.Contains("CABLE",StringComparison.OrdinalIgnoreCase)) continue;
            try
            {
                using var capture=new WasapiCapture(device); float peak=0; long bytes=0;
                capture.DataAvailable+=(_,e)=> { bytes+=e.BytesRecorded; if(capture.WaveFormat.Encoding==WaveFormatEncoding.IeeeFloat) for(int offset=0;offset+3<e.BytesRecorded;offset+=4) peak=Math.Max(peak,Math.Abs(BitConverter.ToSingle(e.Buffer,offset))); };
                capture.StartRecording(); await Task.Delay(1500); capture.StopRecording();
                Console.WriteLine($"{device.FriendlyName}, format={capture.WaveFormat}, bytes={bytes}, peak={peak:F4}");
            } catch(Exception e) { Console.WriteLine($"{device.FriendlyName}: {e.Message}"); }
        }
    }
    return;
}
if(args.Contains("--capture-outputs"))
{
    using var enumerator=new MMDeviceEnumerator();
    using var diagnosticServer=new NetworkServer(deviceId,"Capture diagnostics",IPAddress.Loopback,pin,0,0);
    using var socket=new UdpClient();
    socket.Connect(IPAddress.Loopback,diagnosticServer.ControlPort);
    byte[] diagnosticKey=Protocol.DeriveKey(pin,deviceId,session);
    await socket.SendAsync(JsonSerializer.SerializeToUtf8Bytes(new {type="pair",session,proof=Protocol.Proof(diagnosticKey,"pair:"+session),name="Capture diagnostics"}));
    using var diagnosticTimeout=new CancellationTokenSource(TimeSpan.FromSeconds(5));
    await socket.ReceiveAsync(diagnosticTimeout.Token);
    var client=diagnosticServer.Connection!;
    foreach(var device in enumerator.EnumerateAudioEndPoints(DataFlow.Render,DeviceState.Active))
    {
        using(device)
        try {
            using var sender=new AudioSender(device,client);
            double queuedSum=0,queuedMax=0;
            for(int sample=0;sample<150;sample++)
            {
                double queued=sender.BufferedAudioMilliseconds;
                queuedSum+=queued; queuedMax=Math.Max(queuedMax,queued);
                await Task.Delay(10);
            }
            if(sender.Failure!=null) throw sender.Failure;
            Console.WriteLine($"PASS: {device.FriendlyName}, source={sender.CaptureSource}, transmitted PCM peak={sender.TakePeakPercent()}%, sender queue mean={queuedSum/150:F1} ms, max={queuedMax:F1} ms");
        } catch(Exception e) { Console.WriteLine($"FAIL: {device.FriendlyName}: {e}"); }
    }
    return;
}
if(args.Contains("--test-tone"))
{
    using var enumerator=new MMDeviceEnumerator();
    var devices=enumerator.EnumerateAudioEndPoints(DataFlow.Render,DeviceState.Active).ToList();
    try {
        int outputArgument=Array.IndexOf(args,"--output");
        string outputName=outputArgument>=0 && outputArgument+1<args.Length?args[outputArgument+1]:"CABLE Input";
        var device=devices.FirstOrDefault(d=>d.FriendlyName.Contains(outputName,StringComparison.OrdinalIgnoreCase))??throw new Exception(outputName+" not found");
        using var capture=new WasapiLoopbackCapture(device); float peak=0; long bytes=0;
        capture.DataAvailable+=(_,e)=> { bytes+=e.BytesRecorded; if(capture.WaveFormat.Encoding==WaveFormatEncoding.IeeeFloat) for(int offset=0;offset+3<e.BytesRecorded;offset+=4) peak=Math.Max(peak,Math.Abs(BitConverter.ToSingle(e.Buffer,offset))); };
        capture.StartRecording();
        using var output=new WasapiOut(device,AudioClientShareMode.Shared,true,40);
        output.Init(new SignalGenerator(48000,2){Gain=0.12,Frequency=523.25,Type=SignalGeneratorType.Sin}.Take(TimeSpan.FromSeconds(5)).ToWaveProvider());
        output.Play(); await Task.Delay(5500); output.Stop(); capture.StopRecording();
        Console.WriteLine($"TEST TONE: {device.FriendlyName}, format={capture.WaveFormat}, capturedBytes={bytes}, capturedPeak={peak:F4}");
    } finally { foreach(var device in devices) device.Dispose(); }
    return;
}
if(args.Contains("--audio-levels"))
{
    using var enumerator=new MMDeviceEnumerator(); using var defaultDevice=enumerator.GetDefaultAudioEndpoint(DataFlow.Render,Role.Multimedia);
    var devices=enumerator.EnumerateAudioEndPoints(DataFlow.Render,DeviceState.Active).ToList();
    try {
        var peaks=new float[devices.Count];
        for(int sample=0;sample<10;sample++) { for(int i=0;i<devices.Count;i++) peaks[i]=Math.Max(peaks[i],devices[i].AudioMeterInformation.MasterPeakValue); await Task.Delay(150); }
        for(int i=0;i<devices.Count;i++)
        {
            using var client=devices[i].AudioClient;
            Console.WriteLine($"{devices[i].FriendlyName}: default={devices[i].ID==defaultDevice.ID}, mute={devices[i].AudioEndpointVolume.Mute}, volume={devices[i].AudioEndpointVolume.MasterVolumeLevelScalar:P0}, peak={peaks[i]:F4}, mix={client.MixFormat}");
            var sessions=devices[i].AudioSessionManager.Sessions;
            for(int j=0;j<sessions.Count;j++)
            {
                using var audioSession=sessions[j];
                Console.WriteLine($"  Session PID={audioSession.GetProcessID}, state={audioSession.State}, mute={audioSession.SimpleAudioVolume.Mute}, volume={audioSession.SimpleAudioVolume.Volume:P0}");
            }
        }
    } finally { foreach(var device in devices) device.Dispose(); }
    return;
}
if(args.Contains("--java-server"))
{
    using var javaServer=new NetworkServer(deviceId,"Integration PC",IPAddress.Loopback,pin,0,0);
    Console.WriteLine("JAVA_SERVER_READY "+javaServer.DiscoveryPort);
    using var deadline=new CancellationTokenSource(TimeSpan.FromSeconds(args.Contains("--soak")?240:35));
    while(javaServer.Connection==null) { await Task.Delay(30,deadline.Token); }
    var connected=javaServer.Connection!;
    using var enumerator=new MMDeviceEnumerator(); using var device=enumerator.GetDefaultAudioEndpoint(DataFlow.Render,Role.Multimedia);
    using(var sender=new AudioSender(device,connected))
    {
        while(connected.IsHealthy) { await Task.Delay(30,deadline.Token); if(sender.Failure!=null) throw sender.Failure; }
    }
    Check(connected.PacketsSent>=200,"Java client did not receive a meaningful stream");
    Console.WriteLine($"PASS: Java client connected to Windows server, {connected.PacketsSent} live WASAPI packets sent"); return;
}
string fixtures=Path.GetFullPath(args.FirstOrDefault()??"verification"); Directory.CreateDirectory(fixtures);
byte[] key=Protocol.DeriveKey(pin,deviceId,session), pcm=Enumerable.Range(0,960).Select(i=>(byte)(i%251)).ToArray();
using var aes=new AesGcm(key,16);
byte[] packet=Protocol.Encrypt(aes,Convert.FromHexString(session),42,pcm), decoded=new byte[960];
byte[] nonce=Convert.FromHexString("001122332a00000000000000");
Check(packet.Length==988,"Packet length changed");
aes.Decrypt(nonce,packet.AsSpan(12,960),packet.AsSpan(972,16),decoded,packet.AsSpan(0,12)); Check(decoded.SequenceEqual(pcm),"PCM round trip failed");
packet[20]^=1; bool rejected=false; try { aes.Decrypt(nonce,packet.AsSpan(12,960),packet.AsSpan(972,16),decoded,packet.AsSpan(0,12)); } catch(CryptographicException) { rejected=true; }
Check(rejected,"Tampered data accepted"); packet[20]^=1;
File.WriteAllBytes(Path.Combine(fixtures,"packet.bin"),packet);
File.WriteAllLines(Path.Combine(fixtures,"crypto.txt"),[pin,deviceId,session,Convert.ToHexString(key).ToLowerInvariant(),Protocol.Proof(key,"pair:"+session)]);
Console.WriteLine("PASS: encryption, MTU size, tamper rejection, Java fixtures");

using var timeout=new CancellationTokenSource(TimeSpan.FromSeconds(25));
using var server=new NetworkServer(deviceId,"Test Windows PC",IPAddress.Loopback,pin,0,0);
using var phone=new UdpClient(AddressFamily.InterNetwork);
phone.Connect(IPAddress.Loopback,server.DiscoveryPort);
string queryNonce=Guid.NewGuid().ToString("N"); await phone.SendAsync(Encoding.UTF8.GetBytes("AIRLINK_DISCOVER_V2 "+queryNonce),timeout.Token);
var discovery=await phone.ReceiveAsync(timeout.Token);
using(var json=JsonDocument.Parse(discovery.Buffer))
{
    Check(json.RootElement.GetProperty("type").GetString()=="airlink-server","Windows did not advertise as server");
    Check(json.RootElement.GetProperty("nonce").GetString()==queryNonce,"Discovery nonce mismatch");
    Check(json.RootElement.GetProperty("id").GetString()==deviceId,"Wrong server ID");
    Check(json.RootElement.GetProperty("port").GetInt32()==server.DiscoveryPort,"Pairing did not advertise the same port as discovery");
}
Console.WriteLine("PASS: phone-initiated discovery of Windows server");
phone.Connect(IPAddress.Loopback,server.ControlPort);
async Task Send(object value) => await phone.SendAsync(JsonSerializer.SerializeToUtf8Bytes(value),timeout.Token);
async Task<JsonDocument> Reply() => JsonDocument.Parse((await phone.ReceiveAsync(timeout.Token)).Buffer);
await Send(new {type="pair",session,proof=Protocol.Proof(Protocol.DeriveKey("000000",deviceId,session),"pair:"+session),name="Wrong code test"});
using(var wrong=await Reply()) Check(wrong.RootElement.GetProperty("reason").GetString()=="wrong-code","Wrong code not rejected");
Check(server.Connection==null,"Bad code created a connection"); await Task.Delay(1000);
await Send(new {type="pair",session,proof=Protocol.Proof(key,"pair:"+session),name="Test Android"});
using(var ready=await Reply()) Check(ready.RootElement.GetProperty("proof").GetString()==Protocol.Proof(key,"ready:"+session),"Ready proof invalid");
Check(server.Connection!=null,"Phone-initiated pair not accepted"); var network=server.Connection!;
await Send(new {type="pair",session,proof=Protocol.Proof(key,"pair:"+session),name="Test Android"});
using(var retry=await Reply()) Check(retry.RootElement.GetProperty("type").GetString()=="ready","Pair retry not idempotent");
using(var stranger=new UdpClient())
{
    stranger.Connect(IPAddress.Loopback,server.ControlPort);
    await stranger.SendAsync(JsonSerializer.SerializeToUtf8Bytes(new {type="pair",session=Guid.NewGuid().ToString("N"),proof=new string('0',64)}),timeout.Token);
    var busy=await stranger.ReceiveAsync(timeout.Token); using var json=JsonDocument.Parse(busy.Buffer);
    Check(json.RootElement.GetProperty("reason").GetString()=="busy","Second phone was not rejected");
}
network.SendAudio(pcm); var data=await phone.ReceiveAsync(timeout.Token);
byte[] liveNonce=new byte[12]; Convert.FromHexString(session).AsSpan(0,4).CopyTo(liveNonce); data.Buffer.AsSpan(4,8).CopyTo(liveNonce.AsSpan(4));
aes.Decrypt(liveNonce,data.Buffer.AsSpan(12,960),data.Buffer.AsSpan(972,16),decoded,data.Buffer.AsSpan(0,12)); Check(decoded.SequenceEqual(pcm),"Server PCM mismatch");
using(var ping=await Reply()) Check(ping.RootElement.GetProperty("type").GetString()=="ping","Missing server heartbeat");
await Send(new {type="pong",session,proof=Protocol.Proof(key,"pong:"+session)});
await Send(new {type="stop",session,proof=new string('0',64)}); await Task.Delay(30); Check(network.IsHealthy,"Forged stop accepted");
Console.WriteLine("PASS: phone pairing, wrong code, duplicate retry, busy server, audio decryption, heartbeat, forged-stop rejection");
using(var enumerator=new MMDeviceEnumerator())
using(var device=enumerator.GetDefaultAudioEndpoint(DataFlow.Render,Role.Multimedia))
using(var sender=new AudioSender(device,network))
{
    int count=0; var until=DateTime.UtcNow.AddSeconds(2);
    while(DateTime.UtcNow<until)
    {
        var datagram=await phone.ReceiveAsync(timeout.Token);
        if(datagram.Buffer.Length==Protocol.PacketBytes)
        {
            datagram.Buffer.AsSpan(4,8).CopyTo(liveNonce.AsSpan(4));
            aes.Decrypt(liveNonce,datagram.Buffer.AsSpan(12,960),datagram.Buffer.AsSpan(972,16),decoded,datagram.Buffer.AsSpan(0,12)); count++;
        }
        else await Send(new {type="pong",session,proof=Protocol.Proof(key,"pong:"+session)});
    }
    Check(count>=250 && sender.Failure==null,"WASAPI stream failed"); Console.WriteLine($"PASS: Windows server streamed {count} live WASAPI packets / 2 s");
}
await Send(new {type="stop",session,proof=Protocol.Proof(key,"stop:"+session)}); await Task.Delay(100);
Check(!network.IsHealthy,"Authenticated phone disconnect ignored");
await Send(new {type="pong",session,proof=Protocol.Proof(key,"pong:"+session)}); await Task.Delay(30);
Check(!network.IsHealthy,"Late heartbeat revived a stopped session");
server.DropConnection(); Check(server.Connection==null,"Server did not release phone");
await Task.Delay(1000); string reconnect=Guid.NewGuid().ToString("N"); byte[] reconnectKey=Protocol.DeriveKey(pin,deviceId,reconnect);
await Send(new {type="pair",session=reconnect,proof=Protocol.Proof(reconnectKey,"pair:"+reconnect),name="Reconnected Android"});
// DropConnection sends an authenticated stop; skip that queued packet before ready.
bool connectedAgain=false;
for(int i=0;i<2;i++) { using var reply=await Reply(); if(reply.RootElement.GetProperty("type").GetString()=="ready") { connectedAgain=true; break; } }
Check(connectedAgain,"Server not reusable after disconnect");
Console.WriteLine("PASS: Android disconnect and new-session reconnection");
var closedPeer=server.Connection!; phone.Dispose();
try { closedPeer.SendAudio(pcm); } catch(SocketException) { }
await Task.Delay(150);
Check(server.Failure==null,"A closed phone socket stopped the Windows server: "+server.Failure);
server.DropConnection();
using(var probe=new UdpClient())
{
    probe.Connect(IPAddress.Loopback,server.DiscoveryPort);
    await probe.SendAsync(Encoding.UTF8.GetBytes("AIRLINK_DISCOVER_V2 "+queryNonce),timeout.Token);
    var response=await probe.ReceiveAsync(timeout.Token);
    Check(response.Buffer.Length>0,"Discovery stopped after the phone socket closed");
}
Console.WriteLine("PASS: closed phone socket leaves the Windows server and discovery running");
Console.WriteLine("All Windows server checks passed.");
