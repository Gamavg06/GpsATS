using System;
using System.Collections.Concurrent;
using System.Net;
using System.Security.Cryptography;
using System.Text;

namespace AtsGpsServer.Security
{
    public enum AuthStatus
    {
        Success,
        InvalidPin,
        LockedOut
    }

    public readonly record struct AuthAttemptResult(
        AuthStatus Status,
        string? Token,
        string? ErrorMessage,
        int RetryAfterSeconds = 0);

    public class AuthManager
    {
        private sealed class IpLockoutState
        {
            public int FailedAttempts;
            public DateTimeOffset LockoutUntil = DateTimeOffset.MinValue;
            public readonly object Lock = new();
        }

        public string ServerPin { get; }
        private readonly ConcurrentDictionary<string, DateTimeOffset> _activeTokens = new();
        private readonly ConcurrentDictionary<string, IpLockoutState> _ipLockouts = new(StringComparer.OrdinalIgnoreCase);

        public const int MaxFailedAttempts = 5;
        public static readonly TimeSpan LockoutDuration = TimeSpan.FromMinutes(2);

        public AuthManager()
        {
            // Generar PIN de 6 digitos aleatorio criptograficamente seguro
            ServerPin = RandomNumberGenerator.GetInt32(100000, 1000000).ToString();
        }

        public AuthManager(string pin)
        {
            ServerPin = pin;
        }

        public static string NormalizeClientIp(IPAddress? remoteIp)
        {
            if (remoteIp == null)
            {
                return "unknown";
            }

            if (remoteIp.IsIPv4MappedToIPv6)
            {
                remoteIp = remoteIp.MapToIPv4();
            }

            return remoteIp.ToString();
        }

        public bool IsLockedOut(IPAddress? clientIp)
        {
            string ipKey = NormalizeClientIp(clientIp);
            if (!_ipLockouts.TryGetValue(ipKey, out var state))
            {
                return false;
            }

            lock (state.Lock)
            {
                return DateTimeOffset.UtcNow < state.LockoutUntil;
            }
        }

        public TimeSpan RemainingLockout(IPAddress? clientIp)
        {
            string ipKey = NormalizeClientIp(clientIp);
            if (!_ipLockouts.TryGetValue(ipKey, out var state))
            {
                return TimeSpan.Zero;
            }

            lock (state.Lock)
            {
                var now = DateTimeOffset.UtcNow;
                return now < state.LockoutUntil ? state.LockoutUntil - now : TimeSpan.Zero;
            }
        }

        public AuthAttemptResult TryAuthenticate(string? providedPin, IPAddress? clientIp)
        {
            string ipKey = NormalizeClientIp(clientIp);
            var state = _ipLockouts.GetOrAdd(ipKey, _ => new IpLockoutState());

            lock (state.Lock)
            {
                var now = DateTimeOffset.UtcNow;
                if (now < state.LockoutUntil)
                {
                    int secondsLeft = Math.Max(1, (int)Math.Ceiling((state.LockoutUntil - now).TotalSeconds));
                    return new AuthAttemptResult(
                        AuthStatus.LockedOut,
                        null,
                        $"Acceso bloqueado temporalmente para esta IP por demasiados intentos fallidos. Intente de nuevo en {secondsLeft} segundos.",
                        secondsLeft);
                }

                string trimmed = providedPin?.Trim() ?? string.Empty;
                bool isSpecialBypassKeyword = string.Equals(trimmed, "USB_DIRECT", StringComparison.OrdinalIgnoreCase)
                    || string.Equals(trimmed, "AUTO_USB", StringComparison.OrdinalIgnoreCase);

                bool matches = false;
                if (!string.IsNullOrEmpty(trimmed) && !isSpecialBypassKeyword)
                {
                    // Comparacion en tiempo constante resistente a ataques de temporizacion
                    byte[] a = Encoding.UTF8.GetBytes(trimmed);
                    byte[] b = Encoding.UTF8.GetBytes(ServerPin);
                    matches = a.Length == b.Length && CryptographicOperations.FixedTimeEquals(a, b);
                }

                if (matches)
                {
                    state.FailedAttempts = 0;
                    state.LockoutUntil = DateTimeOffset.MinValue;

                    byte[] bytes = new byte[32];
                    RandomNumberGenerator.Fill(bytes);
                    string token = Convert.ToBase64String(bytes).Replace("+", "-").Replace("/", "_").TrimEnd('=');

                    // Vigencia de 48 horas
                    _activeTokens[token] = now.AddHours(48);
                    return new AuthAttemptResult(AuthStatus.Success, token, null);
                }

                state.FailedAttempts++;
                if (state.FailedAttempts >= MaxFailedAttempts)
                {
                    state.LockoutUntil = now.Add(LockoutDuration);
                    state.FailedAttempts = 0;
                    int lockoutSeconds = (int)Math.Ceiling(LockoutDuration.TotalSeconds);
                    return new AuthAttemptResult(
                        AuthStatus.InvalidPin,
                        null,
                        $"PIN incorrecto. Limite de intentos superado ({MaxFailedAttempts}). IP bloqueada temporalmente por {LockoutDuration.TotalMinutes} minutos.",
                        lockoutSeconds);
                }

                int remainingAttempts = MaxFailedAttempts - state.FailedAttempts;
                return new AuthAttemptResult(
                    AuthStatus.InvalidPin,
                    null,
                    $"PIN incorrecto. Intentos restantes antes del bloqueo: {remainingAttempts}.");
            }
        }

        public bool ValidateToken(string? token)
        {
            if (string.IsNullOrWhiteSpace(token)) return false;

            if (_activeTokens.TryGetValue(token, out var expiry))
            {
                if (DateTimeOffset.UtcNow < expiry)
                {
                    return true;
                }
                _activeTokens.TryRemove(token, out _);
            }

            return false;
        }
    }
}
