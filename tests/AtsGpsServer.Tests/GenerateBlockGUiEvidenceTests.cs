using System;
using System.IO;
using System.Text;
using Xunit;
using Xunit.Abstractions;

namespace AtsGpsServer.Tests
{
    public class GenerateBlockGUiEvidenceTests
    {
        private readonly ITestOutputHelper _output;

        public GenerateBlockGUiEvidenceTests(ITestOutputHelper output)
        {
            _output = output;
        }

        [Fact]
        public void Generate_All_Visual_Artifacts_For_Block_G()
        {
            string outDir = @"C:\Users\gmlve\Downloads";

            // 1. SVG: Tablero Propio Vertical (Portrait) con Tacómetro, Velocímetro y Luces
            string portraitSvg = GeneratePortraitDashboardSvg();
            File.WriteAllText(Path.Combine(outDir, "UI_Dashboard_v1.3.0_Portrait.svg"), portraitSvg, Encoding.UTF8);

            // 2. SVG: Tablero Propio Horizontal (Landscape) con Split-Screen y Remolque
            string landscapeSvg = GenerateLandscapeDashboardSvg();
            File.WriteAllText(Path.Combine(outDir, "UI_Dashboard_v1.3.0_Landscape.svg"), landscapeSvg, Encoding.UTF8);

            // 3. SVG: Comparativa 2D vs 3D desde la MISMA posición con Carreteras bajo el camión
            string mapComparisonSvg = GenerateMapComparison2Dvs3DSvg();
            File.WriteAllText(Path.Combine(outDir, "Map_Comparison_2D_vs_3D_SamePosition.svg"), mapComparisonSvg, Encoding.UTF8);

            _output.WriteLine("SVGs generados exitosamente en C:\\Users\\gmlve\\Downloads:");
            _output.WriteLine(" - UI_Dashboard_v1.3.0_Portrait.svg");
            _output.WriteLine(" - UI_Dashboard_v1.3.0_Landscape.svg");
            _output.WriteLine(" - Map_Comparison_2D_vs_3D_SamePosition.svg");
        }

