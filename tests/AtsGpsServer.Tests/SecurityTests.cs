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
            var auth = new AuthManager();
            string correctPin = auth.ServerPin;
            string incorrectPin = "000000";
            if (correctPin == incorrectPin) incorrectPin = "111111";

            // Intentar con PIN incorrecto
            string? invalidToken = auth.TryAuthenticate(incorrectPin);
            Assert.Null(invalidToken);

            // Intentar con PIN correcto
            string? validToken = auth.TryAuthenticate(correctPin);
            Assert.NotNull(validToken);
            Assert.NotEmpty(validToken);

            // Validar token emitido
            Assert.True(auth.ValidateToken(validToken));
            Assert.False(auth.ValidateToken("fake-token-xyz"));
        }
    }
}
