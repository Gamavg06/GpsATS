using System;
using System.Collections.Concurrent;
using System.Security.Cryptography;

namespace AtsGpsServer.Security
{
    public class AuthManager
    {
        public string ServerPin { get; }
        private readonly ConcurrentDictionary<string, DateTimeOffset> _activeTokens = new();

        public AuthManager()
        {
            // Generar PIN de 6 dígitos aleatorio criptográficamente seguro
            ServerPin = RandomNumberGenerator.GetInt32(100000, 1000000).ToString();
        }

        public string? TryAuthenticate(string providedPin)
        {
            if (string.IsNullOrWhiteSpace(providedPin)) return null;

            if (providedPin.Trim() == ServerPin)
            {
                // Generar token de sesión
                byte[] bytes = new byte[32];
                RandomNumberGenerator.Fill(bytes);
                string token = Convert.ToBase64String(bytes).Replace("+", "-").Replace("/", "_").TrimEnd('=');
                
                // Vigencia de 48 horas
                _activeTokens[token] = DateTimeOffset.UtcNow.AddHours(48);
                return token;
            }

            return null;
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