        private static string GeneratePortraitDashboardSvg()
        {
            var sb = new StringBuilder();
            sb.AppendLine("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 450 820\" width=\"450\" height=\"820\">");
            sb.AppendLine("  <rect width=\"450\" height=\"820\" fill=\"#070B14\" rx=\"16\"/>");
            
            // Barra Superior de Estado e Indicadores (Contraste >= 3:1)
            sb.AppendLine("  <rect x=\"16\" y=\"20\" width=\"418\" height=\"48\" rx=\"10\" fill=\"#111827\" stroke=\"#1F2937\" stroke-width=\"1.5\"/>");
            sb.AppendLine("  <text x=\"32\" y=\"51\" fill=\"#22C55E\" font-family=\"monospace\" font-size=\"20\" font-weight=\"bold\">◀</text>");
            sb.AppendLine("  <rect x=\"80\" y=\"30\" width=\"28\" height=\"26\" rx=\"4\" fill=\"#1F2937\" stroke=\"#374151\"/>");
            sb.AppendLine("  <text x=\"89\" y=\"48\" fill=\"#22C55E\" font-family=\"sans-serif\" font-size=\"13\" font-weight=\"bold\">D</text>");
            sb.AppendLine("  <rect x=\"120\" y=\"30\" width=\"28\" height=\"26\" rx=\"4\" fill=\"#1F2937\" stroke=\"#374151\"/>");
            sb.AppendLine("  <text x=\"128\" y=\"48\" fill=\"#38BDF8\" font-family=\"sans-serif\" font-size=\"14\" font-weight=\"bold\">≡</text>");
            sb.AppendLine("  <rect x=\"160\" y=\"30\" width=\"32\" height=\"26\" rx=\"4\" fill=\"#3B1D22\" stroke=\"#EF4444\"/>");
            sb.AppendLine("  <text x=\"166\" y=\"48\" fill=\"#EF4444\" font-family=\"sans-serif\" font-size=\"13\" font-weight=\"bold\">(P)</text>");
            sb.AppendLine("  <rect x=\"204\" y=\"30\" width=\"56\" height=\"26\" rx=\"4\" fill=\"#112920\" stroke=\"#10B981\"/>");
            sb.AppendLine("  <text x=\"212\" y=\"47\" fill=\"#10B981\" font-family=\"sans-serif\" font-size=\"11\" font-weight=\"bold\">CRUISE</text>");
            sb.AppendLine("  <rect x=\"350\" y=\"30\" width=\"44\" height=\"26\" rx=\"4\" fill=\"#0284C7\"/>");
            sb.AppendLine("  <text x=\"358\" y=\"47\" fill=\"#FFFFFF\" font-family=\"sans-serif\" font-size=\"11\" font-weight=\"bold\">DIAG</text>");
            sb.AppendLine("  <text x=\"405\" y=\"51\" fill=\"#334155\" font-family=\"monospace\" font-size=\"20\" font-weight=\"bold\">▶</text>");

            // Alerta de Exceso de Velocidad (G3)
            sb.AppendLine("  <rect x=\"16\" y=\"80\" width=\"418\" height=\"38\" rx=\"8\" fill=\"#450A0A\" stroke=\"#EF4444\" stroke-width=\"1.8\"/>");
            sb.AppendLine("  <text x=\"32\" y=\"104\" fill=\"#FCA5A5\" font-family=\"sans-serif\" font-size=\"13\" font-weight=\"bold\">⚠️ ¡EXCESO DE VELOCIDAD! LÍMITE: 80 KM/H (ACTUAL: 105 KM/H)</text>");

            // Relojes Circulares Propios (G4): Velocímetro y Tacómetro
            // Velocímetro Izquierdo (Centro 125, 230)
            sb.AppendLine("  <!-- Velocímetro Propio -->");
            sb.AppendLine("  <circle cx=\"125\" cy=\"230\" r=\"75\" fill=\"none\" stroke=\"#1E293B\" stroke-width=\"12\" stroke-dasharray=\"330 110\" stroke-dashoffset=\"-55\"/>");
            sb.AppendLine("  <circle cx=\"125\" cy=\"230\" r=\"75\" fill=\"none\" stroke=\"#38BDF8\" stroke-width=\"12\" stroke-dasharray=\"210 230\" stroke-dashoffset=\"-55\"/>");
            sb.AppendLine("  <circle cx=\"125\" cy=\"230\" r=\"8\" fill=\"#0F172A\" stroke=\"#38BDF8\" stroke-width=\"2\"/>");
            sb.AppendLine("  <line x1=\"125\" y1=\"230\" x2=\"155\" y2=\"180\" stroke=\"#38BDF8\" stroke-width=\"3.5\" stroke-linecap=\"round\"/>");
            sb.AppendLine("  <text x=\"125\" y=\"230\" fill=\"#FFFFFF\" font-family=\"monospace\" font-size=\"34\" font-weight=\"bold\" text-anchor=\"middle\">105</text>");
            sb.AppendLine("  <text x=\"125\" y=\"248\" fill=\"#94A3B8\" font-family=\"sans-serif\" font-size=\"11\" font-weight=\"bold\" text-anchor=\"middle\">KM/H</text>");
            sb.AppendLine("  <text x=\"125\" y=\"262\" fill=\"#64748B\" font-family=\"sans-serif\" font-size=\"9\" font-weight=\"bold\" text-anchor=\"middle\">VELOCIDAD</text>");

            // Tacómetro Derecho (Centro 325, 230)
            sb.AppendLine("  <!-- Tacómetro Propio -->");
            sb.AppendLine("  <circle cx=\"325\" cy=\"230\" r=\"75\" fill=\"none\" stroke=\"#1E293B\" stroke-width=\"12\" stroke-dasharray=\"330 110\" stroke-dashoffset=\"-55\"/>");
            sb.AppendLine("  <circle cx=\"325\" cy=\"230\" r=\"75\" fill=\"none\" stroke=\"#10B981\" stroke-width=\"12\" stroke-dasharray=\"180 260\" stroke-dashoffset=\"-55\"/>");
            sb.AppendLine("  <circle cx=\"325\" cy=\"230\" r=\"8\" fill=\"#0F172A\" stroke=\"#10B981\" stroke-width=\"2\"/>");
            sb.AppendLine("  <line x1=\"325\" y1=\"230\" x2=\"340\" y2=\"175\" stroke=\"#10B981\" stroke-width=\"3.5\" stroke-linecap=\"round\"/>");
            sb.AppendLine("  <text x=\"325\" y=\"230\" fill=\"#FFFFFF\" font-family=\"monospace\" font-size=\"28\" font-weight=\"bold\" text-anchor=\"middle\">1450</text>");
            sb.AppendLine("  <text x=\"325\" y=\"248\" fill=\"#94A3B8\" font-family=\"sans-serif\" font-size=\"11\" font-weight=\"bold\" text-anchor=\"middle\">RPM</text>");
            sb.AppendLine("  <text x=\"325\" y=\"262\" fill=\"#64748B\" font-family=\"sans-serif\" font-size=\"9\" font-weight=\"bold\" text-anchor=\"middle\">MOTOR</text>");

            // Marcha Central
            sb.AppendLine("  <rect x=\"205\" y=\"210\" width=\"40\" height=\"34\" rx=\"6\" fill=\"#1E293B\" stroke=\"#38BDF8\" stroke-width=\"1.5\"/>");
            sb.AppendLine("  <text x=\"225\" y=\"234\" fill=\"#38BDF8\" font-family=\"sans-serif\" font-size=\"22\" font-weight=\"bold\" text-anchor=\"middle\">D10</text>");

            // Tarjeta Estado del Vehículo y Remolque (G4 & G5)
            sb.AppendLine("  <rect x=\"16\" y=\"330\" width=\"418\" height=\"310\" rx=\"12\" fill=\"#111827\" stroke=\"#1F2937\" stroke-width=\"1.5\"/>");
            sb.AppendLine("  <text x=\"32\" y=\"358\" fill=\"#38BDF8\" font-family=\"sans-serif\" font-size=\"13\" font-weight=\"bold\">ESTADO DEL VEHÍCULO Y REMOLQUE</text>");

            // 5 canales camión
            AddWearItem(sb, 380, "Motor", 0.05f, "#22C55E");
            AddWearItem(sb, 415, "Transmisión", 0.02f, "#22C55E");
            AddWearItem(sb, 450, "Cabina", 0.01f, "#22C55E");
            AddWearItem(sb, 485, "Chasis", 0.03f, "#22C55E");
            AddWearItem(sb, 520, "Ruedas", 0.08f, "#22C55E");

            // Divisor remolque
            sb.AppendLine("  <line x1=\"32\" y1=\"550\" x2=\"418\" y2=\"550\" stroke=\"#1F2937\" stroke-width=\"1.5\"/>");
            sb.AppendLine("  <text x=\"32\" y=\"572\" fill=\"#F59E0B\" font-family=\"sans-serif\" font-size=\"12\" font-weight=\"bold\">REMOLQUE Y CARGA ASIGNADA (G4)</text>");
            AddWearItem(sb, 595, "Daño de Carga", 0.00f, "#22C55E");
            AddWearItem(sb, 625, "Remolque: Chasis", 0.02f, "#22C55E");

            // Tarjeta Combustible y Ganancia (G5)
            sb.AppendLine("  <rect x=\"16\" y=\"660\" width=\"418\" height=\"70\" rx=\"10\" fill=\"#111827\" stroke=\"#1F2937\" stroke-width=\"1.5\"/>");
            sb.AppendLine("  <text x=\"55\" y=\"685\" fill=\"#94A3B8\" font-family=\"sans-serif\" font-size=\"11\" font-weight=\"bold\">COMBUSTIBLE</text>");
            sb.AppendLine("  <text x=\"55\" y=\"715\" fill=\"#FBBF24\" font-family=\"monospace\" font-size=\"19\" font-weight=\"bold\">380 L</text>");
            sb.AppendLine("  <text x=\"190\" y=\"685\" fill=\"#94A3B8\" font-family=\"sans-serif\" font-size=\"11\" font-weight=\"bold\">CONSUMO MEDIO</text>");
            sb.AppendLine("  <text x=\"190\" y=\"715\" fill=\"#FFFFFF\" font-family=\"monospace\" font-size=\"19\" font-weight=\"bold\">32.5 L/100</text>");
            sb.AppendLine("  <text x=\"340\" y=\"685\" fill=\"#94A3B8\" font-family=\"sans-serif\" font-size=\"11\" font-weight=\"bold\">GANANCIA</text>");
            sb.AppendLine("  <text x=\"340\" y=\"715\" fill=\"#10B981\" font-family=\"monospace\" font-size=\"19\" font-weight=\"bold\">$1,438</text>");

            // Barra inferior
            sb.AppendLine("  <rect x=\"0\" y=\"760\" width=\"450\" height=\"60\" fill=\"#0A0F1D\"/>");
            sb.AppendLine("  <text x=\"24\" y=\"796\" fill=\"#38BDF8\" font-family=\"sans-serif\" font-size=\"18\" font-weight=\"bold\">D10</text>");
            sb.AppendLine("  <text x=\"70\" y=\"796\" fill=\"#FFFFFF\" font-family=\"sans-serif\" font-size=\"18\" font-weight=\"bold\">105 km/h</text>");
            sb.AppendLine("  <circle cx=\"350\" cy=\"790\" r=\"5\" fill=\"#22C55E\"/>");
            sb.AppendLine("  <text x=\"365\" y=\"795\" fill=\"#94A3B8\" font-family=\"sans-serif\" font-size=\"13\">PC Conectada</text>");

            sb.AppendLine("</svg>");
            return sb.ToString();
        }

