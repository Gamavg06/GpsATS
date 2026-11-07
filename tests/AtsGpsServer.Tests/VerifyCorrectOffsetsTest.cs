using System;
using System.IO.MemoryMappedFiles;
using System.Text;
using Xunit;
using Xunit.Abstractions;

namespace AtsGpsServer.Tests
{
    public class VerifyCorrectOffsetsTest
    {
        private readonly ITestOutputHelper _output;

        public VerifyCorrectOffsetsTest(ITestOutputHelper output)
        {
            _output = output;
        }

        [Fact]
        public void Verify_Correct_Offsets()
        {
            using var mmf = MemoryMappedFile.OpenExisting(@"Local\SCSTelemetry", MemoryMappedFileRights.Read);
            using var accessor = mmf.CreateViewAccessor(0, 32 * 1024, MemoryMappedFileAccess.Read);

            _output.WriteLine($"truckBrandId (2300): \"{ReadStr(accessor, 2300, 64)}\"");
            _output.WriteLine($"truckBrand (2364): \"{ReadStr(accessor, 2364, 64)}\"");
            _output.WriteLine($"truckId (2428): \"{ReadStr(accessor, 2428, 64)}\"");
            _output.WriteLine($"truckName (2492): \"{ReadStr(accessor, 2492, 64)}\"");

            _output.WriteLine($"cargoId (2556): \"{ReadStr(accessor, 2556, 64)}\"");
            _output.WriteLine($"cargo (2620): \"{ReadStr(accessor, 2620, 64)}\"");

            _output.WriteLine($"cityDstId (2684): \"{ReadStr(accessor, 2684, 64)}\"");
            _output.WriteLine($"cityDst (2748): \"{ReadStr(accessor, 2748, 64)}\"");

            _output.WriteLine($"compDstId (2812): \"{ReadStr(accessor, 2812, 64)}\"");
            _output.WriteLine($"compDst (2876): \"{ReadStr(accessor, 2876, 64)}\"");

            _output.WriteLine($"citySrcId (2940): \"{ReadStr(accessor, 2940, 64)}\"");
            _output.WriteLine($"citySrc (3004): \"{ReadStr(accessor, 3004, 64)}\"");

            _output.WriteLine($"compSrcId (3068): \"{ReadStr(accessor, 3068, 64)}\"");
            _output.WriteLine($"compSrc (3132): \"{ReadStr(accessor, 3132, 64)}\"");

            _output.WriteLine($"shifterType (3196): \"{ReadStr(accessor, 3196, 16)}\"");
            _output.WriteLine($"truckLicensePlate (3212): \"{ReadStr(accessor, 3212, 64)}\"");
            _output.WriteLine($"truckLicensePlateCountryId (3276): \"{ReadStr(accessor, 3276, 64)}\"");
            _output.WriteLine($"truckLicensePlateCountry (3340): \"{ReadStr(accessor, 3340, 64)}\"");

            _output.WriteLine($"jobMarket (3404): \"{ReadStr(accessor, 3404, 32)}\"");

            _output.WriteLine($"onJob (4300): {accessor.ReadBoolean(4300)}");
            _output.WriteLine($"isCargoLoaded (1564): {accessor.ReadBoolean(1564)}");
            _output.WriteLine($"specialJob (1565): {accessor.ReadBoolean(1565)}");
            _output.WriteLine($"jobIncome (4000): {accessor.ReadUInt64(4000)}");
            _output.WriteLine($"time_abs_delivery (88): {accessor.ReadUInt32(88)}");
            _output.WriteLine($"navDistanceMeters (1060): {accessor.ReadSingle(1060)}");
            _output.WriteLine($"navTimeSeconds (1064): {accessor.ReadSingle(1064)}");
            _output.WriteLine($"speedLimit (1068): {accessor.ReadSingle(1068) * 3.6f} km/h");
        }

        private static string ReadStr(MemoryMappedViewAccessor accessor, long offset, int maxLen)
        {
            byte[] buf = new byte[maxLen];
            accessor.ReadArray(offset, buf, 0, maxLen);
            int len = 0;
            while (len < maxLen && buf[len] != 0) len++;
            return len > 0 ? Encoding.UTF8.GetString(buf, 0, len) : string.Empty;
        }
    }
}
