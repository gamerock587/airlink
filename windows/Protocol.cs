using System.Buffers.Binary;
using System.Security.Cryptography;
using System.Text;

namespace AirLink;

public static class Protocol
{
    public const int DiscoveryPort = 45832, AudioPort = 45833;
    public const int Frames = 240, PcmBytes = Frames * 4, PacketBytes = 12 + PcmBytes + 16;
    public static byte[] DeriveKey(string code, string deviceId, string session) =>
        Rfc2898DeriveBytes.Pbkdf2(code, Encoding.UTF8.GetBytes("AirLink/1/" + deviceId + "/" + session), 100000, HashAlgorithmName.SHA256, 32);
    public static string Proof(byte[] key, string message) => Convert.ToHexString(HMACSHA256.HashData(key, Encoding.UTF8.GetBytes(message))).ToLowerInvariant();
    public static byte[] Encrypt(AesGcm aes, byte[] session, ulong sequence, ReadOnlySpan<byte> pcm)
    {
        if (pcm.Length != PcmBytes) throw new ArgumentException("A packet must contain exactly 240 stereo frames.");
        byte[] packet = new byte[PacketBytes];
        "ALN1"u8.CopyTo(packet);
        BinaryPrimitives.WriteUInt64LittleEndian(packet.AsSpan(4), sequence);
        Span<byte> nonce = stackalloc byte[12];
        session.AsSpan(0, 4).CopyTo(nonce);
        packet.AsSpan(4, 8).CopyTo(nonce[4..]);
        aes.Encrypt(nonce, pcm, packet.AsSpan(12, PcmBytes), packet.AsSpan(12 + PcmBytes, 16), packet.AsSpan(0, 12));
        return packet;
    }
}
