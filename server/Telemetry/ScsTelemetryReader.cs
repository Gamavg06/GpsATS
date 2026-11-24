using System;
using System.IO;
using System.IO.MemoryMappedFiles;
using System.Text;
using Microsoft.Extensions.DependencyInjection;

namespace AtsGpsServer.Telemetry
{
    public class ScsTelemetryReader : IDisposable
    {
        private const string DefaultMmfName = @"Local\SCSTelemetry";
        private const int MmfSize = 32 * 1024; // 32 KB

        private readonly string _mmfName;
        private MemoryMappedFile? _mmf;
        private MemoryMappedViewAccessor? _accessor;
        private readonly byte[] _stringBuffer = new byte[64];
        private readonly object _syncRoot = new();

        [ActivatorUtilitiesConstructor]
        public ScsTelemetryReader() : this(DefaultMmfName)
        {
        }

        internal ScsTelemetryReader(string mmfName)
        {
            _mmfName = string.IsNullOrWhiteSpace(mmfName) ? DefaultMmfName : mmfName;
        }

        public bool IsGameConnected => CheckConnectionStatus();

        public static float SanitizeFloat(float value, float fallback = 0f) =>
            float.IsFinite(value) ? value : fallback;

        public static double SanitizeDouble(double value, double fallback = 0.0) =>
            double.IsFinite(value) ? value : fallback;

        public static double ConvertScsHeadingToCompassDegrees(double rawHeading)
        {
            double cleanHeading = SanitizeDouble(rawHeading);
            double headingDeg = ((360.0 - (cleanHeading * 360.0)) % 360.0 + 360.0) % 360.0;
            return (headingDeg >= 360.0 || headingDeg == 0.0) ? 0.0 : headingDeg;
        }

