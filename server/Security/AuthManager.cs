using System;
using System.Collections.Concurrent;
using System.Security.Cryptography;
using System.Text;

namespace AtsGpsServer.Security
{
    public class AuthManager
    {
        public string ServerPin { get; }
        private readonly ConcurrentDictionary<string, DateTimeOffset> _activeTokens = new();

        // Control de fuerza bruta y bloqueo temporal
        private int _failedAttempts;
        private DateTimeOffset _lockoutUntil = DateTimeOffset.MinValue;
        private readonly object _authLock = new();

        public const int MaxFailedAttempts = 5;
        public static readonly TimeSpan LockoutDuration = TimeSpan.FromMinutes(2);

        public bool IsLockedOut => DateTimeOffset.UtcNow < _lockoutUntil;
        public TimeSpan RemainingLockout => IsLockedOut ? _lockoutUntil - DateTimeOffset.UtcNow : TimeSpan.Zero;

        public AuthManager()
        {
            // Generar PIN de 6 dígitos aleatorio criptográficamente seguro
            ServerPin = RandomNumberGenerator.GetInt32(100000, 1000000).ToString();
        }

        public AuthManager(string pin)
        {
            ServerPin = pin;
        }

        public (bool success, string? token, string? errorMessage) TryAuthenticate(string providedPin)
        {
            lock (_authLock)
            {
                if (IsLockedOut)
                {
                    int secondsLeft = (int)Math.Ceiling(RemainingLockout.TotalSeconds);
                    return (false, null, $"Acceso bloqueado por demasiados intentos fallidos. Intente de nuevo en {secondsLeft} segundos.");
                }

                if (string.IsNullOrWhiteSpace(providedPin))
                {
                    return (false, null, "El PIN no puede estar vacío.");
                }

                // Comparación en tiempo constante resistente a ataques de temporización (timing attacks)
                byte[] a = Encoding.UTF8.GetBytes(providedPin.Trim());
                byte[] b = Encoding.UTF8.GetBytes(ServerPin);
                bool matches = a.Length == b.Length && CryptographicOperations.FixedTimeEquals(a, b);

                if (matches)
                {
                    // Reiniciar contador de fallos
                    _failedAttempts = 0;

                    // Generar token criptográfico de sesión
                    byte[] bytes = new byte[32];
                    RandomNumberGenerator.Fill(bytes);
                    string token = Convert.ToBase64String(bytes).Replace("+", "-").Replace("/", "_").TrimEnd('=');

                    // Vigencia de 48 horas
                    _activeTokens[token] = DateTimeOffset.UtcNow.AddHours(48);
                    return (true, token, null);
                }
                else
                {
                    _failedAttempts++;
                    if (_failedAttempts >= MaxFailedAttempts)
                    {
                        _lockoutUntil = DateTimeOffset.UtcNow.Add(LockoutDuration);
                        _failedAttempts = 0;
                        return (false, null, $"Límite de intentos superado ({MaxFailedAttempts}). Servidor bloqueado temporalmente por {LockoutDuration.TotalMinutes} minutos.");
                    }

                    int remainingAttempts = MaxFailedAttempts - _failedAttempts;
                    return (false, null, $"PIN incorrecto. Intentos restantes antes del bloqueo: {remainingAttempts}.");
                }
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
