using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Linq;
using System.Runtime.InteropServices;
using System.Threading.Tasks;

namespace AtsGpsServer.Input
{
    public class VirtualInputController
    {
        private const int INPUT_KEYBOARD = 1;
        private const uint KEYEVENTF_EXTENDEDKEY = 0x0001;
        private const uint KEYEVENTF_KEYUP = 0x0002;
        private const uint KEYEVENTF_SCANCODE = 0x0008;

        private const uint WM_KEYDOWN = 0x0100;
        private const uint WM_KEYUP = 0x0101;

        [StructLayout(LayoutKind.Sequential)]
        private struct MOUSEINPUT
        {
            public int dx;
            public int dy;
            public uint mouseData;
            public uint dwFlags;
            public uint time;
            public IntPtr dwExtraInfo;
        }

        [StructLayout(LayoutKind.Sequential)]
        private struct KEYBDINPUT
        {
            public ushort wVk;
            public ushort wScan;
            public uint dwFlags;
            public uint time;
            public IntPtr dwExtraInfo;
        }

        [StructLayout(LayoutKind.Sequential)]
        private struct HARDWAREINPUT
        {
            public uint uMsg;
            public ushort wParamL;
            public ushort wParamH;
        }

        [StructLayout(LayoutKind.Explicit)]
        private struct InputUnion
        {
            [FieldOffset(0)]
            public MOUSEINPUT mi;
            [FieldOffset(0)]
            public KEYBDINPUT ki;
            [FieldOffset(0)]
            public HARDWAREINPUT hi;
        }

        [StructLayout(LayoutKind.Sequential)]
        private struct INPUT
        {
            public int type;
            public InputUnion u;
        }

        internal static int GetInputStructSize() => Marshal.SizeOf<INPUT>();

        [DllImport("user32.dll", SetLastError = true)]
        private static extern uint SendInput(uint nInputs, INPUT[] pInputs, int cbSize);

        [DllImport("user32.dll")]
        private static extern uint MapVirtualKey(uint uCode, uint uMapType);

        [DllImport("user32.dll")]
        private static extern IntPtr GetForegroundWindow();

        [DllImport("user32.dll")]
        private static extern bool SetForegroundWindow(IntPtr hWnd);

        [DllImport("user32.dll")]
        private static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint lpdwProcessId);

        [DllImport("user32.dll")]
        private static extern bool AttachThreadInput(uint idAttach, uint idAttachTo, bool fAttach);

        [DllImport("kernel32.dll")]
        private static extern uint GetCurrentThreadId();

        [DllImport("user32.dll")]
        private static extern bool PostMessage(IntPtr hWnd, uint Msg, IntPtr wParam, IntPtr lParam);

        // Mapeo con Códigos Virtuales (VK) y Scancodes de Hardware DirectX (DIK)
        // para compatibilidad total con el motor SCS Software (amtrucks.exe)
        private struct KeyBinding
        {
            public ushort Vk;
            public ushort ScanCode;

            public KeyBinding(ushort vk, ushort scanCode)
            {
                Vk = vk;
                ScanCode = scanCode;
            }
        }

        private static readonly Dictionary<string, KeyBinding> ActionKeyMap = new(StringComparer.OrdinalIgnoreCase)
        {
            // Conducción y frenos (Configurados en ATS por el usuario)
            { "handbrake", new KeyBinding(0x43, 0x2E) },          // C - Freno de mano (DIK_C)
            { "parking_brake", new KeyBinding(0x20, 0x39) },     // Espacio - Freno de estacionamiento (DIK_SPACE)
            { "parkbrake", new KeyBinding(0x20, 0x39) },         // Alias espacio
            { "engine", new KeyBinding(0x45, 0x12) },            // E - Prender/apagar motor (DIK_E)
            { "wipers", new KeyBinding(0x50, 0x19) },            // P - Limpiaparabrisas (DIK_P)

            // Luces y señales
            { "blinker_left", new KeyBinding(0xDB, 0x1A) },      // [ - Intermitente Izquierdo (DIK_LBRACKET)
            { "blinker_right", new KeyBinding(0xDD, 0x1B) },     // ] - Intermitente Derecho (DIK_RBRACKET)
            { "lights_mode", new KeyBinding(0x4C, 0x26) },       // L - Modos de luces (DIK_L)
            { "lights_high", new KeyBinding(0x4B, 0x25) },       // K - Luces de largo alcance (DIK_K)
            { "hazard", new KeyBinding(0x46, 0x21) },            // F - Luces intermitentes / emergencia (DIK_F)
            { "horn", new KeyBinding(0x48, 0x23) },              // H - Claxon (DIK_H)
            { "airhorn", new KeyBinding(0x4E, 0x31) },           // N - Bocina de aire (DIK_N)
            { "beacon", new KeyBinding(0x4F, 0x18) },            // O - Baliza / Luces rotativas (DIK_O)
            { "cabin_light", new KeyBinding(0x4A, 0x24) },       // J - Luces interiores (DIK_J)
            { "diff_lock", new KeyBinding(0x56, 0x2F) },         // V - Bloqueo de diferencial (DIK_V)
            { "lift_axle", new KeyBinding(0x55, 0x16) },         // U - Elevar o bajar ejes (DIK_U)
            { "trailer", new KeyBinding(0x54, 0x14) },           // T - Enganche remolque (DIK_T)

            // Ventanillas
            { "window_right_down", new KeyBinding(0xBE, 0x34) }, // . - Bajar ventanilla derecha
            { "window_right_up", new KeyBinding(0xBC, 0x33) },   // , - Subir ventanilla derecha
            { "window_left_down", new KeyBinding(0xBA, 0x27) },  // ; - Bajar ventanilla izquierda
            { "window_left_up", new KeyBinding(0xDE, 0x28) }     // ' - Subir ventanilla izquierda
        };

