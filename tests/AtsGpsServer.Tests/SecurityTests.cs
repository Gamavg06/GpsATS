using System.Net;
using AtsGpsServer.Security;
using Xunit;

namespace AtsGpsServer.Tests
{
    public class SecurityTests
    {
        [Theory]
        [InlineData("127.0.0.1", true)]
        [InlineData("::1", true)]
        [InlineData("192.168.1.1", true)]
        [InlineData("192.168.100.254", true)]
        [InlineData("10.0.0.1", true)]
        [InlineData("10.255.255.255", true)]
        [InlineData("172.16.0.1", true)]
        [InlineData("172.24.50.100", true)]
        [InlineData("172.31.255.254", true)]
        [InlineData("8.8.8.8", false)] // DNS Google (Pública)
        [InlineData("1.1.1.1", false)] // Cloudflare (Pública)
        [InlineData("172.15.255.255", false)] // Fuera del rango 172.16.0.0/12
        [InlineData("172.32.0.1", false)] // Fuera del rango 172.16.0.0/12
        [InlineData("200.50.10.5", false)] // IP Pública WAN
        public void IpWhitelist_ValidatesPrivateSubnetsCorrectly(string ipString, bool expectedAllowed)
        {
            var ip = IPAddress.Parse(ipString);
            bool allowed = IpWhitelistMiddleware.IsPrivateOrLocal(ip);
            Assert.Equal(expectedAllowed, allowed);
        }

        [Fact]
        public void AuthManager_GeneratesSixDigitPin()
        {
            var auth = new AuthManager();
            Assert.NotNull(auth.ServerPin);
            Assert.Equal(6, auth.ServerPin.Length);
            Assert.True(int.TryParse(auth.ServerPin, out int pinNum));
            Assert.InRange(pinNum, 100000, 999999);
        }

        [Fact]
        public void AuthManager_AuthenticatesOnlyWithCorrectPin()
        {
            var auth = new AuthManager("123456");

            // Intentar con PIN incorrecto
            var (success1, token1, err1) = auth.TryAuthenticate("999999");
            Assert.False(success1);
            Assert.Null(token1);
            Assert.Contains("PIN incorrecto", err1);

            // Intentar con PIN correcto
            var (success2, token2, err2) = auth.TryAuthenticate("123456");
            Assert.True(success2);
            Assert.NotNull(token2);
            Assert.NotEmpty(token2);
            Assert.Null(err2);

            // Validar token emitido
            Assert.True(auth.ValidateToken(token2));
            Assert.False(auth.ValidateToken("fake-token-xyz"));
        }

        [Fact]
        public void AuthManager_EnforcesRateLimitingAndLockout()
        {
            var auth = new AuthManager("654321");

            // 5 intentos fallidos consecutivos
            for (int i = 0; i < 4; i++)
            {
                var (success, _, _) = auth.TryAuthenticate("000000");
                Assert.False(success);
                Assert.False(auth.IsLockedOut);
            }

            // Quinto intento activa el bloqueo
            var (success5, token5, err5) = auth.TryAuthenticate("000000");
            Assert.False(success5);
            Assert.Null(token5);
            Assert.True(auth.IsLockedOut);
            Assert.Contains("bloqueado temporalmente", err5);

            // Intento con el PIN correcto mientras está bloqueado debe ser rechazado
            var (blockedSuccess, _, blockedErr) = auth.TryAuthenticate("654321");
            Assert.False(blockedSuccess);
            Assert.Contains("bloqueado", blockedErr);
        }
    }
}
