using System;
using System.Collections.Generic;
using System.IO;
using System.Text.RegularExpressions;
using Microsoft.Win32;

namespace AtsGpsServer.Map
{
    /// <summary>
    /// Portable detector for the local American Truck Simulator installation directory.
    /// </summary>
    public static class AtsPathDetector
    {
        private const string GameSubPath = @"steamapps\common\American Truck Simulator";

        public static string DetectAtsDirectory()
        {
            var candidates = new List<string>();

            try
            {
                if (OperatingSystem.IsWindows())
                {
                    AddRegistryCandidates(candidates, Registry.CurrentUser, @"Software\Valve\Steam", "SteamPath");
                    AddRegistryCandidates(candidates, Registry.LocalMachine, @"SOFTWARE\WOW6432Node\Valve\Steam", "InstallPath");
                    AddRegistryCandidates(candidates, Registry.LocalMachine, @"SOFTWARE\Valve\Steam", "InstallPath");
                }
            }
            catch
            {
                // Ignore registry access failures and continue with drive fallbacks
            }

            string progFilesX86 = Environment.GetFolderPath(Environment.SpecialFolder.ProgramFilesX86);
            string progFiles = Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles);
            if (!string.IsNullOrEmpty(progFilesX86))
            {
                candidates.Add(Path.Combine(progFilesX86, "Steam", GameSubPath));
            }
            if (!string.IsNullOrEmpty(progFiles))
            {
                candidates.Add(Path.Combine(progFiles, "Steam", GameSubPath));
            }

            foreach (char drive in new[] { 'C', 'D', 'E', 'F' })
            {
                candidates.Add($@"{drive}:\SteamLibrary\{GameSubPath}");
                candidates.Add($@"{drive}:\Steam\{GameSubPath}");
                candidates.Add($@"{drive}:\Games\Steam\{GameSubPath}");
            }

            string? fallbackExisting = null;
            var seen = new HashSet<string>(StringComparer.OrdinalIgnoreCase);

            foreach (string rawCandidate in candidates)
            {
                if (string.IsNullOrWhiteSpace(rawCandidate)) continue;
                string candidate = rawCandidate.Replace('/', '\\').TrimEnd('\\');
                if (!seen.Add(candidate)) continue;

                if (Directory.Exists(candidate))
                {
                    if (File.Exists(Path.Combine(candidate, "base_map.scs")) ||
                        File.Exists(Path.Combine(candidate, "base.scs")) ||
                        Directory.Exists(Path.Combine(candidate, "bin")))
                    {
                        return candidate;
                    }
                    fallbackExisting ??= candidate;
                }
            }

            if (fallbackExisting != null)
            {
                return fallbackExisting;
            }

            return !string.IsNullOrEmpty(progFilesX86)
                ? Path.Combine(progFilesX86, "Steam", GameSubPath)
                : @"C:\Program Files (x86)\Steam\steamapps\common\American Truck Simulator";
        }

        private static void AddRegistryCandidates(List<string> candidates, RegistryKey root, string subKeyPath, string valueName)
        {
            using RegistryKey? key = root.OpenSubKey(subKeyPath);
            string? steamPath = key?.GetValue(valueName) as string;
            if (string.IsNullOrWhiteSpace(steamPath)) return;

            steamPath = steamPath.Replace('/', '\\').TrimEnd('\\');
            candidates.Add(Path.Combine(steamPath, GameSubPath));

            string vdfPath = Path.Combine(steamPath, "steamapps", "libraryfolders.vdf");
            if (!File.Exists(vdfPath)) return;

            try
            {
                string content = File.ReadAllText(vdfPath);
                foreach (Match match in Regex.Matches(content, "\"path\"\\s+\"([^\"]+)\""))
                {
                    string libPath = match.Groups[1].Value.Replace("\\\\", "\\").Replace('/', '\\').TrimEnd('\\');
                    if (!string.IsNullOrWhiteSpace(libPath))
                    {
                        candidates.Add(Path.Combine(libPath, GameSubPath));
                    }
                }
            }
            catch
            {
                // Ignore malformed or unreadable libraryfolders.vdf
            }
        }
    }
}