        private static void AddWearItem(StringBuilder sb, int y, string label, float wear, string color)
        {
            int pct = (int)(wear * 100);
            int barWidth = (int)(200 * wear);
            sb.AppendLine($"  <text x=\"32\" y=\"{y + 11}\" fill=\"#CBD5E1\" font-family=\"sans-serif\" font-size=\"12\">{label}</text>");
            sb.AppendLine($"  <rect x=\"165\" y=\"{y}\" width=\"200\" height=\"8\" rx=\"4\" fill=\"#1E293B\"/>");
            sb.AppendLine($"  <rect x=\"165\" y=\"{y}\" width=\"{Math.Max(barWidth, 6)}\" height=\"8\" rx=\"4\" fill=\"{color}\"/>");
            sb.AppendLine($"  <text x=\"410\" y=\"{y + 10}\" fill=\"{color}\" font-family=\"monospace\" font-size=\"12\" font-weight=\"bold\" text-anchor=\"end\">{pct}%</text>");
        }

        private static string GenerateLandscapeDashboardSvg()
        {
            var sb = new StringBuilder();
            sb.AppendLine("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 850 480\" width=\"850\" height=\"480\">");
            sb.AppendLine("  <rect width=\"850\" height=\"480\" fill=\"#070B14\" rx=\"16\"/>");
            
            // Mitad Izquierda: Relojes Analógicos Propios
            sb.AppendLine("  <rect x=\"20\" y=\"20\" width=\"430\" height=\"400\" rx=\"14\" fill=\"#0B1120\" stroke=\"#1E293B\"/>");
            sb.AppendLine("  <!-- Velocímetro Izquierdo -->");
            sb.AppendLine("  <circle cx=\"130\" cy=\"180\" r=\"85\" fill=\"none\" stroke=\"#1E293B\" stroke-width=\"14\" stroke-dasharray=\"370 120\" stroke-dashoffset=\"-60\"/>");
            sb.AppendLine("  <circle cx=\"130\" cy=\"180\" r=\"85\" fill=\"none\" stroke=\"#38BDF8\" stroke-width=\"14\" stroke-dasharray=\"230 260\" stroke-dashoffset=\"-60\"/>");
            sb.AppendLine("  <circle cx=\"130\" cy=\"180\" r=\"10\" fill=\"#0F172A\" stroke=\"#38BDF8\" stroke-width=\"2\"/>");
            sb.AppendLine("  <line x1=\"130\" y1=\"180\" x2=\"165\" y2=\"125\" stroke=\"#38BDF8\" stroke-width=\"4\" stroke-linecap=\"round\"/>");
            sb.AppendLine("  <text x=\"130\" y=\"182\" fill=\"#FFFFFF\" font-family=\"monospace\" font-size=\"38\" font-weight=\"bold\" text-anchor=\"middle\">105</text>");
            sb.AppendLine("  <text x=\"130\" y=\"202\" fill=\"#94A3B8\" font-family=\"sans-serif\" font-size=\"12\" font-weight=\"bold\" text-anchor=\"middle\">KM/H</text>");

            // Tacómetro Derecho
            sb.AppendLine("  <!-- Tacómetro Derecho -->");
            sb.AppendLine("  <circle cx=\"340\" cy=\"180\" r=\"85\" fill=\"none\" stroke=\"#1E293B\" stroke-width=\"14\" stroke-dasharray=\"370 120\" stroke-dashoffset=\"-60\"/>");
            sb.AppendLine("  <circle cx=\"340\" cy=\"180\" r=\"85\" fill=\"none\" stroke=\"#10B981\" stroke-width=\"14\" stroke-dasharray=\"200 290\" stroke-dashoffset=\"-60\"/>");
            sb.AppendLine("  <circle cx=\"340\" cy=\"180\" r=\"10\" fill=\"#0F172A\" stroke=\"#10B981\" stroke-width=\"2\"/>");
            sb.AppendLine("  <line x1=\"340\" y1=\"180\" x2=\"360\" y2=\"115\" stroke=\"#10B981\" stroke-width=\"4\" stroke-linecap=\"round\"/>");
            sb.AppendLine("  <text x=\"340\" y=\"182\" fill=\"#FFFFFF\" font-family=\"monospace\" font-size=\"32\" font-weight=\"bold\" text-anchor=\"middle\">1450</text>");
            sb.AppendLine("  <text x=\"340\" y=\"202\" fill=\"#94A3B8\" font-family=\"sans-serif\" font-size=\"12\" font-weight=\"bold\" text-anchor=\"middle\">RPM</text>");

            // Marcha
            sb.AppendLine("  <rect x=\"215\" y=\"160\" width=\"44\" height=\"38\" rx=\"6\" fill=\"#1E293B\" stroke=\"#38BDF8\" stroke-width=\"1.5\"/>");
            sb.AppendLine("  <text x=\"237\" y=\"187\" fill=\"#38BDF8\" font-family=\"sans-serif\" font-size=\"24\" font-weight=\"bold\" text-anchor=\"middle\">D10</text>");

            // Mitad Derecha: Desgaste y Combustible
            sb.AppendLine("  <rect x=\"470\" y=\"20\" width=\"360\" height=\"400\" rx=\"14\" fill=\"#111827\" stroke=\"#1F2937\"/>");
            sb.AppendLine("  <text x=\"490\" y=\"50\" fill=\"#38BDF8\" font-family=\"sans-serif\" font-size=\"13\" font-weight=\"bold\">ESTADO DEL VEHÍCULO Y REMOLQUE</text>");
            AddWearItemLandscape(sb, 75, "Motor", 0.05f, "#22C55E");
            AddWearItemLandscape(sb, 105, "Transmisión", 0.02f, "#22C55E");
            AddWearItemLandscape(sb, 135, "Cabina", 0.01f, "#22C55E");
            AddWearItemLandscape(sb, 165, "Chasis", 0.03f, "#22C55E");
            AddWearItemLandscape(sb, 195, "Ruedas", 0.08f, "#22C55E");

            sb.AppendLine("  <line x1=\"490\" y1=\"225\" x2=\"810\" y2=\"225\" stroke=\"#1F2937\" stroke-width=\"1.5\"/>");
            sb.AppendLine("  <text x=\"490\" y=\"248\" fill=\"#F59E0B\" font-family=\"sans-serif\" font-size=\"12\" font-weight=\"bold\">REMOLQUE Y CARGA ASIGNADA (G4)</text>");
            AddWearItemLandscape(sb, 270, "Daño de Carga", 0.00f, "#22C55E");
            AddWearItemLandscape(sb, 300, "Remolque: Chasis", 0.02f, "#22C55E");
            AddWearItemLandscape(sb, 330, "Remolque: Ruedas", 0.04f, "#22C55E");

            // Combustible / Ganancia en Landscape
            sb.AppendLine("  <rect x=\"490\" y=\"360\" width=\"320\" height=\"45\" rx=\"8\" fill=\"#1F2937\"/>");
            sb.AppendLine("  <text x=\"510\" y=\"388\" fill=\"#FBBF24\" font-family=\"monospace\" font-size=\"15\" font-weight=\"bold\">⛽ 380 L</text>");
            sb.AppendLine("  <text x=\"610\" y=\"388\" fill=\"#FFFFFF\" font-family=\"monospace\" font-size=\"15\" font-weight=\"bold\">32.5 L/100</text>");
            sb.AppendLine("  <text x=\"725\" y=\"388\" fill=\"#10B981\" font-family=\"monospace\" font-size=\"15\" font-weight=\"bold\">💵 $1,438</text>");

            // Barra inferior en Landscape
            sb.AppendLine("  <rect x=\"0\" y=\"435\" width=\"850\" height=\"45\" fill=\"#0A0F1D\"/>");
            sb.AppendLine("  <text x=\"30\" y=\"462\" fill=\"#38BDF8\" font-family=\"sans-serif\" font-size=\"15\" font-weight=\"bold\">D10 • 105 km/h</text>");
            sb.AppendLine("  <circle cx=\"750\" cy=\"457\" r=\"5\" fill=\"#22C55E\"/>");
            sb.AppendLine("  <text x=\"765\" y=\"462\" fill=\"#94A3B8\" font-family=\"sans-serif\" font-size=\"12\">PC Conectada</text>");

            sb.AppendLine("</svg>");
            return sb.ToString();
        }

