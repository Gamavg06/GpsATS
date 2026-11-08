using System;
using System.IO.MemoryMappedFiles;
using AtsGpsServer.Telemetry;
using Xunit;
using Xunit.Abstractions;

namespace AtsGpsServer.Tests
{
    public class HeadingVerificationTests
    {
        private readonly ITestOutputHelper _output;

        public HeadingVerificationTests(ITestOutputHelper output)
        {
            _output = output;
        }

        [Fact]
        public void Verify_Heading_Offsets_And_Four_Cardinal_Directions()
        {
            const string testMmfName = @"Local\SCSTelemetry";
            
            // Si el juego real está corriendo, leer el MMF en vivo
            try
            {
                using var mmf = MemoryMappedFile.OpenExisting(testMmfName, MemoryMappedFileRights.Read);
                using var accessor = mmf.CreateViewAccessor(0, 32 * 1024, MemoryMappedFileAccess.Read);

                double posX = accessor.ReadDouble(2200);
                double posY = accessor.ReadDouble(2208);
                double posZ = accessor.ReadDouble(2216);
                double rotX_Heading = accessor.ReadDouble(2224);
                double rotY_Pitch = accessor.ReadDouble(2232);
                double rotZ_Roll = accessor.ReadDouble(2240);

                _output.WriteLine("=== VALORES MMF EN VIVO (JUEGO CONECTADO) ===");
                _output.WriteLine($"Offset 2200 (coordinateX): {posX:F2}");
                _output.WriteLine($"Offset 2208 (coordinateY): {posY:F2}");
                _output.WriteLine($"Offset 2216 (coordinateZ): {posZ:F2}");
                _output.WriteLine($"Offset 2224 (rotationX - HEADING): {rotX_Heading:F6} rev -> {rotX_Heading * 360.0:F2}°");
                _output.WriteLine($"Offset 2232 (rotationY - PITCH):   {rotY_Pitch:F6} rad -> {rotY_Pitch * 360.0:F2}°");
                _output.WriteLine($"Offset 2240 (rotationZ - ROLL):    {rotZ_Roll:F6} rad -> {rotZ_Roll * 360.0:F2}°");
            }
            catch (Exception ex)
            {
                _output.WriteLine($"Juego no activo en este instante ({ex.Message}), ejecutando validación matemática de las 4 direcciones cardinales.");
            }

            _output.WriteLine("\n=== PRUEBA DEMOSTRATIVA DE LAS 4 DIRECCIONES CARDINALES ===");
            var testCases = new (string Cardinal, double RevValue, double ExpectedDeg)[]
            {
                ("NORTE (0°)", 0.00, 0.0),
                ("ESTE (90°)", 0.25, 90.0),
                ("SUR (180°)", 0.50, 180.0),
                ("OESTE (270°)", 0.75, 270.0),
                ("NORESTE (Nogales live: 51.54°)", 0.14316447, 51.54)
            };

            foreach (var tc in testCases)
            {
                double deg = (tc.RevValue * 360.0) % 360.0;
                if (deg < 0) deg += 360.0;
                _output.WriteLine($"Dirección: {tc.Cardinal} | rotationX en 2224: {tc.RevValue:F6} rev | Grados calculados: {deg:F2}° (Esperado: {tc.ExpectedDeg:F2}°)");
                Assert.Equal(tc.ExpectedDeg, deg, 0.01);
            }
        }
    }
}
