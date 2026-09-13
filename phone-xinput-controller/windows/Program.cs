using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Text;
using System.Text.Json;
using Nefarius.ViGEm.Client;
using Nefarius.ViGEm.Client.Targets.Xbox360;

const int Port = 45990;

Console.Title = "Xbox Phone Receiver";
Console.WriteLine("============================================");
Console.WriteLine(" Xbox Phone Receiver - XInput virtual Xbox 360");
Console.WriteLine("============================================");
Console.WriteLine($"Porta UDP: {Port}");
Console.WriteLine("IPs deste PC:");
foreach (var ip in GetLocalIPv4()) Console.WriteLine($"  {ip}");
Console.WriteLine();
Console.WriteLine("No celular, use o IP acima. Para menor latência, prefira Ancoragem USB.");
Console.WriteLine();

ViGEmClient client;
try
{
    client = new ViGEmClient();
}
catch (Exception ex)
{
    Console.ForegroundColor = ConsoleColor.Red;
    Console.WriteLine("ViGEmBus não está instalado ou não pôde ser iniciado.");
    Console.WriteLine(ex.Message);
    Console.ResetColor();
    Console.WriteLine("Instale o ViGEmBus e execute novamente.");
    Console.ReadKey();
    return;
}

using (client)
{
    var controller = client.CreateXbox360Controller();
    controller.Connect();
    Console.ForegroundColor = ConsoleColor.Green;
    Console.WriteLine("Controle virtual conectado: Xbox 360 Controller (XInput)");
    Console.ResetColor();
    Console.WriteLine("Aguardando o celular...");

    using var udp = new UdpClient(Port);
    udp.Client.ReceiveBufferSize = 1 << 20;
    IPEndPoint? lastRemote = null;

    while (true)
    {
        try
        {
            var result = await udp.ReceiveAsync();
            if (lastRemote == null || !lastRemote.Address.Equals(result.RemoteEndPoint.Address))
            {
                lastRemote = result.RemoteEndPoint;
                Console.WriteLine($"Celular conectado: {lastRemote.Address}");
            }

            var json = Encoding.UTF8.GetString(result.Buffer);
            var s = JsonSerializer.Deserialize<GamepadState>(json);
            if (s == null) continue;

            controller.SetAxisValue(Xbox360Axis.LeftThumbX, ToAxis(s.lx));
            controller.SetAxisValue(Xbox360Axis.LeftThumbY, ToAxis(s.ly));
            controller.SetAxisValue(Xbox360Axis.RightThumbX, ToAxis(s.rx));
            controller.SetAxisValue(Xbox360Axis.RightThumbY, ToAxis(s.ry));
            controller.SetSliderValue(Xbox360Slider.LeftTrigger, ToTrigger(s.lt));
            controller.SetSliderValue(Xbox360Slider.RightTrigger, ToTrigger(s.rt));

            Set(controller, Xbox360Button.A, s.a);
            Set(controller, Xbox360Button.B, s.b);
            Set(controller, Xbox360Button.X, s.x);
            Set(controller, Xbox360Button.Y, s.y);
            Set(controller, Xbox360Button.LeftShoulder, s.lb);
            Set(controller, Xbox360Button.RightShoulder, s.rb);
            Set(controller, Xbox360Button.LeftThumb, s.l3);
            Set(controller, Xbox360Button.RightThumb, s.r3);
            Set(controller, Xbox360Button.Up, s.up);
            Set(controller, Xbox360Button.Down, s.down);
            Set(controller, Xbox360Button.Left, s.left);
            Set(controller, Xbox360Button.Right, s.right);
            Set(controller, Xbox360Button.Back, s.view);
            Set(controller, Xbox360Button.Start, s.menu);
            Set(controller, Xbox360Button.Guide, s.guide);
        }
        catch (SocketException ex)
        {
            Console.WriteLine($"Rede: {ex.Message}");
        }
        catch (JsonException)
        {
            // Ignore malformed/transient packet.
        }
        catch (Exception ex)
        {
            Console.WriteLine($"Erro: {ex.Message}");
        }
    }
}

static void Set(Nefarius.ViGEm.Client.Targets.IXbox360Controller c, Xbox360Button button, bool value)
    => c.SetButtonState(button, value);

static short ToAxis(float value)
{
    var v = Math.Clamp(value, -1f, 1f);
    return v >= 0
        ? (short)Math.Round(v * short.MaxValue)
        : (short)Math.Round(v * -short.MinValue);
}

static byte ToTrigger(float value)
    => (byte)Math.Round(Math.Clamp(value, 0f, 1f) * byte.MaxValue);

static IEnumerable<IPAddress> GetLocalIPv4()
{
    return NetworkInterface.GetAllNetworkInterfaces()
        .Where(n => n.OperationalStatus == OperationalStatus.Up)
        .SelectMany(n => n.GetIPProperties().UnicastAddresses)
        .Select(u => u.Address)
        .Where(a => a.AddressFamily == AddressFamily.InterNetwork && !IPAddress.IsLoopback(a))
        .Distinct();
}

public sealed class GamepadState
{
    public float lx { get; set; }
    public float ly { get; set; }
    public float rx { get; set; }
    public float ry { get; set; }
    public float lt { get; set; }
    public float rt { get; set; }
    public bool a { get; set; }
    public bool b { get; set; }
    public bool x { get; set; }
    public bool y { get; set; }
    public bool lb { get; set; }
    public bool rb { get; set; }
    public bool l3 { get; set; }
    public bool r3 { get; set; }
    public bool up { get; set; }
    public bool down { get; set; }
    public bool left { get; set; }
    public bool right { get; set; }
    public bool view { get; set; }
    public bool menu { get; set; }
    public bool guide { get; set; }
}
