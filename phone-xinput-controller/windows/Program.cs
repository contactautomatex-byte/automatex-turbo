using System.Buffers.Binary;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Text;
using System.Text.Json;
using Nefarius.ViGEm.Client;
using Nefarius.ViGEm.Client.Targets.Xbox360;

const int Port = 45990;
const int WatchdogMs = 150;
const int V2PacketSize = 32;

Console.Title = "Xbox Touch Receiver V2";
Console.WriteLine("============================================");
Console.WriteLine(" Xbox Touch Receiver V2 - XInput Xbox 360");
Console.WriteLine("============================================");
Console.WriteLine($"Porta UDP: {Port}");
Console.WriteLine("IPs deste PC:");
foreach (var ip in GetLocalIPv4()) Console.WriteLine($"  {ip}");
Console.WriteLine();
Console.WriteLine("Use o IP acima no celular. USB + Ancoragem USB oferece a menor latência.");
Console.WriteLine();

ViGEmClient client;
try { client = new ViGEmClient(); }
catch (Exception ex)
{
    Console.ForegroundColor = ConsoleColor.Red;
    Console.WriteLine("ViGEmBus não está instalado ou não pôde ser iniciado.");
    Console.WriteLine(ex.Message);
    Console.ResetColor();
    Console.ReadKey();
    return;
}

using (client)
{
    var controller = client.CreateXbox360Controller();
    controller.Connect();
    Neutralize(controller);

    Console.ForegroundColor = ConsoleColor.Green;
    Console.WriteLine("Controle virtual conectado: Xbox 360 Controller (XInput)");
    Console.ResetColor();
    Console.WriteLine("Aguardando o celular...");

    using var udp = new UdpClient(Port);
    udp.Client.ReceiveBufferSize = 1 << 20;

    IPEndPoint? lastRemote = null;
    long lastJsonTimestamp = 0;
    int lastV2Sequence = -1;
    bool alreadyNeutral = true;
    bool announcedV2 = false;

    while (true)
    {
        try
        {
            using var timeout = new CancellationTokenSource(WatchdogMs);
            UdpReceiveResult result;
            try { result = await udp.ReceiveAsync(timeout.Token); }
            catch (OperationCanceledException)
            {
                if (lastRemote != null && !alreadyNeutral)
                {
                    Neutralize(controller);
                    alreadyNeutral = true;
                    Console.WriteLine("Entrada neutralizada: conexão sem atualização.");
                }
                continue;
            }

            if (lastRemote == null || !lastRemote.Address.Equals(result.RemoteEndPoint.Address))
            {
                lastRemote = result.RemoteEndPoint;
                lastJsonTimestamp = 0;
                lastV2Sequence = -1;
                announcedV2 = false;
                Neutralize(controller);
                alreadyNeutral = true;
                Console.WriteLine($"Celular conectado: {lastRemote.Address}");
            }

            var data = result.Buffer;
            if (IsV2(data))
            {
                var span = data.AsSpan();
                int seq = BinaryPrimitives.ReadInt32LittleEndian(span.Slice(6, 4));
                if (seq <= lastV2Sequence) continue;
                lastV2Sequence = seq;

                short lx = BinaryPrimitives.ReadInt16LittleEndian(span.Slice(18, 2));
                short ly = BinaryPrimitives.ReadInt16LittleEndian(span.Slice(20, 2));
                short rx = BinaryPrimitives.ReadInt16LittleEndian(span.Slice(22, 2));
                short ry = BinaryPrimitives.ReadInt16LittleEndian(span.Slice(24, 2));
                byte lt = span[26];
                byte rt = span[27];
                uint buttons = BinaryPrimitives.ReadUInt32LittleEndian(span.Slice(28, 4));

                ApplyBinary(controller, lx, ly, rx, ry, lt, rt, buttons);
                alreadyNeutral = lx == 0 && ly == 0 && rx == 0 && ry == 0 && lt == 0 && rt == 0 && buttons == 0;
                if (!announcedV2)
                {
                    Console.WriteLine("Protocolo V2 binário ativo (event-driven / low latency).");
                    announcedV2 = true;
                }
                continue;
            }

            // Backwards compatibility with the V1 JSON APKs.
            var json = Encoding.UTF8.GetString(data);
            var s = JsonSerializer.Deserialize<GamepadState>(json);
            if (s == null) continue;
            if (s.ts != 0)
            {
                if (s.ts <= lastJsonTimestamp) continue;
                lastJsonTimestamp = s.ts;
            }
            ApplyState(controller, s);
            alreadyNeutral = IsNeutral(s);
        }
        catch (SocketException ex) { Console.WriteLine($"Rede: {ex.Message}"); }
        catch (JsonException) { }
        catch (Exception ex) { Console.WriteLine($"Erro: {ex.Message}"); }
    }
}

