using System;
using System.IO;
using System.IO.MemoryMappedFiles;
using System.Text;

namespace AtsGpsServer.Telemetry
{
    public class ScsTelemetryReader : IDisposable
    {
        private const string MmfName = @"Local\SCSTelemetry";
        private const int MmfSize = 32 * 1024; // 32 KB

        private MemoryMappedFile? _mmf;
        private MemoryMappedViewAccessor? _accessor;
        private readonly byte[] _stringBuffer = new byte[64];

        public bool IsGameConnected => _accessor != null;

        public TelemetrySnapshot ReadSnapshot()
        {
            var snapshot = new TelemetrySnapshot();

            if (_accessor == null)
            {
                TryConnect();
            }

            if (_accessor == null)
            {
                snapshot.Connected = false;
                snapshot.Timestamp = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
                return snapshot;
            }

            try
            {
                // Zona 1: Control y Estado
                bool sdkActive = _accessor.ReadBoolean(0);
                bool paused = _accessor.ReadBoolean(4);
                long time = _accessor.ReadInt64(8);

                snapshot.Connected = sdkActive;
                snapshot.GamePaused = paused;
                snapshot.Timestamp = time > 0 ? time : DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();

                if (!sdkActive)
                {
                    return snapshot;
                }

                // Zona 3: Enteros (Truck_i)
                snapshot.Gear = _accessor.ReadInt32(504);
                snapshot.GearDashboard = _accessor.ReadInt32(508);

                // Zona 4: Flotantes (Config_f y Truck_f)
                snapshot.FuelCapacity = _accessor.ReadSingle(704);
                snapshot.CargoMassKg = _accessor.ReadSingle(708);
                if (snapshot.CargoMassKg <= 0f)
                {
                    snapshot.CargoMassKg = _accessor.ReadSingle(1800);
                }
                snapshot.EngineRpmMax = _accessor.ReadSingle(740);

                float speedMs = _accessor.ReadSingle(948);
                snapshot.SpeedKmh = speedMs * 3.6f;
                snapshot.EngineRpm = _accessor.ReadSingle(952);

                snapshot.Fuel = _accessor.ReadSingle(1000);
                snapshot.FuelAvgConsumption = _accessor.ReadSingle(1004);

                // 5 Canales Reales de Desgaste del SDK
                snapshot.WearEngine = _accessor.ReadSingle(1036);
                snapshot.WearTransmission = _accessor.ReadSingle(1040);
                snapshot.WearCabin = _accessor.ReadSingle(1044);
                snapshot.WearChassis = _accessor.ReadSingle(1048);
                snapshot.WearWheels = _accessor.ReadSingle(1052);

                snapshot.NavDistanceMeters = _accessor.ReadSingle(1060);
                snapshot.NavTimeSeconds = _accessor.ReadSingle(1064);
                float speedLimitMs = _accessor.ReadSingle(1068);
                snapshot.SpeedLimitKmh = speedLimitMs * 3.6f;

                // Zona 5: Booleanos (Truck_b)
                snapshot.ParkingBrake = _accessor.ReadBoolean(1566);
                snapshot.BlinkerLeft = _accessor.ReadBoolean(1578);
                snapshot.BlinkerRight = _accessor.ReadBoolean(1579);
                snapshot.LightsBeamLow = _accessor.ReadBoolean(1583);
                snapshot.LightsBeamHigh = _accessor.ReadBoolean(1584);
                snapshot.CruiseControl = _accessor.ReadBoolean(1589);

                // Zona 8: Posición y Orientación Doble Precisión (Truck_dp)
                snapshot.PosX = _accessor.ReadDouble(2200);
                snapshot.PosY = _accessor.ReadDouble(2208);
                snapshot.PosZ = _accessor.ReadDouble(2216);

                snapshot.Pitch = _accessor.ReadDouble(2224);
                double heading = _accessor.ReadDouble(2232); // [0.0, 1.0)
                snapshot.Heading = heading;
                snapshot.HeadingDegrees = heading * 360.0;
                snapshot.Roll = _accessor.ReadDouble(2240);

                // Zona 9: Cadenas de Texto (Carga, Trabajo y Destino)
                string cargoId = ReadString(2656);
                string cargoName = ReadString(2720);
                snapshot.CargoId = cargoId;
                snapshot.CargoName = !string.IsNullOrWhiteSpace(cargoName) ? cargoName : cargoId;

                string dstCityId = ReadString(2784);
                string dstCity = ReadString(2848);
                if (string.IsNullOrWhiteSpace(dstCity)) dstCity = ReadString(2748);
                snapshot.DestinationCity = !string.IsNullOrWhiteSpace(dstCity) ? dstCity : dstCityId;

                string dstCompId = ReadString(2912);
                string dstComp = ReadString(2976);
                if (string.IsNullOrWhiteSpace(dstComp)) dstComp = ReadString(2876);
                snapshot.DestinationCompany = !string.IsNullOrWhiteSpace(dstComp) ? dstComp : dstCompId;

                string srcCityId = ReadString(3040);
                string srcCity = ReadString(3104);
                if (string.IsNullOrWhiteSpace(srcCity)) srcCity = ReadString(3004);
                snapshot.SourceCity = !string.IsNullOrWhiteSpace(srcCity) ? srcCity : srcCityId;

                string srcCompId = ReadString(3168);
                string srcComp = ReadString(3232);
                if (string.IsNullOrWhiteSpace(srcComp)) srcComp = ReadString(3132);
                snapshot.SourceCompany = !string.IsNullOrWhiteSpace(srcComp) ? srcComp : srcCompId;

                // Zona 10: ULL (JobIncome)
                snapshot.JobIncome = _accessor.ReadUInt64(4000);
            }
            catch (Exception)
            {
                // Si ATS se cerró o se invalidó la memoria
                Disconnect();
                snapshot.Connected = false;
            }

            return snapshot;
        }

        private string ReadString(long offset)
        {
            if (_accessor == null) return string.Empty;
            _accessor.ReadArray(offset, _stringBuffer, 0, 64);
            int len = 0;
            while (len < 64 && _stringBuffer[len] != 0) len++;
            return len > 0 ? Encoding.UTF8.GetString(_stringBuffer, 0, len) : string.Empty;
        }

        private void TryConnect()
        {
            try
            {
                _mmf = MemoryMappedFile.OpenExisting(MmfName, MemoryMappedFileRights.Read);
                _accessor = _mmf.CreateViewAccessor(0, MmfSize, MemoryMappedFileAccess.Read);
            }
            catch (FileNotFoundException)
            {
                _accessor = null;
                _mmf = null;
            }
            catch (Exception)
            {
                _accessor = null;
                _mmf = null;
            }
        }

        private void Disconnect()
        {
            _accessor?.Dispose();
            _accessor = null;
            _mmf?.Dispose();
            _mmf = null;
        }

        public void Dispose()
        {
            Disconnect();
        }
    }
}
