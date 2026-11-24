using System;
using System.IO;
using System.IO.MemoryMappedFiles;
using System.Text;
using AtsGpsServer.Telemetry;
using Xunit;
using Xunit.Abstractions;

namespace AtsGpsServer.Tests
{
    public class InspectLiveMmfH1H2Tests
    {
        private readonly ITestOutputHelper _output;

        public InspectLiveMmfH1H2Tests(ITestOutputHelper output)
        {
            _output = output;
        }

        [Fact]
        public void Dump_Live_H1_H2_Telemetry_Values()
        {
            MemoryMappedFile? mmf = null;
            try
            {
                mmf = MemoryMappedFile.OpenExisting(@"Local\SCSTelemetry", MemoryMappedFileRights.Read);
            }
            catch (FileNotFoundException)
            {
                _output.WriteLine("[INFO] ATS no esta en ejecucion (memoria compartida Local\\SCSTelemetry no encontrada). Test omitido.");
                return;
            }

            using (mmf)
            using (var accessor = mmf.CreateViewAccessor(0, 32 * 1024, MemoryMappedFileAccess.Read))
            {

            // 1. Control & Pause
            bool sdkActive = accessor.ReadBoolean(0);
            bool paused = accessor.ReadBoolean(4);
            long time = accessor.ReadInt64(8);

            // 2. common_f.scale
            // Zone 4 common_f starts at offset 700: float scale
            float scale = accessor.ReadSingle(700);

            // 3. config_f: cargoMass
            // In scs-telemetry-common.hpp line 264:
            // float fuelCapacity (704)
            // float fuelWarningFactor (708)
            // float adblueCapacity (712)
            // float adblueWarningFactor (716)
            // float airPressureWarning (720)
            // float airPressurEmergency (724)
            // float oilPressureWarning (728)
            // float waterTemperatureWarning (732)
            // float batteryVoltageWarning (736)
            // float engineRpmMax (740)
            // float gearDifferential (744)
            // float cargoMass (748) -> WAIT! Let's check 748!
            float cargoMass708 = accessor.ReadSingle(708);
            float cargoMass748 = accessor.ReadSingle(748);
            float cargoMass1800 = accessor.ReadSingle(1800);

            // 4. plannedDistanceKm
            // config_ui at offset 68..
            // time_abs_delivery (offset 88)
            // maxTrailerCount (92)
            // unitCount (96)
            // plannedDistanceKm (100)
            uint plannedDistanceKm = accessor.ReadUInt32(100);
            uint timeAbsDelivery = accessor.ReadUInt32(88);

            // 5. truck_f navigation floats
            // routeDistance (1060), routeTime (1064), speedLimit (1068)
            float routeDistance = accessor.ReadSingle(1060);
            float routeTime = accessor.ReadSingle(1064);
            float speedLimitMs = accessor.ReadSingle(1068);

            // 6. Boolean flags
            bool isCargoLoaded = accessor.ReadBoolean(1564);
            bool specialJob = accessor.ReadBoolean(1565);
            bool onJob = accessor.ReadBoolean(4300);

            // 7. jobIncome (Zone 10)
            ulong jobIncome = accessor.ReadUInt64(4000);

            // 8. Strings
            string cargoId = ReadStr(accessor, 2556, 64);
            string cargo = ReadStr(accessor, 2620, 64);
            string cityDstId = ReadStr(accessor, 2684, 64);
            string cityDst = ReadStr(accessor, 2748, 64);
            string compDstId = ReadStr(accessor, 2812, 64);
            string compDst = ReadStr(accessor, 2876, 64);
            string citySrcId = ReadStr(accessor, 2940, 64);
            string citySrc = ReadStr(accessor, 3004, 64);
            string compSrcId = ReadStr(accessor, 3068, 64);
            string compSrc = ReadStr(accessor, 3132, 64);
            string jobMarket = ReadStr(accessor, 3404, 32);

            // 9. truck_dp (Offset 2200)
            double posX = accessor.ReadDouble(2200);
            double posY = accessor.ReadDouble(2208);
            double posZ = accessor.ReadDouble(2216);
            double rotX = accessor.ReadDouble(2224);
            double rotY = accessor.ReadDouble(2232);
            double rotZ = accessor.ReadDouble(2240);

            _output.WriteLine("=== VALORES CRUDOS DEL MMF (H2) ===");
            _output.WriteLine($"citySrc: \"{citySrc}\"");
            _output.WriteLine($"citySrcId: \"{citySrcId}\"");
            _output.WriteLine($"compSrc: \"{compSrc}\"");
            _output.WriteLine($"compSrcId: \"{compSrcId}\"");
            _output.WriteLine($"cityDst: \"{cityDst}\"");
            _output.WriteLine($"cityDstId: \"{cityDstId}\"");
            _output.WriteLine($"compDst: \"{compDst}\"");
            _output.WriteLine($"compDstId: \"{compDstId}\"");
            _output.WriteLine($"cargoId: \"{cargoId}\"");
            _output.WriteLine($"cargo: \"{cargo}\"");
            _output.WriteLine($"cargoMass (offset 708): {cargoMass708}");
            _output.WriteLine($"cargoMass (offset 748): {cargoMass748}");
            _output.WriteLine($"cargoMass (offset 1800): {cargoMass1800}");
            _output.WriteLine($"jobIncome: {jobIncome}");
            _output.WriteLine($"jobMarket: \"{jobMarket}\"");
            _output.WriteLine($"onJob: {onJob}");
            _output.WriteLine($"isCargoLoaded: {isCargoLoaded}");
            _output.WriteLine($"specialJob: {specialJob}");
            _output.WriteLine($"plannedDistanceKm: {plannedDistanceKm}");
            _output.WriteLine($"routeDistance: {routeDistance}");
            _output.WriteLine($"routeTime: {routeTime}");
            _output.WriteLine($"speedLimit: {speedLimitMs * 3.6f} km/h ({speedLimitMs} m/s)");
            _output.WriteLine($"common_f.scale: {scale}");
            _output.WriteLine($"paused: {paused}");
            _output.WriteLine($"sdkActive: {sdkActive}");
            _output.WriteLine($"time: {time}");
            _output.WriteLine($"timeAbsDelivery: {timeAbsDelivery}");

            _output.WriteLine("\n=== INSPECCION DE RUMBO Y ORIENTACIÓN (H1) ===");
            _output.WriteLine($"posX (2200): {posX}");
            _output.WriteLine($"posY (2208): {posY}");
            _output.WriteLine($"posZ (2216): {posZ}");
            _output.WriteLine($"rotationX (2224): {rotX} -> Grados: {rotX * 360.0}° (o rad {rotX * 180.0 / Math.PI}°)");
            _output.WriteLine($"rotationY (2232): {rotY} -> Grados: {rotY * 360.0}° (o rad {rotY * 180.0 / Math.PI}°)");
            _output.WriteLine($"rotationZ (2240): {rotZ} -> Grados: {rotZ * 360.0}° (o rad {rotZ * 180.0 / Math.PI}°)");

            // Check where cargo mass is located in config_f (700..760)
            _output.WriteLine("\n=== FLOATS EN CONFIG_F (700..760) ===");
            for (int off = 700; off <= 760; off += 4)
            {
                _output.WriteLine($"Offset {off}: {accessor.ReadSingle(off)}");
            }
            }
        }

        private static string ReadStr(MemoryMappedViewAccessor accessor, long offset, int maxLen)
        {
            byte[] buf = new byte[maxLen];
            accessor.ReadArray(offset, buf, 0, maxLen);
            int len = 0;
            while (len < maxLen && buf[len] != 0 && buf[len] >= 32 && buf[len] <= 126) len++;
            return len > 0 ? Encoding.UTF8.GetString(buf, 0, len).Trim() : string.Empty;
        }
    }
}
