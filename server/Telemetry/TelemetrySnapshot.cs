using System.Text.Json.Serialization;

namespace AtsGpsServer.Telemetry
{
    public class TelemetrySnapshot
    {
        [JsonPropertyName("connected")]
        public bool Connected { get; set; }

        [JsonPropertyName("gamePaused")]
        public bool GamePaused { get; set; }

        [JsonPropertyName("timestamp")]
        public long Timestamp { get; set; }

        // Zona 1 y 4: Escala y Configuración
        [JsonPropertyName("scale")]
        public float Scale { get; set; } = 20.0f;

        [JsonPropertyName("plannedDistanceKm")]
        public uint PlannedDistanceKm { get; set; }

        // Posición y orientación 3D
        [JsonPropertyName("posX")]
        public double PosX { get; set; }

        [JsonPropertyName("posY")]
        public double PosY { get; set; }

        [JsonPropertyName("posZ")]
        public double PosZ { get; set; }

        [JsonPropertyName("heading")]
        public double Heading { get; set; } // [0.0, 1.0) SCS

        [JsonPropertyName("headingDegrees")]
        public double HeadingDegrees { get; set; } // 0..360

        [JsonPropertyName("pitch")]
        public double Pitch { get; set; }

        [JsonPropertyName("roll")]
        public double Roll { get; set; }

        // Datos del camión
        [JsonPropertyName("speedKmh")]
        public float SpeedKmh { get; set; }

        [JsonPropertyName("engineRpm")]
        public float EngineRpm { get; set; }

        [JsonPropertyName("engineRpmMax")]
        public float EngineRpmMax { get; set; } = 2500f;

        [JsonPropertyName("gear")]
        public int Gear { get; set; }

        [JsonPropertyName("gearDashboard")]
        public int GearDashboard { get; set; }

        [JsonPropertyName("fuel")]
        public float Fuel { get; set; }

        [JsonPropertyName("fuelCapacity")]
        public float FuelCapacity { get; set; } = 400f;

        [JsonPropertyName("fuelAvgConsumption")]
        public float FuelAvgConsumption { get; set; }

        // 5 Canales de Desgaste Reales del Camión (SDK)
        [JsonPropertyName("wearEngine")]
        public float WearEngine { get; set; }

        [JsonPropertyName("wearTransmission")]
        public float WearTransmission { get; set; }

        [JsonPropertyName("wearCabin")]
        public float WearCabin { get; set; }

        [JsonPropertyName("wearChassis")]
        public float WearChassis { get; set; }

        [JsonPropertyName("wearWheels")]
        public float WearWheels { get; set; }

        // Desgaste de Remolque y Daño de Carga (SDK)
        [JsonPropertyName("trailerAttached")]
        public bool TrailerAttached { get; set; }

        [JsonPropertyName("trailerWearChassis")]
        public float TrailerWearChassis { get; set; }

        [JsonPropertyName("trailerWearWheels")]
        public float TrailerWearWheels { get; set; }

        [JsonPropertyName("trailerWearBody")]
        public float TrailerWearBody { get; set; }

        [JsonPropertyName("trailerCargoDamage")]
        public float TrailerCargoDamage { get; set; }

        [JsonPropertyName("cargoDamage")]
        public float CargoDamage { get; set; }

        // Luces e Indicadores
        [JsonPropertyName("blinkerLeft")]
        public bool BlinkerLeft { get; set; }

        [JsonPropertyName("blinkerRight")]
        public bool BlinkerRight { get; set; }

        [JsonPropertyName("lightsBeamLow")]
        public bool LightsBeamLow { get; set; }

        [JsonPropertyName("lightsBeamHigh")]
        public bool LightsBeamHigh { get; set; }

        [JsonPropertyName("parkingBrake")]
        public bool ParkingBrake { get; set; }

        [JsonPropertyName("cruiseControl")]
        public bool CruiseControl { get; set; }

        [JsonPropertyName("cruiseControlSpeed")]
        public float CruiseControlSpeed { get; set; }

        // Navegación y Trabajo
        [JsonPropertyName("navDistanceMeters")]
        public float NavDistanceMeters { get; set; }

        [JsonPropertyName("navTimeSeconds")]
        public float NavTimeSeconds { get; set; }

        [JsonPropertyName("speedLimitKmh")]
        public float SpeedLimitKmh { get; set; }

        [JsonPropertyName("cargoName")]
        public string CargoName { get; set; } = string.Empty;

        [JsonPropertyName("cargoId")]
        public string CargoId { get; set; } = string.Empty;

        [JsonPropertyName("cargoMassKg")]
        public float CargoMassKg { get; set; }

        [JsonPropertyName("sourceCity")]
        public string SourceCity { get; set; } = string.Empty;

        [JsonPropertyName("sourceCityId")]
        public string SourceCityId { get; set; } = string.Empty;

        [JsonPropertyName("sourceCompany")]
        public string SourceCompany { get; set; } = string.Empty;

        [JsonPropertyName("sourceCompanyId")]
        public string SourceCompanyId { get; set; } = string.Empty;

        [JsonPropertyName("destinationCity")]
        public string DestinationCity { get; set; } = string.Empty;

        [JsonPropertyName("destinationCityId")]
        public string DestinationCityId { get; set; } = string.Empty;

        [JsonPropertyName("destinationCompany")]
        public string DestinationCompany { get; set; } = string.Empty;

        [JsonPropertyName("destinationCompanyId")]
        public string DestinationCompanyId { get; set; } = string.Empty;

        [JsonPropertyName("jobIncome")]
        public ulong JobIncome { get; set; }

        [JsonPropertyName("timeAbsDelivery")]
        public uint TimeAbsDelivery { get; set; }

        [JsonPropertyName("isCargoLoaded")]
        public bool IsCargoLoaded { get; set; }

        [JsonPropertyName("specialJob")]
        public bool SpecialJob { get; set; }

        [JsonPropertyName("onJob")]
        public bool OnJob { get; set; }

        [JsonPropertyName("jobMarket")]
        public string JobMarket { get; set; } = string.Empty;

        // Destino Resuelto en el Grafo
        [JsonPropertyName("targetNodeIndex")]
        public uint? TargetNodeIndex { get; set; }

        [JsonPropertyName("targetPosX")]
        public float? TargetPosX { get; set; }

        [JsonPropertyName("targetPosZ")]
        public float? TargetPosZ { get; set; }
    }
}