static bool IsV2(byte[] data) => data.Length >= V2PacketSize &&
    data[0] == (byte)'X' && data[1] == (byte)'P' && data[2] == (byte)'C' && data[3] == (byte)'2' && data[4] == 2;

static void ApplyBinary(Nefarius.ViGEm.Client.Targets.IXbox360Controller c,
    short lx, short ly, short rx, short ry, byte lt, byte rt, uint m)
{
    c.SetAxisValue(Xbox360Axis.LeftThumbX, lx);
    c.SetAxisValue(Xbox360Axis.LeftThumbY, ly);
    c.SetAxisValue(Xbox360Axis.RightThumbX, rx);
    c.SetAxisValue(Xbox360Axis.RightThumbY, ry);
    c.SetSliderValue(Xbox360Slider.LeftTrigger, lt);
    c.SetSliderValue(Xbox360Slider.RightTrigger, rt);

    Set(c, Xbox360Button.A, Bit(m, 0)); Set(c, Xbox360Button.B, Bit(m, 1));
    Set(c, Xbox360Button.X, Bit(m, 2)); Set(c, Xbox360Button.Y, Bit(m, 3));
    Set(c, Xbox360Button.LeftShoulder, Bit(m, 4)); Set(c, Xbox360Button.RightShoulder, Bit(m, 5));
    Set(c, Xbox360Button.LeftThumb, Bit(m, 6)); Set(c, Xbox360Button.RightThumb, Bit(m, 7));
    Set(c, Xbox360Button.Up, Bit(m, 8)); Set(c, Xbox360Button.Down, Bit(m, 9));
    Set(c, Xbox360Button.Left, Bit(m, 10)); Set(c, Xbox360Button.Right, Bit(m, 11));
    Set(c, Xbox360Button.Back, Bit(m, 12)); Set(c, Xbox360Button.Start, Bit(m, 13));
    Set(c, Xbox360Button.Guide, Bit(m, 14));
}

static bool Bit(uint m, int bit) => (m & (1u << bit)) != 0;

static void ApplyState(Nefarius.ViGEm.Client.Targets.IXbox360Controller controller, GamepadState s)
{
    controller.SetAxisValue(Xbox360Axis.LeftThumbX, ToAxis(s.lx));
    controller.SetAxisValue(Xbox360Axis.LeftThumbY, ToAxis(s.ly));
    controller.SetAxisValue(Xbox360Axis.RightThumbX, ToAxis(s.rx));
    controller.SetAxisValue(Xbox360Axis.RightThumbY, ToAxis(s.ry));
    controller.SetSliderValue(Xbox360Slider.LeftTrigger, ToTrigger(s.lt));
    controller.SetSliderValue(Xbox360Slider.RightTrigger, ToTrigger(s.rt));
    Set(controller, Xbox360Button.A, s.a); Set(controller, Xbox360Button.B, s.b);
    Set(controller, Xbox360Button.X, s.x); Set(controller, Xbox360Button.Y, s.y);
    Set(controller, Xbox360Button.LeftShoulder, s.lb); Set(controller, Xbox360Button.RightShoulder, s.rb);
    Set(controller, Xbox360Button.LeftThumb, s.l3); Set(controller, Xbox360Button.RightThumb, s.r3);
    Set(controller, Xbox360Button.Up, s.up); Set(controller, Xbox360Button.Down, s.down);
    Set(controller, Xbox360Button.Left, s.left); Set(controller, Xbox360Button.Right, s.right);
    Set(controller, Xbox360Button.Back, s.view); Set(controller, Xbox360Button.Start, s.menu);
    Set(controller, Xbox360Button.Guide, s.guide);
}

