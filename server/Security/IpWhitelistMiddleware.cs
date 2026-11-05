using System.Net;
using System.Net.Sockets;
using Microsoft.AspNetCore.Http;

namespace AtsGpsServer.Security
{
    public class IpWhitelistMiddleware
    {
        private readonly RequestDelegate _next;

        public IpWhitelistMiddleware(RequestDelegate next)
        {
            _next = next;
        }

        public async Task InvokeAsync(HttpContext context)
        {
            var remoteIp = context.Connection.RemoteIpAddress;

            if (remoteIp == null)
            {
                context.Response.StatusCode = (int)HttpStatusCode.Forbidden;
                await context.Response.WriteAsync("Forbidden: Unknown remote client.");
                return;
            }

            // Normalizar IPv4 mapeada en IPv6 (ej. ::ffff:192.168.1.50)
            if (remoteIp.IsIPv4MappedToIPv6)
            {
                remoteIp = remoteIp.MapToIPv4();
            }

            if (!IsPrivateOrLocal(remoteIp))
            {
                context.Response.StatusCode = (int)HttpStatusCode.Forbidden;
                context.Response.ContentType = "application/json";
                await context.Response.WriteAsync("{\"error\": \"Acceso denegado: Únicamente se permiten conexiones de red local privada (RFC 1918 / Loopback).\"}");
                return;
            }

            await _next(context);
        }

        public static bool IsPrivateOrLocal(IPAddress ip)
        {
            // Loopback (127.0.0.1, ::1)
            if (IPAddress.IsLoopback(ip)) return true;

            if (ip.AddressFamily == AddressFamily.InterNetwork)
            {
                byte[] bytes = ip.GetAddressBytes();
                byte b0 = bytes[0];
                byte b1 = bytes[1];

                // 10.0.0.0/8
                if (b0 == 10) return true;

                // 172.16.0.0/12 (172.16.0.0 – 172.31.255.255)
                if (b0 == 172 && (b1 >= 16 && b1 <= 31)) return true;

                // 192.168.0.0/16
                if (b0 == 192 && b1 == 168) return true;

                // 169.254.0.0/16 (Link-Local)
                if (b0 == 169 && b1 == 254) return true;
            }
            else if (ip.AddressFamily == AddressFamily.InterNetworkV6)
            {
                // IPv6 link-local (fe80::) o unique local (fc00::/7)
                if (ip.IsIPv6LinkLocal || ip.IsIPv6SiteLocal) return true;
                byte[] bytes = ip.GetAddressBytes();
                if ((bytes[0] & 0xFE) == 0xFC) return true; // fc00::/7 (ULA)
            }

            return false;
        }
    }
}
