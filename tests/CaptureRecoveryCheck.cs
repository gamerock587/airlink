using System.Net;
using System.Net.Sockets;
using System.Reflection;
using System.Text.Json;
using AirLink;
using NAudio.CoreAudioApi;
using NAudio.Wave;

static class CaptureRecoveryCheck
{
    public static Task Run()
    {
        var done=new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var thread=new Thread(() =>
        {
            try { CheckRecovery(); done.SetResult(); }
            catch(Exception error) { done.SetException(error); }
        });
        thread.SetApartmentState(ApartmentState.STA);
        thread.Start();
        return done.Task;
    }

    private static void CheckRecovery()
    {
        const string session="00112233445566778899aabbccddeeff", id="recovery-test", pin="123456";
        const BindingFlags flags=BindingFlags.Instance|BindingFlags.NonPublic;
        object? Get(MainForm form,string name)=>typeof(MainForm).GetField(name,flags)!.GetValue(form);
        void Set(MainForm form,string name,object? value)=>typeof(MainForm).GetField(name,flags)!.SetValue(form,value);
        void Tick(MainForm form)=>typeof(MainForm).GetMethod("UpdateServer",flags)!.Invoke(form,null);
        void Require(bool condition,string message) { if(!condition) throw new Exception(message); }
        using var form=new MainForm(Path.Combine(Path.GetTempPath(),"AirLink-recovery-"+Guid.NewGuid().ToString("N")));
        using var enumerator=new MMDeviceEnumerator();
        using var defaultOutput=enumerator.GetDefaultAudioEndpoint(DataFlow.Render,Role.Multimedia);
        var outputs=(ComboBox)Get(form,"outputs")!;
        var devices=(List<MMDevice>)Get(form,"devices")!;
        Require(devices[outputs.SelectedIndex].ID==defaultOutput.ID,"AirLink did not select the current Windows output");

        // Hold a real endpoint exclusively to reproduce AUDCLNT_E_DEVICE_IN_USE.
        int target=devices.FindIndex(d=>d.FriendlyName.StartsWith("Speakers",StringComparison.OrdinalIgnoreCase));
        Require(target>=0,"Recovery hardware test needs a Speakers output supporting exclusive 48 kHz PCM16 stereo");
        outputs.SelectedIndex=target;
        using var blocker=new WasapiOut(devices[target],AudioClientShareMode.Exclusive,false,100);
        blocker.Init(new SilenceProvider(new WaveFormat(48000,16,2)));
        blocker.Play();
        using var server=new NetworkServer(id,"Recovery test",IPAddress.Loopback,pin,0,0);
        using var phone=new UdpClient();
        phone.Connect(IPAddress.Loopback,server.ControlPort);
        phone.Client.ReceiveTimeout=5000;
        byte[] key=Protocol.DeriveKey(pin,id,session);
        phone.Send(JsonSerializer.SerializeToUtf8Bytes(new {type="pair",session,proof=Protocol.Proof(key,"pair:"+session),name="Recovery test"}));
        IPEndPoint peer=new(IPAddress.Any,0);
        phone.Receive(ref peer);
        var connection=server.Connection!;
        Set(form,"server",server);
        try
        {
            Tick(form);
            Require(Get(form,"audio")==null,"Exclusive output did not block capture");
            Require(((string?)Get(form,"audioProblem"))?.Contains("Output is busy")==true,"Missing actionable busy-output error");
            Require(ReferenceEquals(server.Connection,connection) && connection.IsHealthy,"Capture failure dropped the paired phone");
            Tick(form);
            Require(Get(form,"audio")==null,"Retry backoff was ignored");
            blocker.Stop(); blocker.Dispose();
            Set(form,"nextAudioAttempt",0L);
            Tick(form);
            Require(Get(form,"audio") is AudioSender,"Capture did not restart after exclusive output was released");
            Thread.Sleep(100);
            Require(ReferenceEquals(server.Connection,connection) && connection.PacketsSent>0,"Recovery did not stream using the existing phone session");
            Require(((AudioSender)Get(form,"audio")!).Failure==null,"Recovered capture failed");
            Console.WriteLine("PASS: default output selection, real exclusive-output failure, retained pairing, retry backoff, automatic capture recovery");
        }
        finally
        {
            ((AudioSender?)Get(form,"audio"))?.Dispose();
            Set(form,"audio",null); Set(form,"server",null);
            ((System.Windows.Forms.Timer)Get(form,"timer")!).Stop();
            ((NotifyIcon)Get(form,"tray")!).Dispose();
            foreach(var device in devices) device.Dispose();
            ((MMDeviceEnumerator)Get(form,"enumerator")!).Dispose();
        }
    }
}
