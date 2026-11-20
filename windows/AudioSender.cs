using NAudio.CoreAudioApi;
using NAudio.Wave;
using NAudio.Wave.SampleProviders;

namespace AirLink;

public sealed class AudioSender : IDisposable
{
    private readonly WasapiCapture capture;
    public string CaptureSource { get; }
    public double BufferedAudioMilliseconds => buffer.BufferedDuration.TotalMilliseconds;
    private readonly BufferedWaveProvider buffer;
    private readonly IWaveProvider pcm;
    private readonly NetworkClient network;
    private readonly CancellationTokenSource stop = new();
    private readonly Task pump;
    private volatile Exception? failure;
    private int peakPercent;
    private long lastSignal=Environment.TickCount64;
    private int disposed;
    public int TakePeakPercent() => Interlocked.Exchange(ref peakPercent,0);
    public bool IsSilent => Environment.TickCount64-Interlocked.Read(ref lastSignal)>5000;
    public Exception? Failure => failure;

    public AudioSender(MMDevice device, NetworkClient network)
    {
        this.network = network;
        capture = CreateCapture(device,out string source);
        CaptureSource=source;
        try
        {
            buffer = new BufferedWaveProvider(capture.WaveFormat) { BufferDuration = TimeSpan.FromMilliseconds(300), DiscardOnBufferOverflow = true, ReadFully = true };
            ISampleProvider samples = buffer.ToSampleProvider();
            if (samples.WaveFormat.Channels == 1) samples = new MonoToStereoSampleProvider(samples);
            else if (samples.WaveFormat.Channels != 2)
            {
                var stereo = new MultiplexingSampleProvider([samples], 2);
                stereo.ConnectInputToOutput(0, 0); stereo.ConnectInputToOutput(1, 1); samples = stereo;
            }
            if (samples.WaveFormat.SampleRate != 48000) samples = new WdlResamplingSampleProvider(samples, 48000);
            pcm = new SampleToWaveProvider16(samples);
            capture.DataAvailable += (_, e) => buffer.AddSamples(e.Buffer, 0, e.BytesRecorded);
            capture.RecordingStopped += (_, e) => { if (e.Exception != null) failure = e.Exception; };
            capture.StartRecording();
            pump = Task.Factory.StartNew(Pump, CancellationToken.None, TaskCreationOptions.LongRunning, TaskScheduler.Default);
        }
        catch { capture.Dispose(); stop.Dispose(); throw; }
    }

    private void Pump()
    {
        // Fixed 5 ms packets, paced by a high resolution wait. Silence keeps the link alive
        // when WASAPI produces no callbacks, and avoids replaying old audio after a pause.
        uint timer = Native.timeBeginPeriod(1);
        try
        {
            var watch = System.Diagnostics.Stopwatch.StartNew();
            long next = 0;
            byte[] chunk = new byte[Protocol.PcmBytes];
            while (!stop.IsCancellationRequested)
            {
                long now = watch.ElapsedMilliseconds;
                if (now < next) { stop.Token.WaitHandle.WaitOne((int)(next - now)); continue; }
                if (now - next > 60) { buffer.ClearBuffer(); next = now; }
                if (buffer.BufferedDuration.TotalMilliseconds > 80) buffer.ClearBuffer();
                int read = pcm.Read(chunk, 0, chunk.Length);
                if (read < chunk.Length) Array.Clear(chunk, read, chunk.Length - read);
                int peak=0;
                for(int i=0;i+1<read;i+=2) peak=Math.Max(peak,Math.Abs((int)System.Buffers.Binary.BinaryPrimitives.ReadInt16LittleEndian(chunk.AsSpan(i,2))));
                if(peak>32) Interlocked.Exchange(ref lastSignal,Environment.TickCount64);
                int percent=(int)Math.Round(peak*100.0/32768);
                // The pump is the only writer; the UI only resets this value to zero.
                int previous=Volatile.Read(ref peakPercent);
                while(percent>previous)
                {
                    int observed=Interlocked.CompareExchange(ref peakPercent,percent,previous);
                    if(observed==previous) break;
                    previous=observed;
                }
                network.SendAudio(chunk);
                next += 5;
            }
        }
        catch (Exception e) { failure = e; }
        finally { if (timer == 0) Native.timeEndPeriod(1); }
    }
    public void Dispose()
    {
        if(Interlocked.Exchange(ref disposed,1)!=0) return;
        stop.Cancel();
        capture.StopRecording();
        pump.GetAwaiter().GetResult();
        capture.Dispose(); stop.Dispose();
    }
    private static class Native
    {
        [System.Runtime.InteropServices.DllImport("winmm.dll")] internal static extern uint timeBeginPeriod(uint period);
        [System.Runtime.InteropServices.DllImport("winmm.dll")] internal static extern uint timeEndPeriod(uint period);
    }
    private static WasapiCapture CreateCapture(MMDevice output,out string source)
    {
        // Standard VB-CABLE forwards both render endpoints to CABLE Output.
        // Some driver/Windows combinations reject render loopback or return silence.
        // Match only the standard cable, never an arbitrary microphone or another cable.
        string name=output.FriendlyName;
        if(name.Equals("CABLE Input (VB-Audio Virtual Cable)",StringComparison.OrdinalIgnoreCase) ||
           name.Equals("CABLE In 16ch (VB-Audio Virtual Cable)",StringComparison.OrdinalIgnoreCase))
        {
            using var enumerator=new MMDeviceEnumerator();
            var inputs=enumerator.EnumerateAudioEndPoints(DataFlow.Capture,DeviceState.Active).ToList();
            try
            {
                var matches=inputs.Where(d=>d.FriendlyName.Equals("CABLE Output (VB-Audio Virtual Cable)",StringComparison.OrdinalIgnoreCase)).ToList();
                if(matches.Count==1)
                {
                    source=matches[0].FriendlyName;
                    return new WasapiCapture(matches[0],true,20);
                }
            }
            finally { foreach(var input in inputs) input.Dispose(); }
        }
        source=name+" (loopback)";
        return new LowLatencyLoopback(output);
    }
    private sealed class LowLatencyLoopback(MMDevice device) : WasapiCapture(device, true, 20)
    {
        protected override AudioClientStreamFlags GetAudioClientStreamFlags() => AudioClientStreamFlags.Loopback | base.GetAudioClientStreamFlags();
    }
}
