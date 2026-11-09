using System.Runtime.InteropServices;

namespace AtsGpsServer.Input
{
    public class VirtualInputController
    {
        private const int INPUT_KEYBOARD = 1;
        private const uint KEYEVENTF_KEYUP = 0x0002;

        [StructLayout(LayoutKind.Sequential)]
        private struct KEYBDINPUT
        {
            public ushort wVk;
            public ushort wScan;
            public uint dwFlags;
            public uint time;
            public IntPtr dwExtraInfo;
        }

        [StructLayout(LayoutKind.Explicit)]
        private struct INPUT
        {
            [FieldOffset(0)]
            public int type;
            [FieldOffset(8)]
            public KEYBDINPUT ki;
        }

        [DllImport("user32.dll", SetLastError = true)]
        private static extern uint SendInput(uint nInputs, INPUT[] pInputs, int cbSize);

        // Mapeo exacto según las capturas del usuario
        private static readonly Dictionary<string, ushort> ActionKeyMap = new(StringComparer.OrdinalIgnoreCase)
        {
            { "handbrake", 0x43 },      // C - Freno de mano
            { "parkbrake", 0x20 },      // Space - Freno de estacionamiento
            { "engine", 0x45 },         // E - Encender/apagar motor
            { "wipers", 0x50 },         // P - Limpiaparabrisas
            { "blinker_left", 0xDB },   // [ (VK_OEM_4) - Intermitente Izquierdo
            { "blinker_right", 0xDD },  // ] (VK_OEM_6) - Intermitente Derecho
            { "lights_mode", 0x4C },    // L - Modos de luces
            { "lights_high", 0x4B },    // K - Luces de largo alcance
            { "hazard", 0x46 },         // F - Luces de emergencia / Intermitentes
            { "horn", 0x48 },           // H - Claxon
            { "airhorn", 0x4E },        // N - Bocina de aire
            { "beacon", 0x4F },         // O - Baliza / Luces rotativas de techo
            { "cabin_light", 0x4A },    // J - Luces interiores
            { "diff_lock", 0x56 },      // V - Bloqueo de diferencial
            { "lift_axle", 0x55 },      // U - Elevar o bajar ejes
            { "trailer", 0x54 },        // T - Enganche de remolque
            { "window_left_down", 0xBA },  // ; (VK_OEM_1)
            { "window_left_up", 0xDE },    // ' (VK_OEM_7)
            { "window_right_down", 0xBE }, // . (VK_OEM_PERIOD)
            { "window_right_up", 0xBC }    // , (VK_OEM_COMMA)
        };

        public bool TriggerAction(string action)
        {
            if (!ActionKeyMap.TryGetValue(action, out ushort vk))
            {
                return false;
            }

            try
            {
                // KeyDown
                INPUT[] downInput = new INPUT[1];
                downInput[0].type = INPUT_KEYBOARD;
                downInput[0].ki.wVk = vk;
                downInput[0].ki.dwFlags = 0;
                SendInput(1, downInput, Marshal.SizeOf(typeof(INPUT)));

                // KeyUp tras breve retardo en hilo de fondo para que el juego registre la pulsación completa
                Task.Run(async () =>
                {
                    await Task.Delay(40);
                    INPUT[] upInput = new INPUT[1];
                    upInput[0].type = INPUT_KEYBOARD;
                    upInput[0].ki.wVk = vk;
                    upInput[0].ki.dwFlags = KEYEVENTF_KEYUP;
                    SendInput(1, upInput, Marshal.SizeOf(typeof(INPUT)));
                });

                return true;
            }
            catch
            {
                return false;
            }
        }
    }
}
