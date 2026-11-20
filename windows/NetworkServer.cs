using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace AirLink;

// A connected phone shares the server socket. Only the audio pump uses AES;
// the server receives control traffic on a separate task.
public sealed class NetworkClient : IDisposable
{
    private UdpClient socket;
    private readonly AesGcm aes;
    private readonly byte[] sessionBytes;
    private readonly byte[] key;
    private readonly object gate = new();
    private ulong sequence;
    private long lastAck = Environment.TickCount64;
    private long lastPing;
    private bool disposed;
    private int ended;
    private long resumeCounter;
    private IPEndPoint peer;
    public string Session { get; }
    public string Name { get; }
    public IPEndPoint Peer => Volatile.Read(ref peer);
    public long ReplyAgeMilliseconds => Environment.TickCount64-Interlocked.Read(ref lastAck);
    public bool IsRecovering => ReplyAgeMilliseconds>3000;
    public bool IsHealthy => !disposed && Volatile.Read(ref ended)==0 && ReplyAgeMilliseconds < 120000;
    public long PacketsSent { get; private set; }
    public long PacketsDropped { get; private set; }
    public string LastNetworkError { get; private set; }="";
    internal NetworkClient(UdpClient socket, IPEndPoint peer, string session, string name, byte[] key)
    {
        this.socket = socket; this.peer = peer; Session = session; Name = name; this.key = key;
        sessionBytes = Convert.FromHexString(session); aes = new AesGcm(key, 16);
    }
    internal bool Validate(string type, string proof)
    {
        lock (gate) return !disposed && CryptographicOperations.FixedTimeEquals(
            Encoding.ASCII.GetBytes(Protocol.Proof(key, type + ":" + Session)), Encoding.ASCII.GetBytes(proof));
    }
    internal void Acknowledge() { if(Volatile.Read(ref ended)==0) Interlocked.Exchange(ref lastAck, Environment.TickCount64); }
    internal void MarkDisconnected() => Interlocked.Exchange(ref ended, 1);
    internal void Reply(string type)
    {
        lock (gate)
        {
            if (disposed) return;
            Send(JsonSerializer.SerializeToUtf8Bytes(new { type, session = Session, proof = Protocol.Proof(key, type + ":" + Session) }));
        }
    }
    public void SendAudio(ReadOnlySpan<byte> pcm)
    {
        lock (gate)
        {
            if (disposed || Volatile.Read(ref ended)!=0) return;
            if(Send(Protocol.Encrypt(aes, sessionBytes, sequence++, pcm))) PacketsSent++;
            Maintain();
        }
    }
    private bool Send(byte[] data)
    {
        try { socket.Send(data,Peer); return true; }
        catch(SocketException e) { PacketsDropped++; LastNetworkError=e.SocketErrorCode.ToString(); return false; }
    }
    public void Maintain()
    {
        lock(gate)
        {
            if(disposed || Volatile.Read(ref ended)!=0) return;
            if(Environment.TickCount64-lastPing>=1000) { lastPing=Environment.TickCount64; Reply("ping"); }
        }
    }
    internal bool TryResume(long counter,string proof,IPEndPoint newPeer,UdpClient newSocket)
    {
        lock(gate)
        {
            if(disposed || Volatile.Read(ref ended)!=0 || counter<=0 || counter<resumeCounter || (counter==resumeCounter && !newPeer.Equals(Peer))) return false;
            if(!CryptographicOperations.FixedTimeEquals(Encoding.ASCII.GetBytes(Protocol.Proof(key,$"resume:{Session}:{counter}")),Encoding.ASCII.GetBytes(proof))) return false;
            resumeCounter=counter; socket=newSocket; Volatile.Write(ref peer,newPeer); Acknowledge();
            Send(JsonSerializer.SerializeToUtf8Bytes(new {type="resumed",session=Session,counter,proof=Protocol.Proof(key,$"resumed:{Session}:{counter}")}));
            return true;
        }
    }
    public void Dispose()
    {
        lock (gate)
        {
            if (disposed) return;
            try { Reply("stop"); } catch (SocketException) { } catch (ObjectDisposedException) { }
            disposed = true; aes.Dispose(); CryptographicOperations.ZeroMemory(key);
        }
    }
}

