using System.Text.Json;
using AtsGpsServer.Telemetry;
using Xunit;

namespace AtsGpsServer.Tests
{
    public class TelemetryModelTests
    {
        [Fact]
        public void TelemetrySnapshot_SerializesAuthenticChannels()
        {
            var snapshot = new TelemetrySnapshot
            {
                Connected = true,
                GamePaused = false,
                Timestamp = 1700000000,
                PosX = -104000.5,
                PosY = 45.2,
                PosZ = -53000.1,
                Heading = 0.25, // Oeste (-X)
                HeadingDegrees = 90.0,
                Pitch = 0.01,
                Roll = 0.0,
                SpeedKmh = 88.5f,
                EngineRpm = 1450.0f,
                EngineRpmMax = 2500.0f,
                Gear = 10,
                GearDashboard = 10,
                Fuel = 380.0f,
                FuelCapacity = 500.0f,
                FuelAvgConsumption = 32.5f,
                WearEngine = 0.05f,
                WearTransmission = 0.02f,
                WearCabin = 0.01f,
                WearChassis = 0.03f,
                WearWheels = 0.08f,
                BlinkerLeft = false,
                BlinkerRight = true,
                LightsBeamLow = true,
                LightsBeamHigh = false,
                ParkingBrake = false,
                CruiseControl = true,
                CruiseControlSpeed = 88.0f,
                NavDistanceMeters = 12500.0f,
                NavTimeSeconds = 620.0f,
                SpeedLimitKmh = 105.0f,
                SourceCity = "portland",
                SourceCompany = "wallbert",
                DestinationCity = "seattle",
                DestinationCompany = "darchelle",
                JobIncome = 24500
            };

            string json = JsonSerializer.Serialize(snapshot);
            Assert.Contains("\"wearEngine\":0.05", json);
            Assert.Contains("\"wearTransmission\":0.02", json);
            Assert.Contains("\"wearCabin\":0.01", json);
            Assert.Contains("\"wearChassis\":0.03", json);
            Assert.Contains("\"wearWheels\":0.08", json);
            Assert.Contains("\"speedKmh\":88.5", json);
            Assert.Contains("\"sourceCity\":\"portland\"", json);
            Assert.Contains("\"destinationCity\":\"seattle\"", json);

            // Deserializar y comprobar integridad
            var deserialized = JsonSerializer.Deserialize<TelemetrySnapshot>(json);
            Assert.NotNull(deserialized);
            Assert.True(deserialized.Connected);
            Assert.Equal(-104000.5, deserialized.PosX);
            Assert.Equal(0.08f, deserialized.WearWheels);
        }
    }
}
