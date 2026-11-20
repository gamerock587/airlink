using System.Diagnostics;
using NAudio.CoreAudioApi;

namespace AirLink;

public sealed class MainForm : Form
{
    private static readonly Color Ink = Color.FromArgb(229,236,245), Muted = Color.FromArgb(157,174,191), Accent = Color.FromArgb(91,231,185);
    private readonly ComboBox outputs = new() { DropDownStyle = ComboBoxStyle.DropDownList, Dock = DockStyle.Fill, ForeColor = Color.FromArgb(13,21,31), BackColor = Color.White };
    private readonly Label code = new() { Text = "— — — — — —", Font = new Font("Segoe UI",30,FontStyle.Bold), ForeColor = Accent, AutoSize = true };
    private readonly Label addresses = new() { Text = "", Dock = DockStyle.Fill, ForeColor = Muted };
    private readonly Label status = new() { Text = "Choose an output, then start the server", Dock = DockStyle.Fill, ForeColor = Accent };
    private readonly Label detail = new() { Dock = DockStyle.Fill, ForeColor = Muted };
    private readonly Button start = Button("Start server"), refresh = Button("Refresh outputs"), disconnect = Button("Disconnect phone");
    private readonly NotifyIcon tray;
    private readonly System.Windows.Forms.Timer timer = new() { Interval = 300 };
    private readonly MMDeviceEnumerator enumerator = new();
    private List<MMDevice> devices = [];
    private NetworkServer? server;
    private NetworkClient? active;
    private AudioSender? audio;
    private long nextAudioAttempt;
    private string? audioProblem;
    private readonly ServerSettings settings;
    public MainForm(string? settingsDirectory=null)
    {
        settings=new ServerSettings(settingsDirectory);
        code.Text=settings.PairingCode;
        Text = "AirLink 1.3.3 · Windows audio server"; Size = new Size(760,900); MinimumSize = new Size(680,780);
        StartPosition = FormStartPosition.CenterScreen; BackColor = Color.FromArgb(13,21,31); ForeColor = Ink; Font = new Font("Segoe UI",10);
        var body = new TableLayoutPanel { Dock = DockStyle.Fill, Padding = new Padding(32), ColumnCount = 1, AutoScroll = true };
        body.ColumnStyles.Add(new ColumnStyle(SizeType.Percent,100)); Controls.Add(body);
        void Add(Control c,int height) { int row=body.Controls.Count; body.RowStyles.Add(new RowStyle(SizeType.Absolute,height)); body.Controls.Add(c,0,row); }
        Add(Label("◉  AIRLINK / WINDOWS SERVER",12,Accent),40);
        Add(Label("Connect from your phone.",25,Ink),60);
        Add(Label("Start here. Then scan for this PC in the Android app.",10,Muted),42);
        Add(Label("01   Choose the Windows output to stream",10,Ink),30); Add(Row(outputs,refresh),44);
        Add(new Label { Text="Choose the same output in Windows Sound and here. On this PC, use CABLE In 16ch. Your selection is remembered.", Dock=DockStyle.Fill, ForeColor=Muted },48);
        var links = new FlowLayoutPanel { Dock=DockStyle.Fill };
        links.Controls.Add(Link("Get virtual output driver ↗",()=>Open("https://vb-audio.com/Cable/")));
        links.Controls.Add(Link("Windows Sound settings ↗",()=>Open("ms-settings:sound"))); Add(links,40);
        Add(Label("02   Start the server · your saved pairing code stays the same",10,Ink),34);
        Add(code,64); Add(Row(start,disconnect),48);
        Add(Label("PC ADDRESS / MANUAL CONNECTION",10,Ink),30); Add(addresses,64);
        Add(status,44); Add(detail,64);
        Add(Link("Allow LAN access in Windows Firewall (admin)",AllowFirewall),32);
        Add(new Label { Text="Android → Scan for PCs → select this PC → enter code → Connect\n48 kHz stereo · encrypted UDP · local network only", ForeColor=Muted, Dock=DockStyle.Fill },60);
        refresh.Click+=(_,_)=>RefreshOutputs(); start.Click+=(_,_)=>ToggleServer();
        disconnect.Click+=(_,_)=>DropPhone("Phone disconnected. Server is waiting for a connection.");
        tray=new NotifyIcon { Icon=SystemIcons.Application, Text="AirLink Windows server", Visible=true };
        var menu=new ContextMenuStrip(); menu.Items.Add("Open AirLink",null,(_,_)=>{ Show(); WindowState=FormWindowState.Normal; Activate(); });
        menu.Items.Add("Disconnect phone",null,(_,_)=>DropPhone("Phone disconnected")); menu.Items.Add("Exit",null,(_,_)=>Close()); tray.ContextMenuStrip=menu;
        tray.DoubleClick+=(_,_)=>{ Show(); WindowState=FormWindowState.Normal; Activate(); };
        Resize+=(_,_)=>{ if(WindowState==FormWindowState.Minimized) Hide(); };
        timer.Tick+=(_,_)=>UpdateServer(); timer.Start(); RefreshOutputs(); RefreshAddresses(); SetControls();
    }
    private static Label Label(string text,int size,Color color)=>new() { Text=text, Font=new Font("Segoe UI",size,FontStyle.Bold), ForeColor=color, AutoSize=true };
    private static Button Button(string text)=>new() { Text=text, Dock=DockStyle.Fill, FlatStyle=FlatStyle.Flat, BackColor=Accent, ForeColor=Color.FromArgb(13,21,31), Cursor=Cursors.Hand };
    private static LinkLabel Link(string text,Action action) { var l=new LinkLabel { Text=text, LinkColor=Accent, AutoSize=true, Margin=new Padding(0,2,22,0) }; l.LinkClicked+=(_,_)=>action(); return l; }
    private static TableLayoutPanel Row(Control left,Control right) { var p=new TableLayoutPanel { Dock=DockStyle.Fill, ColumnCount=2 }; p.ColumnStyles.Add(new ColumnStyle(SizeType.Percent,100)); p.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute,180)); p.Controls.Add(left,0,0); p.Controls.Add(right,1,0); return p; }
    private static void Open(string path) { try { Process.Start(new ProcessStartInfo(path) { UseShellExecute=true }); } catch(Exception e) { MessageBox.Show(e.Message); } }
    private void AllowFirewall()
    {
        string script=Path.Combine(AppContext.BaseDirectory,"Allow-LanAudio.ps1");
        if(!File.Exists(script)) { status.Text="Use the published app folder containing Allow-LanAudio.ps1."; return; }
        try
        {
            var info=new ProcessStartInfo("powershell.exe") { UseShellExecute=true, Verb="runas", WindowStyle=ProcessWindowStyle.Hidden };
            foreach(string arg in new[]{"-NoProfile","-ExecutionPolicy","Bypass","-File",script,"-AppPath",Environment.ProcessPath!}) info.ArgumentList.Add(arg);
            Process.Start(info);
            detail.Text="Accept the Windows administrator prompt to add the Private LAN rule.\nThen scan again on Android. Your trusted LAN must use the Private profile.";
        }
        catch(System.ComponentModel.Win32Exception e) when(e.NativeErrorCode==1223) { detail.Text="Firewall setup was cancelled. You can retry the link."; }
        catch(Exception e) { detail.Text="Could not open firewall setup: "+e.Message; }
    }
    private void RefreshAddresses() { try { addresses.Text=Environment.MachineName+"\n"+string.Join("   ·   ",NetworkServer.LocalAddresses())+"   /   UDP 45832–45833"; } catch { addresses.Text="Check Windows network settings for your IPv4 address."; } }
    private void RefreshOutputs()
    {
        if(server!=null) return;
        string? selectedId=outputs.SelectedIndex>=0?devices[outputs.SelectedIndex].ID:settings.OutputId;
        foreach(var d in devices) d.Dispose(); devices.Clear(); outputs.Items.Clear();
        try
        {
            devices=enumerator.EnumerateAudioEndPoints(DataFlow.Render,DeviceState.Active).ToList(); foreach(var d in devices) outputs.Items.Add(d.FriendlyName);
            if(devices.Count==0) { status.Text="No active audio output. Enable one or install VB-CABLE."; return; }
            using var defaultDevice=enumerator.GetDefaultAudioEndpoint(DataFlow.Render,Role.Multimedia);
            // Capture the output Windows is actually playing through. Different VB-CABLE
            // endpoints can share a driver; preferring CABLE Input can pick a busy one.
            int saved=devices.FindIndex(d=>d.ID==selectedId);
            outputs.SelectedIndex=saved>=0?saved:Math.Max(0,devices.FindIndex(d=>d.ID==defaultDevice.ID));
        }
        catch(Exception e) { status.Text="Cannot list outputs: "+e.Message; }
    }
    private void ToggleServer()
    {
        if(server!=null) { StopServer(); return; }
        if(outputs.SelectedIndex<0) { status.Text="Choose an audio output first."; return; }
        try
        {
            settings.SaveOutput(devices[outputs.SelectedIndex].ID);
            server=new NetworkServer(settings.Id,Environment.MachineName,code:settings.PairingCode); code.Text=server.Code;
            status.Text="● Server ready. Scan for PCs from Android.";
            detail.Text="Allow AirLink on Private networks if Windows Firewall asks.\nIf scanning finds nothing, enter one of the PC addresses above on Android.";
            RefreshAddresses(); SetControls();
        }
        catch(Exception e) { status.Text="Cannot start server: "+e.Message; detail.Text="Close another AirLink server using ports 45832/45833, then retry."; }
    }
    public void StartServer() { if(server==null) ToggleServer(); }
    private void UpdateServer()
    {
        if(server==null) return;
        if(server.Failure is string error) { StopServer(); status.Text=error; return; }
        var connected=server.Connection;
        if(connected!=null && !connected.IsHealthy) { DropPhone("Phone ended the stream or was unreachable for two minutes. Connect again from Android."); return; }
        if(connected!=null && active==null) { active=connected; SetControls(); }
        if(connected!=null && audio==null && Environment.TickCount64>=nextAudioAttempt)
        {
            try { using var device=enumerator.GetDevice(devices[outputs.SelectedIndex].ID); audio=new AudioSender(device,connected); audioProblem=null; }
            catch(Exception e) { WaitForAudio(e); }
        }
        if(audio?.Failure is Exception failure) { audio.Dispose(); audio=null; WaitForAudio(failure); }
        if(active!=null && audio==null)
        {
            status.Text="Waiting for Windows audio · retrying automatically";
            detail.Text=audioProblem;
            return;
        }
        if(active!=null)
        {
            status.Text=active.IsRecovering?$"Recovering phone connection · {active.ReplyAgeMilliseconds/1000} s without reply":"● Streaming to "+active.Name+" · "+active.Peer.Address;
            detail.Text=$"{active.PacketsSent:N0} packets sent · {active.PacketsDropped:N0} drops · audio signal {audio!.TakePeakPercent()}%\n"+(active.IsRecovering?"Keep Android open; it will rejoin automatically.":audio.IsSilent?"No sound captured. Play PC audio and match the Windows output to: "+outputs.Text:"Capturing: "+audio.CaptureSource);
        }
        else detail.Text=server.LastEvent+"\nAndroid 1.3 can restore an interrupted connection automatically.";
    }
    private void SetControls() { outputs.Enabled=refresh.Enabled=server==null; start.Text=server==null?"Start server":"Stop server"; disconnect.Enabled=active!=null; }
    private void WaitForAudio(Exception error)
    {
        nextAudioAttempt=Environment.TickCount64+2000;
        audioProblem=error.HResult==unchecked((int)0x8889000A)
            ? "Output is busy. Close the app using exclusive audio, or stop the server and choose the current Windows output.\nPhone stays paired; capture retries every 2 seconds."
            : $"Audio unavailable (0x{error.HResult:X8}): {error.Message}\nCheck the selected output. Phone stays paired; capture retries every 2 seconds.";
    }
    private void DropPhone(string message) { audio?.Dispose(); audio=null; active=null; nextAudioAttempt=0; audioProblem=null; server?.DropConnection(); status.Text=message; detail.Text=""; SetControls(); }
    private void StopServer() { DropPhone("Server stopped"); server?.Dispose(); server=null; code.Text=settings.PairingCode; SetControls(); }
    protected override void OnFormClosing(FormClosingEventArgs e) { timer.Stop(); StopServer(); tray.Dispose(); foreach(var d in devices) d.Dispose(); enumerator.Dispose(); base.OnFormClosing(e); }
}