static void Neutralize(Nefarius.ViGEm.Client.Targets.IXbox360Controller controller)
{
    controller.SetAxisValue(Xbox360Axis.LeftThumbX, 0);
    controller.SetAxisValue(Xbox360Axis.LeftThumbY, 0);
    controller.SetAxisValue(Xbox360Axis.RightThumbX, 0);
    controller.SetAxisValue(Xbox360Axis.RightThumbY, 0);
    controller.SetSliderValue(Xbox360Slider.LeftTrigger, 0);
    controller.SetSliderValue(Xbox360Slider.RightTrigger, 0);
    foreach (var button in new[] {
        Xbox360Button.A, Xbox360Button.B, Xbox360Button.X, Xbox360Button.Y,
        Xbox360Button.LeftShoulder, Xbox360Button.RightShoulder,
        Xbox360Button.LeftThumb, Xbox360Button.RightThumb,
        Xbox360Button.Up, Xbox360Button.Down, Xbox360Button.Left, Xbox360Button.Right,
        Xbox360Button.Back, Xbox360Button.Start, Xbox360Button.Guide,
    }) Set(controller, button, false);
}

static bool IsNeutral(GamepadState s)
{
    const float e = 0.0001f;
    return Math.Abs(s.lx) < e && Math.Abs(s.ly) < e && Math.Abs(s.rx) < e && Math.Abs(s.ry) < e &&
           s.lt < e && s.rt < e && !s.a && !s.b && !s.x && !s.y && !s.lb && !s.rb && !s.l3 && !s.r3 &&
           !s.up && !s.down && !s.left && !s.right && !s.view && !s.menu && !s.guide;
}

static void Set(Nefarius.ViGEm.Client.Targets.IXbox360Controller c, Xbox360Button button, bool value)
    => c.SetButtonState(button, value);

static short ToAxis(float value)
{
    var v = Math.Clamp(value, -1f, 1f);
    return v >= 0 ? (short)Math.Round(v * short.MaxValue) : (short)Math.Round(v * -short.MinValue);
}

static byte ToTrigger(float value) => (byte)Math.Round(Math.Clamp(value, 0f, 1f) * byte.MaxValue);

static IEnumerable<IPAddress> GetLocalIPv4() => NetworkInterface.GetAllNetworkInterfaces()
    .Where(n => n.OperationalStatus == OperationalStatus.Up)
    .SelectMany(n => n.GetIPProperties().UnicastAddresses)
    .Select(u => u.Address)
    .Where(a => a.AddressFamily == AddressFamily.InterNetwork && !IPAddress.IsLoopback(a))
    .Distinct();

public sealed class GamepadState
{
    public float lx { get; set; } public float ly { get; set; } public float rx { get; set; } public float ry { get; set; }
    public float lt { get; set; } public float rt { get; set; }
    public bool a { get; set; } public bool b { get; set; } public bool x { get; set; } public bool y { get; set; }
    public bool lb { get; set; } public bool rb { get; set; } public bool l3 { get; set; } public bool r3 { get; set; }
    public bool up { get; set; } public bool down { get; set; } public bool left { get; set; } public bool right { get; set; }
    public bool view { get; set; } public bool menu { get; set; } public bool guide { get; set; }
    public long ts { get; set; }
}
