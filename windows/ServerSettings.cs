using System.Security.Cryptography;

namespace AirLink;

public sealed class ServerSettings
{
    private readonly string folder;
    public string Id { get; }
    public string PairingCode { get; }
    public string? OutputId => File.Exists(Path.Combine(folder,"output-id.txt")) ? File.ReadAllText(Path.Combine(folder,"output-id.txt")).Trim() : null;

    public ServerSettings(string? directory=null)
    {
        folder=directory ?? Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),"AirLink");
        Directory.CreateDirectory(folder);
        Id=ReadOrCreate("server-id.txt",()=>Guid.NewGuid().ToString("N"));
        PairingCode=ReadOrCreate("pairing-code.txt",()=>RandomNumberGenerator.GetInt32(1000000).ToString("D6"));
        if(PairingCode.Length!=6 || !PairingCode.All(char.IsAsciiDigit))
            throw new InvalidDataException("Saved pairing code must contain exactly six digits: "+Path.Combine(folder,"pairing-code.txt"));
    }

    private string ReadOrCreate(string name,Func<string> create)
    {
        string path=Path.Combine(folder,name);
        if(!File.Exists(path))
        {
            string temporary=path+"."+Guid.NewGuid().ToString("N")+".tmp";
            try
            {
                File.WriteAllText(temporary,create());
                try { File.Move(temporary,path); }
                catch(IOException) when(File.Exists(path)) { }
            }
            finally { if(File.Exists(temporary)) File.Delete(temporary); }
        }
        return File.ReadAllText(path).Trim();
    }

    public void SaveOutput(string id)
    {
        string path=Path.Combine(folder,"output-id.txt"), temporary=path+"."+Guid.NewGuid().ToString("N")+".tmp";
        try { File.WriteAllText(temporary,id); File.Move(temporary,path,true); }
        finally { if(File.Exists(temporary)) File.Delete(temporary); }
    }
}
