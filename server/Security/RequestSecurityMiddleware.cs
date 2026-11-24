using System;
using System.Net;
using System.Net.Sockets;
using System.Threading.Tasks;
using Microsoft.AspNetCore.Http;

namespace AtsGpsServer.Security
{
    public class RequestSecurityMiddleware
    {
        private readonly RequestDelegate _next;

        public RequestSecurityMiddleware(RequestDelegate next)
        {
            _next = next;
        }

        public async Task InvokeAsync(HttpContext context)
        {
            if (context.Request.Headers.ContainsKey("Origin"))
            {
                await WriteForbiddenJsonAsync(context, "Acceso denegado: el encabezado Origin no esta permitido.");
                return;
            }

            if (!IsHostAllowed(context.Request.Headers))
            {
                await WriteForbiddenJsonAsync(context, "Acceso denegado: encabezado Host invalido o no autorizado.");
                return;
            }

            await _next(context);
        }

        public static bool IsHostAllowed(IHeaderDictionary headers)
        {
            if (!headers.TryGetValue("Host", out var hostValues) || hostValues.Count != 1)
            {
                return false;
            }

            return IsHostValueAllowed(hostValues[0]);
        }

        public static bool IsHostValueAllowed(string? rawHostHeader)
        {
            if (string.IsNullOrWhiteSpace(rawHostHeader))
            {
                return false;
            }

            string raw = rawHostHeader.Trim();
            if (!TryExtractHost(raw, out string host, out bool wasBracketed))
            {
                return false;
            }

            if (string.Equals(host, "localhost", StringComparison.OrdinalIgnoreCase))
            {
                return !wasBracketed;
            }

            if (!IPAddress.TryParse(host, out var ip))
            {
                return false;
            }

            if (wasBracketed && ip.AddressFamily != AddressFamily.InterNetworkV6)
            {
                return false;
            }

            if (ip.IsIPv4MappedToIPv6)
            {
                ip = ip.MapToIPv4();
            }

            return IsAllowedHostIp(ip);
        }

        public static bool TryExtractHost(string rawHost, out string host, out bool wasBracketed)
        {
            host = string.Empty;
            wasBracketed = false;

            if (string.IsNullOrWhiteSpace(rawHost))
            {
                return false;
            }

            if (rawHost.StartsWith('['))
            {
                wasBracketed = true;
                int closeIdx = rawHost.IndexOf(']');
                if (closeIdx <= 1)
                {
                    return false;
                }

                host = rawHost.Substring(1, closeIdx - 1);
                string remainder = rawHost.Substring(closeIdx + 1);
                if (remainder.Length == 0)
                {
                    return true;
                }

                return remainder.StartsWith(':') && IsValidPort(remainder.AsSpan(1));
            }

            int firstColon = rawHost.IndexOf(':');
            if (firstColon < 0)
            {
                host = rawHost;
                return true;
            }

            int lastColon = rawHost.LastIndexOf(':');
            if (firstColon == lastColon)
            {
                if (firstColon == 0)
                {
                    return false;
                }

                var portSpan = rawHost.AsSpan(firstColon + 1);
                if (!IsValidPort(portSpan))
                {
                    return false;
                }

                host = rawHost.Substring(0, firstColon);
                return host.Length > 0;
            }

            // Multiple colons without brackets: only valid as a bare IPv6 literal without port
            host = rawHost;
            return true;
        }

        private static bool IsValidPort(ReadOnlySpan<char> portSpan)
        {
            if (portSpan.IsEmpty)
            {
                return false;
            }

            foreach (char c in portSpan)
            {
                if (c < '0' || c > '9')
                {
                    return false;
                }
            }

            return int.TryParse(portSpan, out int port) && port >= 1 && port <= 65535;
        }

        private static bool IsAllowedHostIp(IPAddress ip)
        {
            if (IPAddress.IsLoopback(ip))
            {
                return true;
            }

            if (ip.AddressFamily == AddressFamily.InterNetwork)
            {
                byte[] bytes = ip.GetAddressBytes();
                byte b0 = bytes[0];
                byte b1 = bytes[1];

                // 10.0.0.0/8
                if (b0 == 10) return true;

                // 172.16.0.0/12
                if (b0 == 172 && b1 >= 16 && b1 <= 31) return true;

                // 192.168.0.0/16
                if (b0 == 192 && b1 == 168) return true;

                return false;
            }

            if (ip.AddressFamily == AddressFamily.InterNetworkV6)
            {
                byte[] bytes = ip.GetAddressBytes();

                // IPv6 link-local (fe80::/10)
                if (ip.IsIPv6LinkLocal || (bytes[0] == 0xFE && (bytes[1] & 0xC0) == 0x80))
                {
                    return true;
                }

                // IPv6 Unique Local Address (fc00::/7)
                if ((bytes[0] & 0xFE) == 0xFC)
                {
                    return true;
                }
            }

            return false;
        }

        private static async Task WriteForbiddenJsonAsync(HttpContext context, string message)
        {
            context.Response.StatusCode = StatusCodes.Status403Forbidden;
            context.Response.ContentType = "application/json";
            await context.Response.WriteAsJsonAsync(new { error = message });
        }
    }
}
