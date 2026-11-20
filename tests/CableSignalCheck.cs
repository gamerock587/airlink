using System.Net;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text.Json;
using AirLink;
using NAudio.CoreAudioApi;
using NAudio.Wave;
using NAudio.Wave.SampleProviders;

static class CableSignalCheck
{
    public static async Task Run()
    {
        const string id="cable-signal-test",pin="123456",session="00112233445566778899aabbccddeeff";
        using var timeout=new CancellationTokenSource(TimeSpan.FromSeconds(10));
        using var server=new NetworkServer(id,"Signal test",IPAddress.Loopback,pin,0,0);
        using var phone=new UdpClient();
        phone.Connect(IPAddress.Loopback,server.ControlPort);
        byte[] key=Protocol.DeriveKey(pin,id,session);
        await phone.SendAsync(JsonSerializer.SerializeToUtf8Bytes(new {type="pair",session,proof=Protocol.Proof(key,"pair:"+session),name="Signal test"}),timeout.Token);
        await phone.ReceiveAsync(timeout.Token);
        using var enumerator=new MMDeviceEnumerator();
        var outputs=enumerator.EnumerateAudioEndPoints(DataFlow.Render,DeviceState.Active).ToList();
        try
        {
            var output=outputs.Single(d=>d.FriendlyName=="CABLE In 16ch (VB-Audio Virtual Cable)");
            using var sender=new AudioSender(output,server.Connection!);
            if(sender.CaptureSource!="CABLE Output (VB-Audio Virtual Cable)") throw new Exception("Wrong cable capture endpoint");
            using var tone=new WasapiOut(output,AudioClientShareMode.Shared,true,40);
            try
            {
                tone.Init(new SignalGenerator(48000,2){Gain=0.06,Frequency=523.25,Type=SignalGeneratorType.Sin}.Take(TimeSpan.FromSeconds(2)).ToWaveProvider());
                tone.Play();
            }
            catch(System.Runtime.InteropServices.COMException error) when(error.HResult==unchecked((int)0x8889000A))
            {
                Console.WriteLine("Render endpoint busy; checking the existing live cable audio instead of injecting a tone.");
            }
            using var aes=new AesGcm(key,16);
            byte[] nonce=new byte[12],pcm=new byte[Protocol.PcmBytes];
            Convert.FromHexString(session).AsSpan(0,4).CopyTo(nonce);
            int packets=0,peak=0;
            var watch=System.Diagnostics.Stopwatch.StartNew();
            while(watch.ElapsedMilliseconds<2300)
            {
                var packet=(await phone.ReceiveAsync(timeout.Token)).Buffer;
                if(packet.Length!=Protocol.PacketBytes)
                {
                    await phone.SendAsync(JsonSerializer.SerializeToUtf8Bytes(new {type="pong",session,proof=Protocol.Proof(key,"pong:"+session)}),timeout.Token);
                    continue;
                }
                packet.AsSpan(4,8).CopyTo(nonce.AsSpan(4));
                aes.Decrypt(nonce,packet.AsSpan(12,Protocol.PcmBytes),packet.AsSpan(12+Protocol.PcmBytes,16),pcm,packet.AsSpan(0,12));
                packets++;
                for(int i=0;i<pcm.Length;i+=2) peak=Math.Max(peak,Math.Abs((int)System.Buffers.Binary.BinaryPrimitives.ReadInt16LittleEndian(pcm.AsSpan(i,2))));
            }
            if(packets<300 || peak<200 || sender.Failure!=null) throw new Exception($"Signal path failed: packets={packets}, peak={peak}, error={sender.Failure}");
            Console.WriteLine($"PASS: VB-CABLE recording endpoint → PCM16 → encrypted UDP → decrypted audio: {packets} packets, peak={peak*100.0/32768:F1}%");
        }
        finally { foreach(var output in outputs) output.Dispose(); }
    }
}