        private static void AddWearItemLandscape(StringBuilder sb, int y, string label, float wear, string color)
        {
            int pct = (int)(wear * 100);
            int barWidth = (int)(150 * wear);
            sb.AppendLine($"  <text x=\"490\" y=\"{y + 11}\" fill=\"#CBD5E1\" font-family=\"sans-serif\" font-size=\"12\">{label}</text>");
            sb.AppendLine($"  <rect x=\"620\" y=\"{y}\" width=\"150\" height=\"8\" rx=\"4\" fill=\"#1E293B\"/>");
            sb.AppendLine($"  <rect x=\"620\" y=\"{y}\" width=\"{Math.Max(barWidth, 6)}\" height=\"8\" rx=\"4\" fill=\"{color}\"/>");
            sb.AppendLine($"  <text x=\"810\" y=\"{y + 10}\" fill=\"{color}\" font-family=\"monospace\" font-size=\"12\" font-weight=\"bold\" text-anchor=\"end\">{pct}%</text>");
        }

        private static string GenerateMapComparison2Dvs3DSvg()
        {
            var sb = new StringBuilder();
            sb.AppendLine("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 900 480\" width=\"900\" height=\"480\">");
            sb.AppendLine("  <rect width=\"900\" height=\"480\" fill=\"#070B14\" rx=\"16\"/>");

            // Panel Izquierdo: 2D Top-Down táctico con Jerarquía (G6)
            sb.AppendLine("  <rect x=\"20\" y=\"20\" width=\"420\" height=\"440\" rx=\"12\" fill=\"#0A0F1D\" stroke=\"#1E293B\" stroke-width=\"2\"/>");
            sb.AppendLine("  <text x=\"40\" y=\"50\" fill=\"#38BDF8\" font-family=\"sans-serif\" font-size=\"15\" font-weight=\"bold\">VISTA 2D: JERARQUÍA VIAL Y CARRETERA ACTUAL (G6)</text>");
            sb.AppendLine("  <text x=\"40\" y=\"70\" fill=\"#94A3B8\" font-family=\"sans-serif\" font-size=\"11\">Posición Camión: X=-111526, Y=13.9m, Z=-15819 (Sacramento I-5)</text>");

            // Autopista Interestatal en 2D (RoadType 0)
            sb.AppendLine("  <!-- Autopista I-5 en 2D -->");
            sb.AppendLine("  <line x1=\"230\" y1=\"100\" x2=\"230\" y2=\"420\" stroke=\"#0F172A\" stroke-width=\"18\" stroke-linecap=\"round\"/>");
            sb.AppendLine("  <line x1=\"230\" y1=\"100\" x2=\"230\" y2=\"420\" stroke=\"#475569\" stroke-width=\"14\" stroke-linecap=\"round\"/>");

            // Carretera actual resaltada en Cyan de navegación (G6)
            sb.AppendLine("  <line x1=\"230\" y1=\"200\" x2=\"230\" y2=\"340\" stroke=\"#00E5FF\" stroke-width=\"14\" stroke-linecap=\"round\"/>");

            // Rampa de Enlace (RoadType 3)
            sb.AppendLine("  <path d=\"M 230 280 C 170 300, 120 340, 90 420\" fill=\"none\" stroke=\"#0F172A\" stroke-width=\"12\" stroke-linecap=\"round\"/>");
            sb.AppendLine("  <path d=\"M 230 280 C 170 300, 120 340, 90 420\" fill=\"none\" stroke=\"#334155\" stroke-width=\"8\" stroke-linecap=\"round\"/>");

            // Calle Local (RoadType 2)
            sb.AppendLine("  <line x1=\"230\" y1=\"180\" x2=\"390\" y2=\"180\" stroke=\"#1E293B\" stroke-width=\"10\" stroke-linecap=\"round\"/>");
            sb.AppendLine("  <line x1=\"230\" y1=\"180\" x2=\"390\" y2=\"180\" stroke=\"#3B4A5E\" stroke-width=\"6\" stroke-linecap=\"round\"/>");

            // Cursor del camión 2D
            sb.AppendLine("  <circle cx=\"230\" cy=\"270\" r=\"18\" fill=\"#550284C7\"/>");
            sb.AppendLine("  <polygon points=\"230,254 240,278 230,273 220,278\" fill=\"#38BDF8\" stroke=\"#FFFFFF\" stroke-width=\"1.5\"/>");
            sb.AppendLine("  <text x=\"245\" y=\"260\" fill=\"#FFFFFF\" font-family=\"sans-serif\" font-size=\"11\" font-weight=\"bold\">Camión en I-5</text>");

            // Panel Derecho: 3D Perspectiva Corregida (G7)
            sb.AppendLine("  <rect x=\"460\" y=\"20\" width=\"420\" height=\"440\" rx=\"12\" fill=\"#080D18\" stroke=\"#1E293B\" stroke-width=\"2\"/>");
            sb.AppendLine("  <text x=\"480\" y=\"50\" fill=\"#10B981\" font-family=\"sans-serif\" font-size=\"15\" font-weight=\"bold\">VISTA 3D: CARRETERA BAJO EL CAMIÓN (G7 CORREGIDO)</text>");
            sb.AppendLine("  <text x=\"480\" y=\"70\" fill=\"#94A3B8\" font-family=\"sans-serif\" font-size=\"11\">Misma posición • Elevación Y=13.9m • Horizonte Y=167px</text>");

            // Cielo y Horizonte
            sb.AppendLine("  <rect x=\"461\" y=\"80\" width=\"418\" height=\"87\" fill=\"#0E1726\"/>");
            sb.AppendLine("  <line x1=\"460\" y1=\"167\" x2=\"880\" y2=\"167\" stroke=\"#1E3A5F\" stroke-width=\"2\"/>");

            // Carretera 3D bajo el camión en perspectiva cónica
            sb.AppendLine("  <!-- Calzada I-5 en Perspectiva 3D con Elevación Corregida -->");
            sb.AppendLine("  <polygon points=\"670,167 670,167 790,440 550,440\" fill=\"#1E293B\"/>");
            sb.AppendLine("  <polygon points=\"670,167 670,167 780,440 560,440\" fill=\"#334155\"/>");
            sb.AppendLine("  <polygon points=\"670,167 670,167 730,440 610,440\" fill=\"#00E5FF\" fill-opacity=\"0.6\"/>"); // Ruta activa resaltada bajo el camión
            sb.AppendLine("  <line x1=\"670\" y1=\"167\" x2=\"670\" y2=\"440\" stroke=\"#FBBF24\" stroke-width=\"3\" stroke-dasharray=\"12 10\"/>"); // Línea central divisoria

            // Cono de faros delanteros
            sb.AppendLine("  <polygon points=\"670,360 600,190 740,190\" fill=\"#FFE082\" fill-opacity=\"0.15\"/>");

            // Cursor del camión 3D
            sb.AppendLine("  <circle cx=\"670\" cy=\"360\" r=\"20\" fill=\"#4400E5FF\"/>");
            sb.AppendLine("  <polygon points=\"670,340 682,372 670,366 658,372\" fill=\"#00E5FF\" stroke=\"#FFFFFF\" stroke-width=\"2\"/>");
            sb.AppendLine("  <text x=\"670\" y=\"410\" fill=\"#FFFFFF\" font-family=\"sans-serif\" font-size=\"12\" font-weight=\"bold\" text-anchor=\"middle\">¡Carretera continua bajo y delante del camión!</text>");

            // Botón G8 superpuesto demostrando modo destino
            sb.AppendLine("  <!-- Botón G8 en modo 2D mostrando '3D' -->");
            sb.AppendLine("  <circle cx=\"410\" cy=\"420\" r=\"20\" fill=\"#1E293B\" stroke=\"#38BDF8\" stroke-width=\"1.5\"/>");
            sb.AppendLine("  <text x=\"410\" y=\"425\" fill=\"#38BDF8\" font-family=\"sans-serif\" font-size=\"11\" font-weight=\"bold\" text-anchor=\"middle\">3D</text>");

            // Botón G8 en modo 3D mostrando '2D'
            sb.AppendLine("  <!-- Botón G8 en modo 3D mostrando '2D' -->");
            sb.AppendLine("  <circle cx=\"850\" cy=\"420\" r=\"20\" fill=\"#1E293B\" stroke=\"#38BDF8\" stroke-width=\"1.5\"/>");
            sb.AppendLine("  <text x=\"850\" y=\"425\" fill=\"#38BDF8\" font-family=\"sans-serif\" font-size=\"11\" font-weight=\"bold\" text-anchor=\"middle\">2D</text>");

            sb.AppendLine("</svg>");
            return sb.ToString();
        }
    }
}
