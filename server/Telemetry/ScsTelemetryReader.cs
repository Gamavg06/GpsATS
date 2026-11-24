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
        private readonly object _syncRoot = new();

        public bool IsGameConnected
        {
            get
            {
                lock (_syncRoot)
                {
                    return _accessor != null;
                }
            }
        }

        public TelemetrySnapshot ReadSnapshot()
        {
            lock (_syncRoot)
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

                    // Zona 2: Enteros de Configuración
                    snapshot.TimeAbsDelivery = _accessor.ReadUInt32(88);
                    snapshot.PlannedDistanceKm = _accessor.ReadUInt32(100);

                    // Zona 3: Enteros (Truck_i y Common_i)
                    snapshot.RestStopMinutes = _accessor.ReadInt32(500);
                    snapshot.Gear = _accessor.ReadInt32(504);
                    snapshot.GearDashboard = _accessor.ReadInt32(508);

                    // Zona 4: Flotantes (Config_f y Truck_f)
                    snapshot.Scale = _accessor.ReadSingle(700);
                    if (snapshot.Scale <= 0f) snapshot.Scale = 20.0f;

                    snapshot.FuelCapacity = _accessor.ReadSingle(704);
                    snapshot.CargoMassKg = _accessor.ReadSingle(748);
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
                    if (snapshot.FuelAvgConsumption > 0.0001f)
                    {
                        snapshot.FuelRangeKm = snapshot.Fuel / snapshot.FuelAvgConsumption;
                    }
                    else
                    {
                        snapshot.FuelRangeKm = 0f;
                    }

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

                    // Daño de Carga en Trabajo (job_f)
                    snapshot.CargoDamage = _accessor.ReadSingle(1468);

                    // Zona 5: Booleanos (Config_b y Truck_b)
                    snapshot.IsCargoLoaded = _accessor.ReadBoolean(1564);
                    snapshot.SpecialJob = _accessor.ReadBoolean(1565);
                    snapshot.ParkingBrake = _accessor.ReadBoolean(1566);
                    snapshot.Wipers = _accessor.ReadBoolean(1577);
                    snapshot.BlinkerLeft = _accessor.ReadBoolean(1578);
                    snapshot.BlinkerRight = _accessor.ReadBoolean(1579);
                    snapshot.LightsParking = _accessor.ReadBoolean(1582);
                    snapshot.LightsBeamLow = _accessor.ReadBoolean(1583);
                    snapshot.LightsBeamHigh = _accessor.ReadBoolean(1584);
                    snapshot.LightsBeacon = _accessor.ReadBoolean(1585);
                    snapshot.LightsHazard = _accessor.ReadBoolean(1588);
                    snapshot.CruiseControl = _accessor.ReadBoolean(1589);
                    snapshot.DifferentialLock = _accessor.ReadBoolean(1608);
                    snapshot.LiftAxle = _accessor.ReadBoolean(1609);

                    // Zona 8: Posición y Orientación Doble Precisión (Truck_dp)
                    snapshot.PosX = _accessor.ReadDouble(2200);
                    snapshot.PosY = _accessor.ReadDouble(2208);
                    snapshot.PosZ = _accessor.ReadDouble(2216);

                    // Offsets RenCloud SDK v1.12:
                    // 2200: coordinateX, 2208: coordinateY, 2216: coordinateZ
                    // 2224: rotationX (HEADING en revoluciones [0.0, 1.0))
                    // 2232: rotationY (PITCH)
                    // 2240: rotationZ (ROLL)
                    double heading = _accessor.ReadDouble(2224); // rotationX: Heading
                    snapshot.Heading = heading;
                    double headingDeg = (heading * 360.0) % 360.0;
                    if (headingDeg < 0) headingDeg += 360.0;
                    snapshot.HeadingDegrees = headingDeg;

                    snapshot.Pitch = _accessor.ReadDouble(2232); // rotationY: Pitch
                    snapshot.Roll = _accessor.ReadDouble(2240);  // rotationZ: Roll

                    // Zona 9: Cadenas de Texto (Carga, Trabajo y Destino con Offsets Exactos)
                    string cargoId = ReadString(2556);
                    string cargoName = ReadString(2620);
                    snapshot.CargoId = cargoId;
                    snapshot.CargoName = !string.IsNullOrWhiteSpace(cargoName) ? cargoName : cargoId;

                    string dstCityId = ReadString(2684);
                    string dstCity = ReadString(2748);
                    snapshot.DestinationCityId = dstCityId;
                    snapshot.DestinationCity = !string.IsNullOrWhiteSpace(dstCity) ? dstCity : dstCityId;

                    string dstCompId = ReadString(2812);
                    string dstComp = ReadString(2876);
                    snapshot.DestinationCompanyId = dstCompId;
                    snapshot.DestinationCompany = !string.IsNullOrWhiteSpace(dstComp) ? dstComp : dstCompId;

                    string srcCityId = ReadString(2940);
                    string srcCity = ReadString(3004);
                    snapshot.SourceCityId = srcCityId;
                    snapshot.SourceCity = !string.IsNullOrWhiteSpace(srcCity) ? srcCity : srcCityId;

                    string srcCompId = ReadString(3068);
                    string srcComp = ReadString(3132);
                    snapshot.SourceCompanyId = srcCompId;
                    snapshot.SourceCompany = !string.IsNullOrWhiteSpace(srcComp) ? srcComp : srcCompId;

                    snapshot.JobMarket = ReadString(3404, 32);

                    // Zona 10: ULL (JobIncome)
                    snapshot.JobIncome = _accessor.ReadUInt64(4000);

                    // Zona 12: Eventos Especiales
                    snapshot.OnJob = _accessor.ReadBoolean(4300);

                    // Zona 14: Trailer 0 (Offset 6000)
                    snapshot.TrailerAttached = _accessor.ReadBoolean(6058);
                    if (snapshot.TrailerAttached)
                    {
                        snapshot.TrailerCargoDamage = _accessor.ReadSingle(6152);
                        snapshot.TrailerWearChassis = _accessor.ReadSingle(6156);
                        snapshot.TrailerWearWheels = _accessor.ReadSingle(6160);
                        snapshot.TrailerWearBody = _accessor.ReadSingle(6164);
                    }

                    // Soporte robusto de trabajos con carga oculta o campos vacíos (Customer Priority / Quick Job):
                    // Si el juego tiene una ruta de navegación activa o flag onJob/isCargoLoaded pero el nombre de la carga está vacío
                    if (string.IsNullOrWhiteSpace(snapshot.CargoName))
                    {
                        if (snapshot.SpecialJob)
                        {
                            snapshot.CargoName = "Transporte Especial";
                        }
                        else if (snapshot.IsCargoLoaded || snapshot.OnJob)
                        {
                            snapshot.CargoName = "Envío Prioritario / General";
                        }
                        else if (snapshot.NavDistanceMeters > 50f && !string.IsNullOrWhiteSpace(snapshot.DestinationCity))
                        {
                            snapshot.CargoName = "Carga en Tránsito";
                        }
                    }
                }
                catch (Exception)
                {
                    // Si ATS se cerró o se invalidó la memoria
                    Disconnect();
                    snapshot.Connected = false;
                }

                return snapshot;
            }
        }

        private string ReadString(long offset, int maxLen = 64)
        {
            if (_accessor == null) return string.Empty;
            int bufSize = Math.Min(maxLen, _stringBuffer.Length);
            _accessor.ReadArray(offset, _stringBuffer, 0, bufSize);
            int len = 0;
            while (len < bufSize && _stringBuffer[len] != 0) len++;
            return len > 0 ? Encoding.UTF8.GetString(_stringBuffer, 0, len).Trim() : string.Empty;
        }

        private void TryConnect()
        {
            lock (_syncRoot)
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
        }

        private void Disconnect()
        {
            lock (_syncRoot)
            {
                _accessor?.Dispose();
                _accessor = null;
                _mmf?.Dispose();
                _mmf = null;
            }
        }

        public void Dispose()
        {
            Disconnect();
        }
    }
}
