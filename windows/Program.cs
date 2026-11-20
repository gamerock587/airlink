namespace AirLink;

internal static class Program
{
    [STAThread]
    static void Main(string[] args)
    {
        ApplicationConfiguration.Initialize();
        using var instance=new Mutex(true,@"Local\AirLink.Windows.Server",out bool firstInstance);
        if(!firstInstance) { MessageBox.Show("AirLink is already running. Open it from the system tray.","AirLink"); return; }
        var form = new MainForm();
        if(args.Contains("--start-server")) form.Shown+=(_,_)=>form.StartServer();
        if(args.Length == 2 && args[0] == "--smoke-test")
        {
            form.Shown += (_, _) => form.BeginInvoke(() =>
            {
                IEnumerable<Control> Descendants(Control parent) => parent.Controls.Cast<Control>().SelectMany(c => new[] { c }.Concat(Descendants(c)));
                File.WriteAllLines(Path.GetFullPath(args[1])+".state.txt", Descendants(form).OfType<ComboBox>().Select(c => $"Items={c.Items.Count}; SelectedIndex={c.SelectedIndex}; Text={c.Text}; SelectedItem={c.SelectedItem}"));
                using var bitmap = new System.Drawing.Bitmap(form.Width, form.Height);
                form.DrawToBitmap(bitmap, new Rectangle(Point.Empty, form.Size));
                bitmap.Save(Path.GetFullPath(args[1]), System.Drawing.Imaging.ImageFormat.Png);
                form.Close();
            });
        }
        Application.Run(form);
    }
}