public sealed class NetworkServer : IDisposable
{
    private readonly UdpClient discovery;
    private readonly UdpClient control;
    private readonly CancellationTokenSource stop = new();
    private readonly Task discoveryLoop, controlLoop, maintenanceLoop;
    private readonly object gate = new();
    private NetworkClient? connection;
    private readonly SemaphoreSlim pairingGate = new(1,1);
    private string lastEvent="No pairing request received yet.";
    private long lastPair;
    private readonly Dictionary<IPAddress, long> attempts = new();
    public string Code { get; }
    public string Id { get; }
    public string Name { get; }
    public string? Failure { get; private set; }
    public string LastEvent => Volatile.Read(ref lastEvent);
    public int DiscoveryPort => ((IPEndPoint)discovery.Client.LocalEndPoint!).Port;
    public int ControlPort => ((IPEndPoint)control.Client.LocalEndPoint!).Port;
    public NetworkClient? Connection { get { lock (gate) return connection; } }
    public NetworkServer(string id, string name, IPAddress? bindAddress = null, string? code = null,int discoveryPort=Protocol.DiscoveryPort,int audioPort=Protocol.AudioPort)
    {
        Id = id; Name = name;
        Code = code ?? RandomNumberGenerator.GetInt32(1000000).ToString("D6");
        bindAddress ??= IPAddress.Any;
        discovery = new UdpClient(new IPEndPoint(bindAddress, discoveryPort));
        try { control = new UdpClient(new IPEndPoint(bindAddress, audioPort)); }
        catch { discovery.Dispose(); throw; }
        // Windows can report an ICMP "port unreachable" from an old UDP peer
        // against a later receive. It is not an authenticated disconnect.
        foreach(var udp in new[]{discovery,control}) try { udp.Client.IOControl(unchecked((int)0x9800000C),new byte[4],null); } catch(SocketException) { }
        discoveryLoop = Task.Run(DiscoverLoop); controlLoop = Task.Run(ControlLoop); maintenanceLoop=Task.Run(MaintainLoop);
    }
    private async Task MaintainLoop()
    {
        using var timer=new PeriodicTimer(TimeSpan.FromSeconds(1));
        try { while(await timer.WaitForNextTickAsync(stop.Token)) Connection?.Maintain(); }
        catch(OperationCanceledException) { }
        catch(ObjectDisposedException) { }
    }
    public static string[] LocalAddresses() => NetworkInterface.GetAllNetworkInterfaces()
        .Where(n => n.OperationalStatus == OperationalStatus.Up && n.NetworkInterfaceType != NetworkInterfaceType.Loopback)
        .SelectMany(n => n.GetIPProperties().UnicastAddresses)
        .Where(a => a.Address.AddressFamily == AddressFamily.InterNetwork)
        .Select(a => a.Address.ToString()).Distinct().ToArray();
    private async Task DiscoverLoop()
    {
        try
        {
            while (!stop.IsCancellationRequested)
            {
                UdpReceiveResult datagram;
                try { datagram = await discovery.ReceiveAsync(stop.Token); }
                catch(SocketException e) when(e.SocketErrorCode==SocketError.ConnectionReset) { continue; }
                if(datagram.Buffer.Length>0 && datagram.Buffer[0]=='{') { await HandleControl(datagram,discovery); continue; }
                if (datagram.Buffer.Length > 128) continue;
                string query = Encoding.UTF8.GetString(datagram.Buffer);
                const string prefix = "AIRLINK_DISCOVER_V2 ";
                if (!query.StartsWith(prefix) || query.Length != prefix.Length + 32 || !query[prefix.Length..].All(char.IsAsciiHexDigit)) continue;
                Volatile.Write(ref lastEvent,$"Discovered by {datagram.RemoteEndPoint.Address}. Waiting for pairing.");
                await discovery.SendAsync(JsonSerializer.SerializeToUtf8Bytes(new { type = "airlink-server", version = 2, port = DiscoveryPort, nonce = query[prefix.Length..], id = Id, name = Name }), datagram.RemoteEndPoint, stop.Token);
            }
        }
        catch (OperationCanceledException) { }
        catch (ObjectDisposedException) { }
        catch (Exception e) { if (!stop.IsCancellationRequested) Failure = "Discovery: " + e.Message; }
    }
    private async Task ControlLoop()
    {
        try
        {
            while (!stop.IsCancellationRequested)
            {
                UdpReceiveResult datagram;
                try { datagram = await control.ReceiveAsync(stop.Token); }
                catch(SocketException e) when(e.SocketErrorCode==SocketError.ConnectionReset) { continue; }
                await HandleControl(datagram,control);
            }
        }
        catch (OperationCanceledException) { }
        catch (ObjectDisposedException) { }
        catch (Exception e) { if (!stop.IsCancellationRequested) Failure = "Connection: " + e.Message; }
    }
    private async Task HandleControl(UdpReceiveResult datagram,UdpClient responseSocket)
    {
        await pairingGate.WaitAsync(stop.Token);
        try
        {
                    if (datagram.Buffer.Length > 1024) return;
                    using var doc = JsonDocument.Parse(datagram.Buffer); var root = doc.RootElement;
                    string type = root.GetProperty("type").GetString() ?? "";
                    string session = root.GetProperty("session").GetString() ?? "";
                    string proof = root.GetProperty("proof").GetString() ?? "";
                    if (session.Length != 32 || !session.All(char.IsAsciiHexDigit) || proof.Length != 64) return;
                    var current = Connection;
                    if(type=="resume")
                    {
                        if(current!=null && current.Session==session && root.TryGetProperty("counter",out var counter) && counter.TryGetInt64(out long number) && current.TryResume(number,proof,datagram.RemoteEndPoint,responseSocket))
                            Volatile.Write(ref lastEvent,"Phone connection restored automatically; continuing the existing audio sequence.");
                        return;
                    }
                    if (type == "pair")
                    {
                        Volatile.Write(ref lastEvent,$"Pairing request received from {datagram.RemoteEndPoint.Address} on UDP {((IPEndPoint)responseSocket.Client.LocalEndPoint!).Port}.");
                        if (current != null)
                        {
                            if (current.Peer.Equals(datagram.RemoteEndPoint) && current.Session == session && current.Validate("pair", proof)) current.Reply("ready");
                            else { Volatile.Write(ref lastEvent,"Pairing rejected: another phone is connected."); await Error("busy", datagram.RemoteEndPoint,responseSocket); }
                            return;
                        }
                        long now = Environment.TickCount64;
                        // Per-address limits prevent a mistaken code from monopolizing PBKDF2.
                        if (now - lastPair < 100 || (attempts.TryGetValue(datagram.RemoteEndPoint.Address, out long previous) && now - previous < 900)) return;
                        lastPair = now;
                        if (attempts.Count > 128) attempts.Clear();
                        attempts[datagram.RemoteEndPoint.Address] = now;
                        byte[] key = Protocol.DeriveKey(Code, Id, session);
                        if (!CryptographicOperations.FixedTimeEquals(Encoding.ASCII.GetBytes(Protocol.Proof(key, "pair:" + session)), Encoding.ASCII.GetBytes(proof)))
                        { CryptographicOperations.ZeroMemory(key); Volatile.Write(ref lastEvent,"Pairing rejected: code does not match. Enter the current Windows code."); await Error("wrong-code", datagram.RemoteEndPoint,responseSocket); return; }
                        string phoneName = root.TryGetProperty("name", out var name) ? name.GetString() ?? "Android" : "Android";
                        current = new NetworkClient(responseSocket, datagram.RemoteEndPoint, session, phoneName[..Math.Min(phoneName.Length, 80)], key);
                        lock (gate)
                        {
                            if (stop.IsCancellationRequested) { current.Dispose(); return; }
                            connection = current;
                        }
                        current.Reply("ready");
                        Volatile.Write(ref lastEvent,"Pairing accepted. Starting the Windows audio stream.");
                    }
                    else if (current != null && current.Peer.Equals(datagram.RemoteEndPoint) && current.Session == session && current.Validate(type, proof))
                    {
                        if (type is "pong" or "ping") { current.Acknowledge(); if (type == "ping") current.Reply("pong"); }
                        else if (type == "stop") current.MarkDisconnected();
                    }
        }
        catch (Exception e) when (e is JsonException or KeyNotFoundException or InvalidOperationException or ArgumentException) { }
        catch(SocketException e) { Volatile.Write(ref lastEvent,"Temporary UDP error: "+e.SocketErrorCode+". Waiting for the phone to recover."); }
        finally { pairingGate.Release(); }
    }
    private async Task Error(string reason, IPEndPoint peer,UdpClient socket) => await socket.SendAsync(JsonSerializer.SerializeToUtf8Bytes(new { type = "error", reason }), peer, stop.Token);
    public void DropConnection()
    {
        lock (gate) { connection?.Dispose(); connection = null; }
    }
    public void Dispose()
    {
        stop.Cancel(); DropConnection(); discovery.Dispose(); control.Dispose();
        Task.WhenAll(discoveryLoop, controlLoop, maintenanceLoop).GetAwaiter().GetResult(); stop.Dispose(); pairingGate.Dispose();
    }
}