        public bool TriggerAction(string action)
        {
            if (!ActionKeyMap.TryGetValue(action, out var binding))
            {
                Console.WriteLine($"[CONTROL REMOTO] Acción desconocida: '{action}'");
                return false;
            }

            try
            {
                var processes = Process.GetProcessesByName("amtrucks");
                IntPtr atsHwnd = IntPtr.Zero;
                try
                {
                    atsHwnd = processes.Select(p => p.MainWindowHandle).FirstOrDefault(h => h != IntPtr.Zero);
                }
                finally
                {
                    foreach (var proc in processes)
                    {
                        proc.Dispose();
                    }
                }

                if (atsHwnd == IntPtr.Zero)
                {
                    Console.WriteLine($"[CONTROL REMOTO] ATS no está ejecutándose o no tiene ventana activa; acción '{action}' ignorada.");
                    return false;
                }

                // Asegurar foco activo en ATS
                EnsureGameFocus(atsHwnd);

                ushort scan = binding.ScanCode > 0 ? binding.ScanCode : (ushort)MapVirtualKey(binding.Vk, 0);

                // 1. DirectInput / RawInput hardware scan code:
                // Con KEYEVENTF_SCANCODE, wVk DEBE ser 0 estrictamente para evitar descarte por DirectInput
                INPUT[] down = new INPUT[1];
                down[0].type = INPUT_KEYBOARD;
                down[0].u.ki.wVk = 0;
                down[0].u.ki.wScan = scan;
                down[0].u.ki.dwFlags = KEYEVENTF_SCANCODE;
                uint sentDown = SendInput(1, down, Marshal.SizeOf<INPUT>());
                if (sentDown == 0)
                {
                    int win32Error = Marshal.GetLastWin32Error();
                    Console.WriteLine($"[CONTROL REMOTO] SendInput (key down) fallo con error Win32 {win32Error} para '{action}'.");
                    return false;
                }

                // 2. PostMessage directo a la ventana de ATS como redundancia
                IntPtr downLParam = (IntPtr)((scan << 16) | 1);
                PostMessage(atsHwnd, WM_KEYDOWN, (IntPtr)binding.Vk, downLParam);

                // Mantener pulsado 85 ms (tiempo estándar de frame para captura en simulación de física a 60 fps)
                _ = Task.Run(async () =>
                {
                    try
                    {
                        await Task.Delay(85);
                        INPUT[] up = new INPUT[1];
                        up[0].type = INPUT_KEYBOARD;
                        up[0].u.ki.wVk = 0;
                        up[0].u.ki.wScan = scan;
                        up[0].u.ki.dwFlags = KEYEVENTF_SCANCODE | KEYEVENTF_KEYUP;
                        uint sentUp = SendInput(1, up, Marshal.SizeOf<INPUT>());
                        if (sentUp == 0)
                        {
                            int win32Error = Marshal.GetLastWin32Error();
                            Console.WriteLine($"[CONTROL REMOTO] SendInput (key up) fallo con error Win32 {win32Error} para '{action}'.");
                        }

                        IntPtr upLParam = (IntPtr)(0xC0000001L | ((long)scan << 16));
                        PostMessage(atsHwnd, WM_KEYUP, (IntPtr)binding.Vk, upLParam);
                    }
                    catch (Exception ex)
                    {
                        Console.WriteLine($"[CONTROL REMOTO] Error liberando tecla: {ex.Message}");
                    }
                });

                Console.WriteLine($"[CONTROL REMOTO] Tecla enviada a ATS: {action} (VK: 0x{binding.Vk:X2}, Scan: 0x{scan:X2})");
                return true;
            }
            catch (Exception ex)
            {
                Console.WriteLine($"[CONTROL REMOTO] Error enviando tecla: {ex.Message}");
                return false;
            }
        }

        private static void EnsureGameFocus(IntPtr atsHwnd)
        {
            try
            {
                if (atsHwnd == IntPtr.Zero) return;
                var currentFg = GetForegroundWindow();
                if (atsHwnd != currentFg)
                {
                    uint fgThread = currentFg != IntPtr.Zero ? GetWindowThreadProcessId(currentFg, out _) : 0;
                    uint currentThread = GetCurrentThreadId();
                    bool attached = false;
                    if (fgThread != 0 && fgThread != currentThread)
                    {
                        attached = AttachThreadInput(currentThread, fgThread, true);
                    }
                    try
                    {
                        SetForegroundWindow(atsHwnd);
                    }
                    finally
                    {
                        if (attached)
                        {
                            AttachThreadInput(currentThread, fgThread, false);
                        }
                    }
                }
            }
            catch { }
        }
    }
}
