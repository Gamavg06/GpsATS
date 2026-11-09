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

        [DllImport("user32.dll")]
        private static extern uint MapVirtualKey(uint uCode, uint uMapType);

        [DllImport("user32.dll")]
        private static extern IntPtr GetForegroundWindow();

        [DllImport("user32.dll")]
        private static extern bool SetForegroundWindow(IntPtr hWnd);

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
                // Asegurar foco en amtrucks si está en ejecución
                EnsureGameFocus();

                ushort scan = binding.ScanCode > 0 ? binding.ScanCode : (ushort)MapVirtualKey(binding.Vk, 0);

                // DirectX / DirectInput requiere KEYEVENTF_SCANCODE con wScan válido
                INPUT[] down = new INPUT[1];
                down[0].type = INPUT_KEYBOARD;
                down[0].ki.wVk = binding.Vk;
                down[0].ki.wScan = scan;
                down[0].ki.dwFlags = KEYEVENTF_SCANCODE;
                SendInput(1, down, Marshal.SizeOf(typeof(INPUT)));

                // Duración de pulsación de 65 ms para garantizar captura por el bucle de físicas del juego
                Task.Run(async () =>
                {
                    await Task.Delay(65);
                    INPUT[] up = new INPUT[1];
                    up[0].type = INPUT_KEYBOARD;
                    up[0].ki.wVk = binding.Vk;
                    up[0].ki.wScan = scan;
                    up[0].ki.dwFlags = KEYEVENTF_SCANCODE | KEYEVENTF_KEYUP;
                    SendInput(1, up, Marshal.SizeOf(typeof(INPUT)));
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

        private static void EnsureGameFocus()
        {
            try
            {
                var currentFg = GetForegroundWindow();
                var atsProc = Process.GetProcessesByName("amtrucks").FirstOrDefault();
                if (atsProc != null && atsProc.MainWindowHandle != IntPtr.Zero && atsProc.MainWindowHandle != currentFg)
                {
                    SetForegroundWindow(atsProc.MainWindowHandle);
                }
            }
            catch { }
        }
    }
}