        public bool CheckConnectionStatus()
        {
            lock (_syncRoot)
            {
                if (_accessor == null)
                {
                    TryConnect();
                }

                if (_accessor == null)
                {
                    return false;
                }

                try
                {
                    bool sdkActive = _accessor.ReadBoolean(0);
                    if (!sdkActive)
                    {
                        Disconnect();
                        return false;
                    }

                    return true;
                }
                catch
                {
                    Disconnect();
                    return false;
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
                        Disconnect();
                        snapshot.Connected = false;
                        return snapshot;
                    }

                    // Zona 2: Enteros de Configuracion
                    snapshot.TimeAbsDelivery = _accessor.ReadUInt32(88);
                    snapshot.PlannedDistanceKm = _accessor.ReadUInt32(100);

                    // Zona 3: Enteros (Truck_i y Common_i)
                    snapshot.RestStopMinutes = _accessor.ReadInt32(500);
                    snapshot.Gear = _accessor.ReadInt32(504);
                    snapshot.GearDashboard = _accessor.ReadInt32(508);

                    // Zona 4: Flotantes (Config_f y Truck_f)
                    float scale = SanitizeFloat(_accessor.ReadSingle(700), 20.0f);
                    snapshot.Scale = scale > 0f ? scale : 20.0f;

                    snapshot.FuelCapacity = SanitizeFloat(_accessor.ReadSingle(704));
                    float cargoMass = SanitizeFloat(_accessor.ReadSingle(748));
                    snapshot.CargoMassKg = cargoMass >= 0f ? cargoMass : 0f;
                    snapshot.EngineRpmMax = SanitizeFloat(_accessor.ReadSingle(740));

                    float speedMs = SanitizeFloat(_accessor.ReadSingle(948));
                    snapshot.SpeedKmh = SanitizeFloat(speedMs * 3.6f);
                    snapshot.EngineRpm = SanitizeFloat(_accessor.ReadSingle(952));

                    float cruiseControlMs = SanitizeFloat(_accessor.ReadSingle(988));
                    snapshot.CruiseControlSpeed = cruiseControlMs > 0f ? SanitizeFloat(cruiseControlMs * 3.6f) : 0f;

                    snapshot.Fuel = SanitizeFloat(_accessor.ReadSingle(1000));
                    snapshot.FuelAvgConsumption = SanitizeFloat(_accessor.ReadSingle(1004));
                    if (snapshot.FuelAvgConsumption > 0.0001f)
                    {
                        snapshot.FuelRangeKm = SanitizeFloat(snapshot.Fuel / snapshot.FuelAvgConsumption);
                    }
                    else
                    {
                        snapshot.FuelRangeKm = 0f;
                    }

                    // 5 Canales Reales de Desgaste del SDK
                    snapshot.WearEngine = SanitizeFloat(_accessor.ReadSingle(1036));
                    snapshot.WearTransmission = SanitizeFloat(_accessor.ReadSingle(1040));
                    snapshot.WearCabin = SanitizeFloat(_accessor.ReadSingle(1044));
                    snapshot.WearChassis = SanitizeFloat(_accessor.ReadSingle(1048));
                    snapshot.WearWheels = SanitizeFloat(_accessor.ReadSingle(1052));

                    snapshot.NavDistanceMeters = SanitizeFloat(_accessor.ReadSingle(1060));
                    snapshot.NavTimeSeconds = SanitizeFloat(_accessor.ReadSingle(1064));
                    float speedLimitMs = SanitizeFloat(_accessor.ReadSingle(1068));
                    snapshot.SpeedLimitKmh = SanitizeFloat(speedLimitMs * 3.6f);

                    // Dano de Carga en Trabajo (job_f)
                    snapshot.CargoDamage = SanitizeFloat(_accessor.ReadSingle(1468));

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

                    // Zona 8: Posicion y Orientacion Doble Precision (Truck_dp)
                    snapshot.PosX = SanitizeDouble(_accessor.ReadDouble(2200));
                    snapshot.PosY = SanitizeDouble(_accessor.ReadDouble(2208));
                    snapshot.PosZ = SanitizeDouble(_accessor.ReadDouble(2216));

                    // Offsets RenCloud SDK v1.12:
                    // 2200: coordinateX, 2208: coordinateY, 2216: coordinateZ
                    // 2224: rotationX (HEADING en revoluciones [0.0, 1.0), antihorario)
                    // 2232: rotationY (PITCH)
                    // 2240: rotationZ (ROLL)
                    double rawHeading = SanitizeDouble(_accessor.ReadDouble(2224));
                    snapshot.Heading = rawHeading;
                    snapshot.HeadingDegrees = ConvertScsHeadingToCompassDegrees(rawHeading);

                    snapshot.Pitch = SanitizeDouble(_accessor.ReadDouble(2232));
                    snapshot.Roll = SanitizeDouble(_accessor.ReadDouble(2240));

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

                    // Zona 14: Trailer 0 (Offset base 6000; attached en offset 6080)
                    snapshot.TrailerAttached = _accessor.ReadBoolean(6080);
                    if (snapshot.TrailerAttached)
                    {
                        snapshot.TrailerCargoDamage = SanitizeFloat(_accessor.ReadSingle(6152));
                        snapshot.TrailerWearChassis = SanitizeFloat(_accessor.ReadSingle(6156));
                        snapshot.TrailerWearWheels = SanitizeFloat(_accessor.ReadSingle(6160));
                        snapshot.TrailerWearBody = SanitizeFloat(_accessor.ReadSingle(6164));
                    }

                    // Soporte robusto de trabajos con carga oculta o campos vacios (Customer Priority / Quick Job):
                    if (string.IsNullOrWhiteSpace(snapshot.CargoName))
                    {
                        if (snapshot.SpecialJob)
                        {
                            snapshot.CargoName = "Transporte Especial";
                        }
                        else if (snapshot.IsCargoLoaded || snapshot.OnJob)
                        {
                            snapshot.CargoName = "Envio Prioritario / General";
                        }
                        else if (snapshot.NavDistanceMeters > 50f && !string.IsNullOrWhiteSpace(snapshot.DestinationCity))
                        {
                            snapshot.CargoName = "Carga en Transito";
                        }
                    }
                }
                catch (Exception)
                {
                    // Si ATS se cerro o se invalido la memoria
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
                    _mmf = MemoryMappedFile.OpenExisting(_mmfName, MemoryMappedFileRights.Read);
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
