package com.ats.gps.data.model

data class TelemetryData(
    val connected: Boolean = false,
    val gamePaused: Boolean = false,
    val timestamp: Long = 0L,

    // Posición y orientación 3D
    val posX: Double = 0.0,
    val posY: Double = 0.0,
    val posZ: Double = 0.0,
    val heading: Double = 0.0,          // [0.0, 1.0) formato SCS
    val headingDegrees: Double = 0.0,   // [0..360)
    val pitch: Double = 0.0,
    val roll: Double = 0.0,

    // Datos del camión
    val speedKmh: Float = 0f,
    val engineRpm: Float = 0f,
    val engineRpmMax: Float = 2500f,
    val gear: Int = 0,
    val gearDashboard: Int = 0,
    val fuel: Float = 0f,
    val fuelCapacity: Float = 400f,
    val fuelAvgConsumption: Float = 0f,

    // 5 Canales Reales de Desgaste del SDK
    val wearEngine: Float = 0f,
    val wearTransmission: Float = 0f,
    val wearCabin: Float = 0f,
    val wearChassis: Float = 0f,
    val wearWheels: Float = 0f,

    // Luces e Indicadores
    val blinkerLeft: Boolean = false,
    val blinkerRight: Boolean = false,
    val lightsBeamLow: Boolean = false,
    val lightsBeamHigh: Boolean = false,
    val parkingBrake: Boolean = false,
    val cruiseControl: Boolean = false,
    val cruiseControlSpeed: Float = 0f,

    // Navegación y Trabajo
    val navDistanceMeters: Float = 0f,
    val navTimeSeconds: Float = 0f,
    val speedLimitKmh: Float = 0f,
    val cargoName: String = "",
    val cargoId: String = "",
    val cargoMassKg: Float = 0f,
    val sourceCity: String = "",
    val sourceCompany: String = "",
    val destinationCity: String = "",
    val destinationCompany: String = "",
    val jobIncome: Long = 0L,

    // Destino resuelto en el mapa
    val targetNodeIndex: Long? = null,
    val targetPosX: Float? = null,
    val targetPosZ: Float? = null
)
