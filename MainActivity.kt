package com.gestor.archivos

import android.Manifest
import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.res.ColorStateList
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.media.ExifInterface
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.storage.StorageManager
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.TextUtils
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.webkit.MimeTypeMap
import android.widget.*
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipInputStream
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter

private fun c(v: Long): Int = v.toInt()

private class Item(val f: File, val size: Long, val mod: Long, val cat: Int) {
    val key: String = f.name.lowercase()
    val ext: String = f.extension.lowercase()
}

private class DupGroup(val files: List<File>, val size: Long)
private class FolderStat(val dir: File, val files: Int, val bytes: Long, val direct: Int)
private class Sugerencia(val icon: String, val titulo: String, val detalle: String, val accion: String, val hacer: () -> Unit)
private class CleanScan(
    val rootPath: String, val time: Long, val files: Int, val bytes: Long, val complete: Boolean, val note: String?, val skipped: Int,
    val dups: List<DupGroup>, val big: List<Item>, val folders: List<FolderStat>, val crowded: List<FolderStat>,
    val similar: List<List<File>>, val generic: Array<HashMap<String, ArrayList<File>>>, val mediaByDir: Array<HashMap<String, Int>>,
    val docClash: List<List<File>>, val docMixed: List<Pair<File, Int>>
)

private data class Seccion(val titulo: String, val puntos: List<String>)
private data class Entrada(val version: String, val fecha: String?, val titulo: String, val resumen: String, val secciones: List<Seccion>)
private data class Fondo(val nombre: String, val top: Int, val bottom: Int, val card: Int)
private data class Acento(val nombre: String, val c1: Int, val c2: Int)
private class Plan(val orig: File, val newName: String, val status: String, val ok: Boolean)
private class MEntry(val icon: Int, val label: String, val danger: Boolean, val group: Int, val dot: Boolean, val action: () -> Unit)

class MainActivity : Activity() {

    // ---------- Historial de novedades ----------
    // REGLA: una sola entrada por actualización, con todos sus cambios agrupados por sección
    // (Novedades, Mejoras visuales, Mejoras, Correcciones). Se agrega al FINAL de la lista.
    // Versión X.Y.Z: cada novedad individual suma 1 al último número; al llegar a 10 sube el del medio.
    // Al actualizar: agrega la entrada aquí y sube versionCode/versionName en build.gradle.kts.
    private val historial = listOf(
        Entrada("0.1.0", "13 oct 2026", "Primera versión de Nexora",
            "El punto de partida: un explorador para ver y manejar los archivos de tu teléfono.",
            listOf(Seccion("Novedades", listOf(
                "Acceso a todo el almacenamiento del teléfono",
                "Vista previa de fotos, videos y PDF",
                "Extraer archivos ZIP",
                "Renombrar y eliminar archivos y carpetas")))),
        Entrada("0.1.4", null, "Nexora estrena identidad",
            "La app recibe su nombre, su ícono y un diseño oscuro en azul.",
            listOf(
                Seccion("Novedades", listOf(
                    "Nombre Nexora, con título en degradado",
                    "Mensaje con lo nuevo al abrir la app después de cada actualización")),
                Seccion("Mejoras visuales", listOf(
                    "Diseño oscuro con acentos en azul eléctrico",
                    "Colores propios para cada tipo de archivo",
                    "Ícono nuevo: una N en degradado sobre fondo azul")))),
        Entrada("0.2.1", null, "Menú, personalización y orden",
            "Nexora suma un menú completo, se adapta a tu estilo y te deja organizar los archivos a tu manera.",
            listOf(
                Seccion("Novedades", listOf(
                    "Menú de tres puntos con Perfil (aún en desarrollo), Novedades, Personalización y Configuración",
                    "Personalización: 5 fondos, 6 colores principales y 5 tipografías, que se guardan y se aplican al instante",
                    "Ordenar por nombre, tamaño, fecha de modificación o tipo, con opción de mostrar las carpetas primero",
                    "Categorías: imágenes, videos, audio, documentos, comprimidos, APK y otros",
                    "Historial de novedades con punto verde para lo que aún no has visto",
                    "Configuración con el estado del permiso de archivos y la versión instalada")),
                Seccion("Mejoras visuales", listOf(
                    "Íconos de carpeta renovados, con más detalle y color")))),
        Entrada("0.2.2", null, "Tu propia imagen de fondo",
            "Personaliza Nexora con una foto de tu galería.",
            listOf(Seccion("Novedades", listOf(
                "Elige una foto de tu galería como fondo de toda la app",
                "Se oscurece un poco para que todo se lea bien",
                "Puedes cambiarla o quitarla cuando quieras")))),
        Entrada("0.2.3", null, "Novedades más claras",
            "El historial ahora muestra una sola entrada por actualización, con todos sus cambios agrupados.",
            listOf(
                Seccion("Novedades", listOf(
                    "Una entrada por actualización, con versión, resumen y cambios organizados por sección",
                    "Puedes abrir o cerrar los detalles de cada versión anterior",
                    "El punto verde avisa cuando hay una actualización completa sin ver")),
                Seccion("Mejoras", listOf(
                    "Las versiones anteriores se reorganizaron sin perder información")))),
        Entrada("0.2.5", null, "Renombre múltiple (beta)",
            "Renombra varios archivos de una sola vez con un nombre común y numeración automática, viendo antes cómo quedarán.",
            listOf(
                Seccion("Novedades", listOf(
                    "Renombre múltiple (beta): nombre común, numeración automática y extensión original conservada, para cualquier tipo de archivo",
                    "Selección múltiple: mantén pulsado un archivo y toca los demás")),
                Seccion("Mejoras", listOf(
                    "Vista previa en vivo con el nombre original, el nuevo y el estado de cada archivo",
                    "Numeración configurable: separador, número inicial, ceros iniciales y orden (selección, nombre, fecha o tamaño)",
                    "Nunca sobrescribe: avisa de los conflictos antes de renombrar",
                    "Resumen final con opción de deshacer todo el renombrado")))),
        Entrada("0.3.2", null, "Acciones completas y miniaturas",
            "Gestiona tus archivos sin salir de Nexora y reconoce tus fotos de un vistazo.",
            listOf(
                Seccion("Novedades", listOf(
                    "Miniaturas reales de las imágenes en la lista, con caché para que carguen rápido",
                    "Copiar archivos a otra carpeta",
                    "Mover archivos a otra carpeta",
                    "Compartir archivos con las opciones de Android",
                    "Información del archivo: nombre, formato, tamaño, ubicación y fecha",
                    "Eliminar uno o varios archivos, con confirmación y resumen de resultados",
                    "Seleccionar todos los archivos de la lista")),
                Seccion("Mejoras", listOf(
                    "Barra de acciones compacta que se desliza y muestra solo lo disponible en cada caso",
                    "Renombrar para un archivo y Renombre múltiple para dos o más, siempre por separado",
                    "Renombre múltiple: ejemplo con «Foto» y nuevo orden «Lista» (el mismo del explorador)",
                    "Cada operación informa qué archivos se procesaron y cuáles fallaron",
                    "Extraer ZIP desde la barra de acciones")),
                Seccion("Correcciones", listOf(
                    "Con un solo archivo seleccionado, Renombrar ya aparece directamente en la barra")))),
        Entrada("0.3.3", null, "Menú más limpio",
            "El menú de tres puntos ahora se despliega justo debajo del botón, con íconos propios.",
            listOf(Seccion("Mejoras visuales", listOf(
                "Menú desplegable bajo los tres puntos, con las opciones una debajo de otra",
                "Íconos dibujados a medida, sin emojis, y fondo sólido con borde sutil",
                "Se cierra al elegir una opción, al tocar fuera o al pulsar otra vez los tres puntos",
                "Se ajusta a los bordes de la pantalla para no salirse")))),
        Entrada("0.3.4", null, "Menú de archivos renovado",
            "Las acciones de un archivo ahora salen en un menú vertical, junto a él y mejor organizadas.",
            listOf(
                Seccion("Novedades", listOf(
                    "Menú vertical junto al archivo al mantenerlo pulsado, con las acciones agrupadas y Eliminar separado")),
                Seccion("Mejoras visuales", listOf(
                    "Íconos dibujados a medida en lugar de emojis",
                    "Barra de selección compacta con Cancelar y Acciones",
                    "El menú se coloca arriba o abajo según el espacio y no se sale de la pantalla")),
                Seccion("Mejoras", listOf(
                    "Las carpetas tienen su menú con Abrir, Renombrar y Eliminar")))),
        Entrada("0.3.8", null, "Convertidor de archivos",
            "Convierte imágenes, audio y PDF sin salir de Nexora y sin subir nada a internet.",
            listOf(
                Seccion("Novedades", listOf(
                    "Convertidor de imágenes entre JPG, PNG y WebP, con calidad y color de fondo para las transparencias",
                    "Imágenes a PDF, una por una o unidas en un solo PDF",
                    "Páginas de un PDF a imágenes PNG o JPG, con la resolución que elijas",
                    "Audio a M4A (AAC) o WAV, con elección de bitrate")),
                Seccion("Mejoras", listOf(
                    "Convertir formato en el menú de los archivos compatibles, también para varios a la vez",
                    "Progreso y resultado de cada archivo; si uno falla, sigue con los demás",
                    "Los originales no se tocan y nunca se sobrescribe: si el nombre ya existe se agrega (1)",
                    "Todo se procesa dentro del teléfono")))),
        Entrada("0.3.9", null, "Calidad HD y ULTRA HD en el convertidor",
            "El convertidor ahora ofrece tres niveles de calidad que cambian el procesamiento real de cada archivo.",
            listOf(
                Seccion("Novedades", listOf(
                    "Niveles Predeterminada, HD y ULTRA HD en el convertidor")),
                Seccion("Mejoras", listOf(
                    "JPG y WebP: calidad de compresión 85, 92 o 98 % (WebP sin pérdida en ULTRA HD cuando el sistema lo permite)",
                    "HD y ULTRA HD amplían las imágenes pequeñas a 1920 o 3840 px por interpolación, y el resultado indica la resolución real obtenida",
                    "Imágenes a PDF: 1600, 2400 o 3600 px por página. PDF a imágenes: 150, 200 o 300 ppp",
                    "Audio a M4A: 128, 192 o 320 kbps",
                    "Reemplazan a los controles anteriores de calidad, bitrate y resolución")))),
        Entrada("0.4.2", null, "Mejorar imágenes con IA",
            "Una sección nueva para ampliar fotos y mejorar sus detalles con una red neuronal que se ejecuta en tu teléfono.",
            listOf(
                Seccion("Novedades", listOf(
                    "Mejorar imágenes con IA (Real-ESRGAN): ampliación 2× y 4×, menos ruido y reconstrucción de texturas",
                    "Comparación antes y después con barra deslizante, zoom y las dimensiones de cada versión",
                    "Guardado de la imagen mejorada como archivo nuevo, con nombre, carpeta y formato a elección")),
                Seccion("Mejoras", listOf(
                    "Ampliación clásica (sin IA) como alternativa y para comparar el resultado",
                    "El modelo de IA (unos 5 MB) se descarga solo con tu permiso: tus imágenes nunca salen del teléfono",
                    "Progreso con tiempo restante, cancelación y avisos de memoria",
                    "Puedes volver al original antes de guardar y nunca se sobrescribe un archivo")))),
        Entrada("0.4.3", null, "Mejorar imágenes: proceso a la vista",
            "Al pulsar Mejorar ahora ves tu imagen y el avance real del trabajo, y los errores se explican con opciones para reintentar.",
            listOf(
                Seccion("Novedades", listOf(
                    "Pantalla de procesamiento con tu imagen a la vista, etapas reales y un porcentaje que solo refleja trabajo ya hecho")),
                Seccion("Mejoras", listOf(
                    "Si falta el modelo de IA, Mejorar ofrece descargarlo y continúa solo al terminar",
                    "Los errores se explican con opciones para reintentar, descargar el modelo otra vez o usar la ampliación clásica",
                    "Aviso claro cuando la imagen supera el tamaño permitido para la ampliación elegida")),
                Seccion("Correcciones", listOf(
                    "El botón Mejorar ya no queda inactivo cuando el modelo de IA no está instalado")))),
        Entrada("0.4.4", null, "Mejorar imágenes: fotos grandes",
            "Las fotos de teléfono ya se pueden mejorar: si superan el límite, puedes reducirlas antes de procesarlas.",
            listOf(
                Seccion("Novedades", listOf(
                    "Opción de reducir y mejorar para las fotos que superan el tamaño permitido, indicando a qué tamaño se reduce y qué resultado esperar")),
                Seccion("Mejoras", listOf(
                    "Aviso de batería baja antes de usar la IA",
                    "El resultado indica cuando la imagen fue reducida y no muestra un factor de ampliación engañoso")),
                Seccion("Correcciones", listOf(
                    "El aviso de tamaño ya no sugiere elegir 2× cuando 2× ya está elegido")))),
        Entrada("0.4.6", null, "Todo tu almacenamiento",
            "Nexora ahora muestra la tarjeta SD y las memorias USB, y te explica por qué algunas carpetas del sistema no se pueden abrir.",
            listOf(
                Seccion("Novedades", listOf(
                    "Selector de almacenamiento para cambiar entre la memoria interna, la tarjeta SD y las memorias USB conectadas",
                    "Aviso claro en las carpetas que Android protege (Android/data y Android/obb) en vez de mostrarlas vacías")),
                Seccion("Mejoras", listOf(
                    "Si quitas la tarjeta SD o la memoria USB mientras la usabas, Nexora vuelve sola a la memoria interna")))),
        Entrada("0.5.1", null, "Limpieza inteligente y accesos rápidos",
            "Convertir y Limpiar quedan a un toque, el orden de tus archivos ya no se pierde y Nexora te ayuda a encontrar duplicados y espacio ocupado.",
            listOf(
                Seccion("Novedades", listOf(
                    "Botón Convertir en la parte superior: abre directamente el convertidor",
                    "Limpiar: análisis del almacenamiento con informe, indicador de progreso y opción de cancelar",
                    "Duplicados exactos confirmados por contenido, con el espacio recuperable y eliminación solo con tu confirmación",
                    "Archivos grandes por tipo y carpetas que más ocupan, con acceso directo a su ubicación",
                    "Recomendaciones según el tipo de archivo y posibles fotos similares, marcadas como sin confirmar")),
                Seccion("Mejoras visuales", listOf(
                    "Convertir y Limpiar ocupan el lugar donde estaba «Almacenamiento interno»; la ruta de la carpeta sigue visible debajo")),
                Seccion("Correcciones", listOf(
                    "El orden elegido se guarda al instante y se conserva al pasar la aplicación a segundo plano"))))
    )

    private val pendientes = listOf(
        "Perfil: en desarrollo, todavía sin funciones.",
        "Ordenar por fecha de creación: Android no entrega ese dato de forma fiable.",
        "Renombre múltiple de carpetas: se evaluará como mejora futura.",
        "Copiar, mover y compartir carpetas: por ahora solo funcionan con archivos.",
        "Miniaturas de videos: por ahora solo se muestran las de imágenes.",
        "Convertir a MP3: Android no incluye codificador de MP3 y Nexora no tiene una biblioteca para ello.",
        "Convertir videos: requiere una biblioteca adicional (por ejemplo Media3 Transformer) que todavía no está incorporada.",
        "PDF a Word y otros documentos: no se puede conservar el formato con las herramientas actuales.",
        "Mejorar imágenes: una imagen a la vez; entrada de hasta 1 MP en 4× y 2 MP en 2× (las fotos más grandes se reducen antes de mejorarlas); se procesa en el procesador del teléfono, sin GPU.",
        "Vista en cuadrícula: aún no existe.",
        "Extraer RAR y 7z: por ahora solo se extraen ZIP.",
        "Limpiar: el análisis se guarda solo mientras la app está abierta y se cancela si sales de la pantalla.",
        "Posibles fotos similares: solo se comparan por nombre base dentro de la misma carpeta, no por el contenido de la imagen.",
        "Android/data y Android/obb: desde Android 11 el sistema bloquea su lectura a todas las apps normales; abrirlas requeriría un método avanzado (por ejemplo Shizuku)."
    )

    // ---------- Opciones de personalización ----------
    private val ACENTOS = listOf(
        Acento("Azul", c(0xFF3D7BFF), c(0xFF22D3EE)),
        Acento("Violeta", c(0xFF8B5CF6), c(0xFFC4B5FD)),
        Acento("Esmeralda", c(0xFF10B981), c(0xFF6EE7B7)),
        Acento("Rosa", c(0xFFEC4899), c(0xFFF9A8D4)),
        Acento("Ámbar", c(0xFFF59E0B), c(0xFFFCD34D)),
        Acento("Cian", c(0xFF06B6D4), c(0xFF67E8F9))
    )
    private val FONDOS = listOf(
        Fondo("Medianoche", c(0xFF0D1B4F), c(0xFF070B18), c(0xFF111B3D)),
        Fondo("Aurora", c(0xFF08403C), c(0xFF06121A), c(0xFF0C2A2E)),
        Fondo("Nebulosa", c(0xFF2D1660), c(0xFF0A0716), c(0xFF1B1238)),
        Fondo("Carbón", c(0xFF262B38), c(0xFF0B0D12), c(0xFF191D27)),
        Fondo("Océano", c(0xFF0B4F82), c(0xFF04101C), c(0xFF0D2842))
    )
    private val FUENTES = listOf(
        "Predeterminada" to "sans-serif",
        "Moderna" to "sans-serif-medium",
        "Condensada" to "sans-serif-condensed",
        "Clásica" to "serif",
        "Mono" to "monospace"
    )
    private val SORTS = listOf("Nombre: A → Z", "Nombre: Z → A", "Tamaño: de mayor a menor", "Tamaño: de menor a mayor", "Fecha de modificación: más reciente primero", "Fecha de modificación: más antigua primero", "Tipo de archivo")
    private val SHORT = listOf("Nombre A–Z", "Nombre Z–A", "Mayor tamaño", "Menor tamaño", "Más reciente", "Más antiguo", "Tipo")
    private val CATS = listOf("Todo", "Imágenes", "Videos", "Audio", "Documentos", "Comprimidos", "APK", "Otros")

    // ---------- Tema (se carga de las preferencias) ----------
    private var ACC = c(0xFF3D7BFF)
    private var ACC2 = c(0xFF22D3EE)
    private var BG1 = c(0xFF0D1B4F)
    private var BG2 = c(0xFF070B18)
    private var CARD = c(0xFF111B3D)
    private val TXT = c(0xFFE8EEFF)
    private val MUTE = c(0xFF8D9AC0)
    private val GREEN = c(0xFF34D399)
    private var fontIdx = 0
    private var sortIdx = 0
    private var sortReady = false
    private var foldersFirst = true
    private var catIdx = 0
    private var bgBitmap: Bitmap? = null
    private val selected = LinkedHashSet<File>()
    private lateinit var selBar: LinearLayout
    private lateinit var selWrap: HorizontalScrollView
    private var menuPop: PopupWindow? = null
    private var menuClosedAt = 0L
    private val convFiles = ArrayList<File>()
    private var convDest: File? = null
    private var convTarget = 0
    private var convLevel = 0
    private var convBg = Color.WHITE
    private var convMerge = true
    private var convName = ""
    @Volatile private var convCancel = false
    private var converting = false
    private var convHead: TextView? = null
    private var convOverall: ProgressBar? = null
    private var pageScroll: ScrollView? = null
    private var curPage = ""
    private var aiFile: File? = null
    private var aiOrig: Bitmap? = null
    private var aiResult: Bitmap? = null
    private var aiMethod = 0
    private var aiScale = 1
    private var aiInfo = ""
    private var aiError = ""
    private var aiName = ""
    private var aiFmt = 0
    private var aiDest: File? = null
    private var aiShrink = false
    private var aiShrunkUsed = false
    private var aiDlPct = -1
    private var aiDlBar: ProgressBar? = null
    private var aiDlLabel: TextView? = null
    @Volatile private var aiCancel = false
    private val dateFull = SimpleDateFormat("d MMM yyyy, HH:mm", Locale("es"))
    private val thumbPx: Int get() = dp(52)
    private val memCache = object : android.util.LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 10).toInt()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val failedThumbs: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet<String>())
    private val thumbPool = java.util.concurrent.ThreadPoolExecutor(3, 3, 30L, java.util.concurrent.TimeUnit.SECONDS, LifoQueue())
    init {
        thumbPool.allowCoreThreadTimeOut(true)
    }

    private val prefs by lazy { getSharedPreferences("nexora", MODE_PRIVATE) }
    private val dateFmt = SimpleDateFormat("d MMM yyyy", Locale("es"))

    private lateinit var root: File
    private lateinit var dir: File
    private var rootName = "Almacenamiento interno"
    private lateinit var pathView: TextView
    private lateinit var list: ListView
    private lateinit var emptyView: TextView
    private lateinit var toolsRow: LinearLayout
    private var items: List<Item> = emptyList()
    private var counts = IntArray(8)
    private var inViewer = false
    @Volatile private var token = 0

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        loadTheme()
        root = Environment.getExternalStorageDirectory()
        dir = root
        Thread { trimCache() }.start()
    }

    override fun onResume() {
        super.onResume()
        if (hasAccess()) {
            if (!root.exists()) {
                root = Environment.getExternalStorageDirectory()
                rootName = "Almacenamiento interno"
                dir = root
            } else if (!dir.exists()) {
                dir = root
            }
            if (!inViewer) {
                showBrowser()
                showWhatsNew()
            }
        } else {
            showAskAccess()
        }
    }

    // ---------- utilidades ----------
    private fun dp(n: Int): Int = (n * resources.displayMetrics.density).toInt()
    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()
    private fun alpha(col: Int, a: Int): Int = (col and 0x00FFFFFF) or (a shl 24)
    private fun lp(w: Int, h: Int, wt: Float = 0f) = LinearLayout.LayoutParams(w, h, wt)

    private fun mix(a: Int, b: Int, t: Float): Int = Color.rgb(
        (Color.red(a) * (1 - t) + Color.red(b) * t).toInt(),
        (Color.green(a) * (1 - t) + Color.green(b) * t).toInt(),
        (Color.blue(a) * (1 - t) + Color.blue(b) * t).toInt()
    )

    private fun loadTheme() {
        val a = ACENTOS[prefs.getInt("accent", 0).coerceIn(0, ACENTOS.size - 1)]
        ACC = a.c1
        ACC2 = a.c2
        val f = FONDOS[prefs.getInt("bg", 0).coerceIn(0, FONDOS.size - 1)]
        BG1 = f.top
        BG2 = f.bottom
        CARD = f.card
        fontIdx = prefs.getInt("font", 0).coerceIn(0, FUENTES.size - 1)
        if (!sortReady) {
            sortIdx = prefs.getInt("sort", 0).coerceIn(0, SORTS.size - 1)
            foldersFirst = prefs.getBoolean("folders", true)
            sortReady = true
        }
        val bf = File(filesDir, "fondo.jpg")
        bgBitmap = if (prefs.getBoolean("bgCustom", false) && bf.exists()) BitmapFactory.decodeFile(bf.path) else null
        window.statusBarColor = if (bgBitmap != null) c(0xFF05070C) else BG1
        window.navigationBarColor = if (bgBitmap != null) c(0xFF05070C) else BG2
    }

    private fun screenBg(): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(BG1, BG2))

    private fun withBg(content: View): View {
        val bm = bgBitmap
        if (bm == null) {
            content.background = screenBg()
            return content
        }
        val fl = FrameLayout(this)
        val iv = ImageView(this)
        iv.scaleType = ImageView.ScaleType.CENTER_CROP
        iv.setImageBitmap(bm)
        fl.addView(iv, FrameLayout.LayoutParams(-1, -1))
        val scrim = View(this)
        scrim.setBackgroundColor(alpha(Color.BLACK, 0x99))
        fl.addView(scrim, FrameLayout.LayoutParams(-1, -1))
        fl.addView(content, FrameLayout.LayoutParams(-1, -1))
        return fl
    }

    private fun face(bold: Boolean): Typeface =
        Typeface.create(FUENTES[fontIdx].second, if (bold) Typeface.BOLD else Typeface.NORMAL)

    private fun txt(s: String, size: Float, color: Int, bold: Boolean = false): TextView {
        val t = TextView(this)
        t.text = s
        t.textSize = size
        t.setTextColor(color)
        t.typeface = face(bold)
        return t
    }

    private fun rounded(color: Int, r: Int): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(color)
        g.cornerRadius = dp(r).toFloat()
        return g
    }

    private fun dot(): View {
        val v = View(this)
        val g = GradientDrawable()
        g.shape = GradientDrawable.OVAL
        g.setColor(GREEN)
        v.background = g
        return v
    }

    private fun wordmark(size: Float): TextView {
        val t = txt("NEXORA", size, ACC2, true)
        t.letterSpacing = 0.2f
        t.paint.shader = LinearGradient(0f, 0f, dp(170).toFloat(), 0f, ACC2, ACC, Shader.TileMode.CLAMP)
        return t
    }

    private fun primaryButton(text: String): Button {
        val b = Button(this)
        b.text = text
        b.isAllCaps = false
        b.setTextColor(Color.WHITE)
        b.typeface = face(true)
        val g = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(ACC, mix(ACC, Color.BLACK, 0.35f)))
        g.cornerRadius = dp(14).toFloat()
        b.background = g
        return b
    }

    private fun ghostButton(text: String): Button {
        val b = Button(this)
        b.text = text
        b.isAllCaps = false
        b.setTextColor(TXT)
        b.typeface = face(true)
        b.background = rounded(alpha(Color.WHITE, 0x1A), 14)
        return b
    }

    private fun fmt(b: Long): String =
        if (b < 1048576) maxOf(1L, b / 1024).toString() + " KB" else String.format("%.1f MB", b / 1048576.0)

    private fun showSheet(bottom: Boolean = true, build: (Dialog) -> View): Dialog {
        val d = Dialog(this)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.setPadding(dp(18), dp(18), dp(18), dp(18))
        val g = rounded(CARD, 24)
        g.setStroke(dp(1), alpha(ACC2, 0x55))
        card.background = g
        card.addView(build(d))
        val wrap = FrameLayout(this)
        wrap.setPadding(dp(12), dp(12), dp(12), dp(12))
        wrap.addView(card, FrameLayout.LayoutParams(-1, -2))
        d.setContentView(wrap)
        d.show()
        val w = d.window
        if (w != null) {
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            w.setLayout(-1, -2)
            w.setGravity(if (bottom) Gravity.BOTTOM else Gravity.CENTER)
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        return d
    }

    private fun optionRow(label: String, selected: Boolean): TextView {
        val t = txt(label, 15f, if (selected) ACC2 else TXT, selected)
        t.setPadding(dp(14), dp(13), dp(14), dp(13))
        if (selected) t.background = rounded(alpha(ACC, 0x2E), 12)
        return t
    }

    private fun confirmSheet(title: String, msg: String, ok: String, onOk: () -> Unit) {
        showSheet(false) { d ->
            val col = LinearLayout(this)
            col.orientation = LinearLayout.VERTICAL
            col.addView(txt(title, 18f, TXT, true))
            if (msg.isNotEmpty()) col.addView(txt(msg, 14f, MUTE), lp(-1, -2).also { it.topMargin = dp(8) })
            val row = LinearLayout(this)
            val no = ghostButton("Cancelar")
            no.setOnClickListener { d.dismiss() }
            val yes = primaryButton(ok)
            yes.setOnClickListener { d.dismiss(); onOk() }
            row.addView(no, lp(0, dp(48), 1f))
            row.addView(yes, lp(0, dp(48), 1f).also { it.leftMargin = dp(10) })
            col.addView(row, lp(-1, -2).also { it.topMargin = dp(18) })
            col
        }
    }

    // ---------- permisos ----------
    private fun hasAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager()
        else checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    private fun showAskAccess() {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = Gravity.CENTER
        col.setPadding(dp(28), dp(24), dp(28), dp(24))
        val t = txt("Para mostrar tus archivos, Nexora necesita acceso a todos los archivos del teléfono.", 17f, TXT)
        t.gravity = Gravity.CENTER
        val btn = primaryButton("Dar acceso")
        btn.setOnClickListener { askAccess() }
        col.addView(wordmark(30f))
        col.addView(t, lp(-2, -2).also { it.topMargin = dp(20); it.bottomMargin = dp(24) })
        col.addView(btn, lp(-1, dp(52)))
        setContentView(withBg(col))
    }

    private fun askAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)
        }
    }

    // ---------- novedades ----------
    private fun migrate(old: Int): Int = if (old >= 12) 4 else if (old >= 11) 3 else if (old >= 4) 2 else if (old >= 0) 1 else 0
    private fun seenEntries(): Int = if (prefs.contains("seenE")) prefs.getInt("seenE", 0) else migrate(prefs.getInt("seenN", -1))
    private fun hasUnseen(): Boolean = historial.size > seenEntries()

    private fun bullet(s: String, mark: String, size: Float, color: Int): View {
        val row = LinearLayout(this)
        val m = txt(mark, size, ACC2, true)
        m.setPadding(0, 0, dp(10), 0)
        row.addView(m)
        row.addView(txt(s, size, color), lp(0, -2, 1f))
        return row
    }

    private fun showWhatsNew() {
        val last = historial.last()
        val shown = if (prefs.contains("dlgE")) prefs.getInt("dlgE", 0) else migrate(prefs.getInt("dlg", -1))
        if (shown >= historial.size) return
        prefs.edit().putInt("dlgE", historial.size).apply()
        val puntos = last.secciones.flatMap { it.puntos }.take(5)
        showSheet(false) { d ->
            val col = LinearLayout(this)
            col.orientation = LinearLayout.VERTICAL
            val chip = txt("NOVEDADES  •  v" + last.version, 11f, ACC2, true)
            chip.letterSpacing = 0.12f
            chip.setPadding(dp(12), dp(6), dp(12), dp(6))
            chip.background = rounded(alpha(ACC2, 0x22), 20)
            col.addView(chip, lp(-2, -2))
            col.addView(txt(last.titulo, 24f, Color.WHITE, true), lp(-1, -2).also { it.topMargin = dp(14) })
            col.addView(txt(last.resumen, 14f, MUTE), lp(-1, -2).also { it.topMargin = dp(6) })
            for (p in puntos) col.addView(bullet(p, "◆", 14f, TXT), lp(-1, -2).also { it.topMargin = dp(10) })
            val btns = LinearLayout(this)
            val hist = ghostButton("Ver historial")
            hist.setOnClickListener { d.dismiss(); showNews() }
            val go = primaryButton("¡Vamos!")
            go.setOnClickListener { d.dismiss() }
            btns.addView(hist, lp(0, dp(50), 1f))
            btns.addView(go, lp(0, dp(50), 1f).also { it.leftMargin = dp(10) })
            col.addView(btns, lp(-1, -2).also { it.topMargin = dp(22) })
            col
        }
    }

    // ---------- páginas del menú ----------
    private fun page(title: String, content: View) {
        inViewer = true
        curPage = title
        token++
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        val bar = LinearLayout(this)
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setPadding(dp(16), dp(14), dp(16), dp(10))
        val back = txt("‹", 30f, TXT, true)
        back.setPadding(dp(4), 0, dp(14), 0)
        bar.addView(back)
        bar.addView(txt(title, 20f, TXT, true))
        bar.setOnClickListener { closeViewer() }
        val sv = ScrollView(this)
        sv.addView(content)
        pageScroll = sv
        col.addView(bar, lp(-1, -2))
        col.addView(sv, lp(-1, 0, 1f))
        setContentView(withBg(col))
    }

    private fun pageFixed(title: String, content: View) {
        inViewer = true
        curPage = title
        token++
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        val bar = LinearLayout(this)
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setPadding(dp(16), dp(14), dp(16), dp(10))
        val back = txt("‹", 30f, TXT, true)
        back.setPadding(dp(4), 0, dp(14), 0)
        bar.addView(back)
        bar.addView(txt(title, 20f, TXT, true))
        bar.setOnClickListener { closeViewer() }
        col.addView(bar, lp(-1, -2))
        col.addView(content, lp(-1, 0, 1f))
        setContentView(withBg(col))
    }

    private fun body(): LinearLayout {
        val b = LinearLayout(this)
        b.orientation = LinearLayout.VERTICAL
        b.setPadding(dp(16), dp(6), dp(16), dp(28))
        return b
    }

    private fun cardBox(): LinearLayout {
        val b = LinearLayout(this)
        b.orientation = LinearLayout.VERTICAL
        b.setPadding(dp(16), dp(14), dp(16), dp(14))
        b.background = rounded(alpha(CARD, 0xE6), 18)
        return b
    }

    private fun showProfile() {
        val b = body()
        b.gravity = Gravity.CENTER_HORIZONTAL
        val av = txt("👤", 44f, TXT)
        av.gravity = Gravity.CENTER
        val g = GradientDrawable()
        g.shape = GradientDrawable.OVAL
        g.setColor(alpha(ACC, 0x33))
        g.setStroke(dp(2), ACC2)
        av.background = g
        b.addView(av, lp(dp(110), dp(110)).also { it.topMargin = dp(40) })
        b.addView(txt("Perfil", 24f, TXT, true), lp(-2, -2).also { it.topMargin = dp(18) })
        val chip = txt("En desarrollo", 13f, ACC2, true)
        chip.setPadding(dp(14), dp(6), dp(14), dp(6))
        chip.background = rounded(alpha(ACC2, 0x22), 20)
        b.addView(chip, lp(-2, -2).also { it.topMargin = dp(12) })
        val d = txt("Esta sección todavía no tiene funciones. Estará disponible en una próxima versión.", 14f, MUTE)
        d.gravity = Gravity.CENTER
        b.addView(d, lp(-1, -2).also { it.topMargin = dp(16) })
        page("Perfil", b)
    }

    private fun iconoSeccion(t: String): String = when (t) {
        "Novedades" -> "✨"
        "Mejoras visuales" -> "🎨"
        "Mejoras" -> "🔧"
        "Correcciones" -> "🛠️"
        else -> "•"
    }

    private fun showNews() {
        val prev = seenEntries()
        prefs.edit().putInt("seenE", historial.size).apply()
        val b = body()
        b.addView(txt("Versión actual: " + historial.last().version, 14f, MUTE), lp(-2, -2).also { it.bottomMargin = dp(14) })
        for (idx in historial.indices.reversed()) {
            val e = historial[idx]
            val nueva = idx >= prev
            val open = nueva || idx == historial.size - 1
            val row = LinearLayout(this)
            val rail = FrameLayout(this)
            if (idx > 0) {
                val line = View(this)
                line.setBackgroundColor(alpha(ACC2, 0x55))
                rail.addView(line, FrameLayout.LayoutParams(dp(2), -1, Gravity.CENTER_HORIZONTAL))
            }
            val pt = View(this)
            val pg = GradientDrawable()
            pg.shape = GradientDrawable.OVAL
            pg.setColor(if (nueva) GREEN else ACC2)
            pt.background = pg
            rail.addView(pt, FrameLayout.LayoutParams(dp(12), dp(12), Gravity.TOP or Gravity.CENTER_HORIZONTAL).also { it.topMargin = dp(18) })
            row.addView(rail, lp(dp(26), -1))

            val card = cardBox()
            val head = LinearLayout(this)
            head.gravity = Gravity.CENTER_VERTICAL
            val v = txt("v" + e.version, 12f, ACC2, true)
            v.setPadding(dp(10), dp(4), dp(10), dp(4))
            v.background = rounded(alpha(ACC2, 0x22), 20)
            head.addView(v)
            if (e.fecha != null) head.addView(txt(e.fecha, 12f, MUTE), lp(-2, -2).also { it.leftMargin = dp(10) })
            if (nueva) {
                val nt = txt("NUEVA", 11f, GREEN, true)
                nt.setPadding(dp(10), dp(4), dp(10), dp(4))
                nt.background = rounded(alpha(GREEN, 0x22), 20)
                head.addView(nt, lp(-2, -2).also { it.leftMargin = dp(10) })
            }
            card.addView(head)
            card.addView(txt(e.titulo, 17f, TXT, true), lp(-1, -2).also { it.topMargin = dp(8) })
            card.addView(txt(e.resumen, 13f, MUTE), lp(-1, -2).also { it.topMargin = dp(4) })

            val details = LinearLayout(this)
            details.orientation = LinearLayout.VERTICAL
            for (sec in e.secciones) {
                details.addView(txt(iconoSeccion(sec.titulo) + "  " + sec.titulo, 13f, ACC2, true), lp(-1, -2).also { it.topMargin = dp(12); it.bottomMargin = dp(2) })
                for (p in sec.puntos) details.addView(bullet(p, "•", 13f, TXT), lp(-1, -2).also { it.topMargin = dp(5) })
            }
            details.visibility = if (open) View.VISIBLE else View.GONE
            card.addView(details, lp(-1, -2))
            val hint = txt(if (open) "Ocultar detalles ⌃" else "Ver detalles ⌄", 12f, ACC2)
            card.addView(hint, lp(-1, -2).also { it.topMargin = dp(10) })
            card.setOnClickListener {
                val show = details.visibility != View.VISIBLE
                details.visibility = if (show) View.VISIBLE else View.GONE
                hint.text = if (show) "Ocultar detalles ⌃" else "Ver detalles ⌄"
            }
            row.addView(card, lp(0, -2, 1f).also { it.bottomMargin = dp(12) })
            b.addView(row, lp(-1, -2))
        }
        b.addView(txt("Pendiente", 18f, TXT, true), lp(-2, -2).also { it.topMargin = dp(14); it.bottomMargin = dp(8) })
        val pc = cardBox()
        for (p in pendientes) pc.addView(txt("○  " + p, 13f, MUTE), lp(-1, -2).also { it.topMargin = dp(4) })
        b.addView(pc, lp(-1, -2))
        page("Novedades", b)
    }

    private fun sectionTitle(s: String): TextView {
        val t = txt(s, 17f, TXT, true)
        t.setPadding(0, dp(18), 0, dp(10))
        return t
    }

    private fun showPersonalization() {
        val b = body()
        val selBg = prefs.getInt("bg", 0)
        val selAc = prefs.getInt("accent", 0)
        val selFo = prefs.getInt("font", 0)

        b.addView(sectionTitle("Fondos"))
        val bgFile = File(filesDir, "fondo.jpg")
        val hasImg = bgFile.exists()
        val custom = prefs.getBoolean("bgCustom", false) && hasImg
        val hs1 = HorizontalScrollView(this)
        hs1.isHorizontalScrollBarEnabled = false
        val r1 = LinearLayout(this)
        for ((i, f) in FONDOS.withIndex()) {
            val sel = i == selBg && !custom
            val tile = LinearLayout(this)
            tile.orientation = LinearLayout.VERTICAL
            val pv = View(this)
            val g = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(f.top, f.bottom))
            g.cornerRadius = dp(14).toFloat()
            if (sel) g.setStroke(dp(3), ACC2) else g.setStroke(dp(1), alpha(Color.WHITE, 0x33))
            pv.background = g
            tile.addView(pv, lp(dp(88), dp(60)))
            tile.addView(txt(f.nombre, 11f, if (sel) ACC2 else TXT), lp(-2, -2).also { it.topMargin = dp(6) })
            tile.setOnClickListener {
                prefs.edit().putInt("bg", i).putBoolean("bgCustom", false).apply()
                loadTheme()
                showPersonalization()
            }
            r1.addView(tile, lp(-2, -2).also { it.rightMargin = dp(12) })
        }
        val own = LinearLayout(this)
        own.orientation = LinearLayout.VERTICAL
        val ov: View
        if (hasImg) {
            val o = BitmapFactory.Options()
            o.inSampleSize = 8
            val iv = ImageView(this)
            iv.setImageBitmap(BitmapFactory.decodeFile(bgFile.path, o))
            iv.scaleType = ImageView.ScaleType.CENTER_CROP
            val og = rounded(alpha(Color.WHITE, 0x10), 14)
            if (custom) og.setStroke(dp(3), ACC2) else og.setStroke(dp(1), alpha(Color.WHITE, 0x33))
            iv.background = og
            iv.clipToOutline = true
            ov = iv
        } else {
            val tv = txt("🖼", 22f, MUTE)
            tv.gravity = Gravity.CENTER
            tv.background = rounded(alpha(Color.WHITE, 0x10), 14)
            ov = tv
        }
        own.addView(ov, lp(dp(88), dp(60)))
        own.addView(txt(if (hasImg) "Tu imagen" else "Elegir imagen", 11f, if (custom) ACC2 else TXT), lp(-2, -2).also { it.topMargin = dp(6) })
        own.setOnClickListener {
            if (hasImg) {
                prefs.edit().putBoolean("bgCustom", true).apply()
                loadTheme()
                showPersonalization()
            } else {
                pickImage()
            }
        }
        r1.addView(own)
        hs1.addView(r1)
        b.addView(hs1)
        if (hasImg) {
            val acts = LinearLayout(this)
            acts.addView(chip("Cambiar imagen", false) { pickImage() }, lp(-2, -2).also { it.rightMargin = dp(8) })
            acts.addView(chip("Quitar imagen", false) {
                bgFile.delete()
                prefs.edit().putBoolean("bgCustom", false).apply()
                loadTheme()
                showPersonalization()
            }, lp(-2, -2))
            b.addView(acts, lp(-2, -2).also { it.topMargin = dp(12) })
        }
        b.addView(txt("Tu imagen se oscurece un poco para que todo se lea bien.", 12f, MUTE), lp(-2, -2).also { it.topMargin = dp(10) })

        b.addView(sectionTitle("Color principal"))
        val hs2 = HorizontalScrollView(this)
        hs2.isHorizontalScrollBarEnabled = false
        val r2 = LinearLayout(this)
        for ((i, a) in ACENTOS.withIndex()) {
            val tile = LinearLayout(this)
            tile.orientation = LinearLayout.VERTICAL
            tile.gravity = Gravity.CENTER_HORIZONTAL
            val circle = txt(if (i == selAc) "✓" else "", 18f, Color.WHITE, true)
            circle.gravity = Gravity.CENTER
            val g = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(a.c1, a.c2))
            g.shape = GradientDrawable.OVAL
            if (i == selAc) g.setStroke(dp(3), Color.WHITE)
            circle.background = g
            tile.addView(circle, lp(dp(48), dp(48)))
            tile.addView(txt(a.nombre, 11f, if (i == selAc) ACC2 else TXT), lp(-2, -2).also { it.topMargin = dp(6) })
            tile.setOnClickListener {
                prefs.edit().putInt("accent", i).apply()
                loadTheme()
                showPersonalization()
            }
            r2.addView(tile, lp(-2, -2).also { it.rightMargin = dp(14) })
        }
        hs2.addView(r2)
        b.addView(hs2)

        b.addView(sectionTitle("Tipografía"))
        val hs3 = HorizontalScrollView(this)
        hs3.isHorizontalScrollBarEnabled = false
        val r3 = LinearLayout(this)
        for ((i, f) in FUENTES.withIndex()) {
            val t = TextView(this)
            t.text = f.first
            t.textSize = 15f
            t.typeface = Typeface.create(f.second, Typeface.NORMAL)
            t.setTextColor(if (i == selFo) Color.WHITE else TXT)
            t.setPadding(dp(16), dp(11), dp(16), dp(11))
            t.background = rounded(if (i == selFo) ACC else alpha(Color.WHITE, 0x16), 20)
            t.setOnClickListener {
                prefs.edit().putInt("font", i).apply()
                loadTheme()
                showPersonalization()
            }
            r3.addView(t, lp(-2, -2).also { it.rightMargin = dp(10) })
        }
        hs3.addView(r3)
        b.addView(hs3)
        b.addView(txt("Tus elecciones se guardan y se aplican al instante.", 12f, MUTE), lp(-2, -2).also { it.topMargin = dp(18) })
        page("Personalización", b)
    }

    private fun showSettings() {
        val b = body()
        val perm = cardBox()
        perm.addView(txt("Acceso a archivos", 16f, TXT, true))
        perm.addView(txt(if (hasAccess()) "Concedido: Nexora puede ver todo el almacenamiento." else "Pendiente: Nexora necesita este permiso para mostrar tus archivos.", 13f, MUTE), lp(-1, -2).also { it.topMargin = dp(4) })
        val btn = ghostButton("Revisar permiso")
        btn.setOnClickListener { askAccess() }
        perm.addView(btn, lp(-1, dp(46)).also { it.topMargin = dp(12) })
        b.addView(perm, lp(-1, -2).also { it.bottomMargin = dp(12) })

        val ver = cardBox()
        ver.addView(txt("Versión instalada", 16f, TXT, true))
        @Suppress("DEPRECATION")
        val vn = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { null }
        ver.addView(txt("Nexora " + (vn ?: historial.last().version), 13f, MUTE), lp(-1, -2).also { it.topMargin = dp(4) })
        b.addView(ver, lp(-1, -2).also { it.bottomMargin = dp(12) })

        val info = cardBox()
        info.addView(txt("Más ajustes, pronto", 16f, TXT, true))
        info.addView(txt("Esta sección irá creciendo con nuevas versiones. La apariencia se cambia en Personalización y el orden de los archivos desde el explorador.", 13f, MUTE), lp(-1, -2).also { it.topMargin = dp(4) })
        b.addView(info, lp(-1, -2))
        page("Configuración", b)
    }

    // ---------- explorador ----------
    private fun showBrowser() {
        inViewer = false
        cleanNav.clear()
        cleanProgTxt = null
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL

        val head = LinearLayout(this)
        head.orientation = LinearLayout.VERTICAL
        head.setPadding(dp(20), dp(16), dp(16), dp(10))
        val top = LinearLayout(this)
        top.gravity = Gravity.CENTER_VERTICAL
        top.addView(wordmark(28f), lp(0, -2, 1f))
        val dots = txt("⋮", 24f, TXT, true)
        dots.gravity = Gravity.CENTER
        dots.background = rounded(alpha(ACC, 0x33), 14)
        dots.setOnClickListener { openMenu(dots, null, dotsEntries()) }
        top.addView(dots, lp(dp(44), dp(44)))
        head.addView(top, lp(-1, -2))
        val qa = LinearLayout(this)
        qa.addView(quickBtn("⇄", "Convertir") { openConverter(null) }, lp(0, dp(44), 1f))
        qa.addView(quickBtn("🧹", "Limpiar") { openClean() }, lp(0, dp(44), 1f).also { it.leftMargin = dp(10) })
        head.addView(qa, lp(-1, -2).also { it.topMargin = dp(10) })
        pathView = txt("", 12f, MUTE)
        pathView.setPadding(dp(4), dp(8), dp(4), 0)
        pathView.isSingleLine = true
        pathView.ellipsize = TextUtils.TruncateAt.START
        head.addView(pathView, lp(-1, -2))

        val hs = HorizontalScrollView(this)
        hs.isHorizontalScrollBarEnabled = false
        toolsRow = LinearLayout(this)
        toolsRow.setPadding(dp(16), dp(4), dp(8), dp(10))
        hs.addView(toolsRow)

        list = ListView(this)
        list.divider = null
        list.dividerHeight = 0
        list.selector = ColorDrawable(Color.TRANSPARENT)
        list.setPadding(0, dp(2), 0, dp(12))
        list.clipToPadding = false
        emptyView = txt("", 15f, MUTE)
        emptyView.gravity = Gravity.CENTER
        val frame = FrameLayout(this)
        frame.addView(list, FrameLayout.LayoutParams(-1, -1))
        frame.addView(emptyView, FrameLayout.LayoutParams(-1, -1))
        list.emptyView = emptyView

        col.addView(head, lp(-1, -2))
        col.addView(hs, lp(-1, -2))
        col.addView(frame, lp(-1, 0, 1f))
        selBar = LinearLayout(this)
        selBar.gravity = Gravity.CENTER_VERTICAL
        selBar.setPadding(dp(6), dp(6), dp(6), dp(6))
        selWrap = HorizontalScrollView(this)
        selWrap.isHorizontalScrollBarEnabled = false
        selWrap.isFillViewport = true
        val sbg = rounded(alpha(CARD, 0xF5), 20)
        sbg.setStroke(dp(1), alpha(ACC2, 0x55))
        selWrap.background = sbg
        selWrap.visibility = View.GONE
        selWrap.addView(selBar)
        col.addView(selWrap, lp(-1, -2).also { it.leftMargin = dp(12); it.rightMargin = dp(12); it.bottomMargin = dp(10) })
        setContentView(withBg(col))
        list.setOnItemClickListener { _, _, i, _ -> onTap(items[i].f) }
        list.setOnItemLongClickListener { _, v, i, _ -> onLong(items[i].f, v); true }
        refresh()
    }

    private fun catOf(f: File): Int {
        val e = f.extension.lowercase()
        return when (e) {
            "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "svg" -> 1
            "mp4", "mkv", "webm", "3gp", "mov", "avi", "m4v" -> 2
            "mp3", "m4a", "wav", "ogg", "flac", "aac", "opus", "amr" -> 3
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "rtf", "odt", "csv", "epub", "md" -> 4
            "zip", "rar", "7z", "tar", "gz", "tgz", "bz2" -> 5
            "apk", "xapk", "apks" -> 6
            else -> {
                val m = if (e.isEmpty()) null else MimeTypeMap.getSingleton().getMimeTypeFromExtension(e)
                when {
                    m != null && m.startsWith("image/") -> 1
                    m != null && m.startsWith("video/") -> 2
                    m != null && m.startsWith("audio/") -> 3
                    m != null && m.startsWith("text/") -> 4
                    else -> 7
                }
            }
        }
    }

    private fun sortItems(l: List<Item>): List<Item> {
        val byName = compareBy<Item> { it.key }
        val main: Comparator<Item> = when (sortIdx) {
            0 -> byName
            1 -> byName.reversed()
            2 -> compareByDescending<Item> { it.size }.then(byName)
            3 -> compareBy<Item> { it.size }.then(byName)
            4 -> compareByDescending<Item> { it.mod }.then(byName)
            5 -> compareBy<Item> { it.mod }.then(byName)
            else -> compareBy<Item> { it.ext }.then(byName)
        }
        return if (foldersFirst) l.sortedWith(compareBy<Item> { it.cat >= 0 }.then(main)) else l.sortedWith(main)
    }

    private fun extraVolumes(): List<Pair<String, File>> {
        val out = ArrayList<Pair<String, File>>()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return out
        try {
            val sm = getSystemService(Context.STORAGE_SERVICE) as StorageManager
            for (v in sm.storageVolumes) {
                if (v.isPrimary || v.state != Environment.MEDIA_MOUNTED) continue
                var f: File? = null
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    f = v.directory
                } else {
                    try {
                        val path = v.javaClass.getMethod("getPath").invoke(v) as? String
                        if (path != null) f = File(path)
                    } catch (e: Exception) {
                    }
                }
                if (f != null && f.exists()) out.add((v.getDescription(this) ?: "Almacenamiento externo") to f)
            }
        } catch (e: Exception) {
        }
        return out
    }

    private fun storageSheet() {
        val vols = listOf("Almacenamiento interno" to Environment.getExternalStorageDirectory()) + extraVolumes()
        showSheet { d ->
            val col = LinearLayout(this)
            col.orientation = LinearLayout.VERTICAL
            col.addView(txt("Almacenamiento", 18f, TXT, true))
            for ((name, f) in vols) {
                val r = optionRow(name, f.absolutePath == root.absolutePath)
                r.setOnClickListener {
                    d.dismiss()
                    root = f
                    rootName = name
                    dir = f
                    refresh()
                }
                col.addView(r, lp(-1, -2).also { it.topMargin = dp(4) })
            }
            col
        }
    }

    private fun restrictedMsg(): String {
        val p = dir.absolutePath
        val protegida = p.contains("/Android/data") || p.contains("/Android/obb")
        return if (protegida && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            "Android protege esta carpeta (datos de otras apps) desde Android 11 y Nexora todavía no puede abrirla."
        else "Nexora no puede leer esta carpeta."
    }

    private fun refresh() {
        val raw = dir.listFiles()
        val all = (raw ?: emptyArray<File>()).map { f ->
            Item(f, if (f.isDirectory) 0L else f.length(), f.lastModified(), if (f.isDirectory) -1 else catOf(f))
        }
        selected.retainAll(all.map { it.f }.toSet())
        counts = IntArray(CATS.size)
        for (x in all) {
            if (x.cat > 0) {
                counts[x.cat]++
                counts[0]++
            }
        }
        items = sortItems(all.filter { x -> x.cat < 0 || catIdx == 0 || x.cat == catIdx })
        pathView.text = dir.absolutePath.removePrefix(root.absolutePath).ifEmpty { "/" }
        emptyView.text = if (raw == null) restrictedMsg() else if (all.isEmpty()) "Esta carpeta está vacía" else "No hay archivos de esta categoría aquí"
        list.adapter = RowAdapter()
        buildTools()
        updateSelBar()
    }

    private fun chip(label: String, selected: Boolean, onClick: () -> Unit): TextView {
        val t = txt(label, 13f, if (selected) Color.WHITE else TXT, selected)
        t.setPadding(dp(14), dp(8), dp(14), dp(8))
        t.background = rounded(if (selected) ACC else alpha(Color.WHITE, 0x16), 20)
        t.setOnClickListener { onClick() }
        return t
    }

    private fun buildTools() {
        toolsRow.removeAllViews()
        if (extraVolumes().isNotEmpty()) {
            val st = chip("💾  " + rootName + " ▾", false) { storageSheet() }
            toolsRow.addView(st, lp(-2, -2).also { it.rightMargin = dp(8) })
        }
        val pill = txt("⇅  " + SHORT[sortIdx], 13f, ACC2, true)
        pill.setPadding(dp(14), dp(8), dp(14), dp(8))
        val pg = rounded(alpha(ACC, 0x2E), 20)
        pg.setStroke(dp(1), ACC2)
        pill.background = pg
        pill.setOnClickListener { sortSheet() }
        toolsRow.addView(pill, lp(-2, -2).also { it.rightMargin = dp(8) })
        for (i in CATS.indices) {
            if (i == 0 || counts[i] > 0 || i == catIdx) {
                val ct = chip(CATS[i] + " " + counts[i], i == catIdx) {
                    catIdx = i
                    refresh()
                }
                toolsRow.addView(ct, lp(-2, -2).also { it.rightMargin = dp(8) })
            }
        }
    }

    private fun sortSheet() {
        showSheet { d ->
            val col = LinearLayout(this)
            col.orientation = LinearLayout.VERTICAL
            col.addView(txt("Ordenar por", 18f, TXT, true))
            for (i in SORTS.indices) {
                val r = optionRow(SORTS[i], i == sortIdx)
                r.setOnClickListener {
                    sortIdx = i
                    saveSort()
                    d.dismiss()
                    refresh()
                }
                col.addView(r, lp(-1, -2).also { it.topMargin = dp(4) })
            }
            val tg = optionRow((if (foldersFirst) "✓  " else "○  ") + "Mostrar carpetas primero", false)
            tg.setOnClickListener {
                foldersFirst = !foldersFirst
                saveSort()
                d.dismiss()
                refresh()
            }
            col.addView(tg, lp(-1, -2).also { it.topMargin = dp(12) })
            col
        }
    }

    private inner class FolderIcon(ctx: Context, val col: Int) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(cv: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val r = w * 0.12f
            p.shader = null
            p.color = mix(col, Color.BLACK, 0.35f)
            cv.drawRoundRect(RectF(w * 0.08f, h * 0.16f, w * 0.48f, h * 0.40f), r, r, p)
            cv.drawRoundRect(RectF(w * 0.08f, h * 0.24f, w * 0.92f, h * 0.80f), r, r, p)
            p.color = alpha(Color.WHITE, 0xD0)
            cv.drawRoundRect(RectF(w * 0.18f, h * 0.30f, w * 0.82f, h * 0.50f), r * 0.6f, r * 0.6f, p)
            p.shader = LinearGradient(0f, h * 0.34f, 0f, h * 0.86f, mix(col, Color.WHITE, 0.30f), col, Shader.TileMode.CLAMP)
            cv.drawRoundRect(RectF(w * 0.08f, h * 0.36f, w * 0.92f, h * 0.86f), r, r, p)
        }
    }

    private fun badgeOf(it: Item): Pair<String, Int> = when (it.cat) {
        1 -> Pair("IMG", c(0xFF22D3EE))
        2 -> Pair("VID", c(0xFF8B5CF6))
        3 -> Pair("AUD", c(0xFFF472B6))
        4 -> Pair(if (it.ext == "pdf") "PDF" else "DOC", if (it.ext == "pdf") c(0xFFFB7185) else c(0xFF60A5FA))
        5 -> Pair("ZIP", c(0xFFFBBF24))
        6 -> Pair("APK", GREEN)
        else -> Pair(if (it.ext.isEmpty()) "•" else it.ext.uppercase().take(4), MUTE)
    }

    private inner class RowAdapter : BaseAdapter() {
        override fun getCount(): Int = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int): Long = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val it0 = items[position]
            val wrap = FrameLayout(this@MainActivity)
            wrap.setPadding(dp(12), dp(4), dp(12), dp(4))
            val row = LinearLayout(this@MainActivity)
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(dp(12), dp(11), dp(12), dp(11))
            val isSel = selected.contains(it0.f)
            val rbg = rounded(if (isSel) alpha(ACC, 0x40) else alpha(CARD, 0xCC), 16)
            if (isSel) rbg.setStroke(dp(2), ACC2)
            row.background = rbg

            val sz = dp(52)
            if (it0.cat < 0) {
                row.addView(FolderIcon(this@MainActivity, ACC), lp(sz, sz))
            } else {
                val k = badgeOf(it0)
                val lead = FrameLayout(this@MainActivity)
                val badge = txt(k.first, 12f, k.second, true)
                badge.gravity = Gravity.CENTER
                badge.background = rounded(alpha(k.second, 0x26), 13)
                lead.addView(badge, FrameLayout.LayoutParams(-1, -1))
                if (it0.cat == 1) {
                    val iv = ImageView(this@MainActivity)
                    iv.scaleType = ImageView.ScaleType.CENTER_CROP
                    iv.outlineProvider = object : android.view.ViewOutlineProvider() {
                        override fun getOutline(v: View, o: android.graphics.Outline) {
                            o.setRoundRect(0, 0, v.width, v.height, dp(13).toFloat())
                        }
                    }
                    iv.clipToOutline = true
                    lead.addView(iv, FrameLayout.LayoutParams(-1, -1))
                    bindThumb(it0, iv, badge)
                }
                row.addView(lead, lp(sz, sz))
            }

            val col = LinearLayout(this@MainActivity)
            col.orientation = LinearLayout.VERTICAL
            val name = txt(it0.f.name, 15f, TXT)
            name.isSingleLine = true
            name.ellipsize = TextUtils.TruncateAt.END
            val date = if (it0.mod > 0) "  •  " + dateFmt.format(Date(it0.mod)) else ""
            val sub = txt((if (it0.cat < 0) "Carpeta" else fmt(it0.size)) + date, 12f, MUTE)
            col.addView(name)
            col.addView(sub)
            row.addView(col, lp(0, -2, 1f).also { it.leftMargin = dp(14) })
            if (selected.isNotEmpty() && it0.cat >= 0) {
                val mk = txt(if (isSel) "✓" else "○", 16f, if (isSel) ACC2 else MUTE, true)
                mk.setPadding(dp(10), 0, dp(4), 0)
                row.addView(mk)
            }
            wrap.addView(row, FrameLayout.LayoutParams(-1, -2))
            return wrap
        }
    }

    // ---------- selección múltiple y renombre múltiple (beta) ----------
    private fun onTap(f: File) {
        if (selected.isNotEmpty()) {
            if (f.isDirectory) toast("Las carpetas no se pueden seleccionar") else toggle(f)
        } else {
            tap(f)
        }
    }

    private fun onLong(f: File, v: View) {
        if (f.isDirectory) {
            if (selected.isEmpty()) openMenu(v, f.name, folderEntries(f)) else toast("Las carpetas no se pueden seleccionar")
            return
        }
        val first = selected.isEmpty()
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        val rl = loc[0]
        val rt = loc[1]
        val rr = loc[0] + v.width
        val rb = loc[1] + v.height
        toggle(f)
        if (first) openMenuAt(rl, rt, rr, rb, f.name, fileEntries(selected.toList()))
    }

    private fun toggle(f: File) {
        if (!selected.add(f)) selected.remove(f)
        refreshSel()
    }

    private fun refreshSel() {
        (list.adapter as? BaseAdapter)?.notifyDataSetChanged()
        updateSelBar()
    }

    private fun updateSelBar() {
        selBar.removeAllViews()
        if (selected.isEmpty()) {
            selWrap.visibility = View.GONE
            return
        }
        selWrap.visibility = View.VISIBLE
        val cancel = LinearLayout(this)
        cancel.gravity = Gravity.CENTER_VERTICAL
        cancel.setPadding(dp(12), dp(10), dp(14), dp(10))
        cancel.background = rounded(alpha(Color.WHITE, 0x16), 16)
        cancel.addView(MenuIcon(this, 16, TXT), lp(dp(16), dp(16)))
        cancel.addView(txt("Cancelar", 13f, TXT, true), lp(-2, -2).also { it.leftMargin = dp(8) })
        cancel.setOnClickListener {
            selected.clear()
            refreshSel()
        }
        selBar.addView(cancel)
        val n = selected.size
        selBar.addView(txt(if (n == 1) "1 seleccionado" else n.toString() + " seleccionados", 13f, MUTE, true), lp(0, -2, 1f).also { it.leftMargin = dp(12); it.rightMargin = dp(8) })
        val acc = LinearLayout(this)
        acc.gravity = Gravity.CENTER_VERTICAL
        acc.setPadding(dp(14), dp(10), dp(16), dp(10))
        val ag = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(ACC, mix(ACC, Color.BLACK, 0.35f)))
        ag.cornerRadius = dp(16).toFloat()
        acc.background = ag
        acc.addView(MenuIcon(this, 15, Color.WHITE), lp(dp(16), dp(16)))
        acc.addView(txt("Acciones", 13f, Color.WHITE, true), lp(-2, -2).also { it.leftMargin = dp(8) })
        acc.setOnClickListener {
            val files = selected.toList()
            openMenu(acc, if (files.size == 1) files[0].name else files.size.toString() + " archivos seleccionados", fileEntries(files))
        }
        selBar.addView(acc)
    }

    private fun fileEntries(files: List<File>): List<MEntry> {
        val out = ArrayList<MEntry>()
        if (files.isEmpty()) return out
        val one = files.size == 1
        val f0 = files[0]
        val ext = f0.extension.lowercase()
        val previewable = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "mp4", "mkv", "webm", "3gp", "mov", "pdf")
        if (one && ext in previewable) out.add(MEntry(4, "Abrir", false, 0, false) { tap(f0) })
        if (one && ext == "zip") out.add(MEntry(12, "Extraer aquí", false, 0, false) { confirmSheet("¿Extraer " + f0.name + "?", "", "Extraer") { extract(f0) } })
        out.add(MEntry(5, "Compartir", false, 0, false) { shareFiles(files) })
        out.add(MEntry(6, "Copiar a…", false, 1, false) { chooseDest(files, false) })
        out.add(MEntry(7, "Mover a…", false, 1, false) { chooseDest(files, true) })
        if (one) out.add(MEntry(8, "Renombrar", false, 1, false) { rename(f0) })
        else out.add(MEntry(8, "Renombre múltiple", false, 1, false) { renameMultiSheet() })
        if (files.all { convKind(it) > 0 } && files.map { convKind(it) }.distinct().size == 1) out.add(MEntry(17, "Convertir formato", false, 1, false) { openConverter(files) })
        if (one && convKind(f0) == 1) out.add(MEntry(18, "Mejorar imagen", false, 1, false) { openEnhancer(f0) })
        out.add(MEntry(9, "Información", false, 2, false) { infoSheet(files) })
        if (items.count { it.cat >= 0 } > selected.size) out.add(MEntry(10, "Seleccionar todo", false, 2, false) { selectAll() })
        out.add(MEntry(11, if (one) "Eliminar" else "Eliminar " + files.size + " archivos", true, 3, false) { confirmDeleteFiles(files) })
        return out
    }

    private fun folderEntries(f: File): List<MEntry> = listOf(
        MEntry(14, "Abrir", false, 0, false) {
            dir = f
            refresh()
        },
        MEntry(8, "Renombrar", false, 1, false) { rename(f) },
        MEntry(11, "Eliminar carpeta", true, 2, false) {
            confirmSheet("¿Eliminar la carpeta " + f.name + "?", "Se borrará con todo lo que contiene. Esta acción no se puede deshacer.", "Eliminar") {
                toast("Eliminando…")
                Thread {
                    val ok = f.deleteRecursively()
                    runOnUiThread {
                        if (!inViewer) refresh()
                        if (!ok) toast("No se pudo eliminar por completo la carpeta")
                    }
                }.start()
            }
        }
    )

    private fun dotsEntries(): List<MEntry> = listOf(
        MEntry(0, "Perfil", false, 0, false) { showProfile() },
        MEntry(1, "Novedades", false, 0, hasUnseen()) { showNews() },
        MEntry(17, "Convertidor", false, 1, false) { openConverter(null) },
        MEntry(18, "Mejorar imágenes", false, 1, false) { openEnhancer(null) },
        MEntry(2, "Personalización", false, 2, false) { showPersonalization() },
        MEntry(3, "Configuración", false, 2, false) { showSettings() }
    )

    private fun openMenu(anchor: View, title: String?, entries: List<MEntry>) {
        val loc = IntArray(2)
        anchor.getLocationOnScreen(loc)
        openMenuAt(loc[0], loc[1], loc[0] + anchor.width, loc[1] + anchor.height, title, entries)
    }

    private fun openMenuAt(l: Int, t: Int, r: Int, b: Int, title: String?, entries: List<MEntry>) {
        val cur = menuPop
        if (cur != null && cur.isShowing) {
            cur.dismiss()
            return
        }
        if (System.currentTimeMillis() - menuClosedAt < 400) return
        val sw = resources.displayMetrics.widthPixels
        val sh = resources.displayMetrics.heightPixels
        val pw = minOf(dp(244), sw - dp(24))
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.setPadding(dp(6), dp(6), dp(6), dp(6))
        val g = rounded(CARD, 14)
        g.setStroke(dp(1), alpha(Color.WHITE, 0x26))
        card.background = g
        val popup = PopupWindow(card, pw, ViewGroup.LayoutParams.WRAP_CONTENT, true)
        popup.isOutsideTouchable = true
        popup.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        popup.setOnDismissListener {
            menuClosedAt = System.currentTimeMillis()
            menuPop = null
        }
        if (title != null) {
            val ht = txt(title, 12f, MUTE, true)
            ht.isSingleLine = true
            ht.ellipsize = TextUtils.TruncateAt.MIDDLE
            ht.setPadding(dp(12), dp(8), dp(12), dp(6))
            card.addView(ht, lp(-1, -2))
        }
        val listBox = LinearLayout(this)
        listBox.orientation = LinearLayout.VERTICAL
        val red = c(0xFFFB7185)
        var lastGroup = -1
        for ((i, e) in entries.withIndex()) {
            if (i > 0 && e.group != lastGroup) {
                val sp = View(this)
                sp.setBackgroundColor(alpha(Color.WHITE, 0x1F))
                listBox.addView(sp, lp(-1, dp(1)).also {
                    it.topMargin = if (e.danger) dp(8) else dp(4)
                    it.bottomMargin = dp(4)
                    it.leftMargin = dp(8)
                    it.rightMargin = dp(8)
                })
            }
            lastGroup = e.group
            val color = if (e.danger) red else TXT
            val row = LinearLayout(this)
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(dp(12), 0, dp(12), 0)
            val sl = android.graphics.drawable.StateListDrawable()
            sl.addState(intArrayOf(android.R.attr.state_pressed), rounded(alpha(if (e.danger) red else ACC, 0x33), 10))
            sl.addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
            row.background = sl
            row.addView(MenuIcon(this, e.icon, color), lp(dp(22), dp(22)))
            val lab = txt(e.label, 15f, color)
            lab.isSingleLine = true
            lab.ellipsize = TextUtils.TruncateAt.END
            row.addView(lab, lp(0, -2, 1f).also { it.leftMargin = dp(14) })
            if (e.dot) row.addView(dot(), lp(dp(8), dp(8)))
            row.setOnClickListener {
                popup.dismiss()
                e.action()
            }
            listBox.addView(row, lp(-1, dp(46)))
        }
        val sc = MaxScroll(this, (sh * 0.6f).toInt())
        sc.addView(listBox)
        card.addView(sc, lp(-1, -2))

        card.measure(View.MeasureSpec.makeMeasureSpec(pw, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val mh = card.measuredHeight
        val margin = dp(8)
        val x = (r - pw).coerceIn(margin, maxOf(margin, sw - pw - margin))
        val y = if (mh <= sh - b - margin) b + dp(4)
        else if (mh <= t - margin) t - mh - dp(4)
        else maxOf(margin, (sh - mh) / 2)
        menuPop = popup
        popup.showAtLocation(window.decorView, Gravity.TOP or Gravity.START, x, y)
    }

    private fun selectAll() {
        for (x in items) if (x.cat >= 0) selected.add(x.f)
        refreshSel()
    }

    // ---------- eliminar, copiar, mover, compartir, información ----------
    private fun confirmDeleteFiles(files: List<File>) {
        val title = if (files.size == 1) "¿Eliminar " + files[0].name + "?" else "¿Eliminar " + files.size + " archivos?"
        confirmSheet(title, "Esta acción no se puede deshacer.", "Eliminar") { deleteFiles(files) }
    }

    private fun deleteFiles(files: List<File>) {
        toast("Eliminando…")
        Thread {
            var ok = 0
            val failed = ArrayList<Pair<String, String>>()
            for (f in files) {
                try {
                    java.nio.file.Files.delete(f.toPath())
                    ok++
                } catch (e: java.nio.file.NoSuchFileException) {
                    failed.add(Pair(f.name, "ya no existe"))
                } catch (e: java.nio.file.AccessDeniedException) {
                    failed.add(Pair(f.name, "sin permiso para eliminarlo"))
                } catch (e: Exception) {
                    failed.add(Pair(f.name, "el sistema no lo permitió"))
                }
            }
            runOnUiThread {
                selected.clear()
                if (!inViewer) refresh()
                reportSheet("eliminados", ok, failed, null)
            }
        }.start()
    }

    private fun dirRow(label: String, onClick: () -> Unit): View {
        val row = LinearLayout(this)
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(14), dp(9), dp(14), dp(9))
        row.addView(FolderIcon(this, ACC), lp(dp(26), dp(26)))
        val t = txt(label, 15f, TXT)
        t.isSingleLine = true
        t.ellipsize = TextUtils.TruncateAt.END
        row.addView(t, lp(0, -2, 1f).also { it.leftMargin = dp(12) })
        row.setOnClickListener { onClick() }
        return row
    }

    private fun chooseDest(files: List<File>, move: Boolean) {
        val verb = if (move) "Mover" else "Copiar"
        val src = files[0].parentFile
        pickFolder(verb + " " + (if (files.size == 1) files[0].name else files.size.toString() + " archivos") + " a…", verb + " aquí", dir) { cur ->
            if (cur.canonicalPath == src?.canonicalPath) {
                toast("Elige una carpeta distinta a la actual")
                false
            } else {
                transfer(files, cur, move)
                true
            }
        }
    }

    private fun pickFolder(title: String, confirm: String, start: File, onPick: (File) -> Boolean) {
        showSheet { d ->
            var cur: File = if (start.isDirectory) start else dir
            val col = LinearLayout(this)
            col.orientation = LinearLayout.VERTICAL
            col.addView(txt(title, 18f, TXT, true))
            val pathT = txt("", 12f, ACC2)
            pathT.setPadding(dp(12), dp(6), dp(12), dp(6))
            pathT.background = rounded(alpha(ACC, 0x33), 12)
            col.addView(pathT, lp(-2, -2).also { it.topMargin = dp(10) })
            val listBox = LinearLayout(this)
            listBox.orientation = LinearLayout.VERTICAL
            val sc = MaxScroll(this, (resources.displayMetrics.heightPixels * 0.4f).toInt())
            sc.addView(listBox)
            col.addView(sc, lp(-1, -2).also { it.topMargin = dp(8) })

            fun render() {
                pathT.text = rootName + cur.absolutePath.removePrefix(root.absolutePath)
                listBox.removeAllViews()
                if (cur != root && cur.parentFile != null) {
                    val up = optionRow("‹  Subir", false)
                    up.setOnClickListener {
                        cur = cur.parentFile ?: root
                        render()
                    }
                    listBox.addView(up, lp(-1, -2).also { it.topMargin = dp(4) })
                }
                val subs = (cur.listFiles() ?: emptyArray<File>()).filter { it.isDirectory }.sortedBy { it.name.lowercase() }
                for (sf in subs) {
                    listBox.addView(dirRow(sf.name) {
                        cur = sf
                        render()
                    }, lp(-1, -2).also { it.topMargin = dp(2) })
                }
                if (subs.isEmpty()) listBox.addView(txt("No hay subcarpetas aquí", 13f, MUTE), lp(-2, -2).also { it.topMargin = dp(8) })
            }

            val btns = LinearLayout(this)
            val no = ghostButton("Cancelar")
            no.setOnClickListener { d.dismiss() }
            val yes = primaryButton(confirm)
            yes.setOnClickListener {
                if (onPick(cur)) d.dismiss()
            }
            btns.addView(no, lp(0, dp(48), 1f))
            btns.addView(yes, lp(0, dp(48), 1f).also { it.leftMargin = dp(10) })
            col.addView(btns, lp(-1, -2).also { it.topMargin = dp(14) })
            render()
            col
        }
    }

    private fun transfer(files: List<File>, dest: File, move: Boolean) {
        toast(if (move) "Moviendo…" else "Copiando…")
        Thread {
            var ok = 0
            val failed = ArrayList<Pair<String, String>>()
            for (f in files) {
                val dst = File(dest, f.name)
                try {
                    if (move) java.nio.file.Files.move(f.toPath(), dst.toPath()) else java.nio.file.Files.copy(f.toPath(), dst.toPath())
                    ok++
                } catch (e: java.nio.file.FileAlreadyExistsException) {
                    failed.add(Pair(f.name, "ya existe un archivo con ese nombre en el destino"))
                } catch (e: java.nio.file.AccessDeniedException) {
                    failed.add(Pair(f.name, "sin permiso en la carpeta de destino"))
                } catch (e: Exception) {
                    if (!move && dst.exists()) dst.delete()
                    failed.add(Pair(f.name, "el sistema no lo permitió"))
                }
            }
            runOnUiThread {
                selected.clear()
                if (!inViewer) refresh()
                reportSheet(if (move) "movidos correctamente" else "copiados correctamente", ok, failed, null)
            }
        }.start()
    }

    private fun mimeOf(f: File): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "application/octet-stream"

    private fun shareFiles(files: List<File>) {
        val uris = ArrayList<Uri>()
        for (f in files) uris.add(Uri.Builder().scheme("content").authority(SHARE_AUTH).path("/f" + f.absolutePath).build())
        val mimes = files.map { mimeOf(it) }.distinct()
        val type = if (mimes.size == 1) mimes[0]
        else if (mimes.map { it.substringBefore('/') }.distinct().size == 1) mimes[0].substringBefore('/') + "/*"
        else "*/*"
        val intent = Intent(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE)
        if (uris.size == 1) intent.putExtra(Intent.EXTRA_STREAM, uris[0]) else intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        intent.type = type
        val cd = ClipData.newRawUri("", uris[0])
        for (i in 1 until uris.size) cd.addItem(ClipData.Item(uris[i]))
        intent.clipData = cd
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(Intent.createChooser(intent, "Compartir"))
        } catch (e: Exception) {
            toast("No se pudo abrir el menú de compartir")
        }
    }

    private fun infoSheet(files: List<File>) {
        showSheet(false) { d ->
            val col = LinearLayout(this)
            col.orientation = LinearLayout.VERTICAL
            col.addView(txt("Información", 20f, TXT, true))
            val rows = ArrayList<Pair<String, String>>()
            if (files.size == 1) {
                val f = files[0]
                val ex = f.extension.lowercase()
                rows.add(Pair("Nombre", f.name))
                rows.add(Pair("Formato", (if (ex.isEmpty()) "Sin extensión" else ex.uppercase()) + "  ·  " + mimeOf(f)))
                rows.add(Pair("Tamaño", fmt(f.length()) + "  (" + f.length() + " bytes)"))
                if (catOf(f) == 1) {
                    val o = BitmapFactory.Options()
                    o.inJustDecodeBounds = true
                    BitmapFactory.decodeFile(f.path, o)
                    if (o.outWidth > 0) rows.add(Pair("Dimensiones", o.outWidth.toString() + " × " + o.outHeight))
                }
                rows.add(Pair("Ubicación", f.parent ?: ""))
                rows.add(Pair("Modificado", dateFull.format(Date(f.lastModified()))))
            } else {
                rows.add(Pair("Archivos", files.size.toString()))
                rows.add(Pair("Tamaño total", fmt(files.sumOf { it.length() })))
                val exts = files.map { it.extension.lowercase() }.filter { it.isNotEmpty() }.distinct()
                rows.add(Pair("Formatos", if (exts.isEmpty()) "Sin extensión" else exts.take(8).joinToString(", ") { it.uppercase() } + (if (exts.size > 8) "…" else "")))
                rows.add(Pair("Ubicación", files[0].parent ?: ""))
                val mods = files.map { it.lastModified() }
                rows.add(Pair("Modificados", "desde " + dateFull.format(Date(mods.min())) + " hasta " + dateFull.format(Date(mods.max()))))
            }
            val box = LinearLayout(this)
            box.orientation = LinearLayout.VERTICAL
            for (r in rows) {
                box.addView(txt(r.first, 12f, ACC2, true), lp(-1, -2).also { it.topMargin = dp(12) })
                box.addView(txt(r.second, 14f, TXT), lp(-1, -2).also { it.topMargin = dp(2) })
            }
            val sc = MaxScroll(this, (resources.displayMetrics.heightPixels * 0.55f).toInt())
            sc.addView(box)
            col.addView(sc, lp(-1, -2))
            val ok = primaryButton("Cerrar")
            ok.setOnClickListener { d.dismiss() }
            col.addView(ok, lp(-1, dp(48)).also { it.topMargin = dp(16) })
            col
        }
    }

    // ---------- convertidor ----------
    private fun convKind(f: File): Int = when (f.extension.lowercase()) {
        "jpg", "jpeg", "png", "webp", "bmp", "gif", "heic", "heif" -> 1
        "mp3", "wav", "flac", "m4a", "aac", "ogg", "opus", "amr" -> 2
        "pdf" -> 3
        else -> 0
    }

    private fun convTargets(kind: Int): List<String> = when (kind) {
        1 -> listOf("JPG", "PNG", "WebP", "PDF")
        2 -> listOf("M4A (AAC)", "WAV")
        else -> listOf("PNG", "JPG")
    }

    private fun convExt(kind: Int, t: Int): String = when (kind) {
        1 -> listOf("jpg", "png", "webp", "pdf")[t]
        2 -> listOf("m4a", "wav")[t]
        else -> listOf("png", "jpg")[t]
    }

    private fun baseOf(f: File): String = f.name.removeSuffix(extOf(f.name))

    private fun cleanName(s: String): String = s.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")

    private fun openConverter(files: List<File>?) {
        if (files != null) {
            convFiles.clear()
            convFiles.addAll(files.filter { convKind(it) > 0 })
            convDest = null
            convTarget = 0
            convName = ""
            selected.clear()
        }
        showConverter()
    }

    private fun planJobs(kind: Int): List<ConvJob> {
        val n = convFiles.size
        val nm = cleanName(convName)
        val jobs = ArrayList<ConvJob>()
        if (kind == 1 && convTarget == 3 && convMerge && n > 1) {
            jobs.add(ConvJob(n.toString() + " imágenes en un solo PDF", ArrayList(convFiles), if (nm.isEmpty()) baseOf(convFiles[0]) + "_unido" else nm))
        } else {
            for ((i, f) in convFiles.withIndex()) {
                val base = if (nm.isEmpty()) baseOf(f) else if (n == 1) nm else nm + "_" + (i + 1)
                jobs.add(ConvJob(f.name, listOf(f), if (base.isBlank()) "archivo" else base))
            }
        }
        return jobs
    }

    private fun simName(dest: File, base: String, ext: String, used: HashSet<String>): String {
        var n = base + "." + ext
        var i = 1
        while (File(dest, n).exists() || used.contains(n.lowercase())) {
            n = base + " (" + i + ")." + ext
            i++
        }
        used.add(n.lowercase())
        return n
    }

    private fun roundedThumb(): ImageView {
        val iv = ImageView(this)
        iv.scaleType = ImageView.ScaleType.CENTER_CROP
        iv.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(v: View, o: android.graphics.Outline) {
                o.setRoundRect(0, 0, v.width, v.height, dp(11).toFloat())
            }
        }
        iv.clipToOutline = true
        return iv
    }

    private fun convSourceRow(f: File): View {
        val row = LinearLayout(this)
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(10), dp(8), dp(4), dp(8))
        row.background = rounded(alpha(CARD, 0xCC), 14)
        val item = Item(f, f.length(), f.lastModified(), catOf(f))
        val k = badgeOf(item)
        val lead = FrameLayout(this)
        val badge = txt(k.first, 11f, k.second, true)
        badge.gravity = Gravity.CENTER
        badge.background = rounded(alpha(k.second, 0x26), 11)
        lead.addView(badge, FrameLayout.LayoutParams(-1, -1))
        if (convKind(f) == 1) {
            val iv = roundedThumb()
            lead.addView(iv, FrameLayout.LayoutParams(-1, -1))
            bindThumb(Item(f, f.length(), f.lastModified(), 1), iv, badge)
        }
        row.addView(lead, lp(dp(44), dp(44)))
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        val name = txt(f.name, 14f, TXT)
        name.isSingleLine = true
        name.ellipsize = TextUtils.TruncateAt.END
        col.addView(name)
        col.addView(txt((if (f.extension.isEmpty()) "Sin extensión" else f.extension.uppercase()) + "  ·  " + fmt(f.length()), 12f, MUTE))
        row.addView(col, lp(0, -2, 1f).also { it.leftMargin = dp(12) })
        val rm = LinearLayout(this)
        rm.gravity = Gravity.CENTER
        rm.setPadding(dp(12), dp(12), dp(12), dp(12))
        rm.addView(MenuIcon(this, 16, MUTE), lp(dp(14), dp(14)))
        rm.setOnClickListener {
            convFiles.remove(f)
            showConverter()
        }
        row.addView(rm)
        return row
    }

    private fun convPreviewRow(src: String, out: String): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.VERTICAL
        row.setPadding(dp(12), dp(9), dp(12), dp(9))
        row.background = rounded(alpha(Color.WHITE, 0x0E), 12)
        val a = txt(src, 12f, MUTE)
        a.isSingleLine = true
        a.ellipsize = TextUtils.TruncateAt.MIDDLE
        val b = txt("→ " + out, 14f, TXT, true)
        b.isSingleLine = true
        b.ellipsize = TextUtils.TruncateAt.MIDDLE
        row.addView(a)
        row.addView(b)
        return row
    }

    private fun levelDescription(kind: Int, target: Int, level: Int): String {
        val lossless = Build.VERSION.SDK_INT >= 30
        return when (kind) {
            1 -> when (target) {
                0 -> listOf(
                    "JPG con calidad 85 %. Conserva la resolución original.",
                    "JPG con calidad 92 %. Las imágenes de menos de 1920 px de lado mayor se amplían a 1920 px por interpolación (no añade detalle real).",
                    "JPG con calidad 98 %. Las imágenes de menos de 3840 px de lado mayor se amplían a 3840 px por interpolación (no añade detalle real)."
                )[level]
                1 -> listOf(
                    "PNG sin pérdida. Conserva la resolución original.",
                    "PNG sin pérdida: la calidad no cambia. Las imágenes de menos de 1920 px de lado mayor se amplían a 1920 px por interpolación.",
                    "PNG sin pérdida: la calidad no cambia. Las imágenes de menos de 3840 px de lado mayor se amplían a 3840 px por interpolación."
                )[level]
                2 -> listOf(
                    "WebP con calidad 80 %. Conserva la resolución original.",
                    "WebP con calidad 90 %. Las imágenes de menos de 1920 px de lado mayor se amplían a 1920 px por interpolación.",
                    (if (lossless) "WebP sin pérdida. " else "WebP con calidad 100 % (este Android no tiene modo sin pérdida). ") + "Las imágenes de menos de 3840 px de lado mayor se amplían a 3840 px por interpolación."
                )[level]
                else -> listOf(
                    "Imágenes de hasta 1600 px de lado mayor en cada página.",
                    "Imágenes de hasta 2400 px de lado mayor en cada página. El PDF pesa más.",
                    "Imágenes de hasta 3600 px de lado mayor en cada página. El PDF pesa bastante más."
                )[level] + " Nunca se amplían."
            }
            2 -> listOf(
                "AAC a 128 kbps.",
                "AAC a 192 kbps.",
                "AAC a 320 kbps (en audio mono el máximo es 192 kbps)."
            )[level] + " Conserva la frecuencia de muestreo original: subir el bitrate no mejora una grabación que ya estaba comprimida."
            else -> listOf(
                "Páginas a 150 ppp.",
                "Páginas a 200 ppp.",
                "Páginas a 300 ppp."
            )[level] + (if (target == 1) " JPG con calidad " + listOf(85, 92, 98)[level] + " %." else " PNG sin pérdida.") + " Se vuelven a dibujar a esa resolución, así que el texto y los vectores salen nítidos."
        }
    }

    private fun bgSwatches(): View {
        val row = LinearLayout(this)
        val opts = listOf(Pair("Blanco", Color.WHITE), Pair("Negro", Color.BLACK), Pair("Gris", c(0xFF808080)), Pair("Tema", ACC))
        fun draw() {
            row.removeAllViews()
            for (o in opts) {
                val sel = convBg == o.second
                val tile = LinearLayout(this)
                tile.orientation = LinearLayout.VERTICAL
                tile.gravity = Gravity.CENTER_HORIZONTAL
                val sw = View(this)
                val g = GradientDrawable()
                g.shape = GradientDrawable.OVAL
                g.setColor(o.second)
                g.setStroke(dp(if (sel) 3 else 1), if (sel) ACC2 else alpha(Color.WHITE, 0x44))
                sw.background = g
                tile.addView(sw, lp(dp(40), dp(40)))
                tile.addView(txt(o.first, 11f, if (sel) ACC2 else TXT), lp(-2, -2).also { it.topMargin = dp(4) })
                tile.setOnClickListener {
                    convBg = o.second
                    draw()
                }
                row.addView(tile, lp(-2, -2).also { it.rightMargin = dp(14) })
            }
        }
        draw()
        return row
    }

    private fun pickFilesSheet(kindOnly: Int = 0, single: Boolean = false, onDone: (List<File>) -> Unit) {
        showSheet { d ->
            var cur: File = dir
            val chosen = LinkedHashSet<File>()
            val col = LinearLayout(this)
            col.orientation = LinearLayout.VERTICAL
            col.addView(txt("Agregar archivos", 18f, TXT, true))
            val pathT = txt("", 12f, ACC2)
            pathT.setPadding(dp(12), dp(6), dp(12), dp(6))
            pathT.background = rounded(alpha(ACC, 0x33), 12)
            col.addView(pathT, lp(-2, -2).also { it.topMargin = dp(10) })
            val listBox = LinearLayout(this)
            listBox.orientation = LinearLayout.VERTICAL
            val sc = MaxScroll(this, (resources.displayMetrics.heightPixels * 0.42f).toInt())
            sc.addView(listBox)
            col.addView(sc, lp(-1, -2).also { it.topMargin = dp(8) })
            val yes = primaryButton("Agregar")

            fun render() {
                pathT.text = rootName + cur.absolutePath.removePrefix(root.absolutePath)
                yes.text = if (chosen.isEmpty()) "Agregar" else "Agregar (" + chosen.size + ")"
                yes.isEnabled = chosen.isNotEmpty()
                yes.alpha = if (chosen.isEmpty()) 0.4f else 1f
                listBox.removeAllViews()
                if (cur != root && cur.parentFile != null) {
                    val up = optionRow("‹  Subir", false)
                    up.setOnClickListener {
                        cur = cur.parentFile ?: root
                        render()
                    }
                    listBox.addView(up, lp(-1, -2).also { it.topMargin = dp(4) })
                }
                val all = (cur.listFiles() ?: emptyArray<File>()).sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
                for (f in all) {
                    if (f.isDirectory) {
                        listBox.addView(dirRow(f.name) {
                            cur = f
                            render()
                        }, lp(-1, -2).also { it.topMargin = dp(2) })
                    } else if (convKind(f) > 0 && (kindOnly == 0 || convKind(f) == kindOnly)) {
                        val sel = chosen.contains(f)
                        val r = optionRow((if (sel) "✓  " else "") + f.name + "  ·  " + fmt(f.length()), sel)
                        r.isSingleLine = true
                        r.ellipsize = TextUtils.TruncateAt.END
                        r.setOnClickListener {
                            if (single) {
                                chosen.clear()
                                chosen.add(f)
                            } else if (!chosen.add(f)) {
                                chosen.remove(f)
                            }
                            render()
                        }
                        listBox.addView(r, lp(-1, -2).also { it.topMargin = dp(2) })
                    }
                }
            }

            val btns = LinearLayout(this)
            val no = ghostButton("Cancelar")
            no.setOnClickListener { d.dismiss() }
            yes.setOnClickListener {
                d.dismiss()
                onDone(chosen.toList())
            }
            btns.addView(no, lp(0, dp(48), 1f))
            btns.addView(yes, lp(0, dp(48), 1f).also { it.leftMargin = dp(10) })
            col.addView(btns, lp(-1, -2).also { it.topMargin = dp(14) })
            render()
            col
        }
    }

    private fun showConverter() {
        val y = pageScroll?.scrollY ?: 0
        val b = body()
        val kinds = convFiles.map { convKind(it) }.distinct()
        val mixed = kinds.size > 1
        val kind = if (kinds.size == 1) kinds[0] else 0
        val targets = convTargets(kind)
        if (convTarget >= targets.size) convTarget = 0
        val destDir = convDest ?: convFiles.firstOrNull()?.parentFile ?: dir
        val amber = c(0xFFFBBF24)

        b.addView(sectionTitle("Archivos de origen"))
        if (convFiles.isEmpty()) b.addView(txt("Aún no hay archivos. Agrega imágenes, audios o PDF.", 13f, MUTE))
        for (f in convFiles) b.addView(convSourceRow(f), lp(-1, -2).also { it.topMargin = dp(6) })
        val add = ghostButton("Agregar archivos")
        add.setOnClickListener {
            pickFilesSheet { picked ->
                for (pf in picked) if (!convFiles.contains(pf)) convFiles.add(pf)
                showConverter()
            }
        }
        b.addView(add, lp(-1, dp(46)).also { it.topMargin = dp(10) })
        if (mixed) b.addView(txt("Los archivos deben ser del mismo tipo (imágenes, audio o PDF). Quita los que no coincidan.", 13f, amber, true), lp(-1, -2).also { it.topMargin = dp(8) })
        b.addView(txt("Disponible: imágenes (JPG, PNG, WebP, PDF), audio (M4A, WAV) y PDF a imágenes. Los videos y otros documentos todavía no se pueden convertir.", 12f, MUTE), lp(-1, -2).also { it.topMargin = dp(8) })

        val previewBox = LinearLayout(this)
        previewBox.orientation = LinearLayout.VERTICAL
        fun fillPreview() {
            previewBox.removeAllViews()
            if (kind == 0 || convFiles.isEmpty() || mixed) return
            val jobs = planJobs(kind)
            val ext = convExt(kind, convTarget)
            val used = HashSet<String>()
            for (j in jobs.take(8)) {
                val out = if (kind == 3) j.outBase + "_p001." + ext + "  (una imagen por página)" else simName(destDir, j.outBase, ext, used)
                previewBox.addView(convPreviewRow(j.label, out), lp(-1, -2).also { it.topMargin = dp(8) })
            }
            if (jobs.size > 8) previewBox.addView(txt("… y " + (jobs.size - 8) + " más", 12f, MUTE), lp(-2, -2).also { it.topMargin = dp(8) })
        }

        if (kind > 0) {
            b.addView(sectionTitle("Convertir a"))
            b.addView(chipGroup(targets, convTarget) {
                convTarget = it
                showConverter()
            })
            if (kind == 2) b.addView(txt("Android no incluye un codificador de MP3, por eso el audio se convierte a M4A (AAC) o WAV.", 12f, MUTE), lp(-1, -2).also { it.topMargin = dp(8) })

            val opt = LinearLayout(this)
            opt.orientation = LinearLayout.VERTICAL
            if (kind == 2 && convTarget == 1) {
                opt.addView(txt("WAV sin pérdida: conserva la frecuencia original (PCM de 16 bits). No tiene niveles de calidad y el archivo pesa mucho más.", 12f, MUTE), lp(-1, -2).also { it.topMargin = dp(4) })
            } else {
                opt.addView(label("Calidad"))
                opt.addView(chipGroup(listOf("Predeterminada", "HD", "ULTRA HD"), convLevel) {
                    convLevel = it
                    showConverter()
                }, lp(-1, -2).also { it.topMargin = dp(6) })
                opt.addView(txt(levelDescription(kind, convTarget, convLevel), 12f, MUTE), lp(-1, -2).also { it.topMargin = dp(8) })
            }
            if (kind == 1 && convTarget == 0 && convFiles.any { it.extension.lowercase() != "jpg" && it.extension.lowercase() != "jpeg" }) {
                opt.addView(label("Fondo para las zonas transparentes"))
                opt.addView(bgSwatches(), lp(-2, -2).also { it.topMargin = dp(8) })
                opt.addView(txt("El JPG no admite transparencia: esas zonas se rellenan con este color.", 12f, MUTE), lp(-1, -2).also { it.topMargin = dp(8) })
            }
            if (kind == 1 && convTarget == 3 && convFiles.size > 1) {
                opt.addView(label("Resultado"))
                opt.addView(chipGroup(listOf("Un solo PDF", "Un PDF por imagen"), if (convMerge) 0 else 1) {
                    convMerge = it == 0
                    showConverter()
                }, lp(-1, -2).also { it.topMargin = dp(6) })
            }
            if (opt.childCount > 0) {
                b.addView(sectionTitle("Opciones"))
                b.addView(opt)
            }
        }

        b.addView(sectionTitle("Guardar en"))
        val pathT = txt("Almacenamiento interno" + destDir.absolutePath.removePrefix(root.absolutePath), 12f, ACC2)
        pathT.setPadding(dp(12), dp(6), dp(12), dp(6))
        pathT.background = rounded(alpha(ACC, 0x33), 12)
        b.addView(pathT, lp(-2, -2))
        val chg = ghostButton("Cambiar carpeta")
        chg.setOnClickListener {
            pickFolder("Guardar en…", "Elegir esta carpeta", destDir) { picked ->
                convDest = picked
                showConverter()
                true
            }
        }
        b.addView(chg, lp(-1, dp(46)).also { it.topMargin = dp(8) })
        b.addView(label("Nombre del resultado (opcional)"))
        b.addView(field("Igual que el original", false, convName) {
            convName = it
            fillPreview()
        }, lp(-1, -2).also { it.topMargin = dp(6) })
        b.addView(txt("Vacío: cada archivo conserva su nombre. Si ya existe uno igual, se agrega (1): nunca se sobrescribe nada.", 12f, MUTE), lp(-1, -2).also { it.topMargin = dp(6) })

        if (kind > 0) {
            b.addView(sectionTitle("Vista previa"))
            if (kind == 1) {
                val pv = roundedThumb()
                b.addView(pv, lp(dp(120), dp(120)))
                val f0 = convFiles[0]
                Thread {
                    val bm = try {
                        decodeFull(f0, 360)
                    } catch (e: Throwable) {
                        null
                    }
                    if (bm != null) runOnUiThread { pv.setImageBitmap(bm) }
                }.start()
            }
            b.addView(previewBox)
            fillPreview()
        }

        val canWrite = destDir.isDirectory && destDir.canWrite()
        val canGo = convFiles.isNotEmpty() && !mixed && kind > 0 && canWrite
        val go = primaryButton("Convertir" + (if (convFiles.size > 1) " (" + convFiles.size + ")" else ""))
        go.isEnabled = canGo
        go.alpha = if (canGo) 1f else 0.4f
        go.setOnClickListener { startConversion(destDir) }
        b.addView(go, lp(-1, dp(52)).also { it.topMargin = dp(20) })
        if (!canWrite) b.addView(txt("Nexora no puede escribir en esta carpeta. Elige otra para guardar.", 12f, amber, true), lp(-1, -2).also { it.topMargin = dp(8) })

        page("Convertidor", b)
        pageScroll?.post { pageScroll?.scrollTo(0, y) }
    }

    private fun friendlyError(e: Throwable): String =
        if (e is OutOfMemoryError) "memoria insuficiente en el dispositivo"
        else if (e is java.net.UnknownHostException) "no hay conexión a internet"
        else if (e is java.net.SocketTimeoutException) "la conexión tardó demasiado"
        else (e.message ?: e.javaClass.simpleName)

    private fun updateJobRow(j: ConvJob) {
        val st = j.statusView ?: return
        when (j.status) {
            0 -> {
                st.text = "En espera"
                st.setTextColor(MUTE)
            }
            1 -> {
                st.text = "Convirtiendo…"
                st.setTextColor(ACC2)
            }
            2 -> {
                st.text = "Listo"
                st.setTextColor(GREEN)
            }
            3 -> {
                st.text = "Error: " + j.msg
                st.setTextColor(c(0xFFFB7185))
            }
            else -> {
                st.text = "Cancelado"
                st.setTextColor(MUTE)
            }
        }
    }

    private fun showConvertProgress(jobs: List<ConvJob>) {
        val b = body()
        val head = txt("Convirtiendo 0 de " + jobs.size, 17f, TXT, true)
        convHead = head
        b.addView(head)
        val overall = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        overall.max = 1000
        overall.progressTintList = ColorStateList.valueOf(ACC)
        convOverall = overall
        b.addView(overall, lp(-1, dp(8)).also { it.topMargin = dp(10); it.bottomMargin = dp(14) })
        for (j in jobs) {
            val card = cardBox()
            val nm = txt(j.label, 14f, TXT, true)
            nm.isSingleLine = true
            nm.ellipsize = TextUtils.TruncateAt.MIDDLE
            val st = txt("En espera", 12f, MUTE)
            val pb = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
            pb.max = 1000
            pb.progressTintList = ColorStateList.valueOf(ACC2)
            j.statusView = st
            j.bar = pb
            card.addView(nm)
            card.addView(st, lp(-1, -2).also { it.topMargin = dp(2) })
            card.addView(pb, lp(-1, dp(5)).also { it.topMargin = dp(8) })
            b.addView(card, lp(-1, -2).also { it.bottomMargin = dp(8) })
        }
        val cancel = ghostButton("Cancelar conversión")
        cancel.setOnClickListener {
            convCancel = true
            cancel.text = "Cancelando…"
            cancel.isEnabled = false
        }
        b.addView(cancel, lp(-1, dp(48)).also { it.topMargin = dp(10) })
        page("Convertidor", b)
    }

    private fun startConversion(destDir: File) {
        val kind = convKind(convFiles[0])
        convDest = destDir
        val jobs = planJobs(kind)
        converting = true
        convCancel = false
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        showConvertProgress(jobs)
        Thread {
            val total = jobs.size
            for ((i, j) in jobs.withIndex()) {
                if (convCancel) {
                    j.status = 4
                    runOnUiThread { updateJobRow(j) }
                    continue
                }
                j.status = 1
                runOnUiThread {
                    convHead?.text = "Convirtiendo " + (i + 1) + " de " + total
                    updateJobRow(j)
                }
                var last = -1
                val prog: (Float) -> Unit = { p ->
                    val q = (p * 100).toInt()
                    if (q != last) {
                        last = q
                        runOnUiThread {
                            j.bar?.progress = (p * 1000).toInt()
                            convOverall?.progress = ((i + p) / total * 1000).toInt()
                        }
                    }
                }
                try {
                    execJob(kind, j, destDir, prog)
                    j.status = 2
                } catch (e: Throwable) {
                    if (convCancel) {
                        j.status = 4
                    } else {
                        j.status = 3
                        j.msg = friendlyError(e)
                    }
                }
                runOnUiThread {
                    updateJobRow(j)
                    j.bar?.progress = 1000
                    convOverall?.progress = (i + 1) * 1000 / total
                }
            }
            runOnUiThread {
                converting = false
                window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                showConvertResult(jobs, destDir)
            }
        }.start()
    }

    private fun showConvertResult(jobs: List<ConvJob>, destDir: File) {
        val ok = jobs.count { it.status == 2 }
        val bad = jobs.count { it.status == 3 }
        val cancelled = jobs.count { it.status == 4 }
        val b = body()
        b.addView(txt(if (bad == 0 && cancelled == 0) "Conversión completada" else if (ok == 0) "No se pudo convertir" else "Terminó con avisos", 20f, TXT, true))
        val parts = ArrayList<String>()
        if (ok > 0) parts.add("✓ " + ok + " correctos")
        if (bad > 0) parts.add("✕ " + bad + " con error")
        if (cancelled > 0) parts.add(cancelled.toString() + " cancelados")
        b.addView(txt(parts.joinToString("   ·   "), 13f, MUTE, true), lp(-1, -2).also { it.topMargin = dp(6); it.bottomMargin = dp(12) })
        val previewable = setOf("jpg", "jpeg", "png", "webp", "pdf")
        for (j in jobs) {
            val card = cardBox()
            val nm = txt(j.label, 14f, TXT, true)
            nm.isSingleLine = true
            nm.ellipsize = TextUtils.TruncateAt.MIDDLE
            card.addView(nm)
            val st = txt("", 12f, MUTE)
            card.addView(st, lp(-1, -2).also { it.topMargin = dp(2) })
            j.statusView = st
            updateJobRow(j)
            if (j.note.isNotEmpty()) card.addView(txt(j.note, 12f, MUTE), lp(-1, -2).also { it.topMargin = dp(4) })
            for (o in j.outs.take(6)) {
                val t = txt("→ " + o.name, 13f, if (o.extension.lowercase() in previewable) ACC2 else TXT)
                t.isSingleLine = true
                t.ellipsize = TextUtils.TruncateAt.MIDDLE
                if (o.extension.lowercase() in previewable) t.setOnClickListener { tap(o) }
                card.addView(t, lp(-1, -2).also { it.topMargin = dp(6) })
            }
            if (j.outs.size > 6) card.addView(txt("… y " + (j.outs.size - 6) + " más", 12f, MUTE), lp(-2, -2).also { it.topMargin = dp(4) })
            b.addView(card, lp(-1, -2).also { it.bottomMargin = dp(8) })
        }
        val see = primaryButton("Ver en la carpeta")
        see.setOnClickListener {
            dir = destDir
            selected.clear()
            closeViewer()
        }
        b.addView(see, lp(-1, dp(50)).also { it.topMargin = dp(10) })
        val more = ghostButton("Convertir más")
        more.setOnClickListener {
            convFiles.clear()
            convName = ""
            showConverter()
        }
        b.addView(more, lp(-1, dp(48)).also { it.topMargin = dp(8) })
        page("Convertidor", b)
    }

    private fun uniqueFile(d: File, base: String, ext: String): File {
        var f = File(d, base + "." + ext)
        var i = 1
        while (f.exists()) {
            f = File(d, base + " (" + i + ")." + ext)
            i++
        }
        return f
    }

    private fun finalizeFile(tmp: File, dest: File, base: String, ext: String): File {
        var target = uniqueFile(dest, base, ext)
        for (attempt in 0..5) {
            try {
                java.nio.file.Files.move(tmp.toPath(), target.toPath())
                return target
            } catch (e: java.nio.file.FileAlreadyExistsException) {
                target = uniqueFile(dest, base, ext)
            }
        }
        throw Exception("no se pudo guardar sin sobrescribir otro archivo")
    }

    private fun decodeFull(f: File, maxDim: Int): Bitmap {
        val o = BitmapFactory.Options()
        o.inJustDecodeBounds = true
        BitmapFactory.decodeFile(f.path, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) throw Exception("no se pudo leer la imagen (formato no compatible o archivo dañado)")
        var s = 1
        if (maxDim > 0) while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= maxDim) s *= 2
        var res: Bitmap? = null
        while (res == null) {
            try {
                val o2 = BitmapFactory.Options()
                o2.inSampleSize = s
                res = BitmapFactory.decodeFile(f.path, o2) ?: throw Exception("no se pudo decodificar la imagen")
            } catch (e: OutOfMemoryError) {
                s *= 2
                if (s > 64) throw Exception("la imagen es demasiado grande para la memoria disponible")
            }
        }
        var b: Bitmap = res!!
        if (maxDim > 0 && maxOf(b.width, b.height) > maxDim) {
            val k = maxDim.toFloat() / maxOf(b.width, b.height)
            b = Bitmap.createScaledBitmap(b, maxOf(1, (b.width * k).toInt()), maxOf(1, (b.height * k).toInt()), true)
        }
        var rot = 0
        try {
            val ori = ExifInterface(f.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)
            rot = if (ori == 6) 90 else if (ori == 3) 180 else if (ori == 8) 270 else 0
        } catch (e: Exception) {
            rot = 0
        }
        if (rot != 0) {
            val m = Matrix()
            m.postRotate(rot.toFloat())
            b = Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
        }
        return b
    }

    @Suppress("DEPRECATION")
    private fun webpFormat(lossless: Boolean): Bitmap.CompressFormat =
        if (Build.VERSION.SDK_INT >= 30) (if (lossless) Bitmap.CompressFormat.WEBP_LOSSLESS else Bitmap.CompressFormat.WEBP_LOSSY) else Bitmap.CompressFormat.WEBP

    private fun upscale(src: Bitmap, nw: Int, nh: Int): Bitmap {
        var cur = src
        while (cur.width * 2 <= nw && cur.height * 2 <= nh) {
            cur = Bitmap.createScaledBitmap(cur, cur.width * 2, cur.height * 2, true)
        }
        return if (cur.width == nw && cur.height == nh) cur else Bitmap.createScaledBitmap(cur, nw, nh, true)
    }

    private fun tierOf(w: Int, h: Int): String {
        val big = maxOf(w, h)
        return if (big >= 3840) "cumple ULTRA HD (lado mayor de 3840 px o más)"
        else if (big >= 1920) "cumple HD (lado mayor de 1920 px o más)"
        else "menor que HD (lado mayor de " + big + " px)"
    }

    private fun convertImage(f: File, fmtIdx: Int, level: Int, bg: Int, out: File): String {
        val orig = decodeFull(f, 0)
        val ow = orig.width
        val oh = orig.height
        var bm = orig
        val minLong = intArrayOf(0, 1920, 3840)[level]
        val big = maxOf(ow, oh)
        var upscaled = false
        if (minLong > 0 && big < minLong) {
            val k = minLong.toFloat() / big
            bm = upscale(orig, maxOf(1, Math.round(ow * k)), maxOf(1, Math.round(oh * k)))
            upscaled = true
        }
        if (fmtIdx == 0 && bm.hasAlpha()) {
            val flat = Bitmap.createBitmap(bm.width, bm.height, Bitmap.Config.ARGB_8888)
            val cv = Canvas(flat)
            cv.drawColor(bg)
            cv.drawBitmap(bm, 0f, 0f, null)
            bm = flat
        }
        val lossless = fmtIdx == 2 && level == 2 && Build.VERSION.SDK_INT >= 30
        val q = if (fmtIdx == 0) intArrayOf(85, 92, 98)[level] else intArrayOf(80, 90, 100)[level]
        val cf = if (fmtIdx == 0) Bitmap.CompressFormat.JPEG else if (fmtIdx == 1) Bitmap.CompressFormat.PNG else webpFormat(lossless)
        val finalBm = bm
        FileOutputStream(out).use { os ->
            if (!finalBm.compress(cf, q, os)) throw Exception("el sistema no pudo codificar la imagen")
        }
        var note = bm.width.toString() + "×" + bm.height + " px"
        if (upscaled) note += " (ampliada desde " + ow + "×" + oh + " por interpolación, sin añadir detalle real)"
        note += " · " + tierOf(bm.width, bm.height)
        note += if (fmtIdx == 1) " · PNG sin pérdida" else if (lossless) " · WebP sin pérdida" else " · calidad " + q + " %"
        return note
    }

    private fun imagesToPdf(srcs: List<File>, out: File, maxDim: Int, onProg: (Float) -> Unit) {
        val doc = PdfDocument()
        try {
            for ((i, f) in srcs.withIndex()) {
                if (convCancel) throw Exception("cancelado")
                val bm = try {
                    decodeFull(f, maxDim)
                } catch (e: Exception) {
                    throw Exception(f.name + ": " + e.message)
                }
                val pw = if (bm.width > bm.height) 842 else 595
                val ph = maxOf(1, (pw.toFloat() * bm.height / bm.width).toInt())
                val page = doc.startPage(PdfDocument.PageInfo.Builder(pw, ph, i + 1).create())
                val cv = page.canvas
                cv.drawColor(Color.WHITE)
                cv.drawBitmap(bm, null, android.graphics.Rect(0, 0, pw, ph), Paint(Paint.FILTER_BITMAP_FLAG))
                doc.finishPage(page)
                onProg((i + 1f) / srcs.size)
            }
            FileOutputStream(out).use { os -> doc.writeTo(os) }
        } finally {
            doc.close()
        }
    }

    private fun pdfToImages(f: File, dest: File, base: String, fmtIdx: Int, dpi: Int, quality: Int, outs: MutableList<File>, onProg: (Float) -> Unit): String {
        var note = ""
        val pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
        try {
            val rr = PdfRenderer(pfd)
            try {
                val n = rr.pageCount
                for (i in 0 until n) {
                    if (convCancel) throw Exception("cancelado")
                    val pg = rr.openPage(i)
                    val sc = dpi / 72f
                    var w = maxOf(1, (pg.width * sc).toInt())
                    var h = maxOf(1, (pg.height * sc).toInt())
                    val big = maxOf(w, h)
                    var limited = false
                    if (big > 4000) {
                        val k = 4000f / big
                        w = maxOf(1, (w * k).toInt())
                        h = maxOf(1, (h * k).toInt())
                        limited = true
                    }
                    if (i == 0) {
                        note = dpi.toString() + " ppp · " + w + "×" + h + " px por página" + (if (limited) " (limitado a 4000 px de lado mayor)" else "") +
                            (if (fmtIdx == 1) " · JPG calidad " + quality + " %" else " · PNG sin pérdida")
                    }
                    val bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    bm.eraseColor(Color.WHITE)
                    pg.render(bm, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                    pg.close()
                    val tmp = File(dest, ".nexora_" + System.nanoTime() + ".tmp")
                    try {
                        FileOutputStream(tmp).use { os ->
                            if (!bm.compress(if (fmtIdx == 0) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, quality, os)) throw Exception("el sistema no pudo codificar la página " + (i + 1))
                        }
                        outs.add(finalizeFile(tmp, dest, base + "_p" + (i + 1).toString().padStart(3, '0'), if (fmtIdx == 0) "png" else "jpg"))
                    } finally {
                        if (tmp.exists()) tmp.delete()
                        bm.recycle()
                    }
                    onProg((i + 1f) / n)
                }
            } finally {
                rr.close()
            }
        } catch (e: SecurityException) {
            throw Exception("el PDF está protegido con contraseña")
        } finally {
            pfd.close()
        }
        return note
    }

    private fun execJob(kind: Int, j: ConvJob, dest: File, onProg: (Float) -> Unit) {
        if (!dest.canWrite()) throw Exception("sin permiso de escritura en la carpeta de destino")
        val ext = convExt(kind, convTarget)
        val tmp = File(dest, ".nexora_" + System.nanoTime() + ".tmp")
        try {
            if (kind == 1) {
                if (convTarget == 3) {
                    val dim = intArrayOf(1600, 2400, 3600)[convLevel]
                    imagesToPdf(j.srcs, tmp, dim, onProg)
                    j.outs.add(finalizeFile(tmp, dest, j.outBase, "pdf"))
                    j.note = "Páginas con imágenes de hasta " + dim + " px de lado mayor (nunca se amplían)"
                } else {
                    val f = j.srcs[0]
                    if (convTarget == 1 && f.extension.lowercase() == "png" && convLevel == 0) throw Exception("ya está en PNG")
                    j.note = convertImage(f, convTarget, convLevel, convBg, tmp)
                    onProg(1f)
                    j.outs.add(finalizeFile(tmp, dest, j.outBase, ext))
                }
            } else if (kind == 2) {
                val f = j.srcs[0]
                if (f.extension.lowercase() == ext) throw Exception("ya está en ese formato")
                val kbps = intArrayOf(128, 192, 320)[convLevel]
                AudioConverter(f, tmp, convTarget == 0, kbps, { convCancel }, onProg).run()
                j.outs.add(finalizeFile(tmp, dest, j.outBase, ext))
                j.note = if (convTarget == 0) "AAC a " + kbps + " kbps" else "WAV sin pérdida (PCM de 16 bits)"
            } else {
                j.note = pdfToImages(j.srcs[0], dest, j.outBase, convTarget, intArrayOf(150, 200, 300)[convLevel], intArrayOf(85, 92, 98)[convLevel], j.outs, onProg)
            }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    // ---------- mejorar imágenes (IA) ----------
    private val modelUrl = "https://qaihub-public-assets.s3.us-west-2.amazonaws.com/qai-hub-models/models/real_esrgan_general_x4v3/releases/v0.64.0/real_esrgan_general_x4v3-tflite-float.zip"

    private fun modelFile(): File = File(File(filesDir, "models"), "realesr-general-x4v3.tflite")

    private fun modelReady(): Boolean {
        val m = modelFile()
        return m.exists() && m.length() > 500000L
    }

    private fun imageDims(f: File): Pair<Int, Int> {
        val o = BitmapFactory.Options()
        o.inJustDecodeBounds = true
        BitmapFactory.decodeFile(f.path, o)
        var w = o.outWidth
        var h = o.outHeight
        try {
            val ori = ExifInterface(f.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)
            if (ori == 6 || ori == 8) {
                val t = w
                w = h
                h = t
            }
        } catch (e: Exception) {
        }
        return Pair(w, h)
    }

    private fun fmtTime(ms: Long): String {
        val sec = ms / 1000
        return if (sec < 60) sec.toString() + " s" else (sec / 60).toString() + " min " + (sec % 60) + " s"
    }

    private fun flattenWhite(bm: Bitmap): Bitmap {
        if (!bm.hasAlpha()) return bm
        val flat = Bitmap.createBitmap(bm.width, bm.height, Bitmap.Config.ARGB_8888)
        val cv = Canvas(flat)
        cv.drawColor(Color.WHITE)
        cv.drawBitmap(bm, 0f, 0f, null)
        return flat
    }

    private fun previewOf(b: Bitmap): Bitmap {
        val big = maxOf(b.width, b.height)
        if (big <= 4096) return b
        val k = 4096f / big
        return Bitmap.createScaledBitmap(b, maxOf(1, (b.width * k).toInt()), maxOf(1, (b.height * k).toInt()), true)
    }

    private fun aiMaxPx(ia: Boolean, scale: Int): Long =
        if (ia) (if (scale == 4) 1000000L else 2000000L) else 24000000L / (scale * scale)

    private fun shrinkDims(w: Int, h: Int, maxPx: Long): Pair<Int, Int> {
        val k = Math.sqrt(maxPx.toDouble() / (w.toLong() * h)) * 0.999
        return Pair(maxOf(1, (w * k).toInt()), maxOf(1, (h * k).toInt()))
    }

    private fun downscaleTo(src: Bitmap, nw: Int, nh: Int): Bitmap {
        var cur = src
        while (cur.width / 2 >= nw && cur.height / 2 >= nh) {
            cur = Bitmap.createScaledBitmap(cur, cur.width / 2, cur.height / 2, true)
        }
        return if (cur.width == nw && cur.height == nh) cur else Bitmap.createScaledBitmap(cur, nw, nh, true)
    }

    private fun lowBattery(): Boolean {
        val i = registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return false
        val lvl = i.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
        val sc = i.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100)
        val st = i.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1)
        val charging = st == android.os.BatteryManager.BATTERY_STATUS_CHARGING || st == android.os.BatteryManager.BATTERY_STATUS_FULL
        return lvl >= 0 && sc > 0 && lvl * 100 / sc <= 15 && !charging
    }

    private fun openEnhancer(f: File?) {
        if (f != null) {
            aiFile = f
            aiResult = null
            aiOrig = null
            aiName = ""
            aiDest = null
            aiShrink = false
            selected.clear()
        }
        showEnhance()
    }

    private fun addExplanation(b: LinearLayout) {
        val card = cardBox()
        card.addView(txt("Ampliar no es lo mismo que mejorar", 15f, TXT, true))
        card.addView(txt("Ampliar (interpolación) agranda la imagen mezclando los píxeles que ya tiene: no aparece detalle nuevo.", 13f, MUTE), lp(-1, -2).also { it.topMargin = dp(8) })
        card.addView(txt("Mejorar con IA usa una red neuronal entrenada para estimar texturas y bordes plausibles y reducir el ruido. El detalle que añade es una reconstrucción probable, no información recuperada del original, y puede diferir de la realidad.", 13f, MUTE), lp(-1, -2).also { it.topMargin = dp(8) })
        b.addView(card, lp(-1, -2).also { it.topMargin = dp(14) })
    }

    private fun showEnhance() {
        val y = pageScroll?.scrollY ?: 0
        val b = body()
        val amber = c(0xFFFBBF24)
        if (aiError.isNotEmpty()) {
            b.addView(txt(aiError, 13f, amber, true), lp(-1, -2).also { it.bottomMargin = dp(10) })
            aiError = ""
        }
        val f = aiFile
        if (f == null || !f.isFile) {
            b.addView(sectionTitle("Imagen"))
            b.addView(txt("Elige una foto para ampliarla y mejorar sus detalles. Todo se procesa en tu teléfono.", 13f, MUTE))
            val pick = primaryButton("Elegir imagen")
            pick.setOnClickListener {
                pickFilesSheet(1, true) { picked ->
                    if (picked.isNotEmpty()) openEnhancer(picked[0])
                }
            }
            b.addView(pick, lp(-1, dp(50)).also { it.topMargin = dp(12) })
            addExplanation(b)
            page("Mejorar imágenes", b)
            return
        }
        val dims = imageDims(f)
        val w = dims.first
        val h = dims.second
        val scale = if (aiScale == 0) 2 else 4
        val ia = aiMethod == 0
        val px = w.toLong() * h

        b.addView(sectionTitle("Imagen original"))
        val pv = ImageView(this)
        pv.scaleType = ImageView.ScaleType.FIT_CENTER
        pv.background = rounded(alpha(Color.WHITE, 0x10), 14)
        b.addView(pv, lp(-1, dp(190)))
        Thread {
            val bm = try {
                decodeFull(f, 720)
            } catch (e: Throwable) {
                null
            }
            if (bm != null) runOnUiThread { pv.setImageBitmap(bm) }
        }.start()
        b.addView(txt(f.name, 14f, TXT, true), lp(-1, -2).also { it.topMargin = dp(8) })
        b.addView(txt(w.toString() + " × " + h + " px  ·  " + fmt(f.length()), 12f, MUTE))
        val change = ghostButton("Cambiar imagen")
        change.setOnClickListener {
            pickFilesSheet(1, true) { picked ->
                if (picked.isNotEmpty()) openEnhancer(picked[0])
            }
        }
        b.addView(change, lp(-1, dp(44)).also { it.topMargin = dp(8) })

        b.addView(sectionTitle("Método"))
        b.addView(chipGroup(listOf("IA (Real-ESRGAN)", "Ampliación clásica"), aiMethod) {
            aiMethod = it
            showEnhance()
        })
        b.addView(txt(if (ia) "Red neuronal Real-ESRGAN (general-x4v3, 1,2 millones de parámetros) ejecutada en tu teléfono. Reduce ruido y artefactos y reconstruye texturas plausibles. No recupera información que no existe." else "Interpolación sin IA: agranda los píxeles existentes. Es más rápida, pero no añade detalle ni reduce el ruido.", 12f, MUTE), lp(-1, -2).also { it.topMargin = dp(8) })

        b.addView(sectionTitle("Ampliación"))
        b.addView(chipGroup(listOf("2×", "4×"), aiScale) {
            aiScale = it
            showEnhance()
        })
        if (ia && scale == 2) b.addView(txt("2× usa el modelo de 4× y reduce el resultado a la mitad: suele quedar más limpio y usa menos memoria.", 12f, MUTE), lp(-1, -2).also { it.topMargin = dp(8) })
        val limitPx = aiMaxPx(ia, scale)
        val tooBig = w > 0 && px > limitPx
        val shr = if (tooBig) shrinkDims(w, h, limitPx) else Pair(w, h)
        b.addView(txt((if (tooBig) "Resultado tras reducirla: " else "Resultado: ") + (shr.first * scale) + " × " + (shr.second * scale) + " px (" + scale + "× la imagen procesada)", 13f, TXT, true), lp(-1, -2).also { it.topMargin = dp(8) })
        if (ia && w > 0) {
            val tiles = ((shr.first + 95) / 96) * ((shr.second + 95) / 96)
            b.addView(txt("Se procesa en unos " + tiles + " bloques de 128 px. Según tu teléfono puede tardar desde unos segundos hasta varios minutos, y usa batería.", 12f, MUTE), lp(-1, -2).also { it.topMargin = dp(4) })
        }

        var reason = ""
        if (w <= 0) {
            reason = "No se pudo leer esta imagen."
        } else if (tooBig) {
            reason = "Esta imagen tiene " + String.format("%.1f", px / 1000000.0) + " MP y el máximo de entrada para " + (if (ia) "IA " else "ampliación clásica ") + scale + "× es " + String.format("%.1f", limitPx / 1000000.0) + " MP." +
                (if (ia && scale == 4) " Con 2× el máximo sube a 2,0 MP." else "") +
                " Al pulsar Mejorar se te ofrecerá reducirla antes de procesarla."
        }

        if (ia) {
            b.addView(sectionTitle("Modelo de IA"))
            val card = cardBox()
            if (aiDlPct >= 0) {
                val lbl = txt("Descargando… " + aiDlPct + " %", 13f, TXT, true)
                val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
                bar.max = 100
                bar.progress = aiDlPct
                bar.progressTintList = ColorStateList.valueOf(ACC)
                aiDlLabel = lbl
                aiDlBar = bar
                card.addView(lbl)
                card.addView(bar, lp(-1, dp(6)).also { it.topMargin = dp(8) })
            } else if (modelReady()) {
                card.addView(txt("Modelo instalado (" + fmt(modelFile().length()) + ")", 13f, GREEN, true))
                val del = ghostButton("Eliminar modelo")
                del.setOnClickListener {
                    modelFile().delete()
                    showEnhance()
                }
                card.addView(del, lp(-1, dp(42)).also { it.topMargin = dp(8) })
            } else {
                card.addView(txt("El modelo de IA todavía no está en tu teléfono.", 13f, TXT, true))
                card.addView(txt("Pesa unos 5 MB y se descarga una sola vez desde el servidor público de Qualcomm AI Hub (el enlace oficial de la ficha del modelo en Hugging Face). Tus imágenes nunca se envían a ningún sitio: solo se descarga el modelo.", 12f, MUTE), lp(-1, -2).also { it.topMargin = dp(6) })
                val dl = primaryButton("Descargar modelo (≈ 5 MB)")
                dl.setOnClickListener {
                    confirmSheet("¿Descargar el modelo de IA?", "Se descargarán unos 5 MB desde qaihub-public-assets.s3.us-west-2.amazonaws.com y necesitas conexión a internet. Licencias: BSD-3 (modelo original) y AI Model Hub License (archivo descargado).", "Descargar") { startModelDownload() }
                }
                card.addView(dl, lp(-1, dp(46)).also { it.topMargin = dp(10) })
            }
            b.addView(card, lp(-1, -2))
        }

        if (reason.isNotEmpty()) b.addView(txt(reason, 13f, amber, true), lp(-1, -2).also { it.topMargin = dp(10) })
        val go = primaryButton("Mejorar")
        go.setOnClickListener { requestEnhance(f, reason, tooBig) }
        b.addView(go, lp(-1, dp(52)).also { it.topMargin = dp(18) })
        if (ia && aiDlPct < 0 && !modelReady()) b.addView(txt("Al pulsar Mejorar se te ofrecerá descargar el modelo de IA (≈ 5 MB) y la mejora empezará sola.", 12f, MUTE), lp(-1, -2).also { it.topMargin = dp(8) })
        addExplanation(b)
        page("Mejorar imágenes", b)
        pageScroll?.post { pageScroll?.scrollTo(0, y) }
    }

    private fun downloadModel(onPct: (Int) -> Unit) {
        val zipTmp = File(cacheDir, "modelo.zip")
        try {
            val cn = URL(modelUrl).openConnection() as HttpURLConnection
            cn.connectTimeout = 15000
            cn.readTimeout = 30000
            cn.instanceFollowRedirects = true
            if (cn.responseCode != 200) throw Exception("el servidor respondió " + cn.responseCode)
            val total = cn.contentLengthLong
            if (total <= 0) onPct(-1)
            cn.inputStream.use { ins ->
                FileOutputStream(zipTmp).use { os ->
                    val buf = ByteArray(32768)
                    var read = 0L
                    var last = -1
                    while (true) {
                        if (aiCancel) throw Exception("cancelado")
                        val n = ins.read(buf)
                        if (n < 0) break
                        os.write(buf, 0, n)
                        read += n
                        if (total > 0) {
                            val pc = (read * 100 / total).toInt()
                            if (pc != last && pc < 100) {
                                last = pc
                                onPct(pc)
                            }
                        }
                    }
                }
            }
            onPct(-2)
            val md = File(filesDir, "models")
            md.mkdirs()
            val tmp = File(md, "descarga.tmp")
            var found = false
            ZipInputStream(FileInputStream(zipTmp).buffered()).use { z ->
                var e = z.nextEntry
                while (e != null) {
                    if (!e.isDirectory && e.name.lowercase().endsWith(".tflite")) {
                        FileOutputStream(tmp).use { os -> z.copyTo(os) }
                        found = true
                        break
                    }
                    e = z.nextEntry
                }
            }
            if (!found) throw Exception("el paquete descargado no contiene un modelo .tflite")
            if (tmp.length() < 500000L) throw Exception("el modelo descargado está incompleto")
            val mf = modelFile()
            if (mf.exists()) mf.delete()
            if (!tmp.renameTo(mf)) throw Exception("no se pudo guardar el modelo")
        } finally {
            zipTmp.delete()
        }
    }

    private fun startModelDownload() {
        aiDlPct = 0
        aiCancel = false
        showEnhance()
        Thread {
            var err: String? = null
            try {
                downloadModel { pc ->
                    runOnUiThread {
                        if (pc >= 0) {
                            aiDlPct = pc
                            aiDlBar?.progress = pc
                        }
                        aiDlLabel?.text = if (pc == -2) "Instalando modelo…" else if (pc < 0) "Descargando…" else "Descargando… " + pc + " %"
                    }
                }
            } catch (e: Throwable) {
                err = friendlyError(e)
            }
            runOnUiThread {
                aiDlPct = -1
                if (err != null) aiError = "No se pudo descargar el modelo: " + err
                if (inViewer && curPage == "Mejorar imágenes") showEnhance()
            }
        }.start()
    }

    private fun runSuperRes(src: Bitmap, scale: Int, report: (String, Int, String) -> Unit): Bitmap {
        val mf = modelFile()
        if (!mf.exists()) throw ModelError("el modelo de IA no está instalado")
        report("Cargando modelo de IA", -1, "La primera vez puede tardar unos segundos")
        val mapped = FileInputStream(mf).use { it.channel.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, mf.length()) }
        val opts = Interpreter.Options()
        opts.setNumThreads(minOf(4, maxOf(1, Runtime.getRuntime().availableProcessors())))
        val interp = try {
            Interpreter(mapped, opts)
        } catch (e: Throwable) {
            throw ModelError("no se pudo cargar el modelo de IA: " + (e.message ?: e.javaClass.simpleName))
        }
        try {
            val ti = interp.getInputTensor(0)
            val to = interp.getOutputTensor(0)
            if (ti.dataType() != DataType.FLOAT32 || to.dataType() != DataType.FLOAT32) throw ModelError("el modelo descargado no usa números de 32 bits")
            val si = ti.shape()
            val so = to.shape()
            if (si.size != 4 || so.size != 4) throw ModelError("el modelo tiene una forma inesperada")
            val nchw = si[1] == 3 && si[3] != 3
            val inH = if (nchw) si[2] else si[1]
            val inW = if (nchw) si[3] else si[2]
            val outH = if (nchw) so[2] else so[1]
            val outW = if (nchw) so[3] else so[2]
            if (inH <= 0 || inW <= 0) throw ModelError("el modelo tiene una forma inesperada")
            val up = outH / inH
            if (up < 2 || outH != inH * up || outW != inW * up || up % scale != 0) throw ModelError("el modelo no amplía como se esperaba")
            val ds = up / scale
            val iw = src.width
            val ih = src.height
            val px = IntArray(iw * ih)
            src.getPixels(px, 0, iw, 0, 0, iw, ih)
            val result = Bitmap.createBitmap(iw * scale, ih * scale, Bitmap.Config.ARGB_8888)
            val pad = if (inW >= 64) 16 else 4
            val core = inW - 2 * pad
            val tilesX = (iw + core - 1) / core
            val tilesY = (ih + core - 1) / core
            val total = tilesX * tilesY
            val inBuf = ByteBuffer.allocateDirect(inW * inH * 3 * 4).order(java.nio.ByteOrder.nativeOrder())
            val outBuf = ByteBuffer.allocateDirect(outW * outH * 3 * 4).order(java.nio.ByteOrder.nativeOrder())
            val inArr = FloatArray(inW * inH * 3)
            val outArr = FloatArray(outW * outH * 3)
            val plane = inW * inH
            val oplane = outW * outH
            var range = 0
            var done = 0
            val tStart = System.currentTimeMillis()
            report("Mejorando imagen con IA", 0, "Procesando el primer bloque…")
            for (ty in 0 until tilesY) {
                for (tx in 0 until tilesX) {
                    if (aiCancel) throw Exception("cancelado")
                    val cx = tx * core
                    val cy = ty * core
                    val cw = minOf(core, iw - cx)
                    val ch = minOf(core, ih - cy)
                    for (yy in 0 until inH) {
                        val sy = (cy - pad + yy).coerceIn(0, ih - 1)
                        for (xx in 0 until inW) {
                            val sx = (cx - pad + xx).coerceIn(0, iw - 1)
                            val p = px[sy * iw + sx]
                            val r = ((p shr 16) and 255) / 255f
                            val g = ((p shr 8) and 255) / 255f
                            val bl = (p and 255) / 255f
                            if (nchw) {
                                val o = yy * inW + xx
                                inArr[o] = r
                                inArr[plane + o] = g
                                inArr[2 * plane + o] = bl
                            } else {
                                val o = (yy * inW + xx) * 3
                                inArr[o] = r
                                inArr[o + 1] = g
                                inArr[o + 2] = bl
                            }
                        }
                    }
                    inBuf.rewind()
                    inBuf.asFloatBuffer().put(inArr)
                    inBuf.rewind()
                    outBuf.rewind()
                    try {
                        interp.run(inBuf, outBuf)
                    } catch (e: Throwable) {
                        throw ModelError("el motor de IA no pudo ejecutar el modelo en este teléfono: " + (e.message ?: e.javaClass.simpleName))
                    }
                    outBuf.rewind()
                    outBuf.asFloatBuffer().get(outArr)
                    if (range == 0) {
                        var mx = 0f
                        for (v in outArr) if (v > mx) mx = v
                        range = if (mx > 2f) 2 else 1
                    }
                    val mul = if (range == 2) 1f else 255f
                    val rw = cw * scale
                    val rh = ch * scale
                    val block = IntArray(rw * rh)
                    val inv = 1f / (ds * ds)
                    for (oy in 0 until rh) {
                        for (ox in 0 until rw) {
                            var rs = 0f
                            var gs = 0f
                            var bs = 0f
                            for (dy in 0 until ds) {
                                val my = pad * up + oy * ds + dy
                                for (dx in 0 until ds) {
                                    val mx2 = pad * up + ox * ds + dx
                                    if (nchw) {
                                        val o = my * outW + mx2
                                        rs += outArr[o]
                                        gs += outArr[oplane + o]
                                        bs += outArr[2 * oplane + o]
                                    } else {
                                        val o = (my * outW + mx2) * 3
                                        rs += outArr[o]
                                        gs += outArr[o + 1]
                                        bs += outArr[o + 2]
                                    }
                                }
                            }
                            val ri = (rs * inv * mul + 0.5f).toInt().coerceIn(0, 255)
                            val gi = (gs * inv * mul + 0.5f).toInt().coerceIn(0, 255)
                            val bi = (bs * inv * mul + 0.5f).toInt().coerceIn(0, 255)
                            block[oy * rw + ox] = Color.rgb(ri, gi, bi)
                        }
                    }
                    result.setPixels(block, 0, rw, cx * scale, cy * scale, rw, rh)
                    done++
                    if (done < total) {
                        val el = System.currentTimeMillis() - tStart
                        val rem = el * (total - done) / done
                        report("Mejorando imagen con IA", done * 100 / total, "Bloque " + done + " de " + total + " · tiempo restante aproximado: " + fmtTime(rem))
                    } else {
                        report("Finalizando", -1, "")
                    }
                }
            }
            return result
        } finally {
            interp.close()
        }
    }

    private fun noticeSheet(title: String, msg: String) {
        showSheet(false) { d ->
            val col = LinearLayout(this)
            col.orientation = LinearLayout.VERTICAL
            col.addView(txt(title, 18f, TXT, true))
            col.addView(txt(msg, 14f, MUTE), lp(-1, -2).also { it.topMargin = dp(8) })
            val ok = primaryButton("Entendido")
            ok.setOnClickListener { d.dismiss() }
            col.addView(ok, lp(-1, dp(48)).also { it.topMargin = dp(16) })
            col
        }
    }

    private fun requestEnhance(f: File, reason: String, oversize: Boolean) {
        if (reason.isNotEmpty() && !oversize) {
            noticeSheet("No se puede mejorar esta imagen", reason)
            return
        }
        if (oversize) {
            val ia = aiMethod == 0
            val scale = if (aiScale == 0) 2 else 4
            val lim = aiMaxPx(ia, scale)
            val d = imageDims(f)
            val sd = shrinkDims(d.first, d.second, lim)
            confirmSheet(
                "La imagen es demasiado grande",
                "Tiene " + String.format("%.1f", d.first.toLong() * d.second / 1000000.0) + " MP y el máximo de entrada para este método es " + String.format("%.1f", lim / 1000000.0) + " MP. Se puede reducir a " + sd.first + "×" + sd.second + " px antes de mejorarla. El resultado tendrá unos " + (sd.first * scale) + "×" + (sd.second * scale) + " px, parecido al tamaño original: servirá sobre todo para limpiar ruido y artefactos, no para ganar resolución.",
                "Reducir y mejorar"
            ) {
                aiShrink = true
                continueEnhance(f)
            }
            return
        }
        aiShrink = false
        continueEnhance(f)
    }

    private fun continueEnhance(f: File) {
        if (aiMethod == 0 && aiDlPct >= 0) {
            toast("El modelo de IA todavía se está descargando.")
            return
        }
        val needModel = aiMethod == 0 && !modelReady()
        val launch: () -> Unit = {
            if (aiMethod == 0 && lowBattery()) {
                confirmSheet("Batería baja", "La IA usa mucho el procesador y gasta batería. Con poca carga conviene conectar el cargador. ¿Continuar de todos modos?", "Continuar") { startEnhance(f, needModel) }
            } else {
                startEnhance(f, needModel)
            }
        }
        if (needModel) {
            confirmSheet(
                "Hace falta el modelo de IA",
                "Se descargarán unos 5 MB desde qaihub-public-assets.s3.us-west-2.amazonaws.com (necesitas internet) y luego la mejora empieza sola. Tus imágenes no se envían a ningún sitio. Licencias: BSD-3 (modelo original) y AI Model Hub License (archivo descargado).",
                "Descargar y mejorar"
            ) { launch() }
        } else {
            launch()
        }
    }

    private fun startEnhance(f: File, download: Boolean) {
        val scale = if (aiScale == 0) 2 else 4
        val ia = aiMethod == 0
        converting = true
        aiCancel = false
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        val pv = ImageView(this)
        pv.scaleType = ImageView.ScaleType.FIT_CENTER
        pv.setPadding(dp(16), dp(8), dp(16), dp(8))
        col.addView(pv, lp(-1, 0, 1f))
        val panel = LinearLayout(this)
        panel.orientation = LinearLayout.VERTICAL
        panel.setPadding(dp(16), dp(8), dp(16), dp(18))
        val stageT = txt("Preparando imagen", 17f, TXT, true)
        val pctT = txt("", 17f, ACC2, true)
        val top = LinearLayout(this)
        top.gravity = Gravity.CENTER_VERTICAL
        top.addView(stageT, lp(0, -2, 1f))
        top.addView(pctT)
        panel.addView(top)
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        bar.max = 100
        bar.isIndeterminate = true
        bar.progressTintList = ColorStateList.valueOf(ACC)
        bar.indeterminateTintList = ColorStateList.valueOf(ACC)
        panel.addView(bar, lp(-1, dp(8)).also { it.topMargin = dp(10) })
        val detailT = txt("", 12f, MUTE)
        panel.addView(detailT, lp(-1, -2).also { it.topMargin = dp(8) })
        panel.addView(txt(if (ia) "La IA trabaja en el procesador del teléfono: puede calentarse y gastar batería. El porcentaje solo cuenta trabajo ya terminado. Puedes cancelar cuando quieras." else "Sin IA: se agranda la imagen por interpolación.", 12f, MUTE), lp(-1, -2).also { it.topMargin = dp(6) })
        val cancel = ghostButton("Cancelar")
        cancel.setOnClickListener {
            aiCancel = true
            cancel.text = "Cancelando…"
            cancel.isEnabled = false
        }
        panel.addView(cancel, lp(-1, dp(48)).also { it.topMargin = dp(14) })
        col.addView(panel, lp(-1, -2))
        pageFixed("Mejorar imágenes", col)
        Thread {
            val bm = try {
                decodeFull(f, 1200)
            } catch (e: Throwable) {
                null
            }
            if (bm != null) runOnUiThread { pv.setImageBitmap(bm) }
        }.start()
        val report: (String, Int, String) -> Unit = { stage, pct, detail ->
            runOnUiThread {
                stageT.text = stage
                if (pct < 0) {
                    if (!bar.isIndeterminate) bar.isIndeterminate = true
                    pctT.text = ""
                } else {
                    if (bar.isIndeterminate) bar.isIndeterminate = false
                    bar.progress = pct
                    pctT.text = pct.toString() + " %"
                }
                detailT.text = detail
            }
        }
        val t0 = System.currentTimeMillis()
        Thread {
            var fromDownload = false
            try {
                if (ia && download) {
                    fromDownload = true
                    report("Descargando modelo de IA", -1, "Necesario una sola vez (≈ 5 MB)")
                    downloadModel { pc ->
                        if (pc == -2) report("Instalando modelo de IA", -1, "")
                        else report("Descargando modelo de IA", pc, "Necesario una sola vez (≈ 5 MB)")
                    }
                    fromDownload = false
                }
                if (aiCancel) throw Exception("cancelado")
                report("Preparando imagen", -1, "")
                val src = flattenWhite(decodeFull(f, 0))
                var work = src
                var shrunk = ""
                val lim = aiMaxPx(ia, scale)
                if (src.width.toLong() * src.height > lim) {
                    if (!aiShrink) throw Exception("la imagen supera el tamaño permitido")
                    val sd = shrinkDims(src.width, src.height, lim)
                    report("Reduciendo la imagen al tamaño permitido", -1, "")
                    work = downscaleTo(src, sd.first, sd.second)
                    shrunk = " · reducida de " + src.width + "×" + src.height + " a " + sd.first + "×" + sd.second + " antes de mejorarla"
                }
                val result: Bitmap
                if (ia) {
                    result = runSuperRes(work, scale, report)
                } else {
                    report("Ampliando imagen", -1, "Interpolación, sin IA")
                    result = upscale(work, work.width * scale, work.height * scale)
                }
                report("Finalizando", -1, "")
                val secs = fmtTime(System.currentTimeMillis() - t0)
                aiOrig = src
                aiResult = result
                aiInfo = (if (ia) "IA Real-ESRGAN general-x4v3" + (if (scale == 2) " (4× del modelo reducido a 2×)" else "") else "Ampliación clásica por interpolación (sin IA)") + " · tardó " + secs + shrunk
                aiShrunkUsed = shrunk.isNotEmpty()
                runOnUiThread {
                    converting = false
                    window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    showEnhanceResult()
                }
            } catch (e: Throwable) {
                val modelIssue = e is ModelError
                val wasDownload = fromDownload
                runOnUiThread {
                    converting = false
                    window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    if (aiCancel) {
                        toast("Procesamiento cancelado")
                        showEnhance()
                    } else {
                        showEnhanceError(f, friendlyError(e), modelIssue, wasDownload)
                    }
                }
            }
        }.start()
    }

    private fun showEnhanceError(f: File, msg: String, modelIssue: Boolean, wasDownload: Boolean) {
        val b = body()
        b.addView(txt("No se pudo mejorar la imagen", 20f, TXT, true))
        val box = cardBox()
        box.addView(txt(msg.replaceFirstChar { it.uppercase() }, 14f, c(0xFFFB7185), true))
        val hint = if (wasDownload) "No se pudo descargar el modelo de IA. Revisa tu conexión a internet e inténtalo de nuevo."
        else if (modelIssue) "El modelo de IA no funciona en este teléfono. Puedes descargarlo otra vez o usar la ampliación clásica, que no necesita IA."
        else if (msg.contains("memoria")) "Prueba con 2× o con una imagen más pequeña."
        else "Puedes reintentarlo o volver a las opciones."
        box.addView(txt(hint, 13f, MUTE), lp(-1, -2).also { it.topMargin = dp(8) })
        b.addView(box, lp(-1, -2).also { it.topMargin = dp(12) })
        val retry = primaryButton("Reintentar")
        retry.setOnClickListener { startEnhance(f, wasDownload) }
        b.addView(retry, lp(-1, dp(50)).also { it.topMargin = dp(16) })
        if (modelIssue) {
            val again = ghostButton("Descargar el modelo otra vez")
            again.setOnClickListener {
                modelFile().delete()
                startEnhance(f, true)
            }
            b.addView(again, lp(-1, dp(48)).also { it.topMargin = dp(8) })
        }
        if (aiMethod == 0) {
            val classic = ghostButton("Usar ampliación clásica")
            classic.setOnClickListener {
                aiMethod = 1
                startEnhance(f, false)
            }
            b.addView(classic, lp(-1, dp(48)).also { it.topMargin = dp(8) })
        }
        val back = ghostButton("Volver")
        back.setOnClickListener { showEnhance() }
        b.addView(back, lp(-1, dp(48)).also { it.topMargin = dp(8) })
        page("Mejorar imágenes", b)
    }

    private fun showEnhanceResult() {
        val before = aiOrig
        val after = aiResult
        if (before == null || after == null || aiFile == null) {
            showEnhance()
            return
        }
        val scale = if (aiScale == 0) 2 else 4
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        val cmp = CompareView(this, before, previewOf(after))
        col.addView(cmp, lp(-1, 0, 1f))
        val panel = LinearLayout(this)
        panel.orientation = LinearLayout.VERTICAL
        panel.setPadding(dp(16), dp(10), dp(16), dp(14))
        panel.addView(txt("Original " + before.width + "×" + before.height + "  →  Mejorada " + after.width + "×" + after.height + " px" + (if (aiShrunkUsed) "" else " (" + scale + "×)"), 13f, TXT, true))
        panel.addView(txt(aiInfo, 12f, MUTE), lp(-1, -2).also { it.topMargin = dp(2) })
        panel.addView(chipGroup(listOf("Original", "Dividido", "Mejorada"), 1) {
            cmp.setSplit(if (it == 0) 1f else if (it == 1) 0.5f else 0f)
        }, lp(-1, -2).also { it.topMargin = dp(10) })
        panel.addView(txt("Pellizca para ampliar, arrastra para moverte y desliza la barra para comparar.", 12f, MUTE), lp(-1, -2).also { it.topMargin = dp(8) })
        val row = LinearLayout(this)
        val back = ghostButton("Volver al original")
        back.setOnClickListener {
            aiResult = null
            aiOrig = null
            showEnhance()
        }
        val save = primaryButton("Guardar…")
        save.setOnClickListener { saveEnhanced() }
        row.addView(back, lp(0, dp(48), 1f))
        row.addView(save, lp(0, dp(48), 1f).also { it.leftMargin = dp(10) })
        panel.addView(row, lp(-1, -2).also { it.topMargin = dp(12) })
        col.addView(panel, lp(-1, -2))
        pageFixed("Comparar", col)
    }

    private fun saveEnhanced() {
        val res = aiResult ?: return
        val src = aiFile ?: return
        showSheet { d ->
            var name = if (aiName.isBlank()) baseOf(src) + "_mejorada" else aiName
            var fmtI = aiFmt
            var dest: File = aiDest ?: src.parentFile ?: dir
            val col = LinearLayout(this)
            col.orientation = LinearLayout.VERTICAL
            col.addView(txt("Guardar imagen mejorada", 18f, TXT, true))
            col.addView(label("Nombre"))
            col.addView(field("Nombre del archivo", false, name) { name = it }, lp(-1, -2).also { it.topMargin = dp(6) })
            col.addView(label("Formato"))
            col.addView(chipGroup(listOf("JPG", "PNG", "WebP"), fmtI) { fmtI = it }, lp(-1, -2).also { it.topMargin = dp(6) })
            col.addView(txt("JPG y WebP se guardan con calidad 95 %. PNG es sin pérdida y pesa más.", 12f, MUTE), lp(-1, -2).also { it.topMargin = dp(6) })
            col.addView(label("Carpeta"))
            val pathT = txt("Almacenamiento interno" + dest.absolutePath.removePrefix(root.absolutePath), 12f, ACC2)
            pathT.setPadding(dp(12), dp(6), dp(12), dp(6))
            pathT.background = rounded(alpha(ACC, 0x33), 12)
            col.addView(pathT, lp(-2, -2).also { it.topMargin = dp(6) })
            val chg = ghostButton("Cambiar carpeta")
            chg.setOnClickListener {
                pickFolder("Guardar en…", "Elegir esta carpeta", dest) { picked ->
                    dest = picked
                    pathT.text = "Almacenamiento interno" + picked.absolutePath.removePrefix(root.absolutePath)
                    true
                }
            }
            col.addView(chg, lp(-1, dp(44)).also { it.topMargin = dp(8) })
            col.addView(txt("El original no se toca y nunca se sobrescribe: si el nombre ya existe se agrega (1).", 12f, MUTE), lp(-1, -2).also { it.topMargin = dp(8) })
            val btns = LinearLayout(this)
            val no = ghostButton("Cancelar")
            no.setOnClickListener { d.dismiss() }
            val yes = primaryButton("Guardar")
            yes.setOnClickListener {
                val nm = cleanName(name).ifBlank { baseOf(src) + "_mejorada" }
                aiName = nm
                aiFmt = fmtI
                aiDest = dest
                d.dismiss()
                doSaveEnhanced(res, nm, fmtI, dest)
            }
            btns.addView(no, lp(0, dp(48), 1f))
            btns.addView(yes, lp(0, dp(48), 1f).also { it.leftMargin = dp(10) })
            col.addView(btns, lp(-1, -2).also { it.topMargin = dp(16) })
            col
        }
    }

    private fun doSaveEnhanced(bm: Bitmap, base: String, fmtI: Int, dest: File) {
        toast("Guardando…")
        Thread {
            var out: File? = null
            var err: String? = null
            try {
                if (!dest.canWrite()) throw Exception("sin permiso de escritura en la carpeta de destino")
                val ext = if (fmtI == 0) "jpg" else if (fmtI == 1) "png" else "webp"
                val tmp = File(dest, ".nexora_" + System.nanoTime() + ".tmp")
                try {
                    val cf = if (fmtI == 0) Bitmap.CompressFormat.JPEG else if (fmtI == 1) Bitmap.CompressFormat.PNG else webpFormat(false)
                    FileOutputStream(tmp).use { os ->
                        if (!bm.compress(cf, 95, os)) throw Exception("el sistema no pudo codificar la imagen")
                    }
                    out = finalizeFile(tmp, dest, base, ext)
                } finally {
                    if (tmp.exists()) tmp.delete()
                }
            } catch (e: Throwable) {
                err = friendlyError(e)
            }
            runOnUiThread {
                if (!inViewer) refresh()
                val o = out
                if (o != null) reportSheet("imagen guardada como " + o.name, 1, emptyList(), null)
                else reportSheet("", 0, listOf(Pair(base, err ?: "error desconocido")), null)
            }
        }.start()
    }

    // ---------- miniaturas ----------
    private fun sha1(s: String): String {
        val dg = java.security.MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
        val sb = StringBuilder()
        for (b in dg) sb.append(String.format("%02x", b))
        return sb.toString()
    }

    private fun bindThumb(item: Item, iv: ImageView, badge: View) {
        val key = item.f.absolutePath + "|" + item.mod + "|" + item.size
        iv.tag = key
        val hit = memCache.get(key)
        if (hit != null) {
            iv.setImageBitmap(hit)
            badge.visibility = View.INVISIBLE
            return
        }
        if (failedThumbs.contains(key)) return
        thumbPool.execute {
            val bm = thumbFor(item.f, key)
            if (bm == null) {
                failedThumbs.add(key)
            } else {
                memCache.put(key, bm)
                runOnUiThread {
                    if (iv.tag == key) {
                        iv.setImageBitmap(bm)
                        badge.visibility = View.INVISIBLE
                    }
                }
            }
        }
    }

    private fun thumbFor(f: File, key: String): Bitmap? {
        val target = thumbPx
        val td = File(cacheDir, "thumbs")
        td.mkdirs()
        val cf = File(td, sha1(key) + ".jpg")
        if (cf.exists()) {
            val b = BitmapFactory.decodeFile(cf.path)
            if (b != null) {
                cf.setLastModified(System.currentTimeMillis())
                return b
            }
            cf.delete()
        }
        try {
            val o = BitmapFactory.Options()
            o.inJustDecodeBounds = true
            BitmapFactory.decodeFile(f.path, o)
            if (o.outWidth <= 0 || o.outHeight <= 0) return null
            var s = 1
            while (minOf(o.outWidth, o.outHeight) / (s * 2) >= target) s *= 2
            val o2 = BitmapFactory.Options()
            o2.inSampleSize = s
            val bm = BitmapFactory.decodeFile(f.path, o2) ?: return null
            var rot = 0
            try {
                val ori = ExifInterface(f.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)
                rot = if (ori == 6) 90 else if (ori == 3) 180 else if (ori == 8) 270 else 0
            } catch (e: Exception) {
                rot = 0
            }
            val out = Bitmap.createBitmap(target, target, Bitmap.Config.ARGB_8888)
            val cv = Canvas(out)
            cv.drawColor(c(0xFF1A2342))
            val bw = bm.width.toFloat()
            val bh = bm.height.toFloat()
            val m = Matrix()
            m.postTranslate(-bw / 2f, -bh / 2f)
            m.postRotate(rot.toFloat())
            val rw = if (rot == 90 || rot == 270) bh else bw
            val rh = if (rot == 90 || rot == 270) bw else bh
            val sc = maxOf(target / rw, target / rh)
            m.postScale(sc, sc)
            m.postTranslate(target / 2f, target / 2f)
            cv.drawBitmap(bm, m, Paint(Paint.FILTER_BITMAP_FLAG))
            bm.recycle()
            try {
                cf.outputStream().use { os -> out.compress(Bitmap.CompressFormat.JPEG, 85, os) }
            } catch (e: Exception) {
            }
            return out
        } catch (e: Throwable) {
            return null
        }
    }

    private fun trimCache() {
        try {
            val fs = File(cacheDir, "thumbs").listFiles() ?: return
            var total = fs.sumOf { it.length() }
            if (total <= 80L * 1024 * 1024) return
            for (x in fs.sortedBy { it.lastModified() }) {
                if (total <= 50L * 1024 * 1024) break
                total -= x.length()
                x.delete()
            }
        } catch (e: Exception) {
        }
    }

    private fun extOf(name: String): String {
        val i = name.lastIndexOf('.')
        return if (i > 0 && i < name.length - 1) name.substring(i) else ""
    }

    private fun buildPlan(files: List<File>, base: String, sep: String, start: Long, pad: Int, orderIdx: Int, listFiles: List<File>): List<Plan> {
        val sorted = when (orderIdx) {
            1 -> listFiles
            2 -> files.sortedBy { it.name.lowercase() }
            3 -> files.sortedBy { it.lastModified() }
            4 -> files.sortedBy { it.length() }
            else -> files
        }
        val existing = HashSet<String>()
        sorted[0].parentFile?.list()?.forEach { existing.add(it.lowercase()) }
        val used = HashSet<String>()
        val out = ArrayList<Plan>()
        for ((i, f) in sorted.withIndex()) {
            val num = (start + i).toString().padStart(pad, '0')
            val nn = base + sep + num + extOf(f.name)
            val key = nn.lowercase()
            val dup = !used.add(key)
            var status = "Listo"
            var ok = true
            if (nn.toByteArray().size > 255) {
                status = "Muy largo"
                ok = false
            } else if (nn == f.name) {
                status = "Sin cambios"
            } else if (dup) {
                status = "Repetido"
                ok = false
            } else if (existing.contains(key) && key != f.name.lowercase()) {
                status = "Ya existe"
                ok = false
            }
            out.add(Plan(f, nn, status, ok))
        }
        return out
    }

    private inner class MaxScroll(ctx: Context, val maxH: Int) : ScrollView(ctx) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST))
        }
    }

    private fun label(s: String): TextView {
        val t = txt(s, 12f, ACC2, true)
        t.setPadding(0, dp(14), 0, 0)
        return t
    }

    private fun field(hint: String, numeric: Boolean, init: String, onChange: (String) -> Unit): EditText {
        val et = EditText(this)
        et.setText(init)
        et.hint = hint
        et.setHintTextColor(MUTE)
        et.setTextColor(TXT)
        et.typeface = face(false)
        et.isSingleLine = true
        if (numeric) et.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        et.background = rounded(alpha(Color.WHITE, 0x14), 12)
        et.setPadding(dp(14), dp(11), dp(14), dp(11))
        et.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                onChange(s?.toString() ?: "")
            }
            override fun beforeTextChanged(s: CharSequence?, st: Int, cnt: Int, aft: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, bef: Int, cnt: Int) {}
        })
        return et
    }

    private fun chipGroup(opts: List<String>, initial: Int, onPick: (Int) -> Unit): View {
        val hs = HorizontalScrollView(this)
        hs.isHorizontalScrollBarEnabled = false
        val row = LinearLayout(this)
        var cur = initial
        fun draw() {
            row.removeAllViews()
            for (i in opts.indices) {
                row.addView(chip(opts[i], i == cur) {
                    cur = i
                    draw()
                    onPick(i)
                }, lp(-2, -2).also { it.rightMargin = dp(8) })
            }
        }
        draw()
        hs.addView(row)
        return hs
    }

    private fun previewRow(p: Plan): View {
        val row = LinearLayout(this)
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(12), dp(9), dp(12), dp(9))
        row.background = rounded(alpha(Color.WHITE, 0x0E), 12)
        val left = LinearLayout(this)
        left.orientation = LinearLayout.VERTICAL
        val o = txt(p.orig.name, 12f, MUTE)
        o.isSingleLine = true
        o.ellipsize = TextUtils.TruncateAt.MIDDLE
        val n = txt("→ " + p.newName, 14f, TXT, true)
        n.isSingleLine = true
        n.ellipsize = TextUtils.TruncateAt.MIDDLE
        left.addView(o)
        left.addView(n)
        row.addView(left, lp(0, -2, 1f))
        val col = if (!p.ok) c(0xFFFB7185) else if (p.status == "Sin cambios") MUTE else GREEN
        row.addView(txt(p.status, 11f, col, true), lp(-2, -2).also { it.leftMargin = dp(8) })
        return row
    }

    private fun renameMultiSheet() {
        val files = selected.toList().filter { it.isFile }
        if (files.size < 2) {
            toast("Selecciona al menos 2 archivos")
            return
        }
        val seps = listOf("#", "-", "_", "espacio", "ninguno")
        val sepVals = listOf("#", "-", "_", " ", "")
        val idx = HashMap<File, Int>()
        for ((i, x) in items.withIndex()) idx[x.f] = i
        val listFiles = files.sortedBy { idx[it] ?: Int.MAX_VALUE }
        val notas = listOf(
            "Se numerarán en el orden en que los seleccionaste.",
            "Se numerarán en el mismo orden en que aparecen en la lista de Nexora.",
            "Se numerarán por nombre, de la A a la Z.",
            "Se numerarán por fecha de modificación, del más antiguo al más reciente.",
            "Se numerarán por tamaño, del más pequeño al más grande."
        )
        showSheet { d ->
            var base = ""
            var sepIdx = 0
            var startTxt = "1"
            var padIdx = 0
            var orderIdx = 0
            var current: List<Plan> = emptyList()

            val col = LinearLayout(this)
            col.orientation = LinearLayout.VERTICAL
            val head = LinearLayout(this)
            head.gravity = Gravity.CENTER_VERTICAL
            head.addView(txt("Renombre múltiple", 20f, TXT, true))
            val beta = txt("BETA", 10f, ACC2, true)
            beta.letterSpacing = 0.12f
            beta.setPadding(dp(8), dp(3), dp(8), dp(3))
            beta.background = rounded(alpha(ACC2, 0x22), 20)
            head.addView(beta, lp(-2, -2).also { it.leftMargin = dp(10) })
            col.addView(head)
            col.addView(txt("Renombrá varios archivos de una sola vez mediante un nombre común y numeración automática.", 13f, MUTE), lp(-1, -2).also { it.topMargin = dp(6) })

            val info = txt("", 13f, MUTE, true)
            val orderNote = txt(notas[0], 12f, MUTE)
            val ejemplo = txt("", 12f, MUTE)
            val preview = LinearLayout(this)
            preview.orientation = LinearLayout.VERTICAL
            val yes = primaryButton("Renombrar")

            fun update() {
                orderNote.text = notas[orderIdx]
                preview.removeAllViews()
                val b = base.trim()
                val st = startTxt.toLongOrNull()
                current = emptyList()
                val exBase = if (b.isEmpty() || b.contains('/')) "Foto" else b
                val exStart = if (st != null && st in 0L..999999999L) st else 1L
                val ex = buildPlan(files, exBase, sepVals[sepIdx], exStart, padIdx + 1, orderIdx, listFiles).take(3).map { it.newName }
                ejemplo.text = "Ejemplo: " + ex.joinToString(", ") + (if (files.size > 3) "…" else "")
                var canGo = false
                if (b.isEmpty() || b.contains('/') || b.length > 120) {
                    info.text = "Escribe un nombre base (sin “/”) para ver la vista previa."
                    info.setTextColor(MUTE)
                } else if (st == null || st < 0L || st > 999999999L) {
                    info.text = "El número inicial no es válido."
                    info.setTextColor(MUTE)
                } else {
                    val plan = buildPlan(files, b, sepVals[sepIdx], st ?: 0L, padIdx + 1, orderIdx, listFiles)
                    current = plan
                    val bad = plan.count { !it.ok }
                    val changes = plan.count { it.ok && it.newName != it.orig.name }
                    if (bad > 0) {
                        info.text = "⚠ Hay " + bad + " conflicto(s). Cambia el nombre base o el número inicial para continuar."
                        info.setTextColor(c(0xFFFBBF24))
                    } else if (changes == 0) {
                        info.text = "Los nombres ya son esos: no hay nada que cambiar."
                        info.setTextColor(MUTE)
                    } else {
                        info.text = "Todo listo: se renombrarán " + changes + " archivos."
                        info.setTextColor(GREEN)
                        canGo = true
                    }
                    for (p in plan.take(40)) preview.addView(previewRow(p), lp(-1, -2).also { it.topMargin = dp(8) })
                    if (plan.size > 40) preview.addView(txt("… y " + (plan.size - 40) + " más", 12f, MUTE), lp(-2, -2).also { it.topMargin = dp(8) })
                }
                yes.isEnabled = canGo
                yes.alpha = if (canGo) 1f else 0.4f
            }

            val form = LinearLayout(this)
            form.orientation = LinearLayout.VERTICAL
            form.addView(label("Nombre base"))
            form.addView(field("Ej.: Foto", false, "") { base = it; update() }, lp(-1, -2).also { it.topMargin = dp(6) })
            form.addView(ejemplo, lp(-1, -2).also { it.topMargin = dp(6) })
            form.addView(label("Separador"))
            form.addView(chipGroup(seps, 0) { sepIdx = it; update() }, lp(-1, -2).also { it.topMargin = dp(6) })
            form.addView(label("Número inicial"))
            form.addView(field("1", true, "1") { startTxt = it; update() }, lp(-1, -2).also { it.topMargin = dp(6) })
            form.addView(label("Dígitos (ceros iniciales)"))
            form.addView(chipGroup(listOf("1", "01", "001", "0001"), 0) { padIdx = it; update() }, lp(-1, -2).also { it.topMargin = dp(6) })
            form.addView(label("Orden de numeración"))
            form.addView(chipGroup(listOf("Selección", "Lista", "Nombre", "Fecha", "Tamaño"), 0) { orderIdx = it; update() }, lp(-1, -2).also { it.topMargin = dp(6) })
            form.addView(orderNote, lp(-1, -2).also { it.topMargin = dp(6) })
            form.addView(label("Vista previa"))
            form.addView(info, lp(-1, -2).also { it.topMargin = dp(6) })
            form.addView(preview, lp(-1, -2))

            val sc = MaxScroll(this, (resources.displayMetrics.heightPixels * 0.5f).toInt())
            sc.addView(form)
            col.addView(sc, lp(-1, -2).also { it.topMargin = dp(6) })

            val btns = LinearLayout(this)
            val no = ghostButton("Cancelar")
            no.setOnClickListener { d.dismiss() }
            yes.setOnClickListener {
                d.dismiss()
                executeRename(current)
            }
            btns.addView(no, lp(0, dp(48), 1f))
            btns.addView(yes, lp(0, dp(48), 1f).also { it.leftMargin = dp(10) })
            col.addView(btns, lp(-1, -2).also { it.topMargin = dp(14) })
            update()
            col
        }
    }

    private fun executeRename(plan: List<Plan>) {
        val todo = plan.filter { it.ok && it.newName != it.orig.name }
        if (todo.isEmpty()) return
        toast("Renombrando…")
        Thread {
            val done = ArrayList<Pair<File, File>>()
            val failed = ArrayList<Pair<String, String>>()
            for (p in todo) {
                val dst = File(p.orig.parentFile, p.newName)
                try {
                    java.nio.file.Files.move(p.orig.toPath(), dst.toPath())
                    done.add(Pair(p.orig, dst))
                } catch (e: java.nio.file.FileAlreadyExistsException) {
                    failed.add(Pair(p.orig.name, "ya existe un archivo con ese nombre"))
                } catch (e: java.nio.file.AccessDeniedException) {
                    failed.add(Pair(p.orig.name, "sin permiso para renombrarlo"))
                } catch (e: Exception) {
                    failed.add(Pair(p.orig.name, "el sistema no lo permitió"))
                }
            }
            runOnUiThread {
                selected.clear()
                if (!inViewer) refresh()
                resultSheet(done, failed)
            }
        }.start()
    }

    private fun resultSheet(done: List<Pair<File, File>>, failed: List<Pair<String, String>>) {
        val u: () -> Unit = { undoRename(done) }
        reportSheet("renombrados correctamente", done.size, failed, if (done.isNotEmpty()) u else null)
    }

    private fun reportSheet(okText: String, done: Int, failed: List<Pair<String, String>>, undo: (() -> Unit)?) {
        showSheet(false) { d ->
            val col = LinearLayout(this)
            col.orientation = LinearLayout.VERTICAL
            col.addView(txt(if (failed.isEmpty()) "Listo" else if (done == 0) "No se pudo completar" else "Terminó con errores", 20f, TXT, true))
            if (done > 0) col.addView(txt("✓ " + done + " " + okText, 14f, GREEN, true), lp(-1, -2).also { it.topMargin = dp(10) })
            if (failed.isNotEmpty()) {
                col.addView(txt("✕ " + failed.size + " no se pudieron procesar", 14f, c(0xFFFB7185), true), lp(-1, -2).also { it.topMargin = dp(6) })
                for (fp in failed.take(8)) col.addView(txt(fp.first + " — " + fp.second, 12f, MUTE), lp(-1, -2).also { it.topMargin = dp(4) })
                if (failed.size > 8) col.addView(txt("… y " + (failed.size - 8) + " más", 12f, MUTE), lp(-1, -2).also { it.topMargin = dp(4) })
            }
            val btns = LinearLayout(this)
            if (undo != null && done > 0) {
                val ub = ghostButton("Deshacer todo")
                ub.setOnClickListener {
                    d.dismiss()
                    undo()
                }
                btns.addView(ub, lp(0, dp(48), 1f))
            }
            val ok = primaryButton("Listo")
            ok.setOnClickListener { d.dismiss() }
            btns.addView(ok, lp(0, dp(48), 1f).also { if (undo != null && done > 0) it.leftMargin = dp(10) })
            col.addView(btns, lp(-1, -2).also { it.topMargin = dp(18) })
            col
        }
    }

    private fun undoRename(done: List<Pair<File, File>>) {
        Thread {
            var bad = 0
            for (p in done.reversed()) {
                try {
                    java.nio.file.Files.move(p.second.toPath(), p.first.toPath())
                } catch (e: Exception) {
                    bad++
                }
            }
            runOnUiThread {
                if (!inViewer) refresh()
                toast(if (bad == 0) "Se deshizo el renombrado" else "No se pudieron deshacer " + bad + " archivos")
            }
        }.start()
    }

    private fun tap(f: File) {
        if (f.isDirectory) {
            dir = f
            refresh()
            return
        }
        when (f.extension.lowercase()) {
            "jpg", "jpeg", "png", "webp", "gif", "bmp" -> viewImage(f)
            "mp4", "mkv", "webm", "3gp", "mov" -> viewVideo(f)
            "pdf" -> viewPdf(f)
            "zip" -> confirmSheet("¿Extraer " + f.name + "?", "", "Extraer") { extract(f) }
            else -> toast("Todavía no hay vista previa para este tipo de archivo")
        }
    }

    private fun rename(f: File) {
        showSheet(false) { d ->
            val col = LinearLayout(this)
            col.orientation = LinearLayout.VERTICAL
            col.addView(txt("Renombrar", 18f, TXT, true))
            val et = EditText(this)
            et.setText(f.name)
            et.setTextColor(TXT)
            et.typeface = face(false)
            et.background = rounded(alpha(Color.WHITE, 0x14), 12)
            et.setPadding(dp(14), dp(12), dp(14), dp(12))
            col.addView(et, lp(-1, -2).also { it.topMargin = dp(12) })
            val row = LinearLayout(this)
            val no = ghostButton("Cancelar")
            no.setOnClickListener { d.dismiss() }
            val yes = primaryButton("Guardar")
            yes.setOnClickListener {
                val n = et.text.toString().trim()
                d.dismiss()
                if (n.isNotEmpty() && f.renameTo(File(f.parentFile, n))) refresh() else toast("No se pudo renombrar")
            }
            row.addView(no, lp(0, dp(48), 1f))
            row.addView(yes, lp(0, dp(48), 1f).also { it.leftMargin = dp(10) })
            col.addView(row, lp(-1, -2).also { it.topMargin = dp(16) })
            col
        }
    }

    private fun extract(f: File) {
        toast("Extrayendo…")
        Thread {
            try {
                val out = File(f.parentFile, f.nameWithoutExtension)
                out.mkdirs()
                val base = out.canonicalPath
                ZipInputStream(f.inputStream().buffered()).use { z ->
                    var e = z.nextEntry
                    while (e != null) {
                        val t = File(out, e.name)
                        if (!t.canonicalPath.startsWith(base)) throw Exception("ruta no válida")
                        if (e.isDirectory) {
                            t.mkdirs()
                        } else {
                            t.parentFile?.mkdirs()
                            t.outputStream().use { o -> z.copyTo(o) }
                        }
                        e = z.nextEntry
                    }
                }
                runOnUiThread {
                    toast("Extraído en " + out.name)
                    if (!inViewer) refresh()
                }
            } catch (ex: Exception) {
                runOnUiThread { toast("No se pudo extraer: " + ex.message) }
            }
        }.start()
    }

    // ---------- imagen de fondo propia ----------
    private fun pickImage() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "image/*"
        try {
            startActivityForResult(i, 42)
        } catch (e: Exception) {
            toast("No se pudo abrir el selector de imágenes")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req == 42 && res == RESULT_OK) {
            val u = data?.data
            if (u != null) importBackground(u)
        }
    }

    private fun importBackground(uri: Uri) {
        Thread {
            try {
                val o = BitmapFactory.Options()
                o.inJustDecodeBounds = true
                contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
                var s = 1
                while (o.outWidth / s > 2400 || o.outHeight / s > 2400) s *= 2
                val o2 = BitmapFactory.Options()
                o2.inSampleSize = s
                var bm = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o2) }
                    ?: throw Exception("imagen no válida")
                var rot = 0
                try {
                    contentResolver.openInputStream(uri)?.use { ins ->
                        val ori = ExifInterface(ins).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)
                        rot = if (ori == 6) 90 else if (ori == 3) 180 else if (ori == 8) 270 else 0
                    }
                } catch (e: Exception) {
                    rot = 0
                }
                if (rot != 0) {
                    val m = Matrix()
                    m.postRotate(rot.toFloat())
                    bm = Bitmap.createBitmap(bm, 0, 0, bm.width, bm.height, m, true)
                }
                File(filesDir, "fondo.jpg").outputStream().use { out -> bm.compress(Bitmap.CompressFormat.JPEG, 88, out) }
                runOnUiThread {
                    prefs.edit().putBoolean("bgCustom", true).apply()
                    loadTheme()
                    if (inViewer) showPersonalization()
                }
            } catch (e: Exception) {
                runOnUiThread { toast("No se pudo usar esa imagen") }
            }
        }.start()
    }

    // ---------- visores ----------
    private fun viewer(title: String, content: View) {
        inViewer = true
        curPage = ""
        token++
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setBackgroundColor(c(0xFF03050C))
        val bar = txt("‹   " + title, 16f, TXT)
        bar.isSingleLine = true
        bar.ellipsize = TextUtils.TruncateAt.END
        bar.setBackgroundColor(CARD)
        bar.setPadding(dp(18), dp(14), dp(18), dp(14))
        bar.setOnClickListener { closeViewer() }
        col.addView(bar, lp(-1, -2))
        col.addView(content, lp(-1, 0, 1f))
        setContentView(col)
    }

    private fun closeViewer() {
        if (converting) {
            toast("Hay un proceso en curso. Usa Cancelar para detenerlo.")
            return
        }
        aiResult = null
        aiOrig = null
        curPage = ""
        token++
        if (cleanNav.isNotEmpty()) {
            val back = cleanNav.removeAt(cleanNav.size - 1)
            back()
            return
        }
        showBrowser()
    }

    private fun viewImage(f: File) {
        val o = BitmapFactory.Options()
        o.inJustDecodeBounds = true
        BitmapFactory.decodeFile(f.path, o)
        var s = 1
        while (o.outWidth / s > 2048 || o.outHeight / s > 2048) s *= 2
        val o2 = BitmapFactory.Options()
        o2.inSampleSize = s
        val bm = BitmapFactory.decodeFile(f.path, o2)
        if (bm == null) {
            toast("No se pudo abrir la imagen")
            return
        }
        val iv = ImageView(this)
        iv.setImageBitmap(bm)
        iv.scaleType = ImageView.ScaleType.FIT_CENTER
        viewer(f.name, iv)
    }

    private fun viewVideo(f: File) {
        val vv = VideoView(this)
        val mc = MediaController(this)
        mc.setAnchorView(vv)
        vv.setMediaController(mc)
        vv.setVideoURI(Uri.fromFile(f))
        vv.setOnPreparedListener { vv.start() }
        val fl = FrameLayout(this)
        fl.addView(vv, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        viewer(f.name, fl)
    }

    private fun viewPdf(f: File) {
        val sv = ScrollView(this)
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(8), dp(8), dp(8), dp(8))
        sv.addView(box)
        viewer(f.name, sv)
        val my = token
        val w = resources.displayMetrics.widthPixels - dp(16)
        Thread {
            try {
                val pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
                val r = PdfRenderer(pfd)
                for (i in 0 until r.pageCount) {
                    if (token != my) break
                    val p = r.openPage(i)
                    val h = (w.toFloat() * p.height / p.width).toInt()
                    val bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    bm.eraseColor(Color.WHITE)
                    p.render(bm, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    p.close()
                    runOnUiThread {
                        if (token == my) {
                            val iv = ImageView(this)
                            iv.setImageBitmap(bm)
                            iv.adjustViewBounds = true
                            val lpp = LinearLayout.LayoutParams(-1, -2)
                            lpp.bottomMargin = dp(8)
                            box.addView(iv, lpp)
                        }
                    }
                }
                r.close()
                pfd.close()
            } catch (e: Exception) {
                runOnUiThread { toast("No se pudo abrir el PDF") }
            }
        }.start()
    }

    // ---------- Limpieza inteligente ----------
    private val cleanNav = ArrayList<() -> Unit>()
    private val cleanCache = HashMap<String, CleanScan>()
    private val hashCache = HashMap<String, String>()
    private val dupSel = HashSet<String>()
    private var cleanScroll: ScrollView? = null
    private var cleanProgTxt: TextView? = null
    private var cleanMsg = ""
    @Volatile private var cleanRunning = false
    @Volatile private var cleanCancel = false
    @Volatile private var cleanMy = 0

    private fun cleanStopped(): Boolean = cleanCancel || token != cleanMy

    private fun fmtS(b: Long): String =
        if (b >= 1073741824L) String.format("%.2f GB", b / 1073741824.0) else fmt(b)

    private fun relPath(f: File): String {
        val r = f.absolutePath.removePrefix(root.absolutePath)
        return if (r.isEmpty()) "/" else r
    }

    private fun previewable(f: File): Boolean =
        f.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "mp4", "mkv", "webm", "3gp", "mov", "pdf")

    private fun isGenericName(name: String): Boolean {
        val s = name.substringBeforeLast('.').lowercase()
        if (Regex("^(img|dsc|dscn|dscf|pxl|vid|mov|mvi|aud|ptt|audio|recording|record|rec|photo|image|video|screenshot|captura|track|scan)[ _-]?[0-9].*").matches(s)) return true
        if (Regex("^whatsapp (image|video|audio).*").matches(s)) return true
        return Regex("^[0-9a-f-]{12,}$").matches(s)
    }

    private fun stemOf(name: String): String {
        var s = name.substringBeforeLast('.').lowercase()
        s = s.replace(Regex("\\s*\\(\\d+\\)$"), "")
        s = s.replace(Regex("[-_ ]*(copia|copy)( ?\\d+)?$"), "")
        return s
    }

    private fun altName(f: File): String {
        val ext = f.extension
        val par = f.parentFile?.name ?: "copia"
        return f.nameWithoutExtension + " (" + par + ")" + (if (ext.isEmpty()) "" else "." + ext)
    }

    private fun hashFile(f: File, partial: Boolean): String? {
        try {
            val md = java.security.MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(65536)
            FileInputStream(f).use { ins ->
                if (partial) {
                    val n = ins.read(buf)
                    if (n > 0) md.update(buf, 0, n)
                } else {
                    while (true) {
                        if (cleanStopped()) return null
                        val n = ins.read(buf)
                        if (n <= 0) break
                        md.update(buf, 0, n)
                    }
                }
            }
            val sb = StringBuilder()
            for (x in md.digest()) sb.append(String.format("%02x", x))
            return sb.toString()
        } catch (e: Exception) {
            return null
        }
    }

    private fun hashOf(f: File, size: Long, partial: Boolean): String? {
        val key = f.absolutePath + "|" + size + "|" + f.lastModified() + (if (partial) "|p" else "|f")
        val c = hashCache[key]
        if (c != null) return c
        val h = hashFile(f, partial) ?: return null
        if (hashCache.size > 60000) hashCache.clear()
        hashCache[key] = h
        return h
    }

    private fun runCleanScan(rootDir: File): CleanScan? {
        val rootPath = rootDir.absolutePath
        var nFiles = 0
        var nBytes = 0L
        var skipped = 0
        var note: String? = null
        var lastUi = 0L
        val maxFiles = 300000
        val bySize = HashMap<Long, ArrayList<File>>()
        val dirStat = HashMap<String, LongArray>()
        val heap = java.util.PriorityQueue<Item>(401, Comparator<Item> { a, b -> a.size.compareTo(b.size) })
        val generic = Array(4) { HashMap<String, ArrayList<File>>() }
        val mediaByDir = Array(4) { HashMap<String, Int>() }
        val docNames = HashMap<String, ArrayList<File>>()
        val docExt = HashMap<String, HashSet<String>>()
        val docCount = HashMap<String, Int>()
        val imgs = ArrayList<File>()
        val stack = ArrayList<File>()
        stack.add(rootDir)
        while (stack.isNotEmpty()) {
            if (cleanStopped()) return null
            val d = stack.removeAt(stack.size - 1)
            val dPath = d.absolutePath
            val kids = d.listFiles() ?: continue
            var cnt = 0L
            var byt = 0L
            for (f in kids) {
                if (f.isDirectory) {
                    val n = f.name
                    if (dPath == rootPath && n == "Android") continue
                    if (n == ".thumbnails") continue
                    if (java.nio.file.Files.isSymbolicLink(f.toPath())) continue
                    stack.add(f)
                    continue
                }
                val len = f.length()
                nFiles++
                nBytes += len
                cnt++
                byt += len
                val cat = catOf(f)
                if (len >= 1024L) bySize.getOrPut(len) { ArrayList() }.add(f)
                if (heap.size < 400 || len > heap.peek().size) {
                    heap.add(Item(f, len, f.lastModified(), cat))
                    if (heap.size > 400) heap.poll()
                }
                if (cat in 1..3) {
                    mediaByDir[cat][dPath] = (mediaByDir[cat][dPath] ?: 0) + 1
                    if (isGenericName(f.name)) generic[cat].getOrPut(dPath) { ArrayList() }.add(f)
                }
                if (cat == 1) {
                    val e = f.extension.lowercase()
                    if (e == "jpg" || e == "jpeg" || e == "png" || e == "webp") imgs.add(f)
                }
                if (cat == 4) {
                    docNames.getOrPut(f.name.lowercase()) { ArrayList() }.add(f)
                    docCount[dPath] = (docCount[dPath] ?: 0) + 1
                    docExt.getOrPut(dPath) { HashSet() }.add(f.extension.lowercase())
                }
                if (nFiles >= maxFiles) {
                    note = "Se alcanzó el límite de 300.000 archivos analizados."
                    break
                }
            }
            dirStat[dPath] = longArrayOf(cnt, byt)
            if (note != null) break
            val now = System.currentTimeMillis()
            if (now - lastUi > 250) {
                lastUi = now
                val msg = "Explorando…  " + nFiles + " archivos\n" + d.name
                runOnUiThread { cleanMsg = msg; cleanProgTxt?.text = msg }
            }
        }

        var totalCand = 0
        for (l in bySize.values) if (l.size > 1) totalCand += l.size
        var done = 0
        val groups = ArrayList<DupGroup>()
        for ((size, list) in bySize) {
            if (list.size < 2) continue
            val byPart = HashMap<String, ArrayList<File>>()
            for (f in list) {
                if (cleanStopped()) return null
                done++
                val now = System.currentTimeMillis()
                if (now - lastUi > 250) {
                    lastUi = now
                    val msg = "Comparando contenido…  " + done + " de " + totalCand + "\n" + f.name
                    runOnUiThread { cleanMsg = msg; cleanProgTxt?.text = msg }
                }
                val h = hashOf(f, size, size > 65536L)
                if (h == null) {
                    skipped++
                    continue
                }
                byPart.getOrPut(h) { ArrayList() }.add(f)
            }
            for (pl in byPart.values) {
                if (pl.size < 2) continue
                if (size <= 65536L) {
                    groups.add(DupGroup(pl.sortedBy { it.lastModified() }, size))
                    continue
                }
                val byFull = HashMap<String, ArrayList<File>>()
                for (f in pl) {
                    if (cleanStopped()) return null
                    val h = hashOf(f, size, false)
                    if (h == null) {
                        skipped++
                        continue
                    }
                    byFull.getOrPut(h) { ArrayList() }.add(f)
                }
                for (fl in byFull.values) {
                    if (fl.size >= 2) groups.add(DupGroup(fl.sortedBy { it.lastModified() }, size))
                }
            }
        }
        groups.sortByDescending { it.size * (it.files.size - 1) }

        val dupPaths = HashMap<String, Int>()
        for ((i, g) in groups.withIndex()) for (f in g.files) dupPaths[f.absolutePath] = i
        val byStem = HashMap<String, ArrayList<File>>()
        for (f in imgs) byStem.getOrPut((f.parent ?: "") + "/" + stemOf(f.name)) { ArrayList() }.add(f)
        val similar = ArrayList<List<File>>()
        for (l in byStem.values) {
            if (l.size < 2) continue
            val gi = l.map { dupPaths[it.absolutePath] ?: -1 }
            if (gi[0] >= 0 && gi.all { it == gi[0] }) continue
            similar.add(l.sortedBy { it.name.lowercase() })
        }
        similar.sortByDescending { it.size }

        val rec = HashMap<String, LongArray>()
        for ((p, a) in dirStat) {
            var cur: String? = p
            while (cur != null) {
                val r = rec.getOrPut(cur) { LongArray(2) }
                r[0] += a[0]
                r[1] += a[1]
                if (cur == rootPath) break
                cur = File(cur).parent
                if (cur != null && cur.length < rootPath.length) break
            }
        }
        val folders = rec.entries
            .filter { it.key != rootPath && it.value[1] > 0L }
            .sortedByDescending { it.value[1] }
            .take(40)
            .map { FolderStat(File(it.key), it.value[0].toInt(), it.value[1], (dirStat[it.key]?.get(0) ?: 0L).toInt()) }
        val crowded = dirStat.entries
            .filter { it.value[0] >= 100L }
            .sortedByDescending { it.value[0] }
            .take(30)
            .map { FolderStat(File(it.key), it.value[0].toInt(), it.value[1], it.value[0].toInt()) }
        val docClash = docNames.values.filter { it.size >= 2 }.sortedByDescending { it.size }.take(100)
        val docMixed = docCount.entries
            .filter { it.value >= 30 && (docExt[it.key]?.size ?: 0) >= 2 }
            .sortedByDescending { it.value }
            .take(10)
            .map { Pair(File(it.key), it.value) }
        val bigList = heap.toList().sortedByDescending { it.size }
        return CleanScan(rootPath, System.currentTimeMillis(), nFiles, nBytes, note == null, note, skipped,
            groups, bigList, folders, crowded, similar.take(100), generic, mediaByDir, docClash, docMixed)
    }

    private fun liveDups(sc: CleanScan): List<DupGroup> {
        val out = ArrayList<DupGroup>()
        for (g in sc.dups) {
            val l = g.files.filter { it.exists() }
            if (l.size >= 2) out.add(DupGroup(l, g.size))
        }
        return out
    }

    private fun openClean() {
        cleanNav.clear()
        showClean()
    }

    private fun cleanGo(from: () -> Unit, sub: () -> Unit) {
        cleanNav.add(from)
        sub()
    }

    private fun goToFolder(target: File) {
        cleanNav.clear()
        curPage = ""
        token++
        val all = listOf("Almacenamiento interno" to Environment.getExternalStorageDirectory()) + extraVolumes()
        val v = all.firstOrNull { target.absolutePath.startsWith(it.second.absolutePath) }
        if (v != null) {
            root = v.second
            rootName = v.first
        }
        dir = target
        catIdx = 0
        showBrowser()
    }

    private fun renameInFolder(folder: File, files: List<File>) {
        val ok = files.filter { it.isFile && it.exists() }
        if (ok.size < 2) {
            toast("Ya no quedan suficientes archivos. Actualiza el análisis.")
            return
        }
        goToFolder(folder)
        selected.clear()
        selected.addAll(ok)
        refreshSel()
        renameMultiSheet()
    }

    private fun navRow(icon: String, title: String, sub: String, onClick: () -> Unit): View {
        val row = LinearLayout(this)
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(16), dp(14), dp(16), dp(14))
        row.background = rounded(alpha(CARD, 0xE6), 18)
        row.addView(txt(icon, 22f, TXT), lp(dp(36), -2))
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.addView(txt(title, 16f, TXT, true))
        col.addView(txt(sub, 12f, MUTE))
        row.addView(col, lp(0, -2, 1f))
        row.addView(txt("›", 24f, MUTE, true))
        row.setOnClickListener { onClick() }
        return row
    }

    private fun resultRow(title: String, sub: String, onClick: () -> Unit): View {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(14), dp(10), dp(14), dp(10))
        col.background = rounded(alpha(CARD, 0xE6), 14)
        val t = txt(title, 14f, TXT, true)
        t.isSingleLine = true
        t.ellipsize = TextUtils.TruncateAt.MIDDLE
        col.addView(t)
        col.addView(txt(sub, 12f, MUTE))
        col.setOnClickListener { onClick() }
        return col
    }

    private fun cleanChips(count: (Int) -> Int, current: Int, onPick: (Int) -> Unit): View {
        val hs = HorizontalScrollView(this)
        hs.isHorizontalScrollBarEnabled = false
        val row = LinearLayout(this)
        for (i in CATS.indices) {
            val n = count(i)
            if (n == 0 && i != current) continue
            row.addView(chip(CATS[i] + " " + n, i == current) { onPick(i) }, lp(-2, -2).also { it.rightMargin = dp(8) })
        }
        hs.addView(row)
        return hs
    }

    private fun cleanFileSheet(f: File, back: () -> Unit, extra: Pair<String, () -> Unit>?) {
        showSheet { d ->
            val col = LinearLayout(this)
            col.orientation = LinearLayout.VERTICAL
            col.addView(txt(f.name, 17f, TXT, true))
            val info = relPath(f.parentFile ?: f) + "\n" + fmtS(f.length()) + " · " + CATS[catOf(f)] + " · " + dateFmt.format(Date(f.lastModified()))
            col.addView(txt(info, 12f, MUTE), lp(-1, -2).also { it.topMargin = dp(4); it.bottomMargin = dp(8) })
            if (previewable(f)) {
                val r = optionRow("Ver archivo", false)
                r.setOnClickListener {
                    d.dismiss()
                    cleanNav.add(back)
                    tap(f)
                }
                col.addView(r, lp(-1, -2))
            }
            val loc = optionRow("Abrir ubicación", false)
            loc.setOnClickListener {
                d.dismiss()
                goToFolder(f.parentFile ?: f)
            }
            col.addView(loc, lp(-1, -2))
            if (extra != null) {
                val x = optionRow(extra.first, false)
                x.setOnClickListener {
                    d.dismiss()
                    extra.second()
                }
                col.addView(x, lp(-1, -2))
            }
            col
        }
    }

    private fun showClean() {
        val sc = cleanCache[root.absolutePath]
        val b = body()
        val intro = cardBox()
        intro.addView(txt("Limpieza inteligente", 18f, TXT, true))
        intro.addView(txt("Analiza «" + rootName + "» y te sugiere cómo ordenar tus archivos. Nexora nunca borra ni mueve nada sin que tú lo pidas.", 13f, MUTE), lp(-1, -2).also { it.topMargin = dp(6) })
        b.addView(intro, lp(-1, -2))
        cleanProgTxt = null
        if (cleanRunning) {
            val pc = cardBox()
            val pb = ProgressBar(this)
            pb.isIndeterminate = true
            pb.indeterminateDrawable?.setColorFilter(ACC2, android.graphics.PorterDuff.Mode.SRC_IN)
            pc.addView(pb, lp(dp(36), dp(36)))
            val t = txt(if (cleanMsg.isEmpty()) "Preparando análisis…" else cleanMsg, 13f, TXT)
            cleanProgTxt = t
            pc.addView(t, lp(-1, -2).also { it.topMargin = dp(8) })
            val cancel = ghostButton("Cancelar análisis")
            cancel.setOnClickListener {
                cleanCancel = true
                toast("Cancelando…")
            }
            pc.addView(cancel, lp(-1, dp(46)).also { it.topMargin = dp(12) })
            pc.addView(txt("Si sales de esta pantalla, el análisis se cancela.", 11f, MUTE), lp(-1, -2).also { it.topMargin = dp(8) })
            b.addView(pc, lp(-1, -2).also { it.topMargin = dp(12) })
        } else {
            val go = primaryButton(if (sc == null) "Analizar" else "Actualizar análisis")
            go.setOnClickListener { startClean() }
            b.addView(go, lp(-1, dp(52)).also { it.topMargin = dp(12) })
        }
        if (sc == null && !cleanRunning) {
            b.addView(txt("Todavía no hay un análisis de este almacenamiento. Pulsa Analizar para buscar duplicados, archivos grandes y carpetas desordenadas. Puede tardar si tienes muchos archivos.", 13f, MUTE), lp(-1, -2).also { it.topMargin = dp(14) })
        }
        if (sc != null) {
            val dups = liveDups(sc)
            var copies = 0
            var rec = 0L
            for (g in dups) {
                copies += g.files.size - 1
                rec += g.size * (g.files.size - 1)
            }
            val huge = sc.big.count { it.size >= 104857600L && it.f.exists() }
            val from: () -> Unit = { showClean() }
            val sugs = buildSuggestions(sc) { showSuggestions() }
            val rc = cardBox()
            rc.addView(txt("Informe de limpieza", 17f, TXT, true))
            val when0 = java.text.SimpleDateFormat("d MMM yyyy, HH:mm", Locale("es")).format(Date(sc.time))
            rc.addView(txt("Análisis del " + when0, 12f, MUTE), lp(-1, -2).also { it.topMargin = dp(2); it.bottomMargin = dp(8) })
            if (!sc.complete) {
                rc.addView(txt("⚠ Análisis incompleto: " + (sc.note ?: "no se terminó de recorrer todo."), 13f, c(0xFFFBBF24)), lp(-1, -2).also { it.bottomMargin = dp(8) })
            }
            val lines = ArrayList<String>()
            lines.add("✓ Archivos analizados: " + sc.files + " (" + fmtS(sc.bytes) + ")")
            lines.add("✓ Duplicados exactos: " + dups.size + " grupos, " + copies + " copias redundantes")
            lines.add("✓ Espacio recuperable: " + fmtS(rec) + " (si conservas una copia de cada grupo)")
            lines.add("✓ Archivos de más de 100 MB: " + huge)
            lines.add("✓ Carpetas con 100 archivos o más sueltos: " + sc.crowded.size)
            lines.add("≈ Posibles fotos similares: " + sc.similar.size + " grupos (sin confirmar)")
            lines.add("≈ Recomendaciones disponibles: " + sugs.size)
            for (ln in lines) rc.addView(txt(ln, 13f, TXT), lp(-1, -2).also { it.topMargin = dp(4) })
            if (sc.skipped > 0) rc.addView(txt("No se pudieron leer " + sc.skipped + " archivos al comparar; podrían tener duplicados no detectados.", 12f, MUTE), lp(-1, -2).also { it.topMargin = dp(8) })
            rc.addView(txt("✓ dato confirmado   ≈ estimación\nNo se analizan Android/data ni Android/obb (Android las protege). Al buscar duplicados se ignoran los archivos de menos de 1 KB. El análisis se guarda solo mientras la app está abierta.", 11f, MUTE), lp(-1, -2).also { it.topMargin = dp(10) })
            b.addView(rc, lp(-1, -2).also { it.topMargin = dp(14) })

            b.addView(sectionTitle("Revisar"))
            b.addView(navRow("📑", "Duplicados", dups.size.toString() + " grupos · " + fmtS(rec) + " recuperables") { cleanGo(from) { showDupes(0, false) } }, lp(-1, -2))
            b.addView(navRow("📦", "Archivos grandes", huge.toString() + " de más de 100 MB") { cleanGo(from) { showBig(0) } }, lp(-1, -2).also { it.topMargin = dp(10) })
            b.addView(navRow("📁", "Carpetas", sc.folders.size.toString() + " que más ocupan · " + sc.crowded.size + " muy llenas") { cleanGo(from) { showFolders() } }, lp(-1, -2).also { it.topMargin = dp(10) })
            b.addView(navRow("🔍", "Posibles similares", sc.similar.size.toString() + " grupos sin confirmar") { cleanGo(from) { showSimilar() } }, lp(-1, -2).also { it.topMargin = dp(10) })
            b.addView(navRow("💡", "Recomendaciones", sugs.size.toString() + " según tus archivos") { cleanGo(from) { showSuggestions() } }, lp(-1, -2).also { it.topMargin = dp(10) })
        }
        page("Limpiar", b)
    }

    private fun startClean() {
        if (cleanRunning) return
        cleanRunning = true
        cleanCancel = false
        cleanMsg = "Preparando análisis…"
        showClean()
        val my = token
        cleanMy = my
        val rootDir = root
        Thread {
            var res: CleanScan? = null
            var err: String? = null
            try {
                res = runCleanScan(rootDir)
            } catch (e: Throwable) {
                err = "El análisis se detuvo por un error: " + (e.message ?: "desconocido")
            }
            val out = res
            val er = err
            runOnUiThread {
                cleanRunning = false
                cleanProgTxt = null
                if (out != null) cleanCache[rootDir.absolutePath] = out
                if (er != null) toast(er) else if (out == null && token == my) toast("Análisis cancelado")
                if (inViewer && curPage == "Limpiar") showClean()
            }
        }.start()
    }

    private fun showDupes(cat: Int, keep: Boolean) {
        val sc = cleanCache[root.absolutePath]
        if (sc == null) {
            showClean()
            return
        }
        val y = if (keep) (cleanScroll?.scrollY ?: 0) else 0
        val all = liveDups(sc)
        val groups = all.filter { cat == 0 || catOf(it.files[0]) == cat }
        val b = body()
        b.addView(cleanChips({ i -> if (i == 0) all.size else all.count { catOf(it.files[0]) == i } }, cat) { i -> showDupes(i, false) }, lp(-1, -2))
        b.addView(txt("Estos archivos coinciden en tamaño y en todo su contenido (comparado con SHA-256). Revisa cada uno antes de decidir: no se borra nada sin tu confirmación.", 12f, MUTE), lp(-1, -2).also { it.topMargin = dp(10) })
        var rec = 0L
        for (g in groups) rec += g.size * (g.files.size - 1)
        b.addView(txt("Espacio recuperable en esta lista: " + fmtS(rec), 13f, ACC2, true), lp(-1, -2).also { it.topMargin = dp(8) })
        if (groups.isEmpty()) b.addView(txt("No hay duplicados confirmados en esta categoría.", 14f, MUTE), lp(-1, -2).also { it.topMargin = dp(16) })
        for (g in groups.take(150)) {
            val card = cardBox()
            card.addView(txt(g.files.size.toString() + " copias idénticas · " + fmtS(g.size) + " cada una", 15f, TXT, true))
            card.addView(txt("Recuperable: " + fmtS(g.size * (g.files.size - 1)) + " · " + CATS[catOf(g.files[0])], 12f, ACC2))
            for (f in g.files) {
                val on = dupSel.contains(f.absolutePath)
                val row = LinearLayout(this)
                row.gravity = Gravity.CENTER_VERTICAL
                val chk = txt(if (on) "☑" else "☐", 24f, if (on) ACC2 else MUTE, true)
                chk.setPadding(dp(4), dp(10), dp(14), dp(10))
                chk.setOnClickListener {
                    if (!dupSel.add(f.absolutePath)) dupSel.remove(f.absolutePath)
                    showDupes(cat, true)
                }
                row.addView(chk)
                val col = LinearLayout(this)
                col.orientation = LinearLayout.VERTICAL
                val n = txt(f.name, 14f, TXT, true)
                n.isSingleLine = true
                n.ellipsize = TextUtils.TruncateAt.MIDDLE
                val s = txt(relPath(f.parentFile ?: f) + " · " + fmtS(f.length()), 11f, MUTE)
                s.isSingleLine = true
                s.ellipsize = TextUtils.TruncateAt.START
                col.addView(n)
                col.addView(s)
                col.setOnClickListener { cleanFileSheet(f, { showDupes(cat, true) }, null) }
                row.addView(col, lp(0, -2, 1f))
                card.addView(row, lp(-1, -2))
            }
            val mark = ghostButton("Marcar copias (conservar la más antigua)")
            mark.setOnClickListener {
                for (i in 1 until g.files.size) dupSel.add(g.files[i].absolutePath)
                dupSel.remove(g.files[0].absolutePath)
                showDupes(cat, true)
            }
            card.addView(mark, lp(-1, dp(44)).also { it.topMargin = dp(6) })
            b.addView(card, lp(-1, -2).also { it.topMargin = dp(10) })
        }
        if (groups.size > 150) b.addView(txt("Se muestran los 150 grupos que más espacio ocupan.", 12f, MUTE), lp(-1, -2).also { it.topMargin = dp(10) })

        val sv = ScrollView(this)
        sv.addView(b)
        cleanScroll = sv
        val frame = LinearLayout(this)
        frame.orientation = LinearLayout.VERTICAL
        frame.addView(sv, lp(-1, 0, 1f))
        if (dupSel.isNotEmpty()) {
            val sel = ArrayList<File>()
            for (g in all) for (f in g.files) if (dupSel.contains(f.absolutePath)) sel.add(f)
            var freed = 0L
            for (f in sel) freed += f.length()
            val bar = LinearLayout(this)
            bar.setPadding(dp(14), dp(8), dp(14), dp(10))
            val clear = ghostButton("Quitar marcas")
            clear.setOnClickListener {
                dupSel.clear()
                showDupes(cat, true)
            }
            val del = primaryButton("Eliminar " + sel.size + " (" + fmtS(freed) + ")")
            del.setOnClickListener { askDeleteDupes(all, cat) }
            bar.addView(clear, lp(0, dp(48), 1f))
            bar.addView(del, lp(0, dp(48), 1.4f).also { it.leftMargin = dp(10) })
            frame.addView(bar, lp(-1, -2))
        }
        pageFixed("Duplicados", frame)
        if (keep) sv.post { sv.scrollTo(0, y) }
    }

    private fun askDeleteDupes(all: List<DupGroup>, cat: Int) {
        for (g in all) {
            if (g.files.all { dupSel.contains(it.absolutePath) }) {
                toast("En un grupo marcaste todas las copias. Deja al menos una sin marcar.")
                return
            }
        }
        val files = ArrayList<File>()
        for (g in all) for (f in g.files) if (dupSel.contains(f.absolutePath)) files.add(f)
        if (files.isEmpty()) return
        confirmSheet("¿Eliminar " + files.size + " copias?", "Se conserva al menos una copia de cada grupo. Esta acción no se puede deshacer.", "Eliminar") { doDeleteDupes(files, cat) }
    }

    private fun doDeleteDupes(files: List<File>, cat: Int) {
        toast("Eliminando…")
        Thread {
            var ok = 0
            var fail = 0
            var freed = 0L
            for (f in files) {
                val len = f.length()
                try {
                    java.nio.file.Files.delete(f.toPath())
                    ok++
                    freed += len
                } catch (e: Exception) {
                    fail++
                }
            }
            runOnUiThread {
                dupSel.clear()
                toast("Eliminados " + ok + " (liberaste " + fmtS(freed) + ")" + (if (fail > 0) ". No se pudieron eliminar " + fail + "." else ""))
                if (inViewer && curPage == "Duplicados") showDupes(cat, false)
            }
        }.start()
    }

    private fun showBig(cat: Int) {
        val sc = cleanCache[root.absolutePath]
        if (sc == null) {
            showClean()
            return
        }
        val live = sc.big.filter { it.f.exists() }
        val list = live.filter { cat == 0 || it.cat == cat }
        val b = body()
        b.addView(cleanChips({ i -> if (i == 0) live.size else live.count { it.cat == i } }, cat) { i -> showBig(i) }, lp(-1, -2))
        b.addView(txt("Los archivos más grandes que encontró el análisis (hasta 400), del más pesado al más ligero. Revisa cada uno antes de eliminarlo. Nexora no borra nada por su cuenta y no recomienda eliminar archivos del sistema ni de otras apps.", 12f, MUTE), lp(-1, -2).also { it.topMargin = dp(10); it.bottomMargin = dp(4) })
        if (list.isEmpty()) b.addView(txt("No hay archivos de este tipo entre los más grandes.", 14f, MUTE), lp(-1, -2).also { it.topMargin = dp(16) })
        for (x in list.take(100)) {
            val f = x.f
            val sub = fmtS(x.size) + " · " + dateFmt.format(Date(x.mod)) + "\n" + relPath(f.parentFile ?: f)
            b.addView(resultRow(f.name, sub) { cleanFileSheet(f, { showBig(cat) }, null) }, lp(-1, -2).also { it.topMargin = dp(8) })
        }
        page("Archivos grandes", b)
    }

    private fun showFolders() {
        val sc = cleanCache[root.absolutePath]
        if (sc == null) {
            showClean()
            return
        }
        val b = body()
        b.addView(txt("Toca una carpeta para abrirla. Nexora no mueve archivos automáticamente.", 12f, MUTE))
        b.addView(sectionTitle("Las que más ocupan"))
        if (sc.folders.isEmpty()) b.addView(txt("No se encontraron carpetas con archivos.", 14f, MUTE))
        for (fs in sc.folders) {
            val sub = fs.files.toString() + " archivos (con subcarpetas) · " + fmtS(fs.bytes) + "\n" + relPath(fs.dir)
            b.addView(resultRow(fs.dir.name, sub) { goToFolder(fs.dir) }, lp(-1, -2).also { it.topMargin = dp(8) })
        }
        b.addView(sectionTitle("Con muchos archivos sueltos"))
        if (sc.crowded.isEmpty()) b.addView(txt("Ninguna carpeta tiene 100 archivos o más directamente dentro.", 14f, MUTE))
        for (fs in sc.crowded) {
            val sub = fs.direct.toString() + " archivos aquí · " + fmtS(fs.bytes) + "\nPodrías organizarlos en subcarpetas · " + relPath(fs.dir)
            b.addView(resultRow(fs.dir.name, sub) { goToFolder(fs.dir) }, lp(-1, -2).also { it.topMargin = dp(8) })
        }
        page("Carpetas", b)
    }

    private fun showSimilar() {
        val sc = cleanCache[root.absolutePath]
        if (sc == null) {
            showClean()
            return
        }
        val b = body()
        b.addView(txt("Posibles coincidencias, no confirmadas. Son fotos de la misma carpeta con el mismo nombre base (por ejemplo «foto» y «foto (1)») pero sin contenido idéntico. Nexora no compara las imágenes visualmente, así que revisa cada una. No son duplicados exactos.", 12f, MUTE))
        var shown = 0
        for (grp in sc.similar) {
            val live = grp.filter { it.exists() }
            if (live.size < 2) continue
            shown++
            val card = cardBox()
            card.addView(txt(live.size.toString() + " fotos con nombre parecido", 15f, TXT, true))
            card.addView(txt(relPath(live[0].parentFile ?: live[0]), 11f, MUTE))
            for (f in live) {
                val sub = fmtS(f.length()) + " · " + dateFmt.format(Date(f.lastModified()))
                card.addView(resultRow(f.name, sub) { cleanFileSheet(f, { showSimilar() }, null) }, lp(-1, -2).also { it.topMargin = dp(6) })
            }
            b.addView(card, lp(-1, -2).also { it.topMargin = dp(10) })
        }
        if (shown == 0) b.addView(txt("No se encontraron posibles coincidencias.", 14f, MUTE), lp(-1, -2).also { it.topMargin = dp(16) })
        page("Posibles similares", b)
    }

    private fun showDocClashes() {
        val sc = cleanCache[root.absolutePath]
        if (sc == null) {
            showClean()
            return
        }
        val b = body()
        b.addView(txt("Documentos con el mismo nombre en carpetas distintas. Pueden causar confusiones. Toca uno para verlo, abrir su carpeta o renombrarlo con el nombre de su carpeta entre paréntesis.", 12f, MUTE))
        var shown = 0
        for (grp in sc.docClash) {
            val live = grp.filter { it.exists() }
            if (live.size < 2) continue
            shown++
            val card = cardBox()
            card.addView(txt(live[0].name, 15f, TXT, true))
            card.addView(txt(live.size.toString() + " copias con este nombre", 12f, ACC2))
            for (f in live) {
                val sub = fmtS(f.length()) + " · " + dateFmt.format(Date(f.lastModified())) + "\n" + relPath(f.parentFile ?: f)
                val doAlt: () -> Unit = {
                    val nf = File(f.parentFile, altName(f))
                    if (nf.exists()) {
                        toast("Ya existe un archivo con ese nombre")
                    } else if (f.renameTo(nf)) {
                        toast("Renombrado a " + nf.name)
                        if (inViewer && curPage == "Documentos repetidos") showDocClashes()
                    } else {
                        toast("No se pudo renombrar")
                    }
                }
                val alt: Pair<String, () -> Unit> = Pair("Renombrar a «" + altName(f) + "»", doAlt)
                card.addView(resultRow(f.name, sub) { cleanFileSheet(f, { showDocClashes() }, alt) }, lp(-1, -2).also { it.topMargin = dp(6) })
            }
            b.addView(card, lp(-1, -2).also { it.topMargin = dp(10) })
        }
        if (shown == 0) b.addView(txt("Ya no hay nombres repetidos.", 14f, MUTE), lp(-1, -2).also { it.topMargin = dp(16) })
        page("Documentos repetidos", b)
    }

    private fun buildSuggestions(sc: CleanScan, from: () -> Unit): List<Sugerencia> {
        val out = ArrayList<Sugerencia>()
        val names = listOf("", "fotografías", "videos", "audios", "documentos")
        val icons = listOf("", "📷", "🎬", "🎵", "📄")
        val dups = liveDups(sc)
        for (cat in 1..4) {
            val dg = dups.filter { catOf(it.files[0]) == cat }
            if (dg.isNotEmpty()) {
                var rec = 0L
                for (g in dg) rec += g.size * (g.files.size - 1)
                out.add(Sugerencia(icons[cat], dg.size.toString() + " grupos de " + names[cat] + " duplicados", "Podrías recuperar " + fmtS(rec) + " si conservas una copia de cada grupo. Revísalos antes de decidir.", "Revisar duplicados") { cleanGo(from) { showDupes(cat, false) } })
            }
        }
        for (cat in 1..3) {
            val gen = sc.generic[cat]
            var total = 0
            for (l in gen.values) total += l.size
            val best = gen.entries.maxByOrNull { it.value.size }
            if (total >= 5 && best != null && best.value.size >= 2) {
                val verbo = if (cat == 1) "Renombrar fotografías" else if (cat == 2) "Renombrar videos" else "Renombrar audios"
                out.add(Sugerencia(icons[cat], total.toString() + " " + names[cat] + " con nombres genéricos", "Por ejemplo «" + best.value[0].name + "». La mayor parte está en «" + File(best.key).name + "» (" + best.value.size + "). El renombre múltiple les pone un nombre común y las numera.", verbo) { renameInFolder(File(best.key), best.value) })
            }
            val md = sc.mediaByDir[cat].entries.maxByOrNull { it.value }
            val minimo = if (cat == 1) 100 else 50
            if (md != null && md.value >= minimo) {
                out.add(Sugerencia(icons[cat], "La carpeta «" + File(md.key).name + "» tiene " + md.value + " " + names[cat], "Puedes organizarlos en subcarpetas (por fecha, evento o tipo). Nexora no mueve nada automáticamente.", "Organizar carpeta") { goToFolder(File(md.key)) })
            }
        }
        val vids = sc.big.filter { it.cat == 2 && it.f.exists() }
        if (vids.size >= 3) {
            out.add(Sugerencia("🎬", "Entre los archivos más grandes hay " + vids.size + " videos", "El más pesado ocupa " + fmtS(vids[0].size) + ". Míralos ordenados por tamaño y decide cuáles conservar.", "Ver videos por tamaño") { cleanGo(from) { showBig(2) } })
        }
        val clash = ArrayList<List<File>>()
        for (grp in sc.docClash) {
            val live = grp.filter { it.exists() }
            if (live.size >= 2) clash.add(live)
        }
        if (clash.isNotEmpty()) {
            out.add(Sugerencia("📄", clash.size.toString() + " nombres de documentos repetidos", "Por ejemplo «" + clash[0][0].name + "» aparece " + clash[0].size + " veces en carpetas distintas. Puedes darle un nombre distinto a cada copia.", "Revisar documentos") { cleanGo(from) { showDocClashes() } })
        }
        val bigDocs = sc.big.filter { it.cat == 4 && it.size >= 20971520L && it.f.exists() }
        if (bigDocs.isNotEmpty()) {
            out.add(Sugerencia("📄", bigDocs.size.toString() + " documentos de más de 20 MB", "Los documentos grandes suelen ser escaneos o archivos pesados. Revisa si todavía los necesitas.", "Ver documentos grandes") { cleanGo(from) { showBig(4) } })
        }
        val mixed = sc.docMixed.firstOrNull()
        if (mixed != null) {
            out.add(Sugerencia("📄", "La carpeta «" + mixed.first.name + "» tiene " + mixed.second + " documentos de varios tipos", "Puedes ordenarlos por tipo, fecha o nombre con el botón ⇅ de la carpeta, o agruparlos en subcarpetas por extensión.", "Organizar carpeta") { goToFolder(mixed.first) })
        }
        val huge = sc.big.count { it.size >= 104857600L && it.f.exists() }
        if (huge > 0) {
            out.add(Sugerencia("📦", huge.toString() + " archivos de más de 100 MB", "Son los que más espacio ocupan. Revisa cada uno antes de eliminarlo; no borres archivos del sistema ni de otras apps.", "Ver archivos grandes") { cleanGo(from) { showBig(0) } })
        }
        val crowd = sc.crowded.firstOrNull()
        if (crowd != null) {
            out.add(Sugerencia("📁", "La carpeta «" + crowd.dir.name + "» tiene " + crowd.direct + " archivos sueltos", "Podrías organizarla en subcarpetas. Nexora no mueve archivos automáticamente.", "Organizar carpeta") { goToFolder(crowd.dir) })
        }
        if (sc.similar.isNotEmpty()) {
            out.add(Sugerencia("🔍", sc.similar.size.toString() + " grupos de posibles fotos similares", "Son coincidencias por nombre, sin confirmar. Revísalas con calma antes de decidir.", "Ver posibles similares") { cleanGo(from) { showSimilar() } })
        }
        return out
    }

    private fun showSuggestions() {
        val sc = cleanCache[root.absolutePath]
        if (sc == null) {
            showClean()
            return
        }
        val b = body()
        val sugs = buildSuggestions(sc) { showSuggestions() }
        b.addView(txt("Recomendaciones según los archivos que encontró el análisis. Nexora solo sugiere: tú decides qué hacer.", 12f, MUTE))
        if (sugs.isEmpty()) b.addView(txt("Por ahora no hay recomendaciones claras para este almacenamiento.", 14f, MUTE), lp(-1, -2).also { it.topMargin = dp(16) })
        for (s in sugs) {
            val card = cardBox()
            card.addView(txt(s.icon + "  " + s.titulo, 15f, TXT, true))
            card.addView(txt(s.detalle, 13f, MUTE), lp(-1, -2).also { it.topMargin = dp(4) })
            val btn = ghostButton(s.accion)
            btn.setOnClickListener { s.hacer() }
            card.addView(btn, lp(-1, dp(46)).also { it.topMargin = dp(10) })
            b.addView(card, lp(-1, -2).also { it.topMargin = dp(10) })
        }
        page("Recomendaciones", b)
    }

    private fun quickBtn(icon: String, label: String, onClick: () -> Unit): TextView {
        val t = txt(icon + "  " + label, 14f, TXT, true)
        t.gravity = Gravity.CENTER
        t.isSingleLine = true
        t.background = rounded(alpha(ACC, 0x33), 14)
        t.setOnClickListener { onClick() }
        return t
    }

    private fun saveSort() {
        prefs.edit().putInt("sort", sortIdx).putBoolean("folders", foldersFirst).commit()
    }

    override fun onPause() {
        super.onPause()
        if (sortReady) saveSort()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val parent = dir.parentFile
        if (!inViewer && selected.isNotEmpty()) {
            selected.clear()
            refreshSel()
        } else if (inViewer) {
            closeViewer()
        } else if (hasAccess() && dir != root && parent != null) {
            dir = parent
            refresh()
        } else {
            super.onBackPressed()
        }
    }
}


private const val SHARE_AUTH = "com.gestor.archivos.share"

private class LifoQueue : java.util.concurrent.LinkedBlockingDeque<Runnable>() {
    override fun offer(e: Runnable): Boolean {
        if (size > 400) pollLast()
        return offerFirst(e)
    }
}

class ShareProvider : ContentProvider() {
    private fun fileOf(uri: Uri): File? {
        val p = uri.path ?: return null
        if (!p.startsWith("/f/")) return null
        val f = File(p.substring(2))
        return if (f.isFile) f else null
    }

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val f = fileOf(uri) ?: throw FileNotFoundException("no encontrado")
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        val f = fileOf(uri) ?: return null
        val cols: Array<String> = if (projection != null) Array(projection.size) { projection[it] } else arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val cur = MatrixCursor(cols)
        val row = arrayOfNulls<Any>(cols.size)
        for (i in cols.indices) {
            row[i] = when (cols[i]) {
                OpenableColumns.DISPLAY_NAME -> f.name
                OpenableColumns.SIZE -> f.length()
                else -> null
            }
        }
        cur.addRow(row)
        return cur
    }

    override fun getType(uri: Uri): String? {
        val f = fileOf(uri) ?: return null
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "application/octet-stream"
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}


private class MenuIcon(ctx: Context, val kind: Int, val col: Int) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.ROUND
        p.strokeJoin = Paint.Join.ROUND
        p.color = col
    }

    override fun onDraw(cv: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        p.strokeWidth = w * 0.09f
        p.style = Paint.Style.STROKE
        val path = Path()
        when (kind) {
            0 -> {
                cv.drawCircle(w * 0.5f, h * 0.33f, w * 0.17f, p)
                cv.drawArc(RectF(w * 0.17f, h * 0.58f, w * 0.83f, h * 1.12f), 200f, 140f, false, p)
            }
            1 -> {
                val cx = w * 0.5f
                val cy = h * 0.5f
                path.moveTo(cx, h * 0.10f)
                path.quadTo(cx, cy, w * 0.90f, cy)
                path.quadTo(cx, cy, cx, h * 0.90f)
                path.quadTo(cx, cy, w * 0.10f, cy)
                path.quadTo(cx, cy, cx, h * 0.10f)
                cv.drawPath(path, p)
            }
            2 -> {
                val xs = floatArrayOf(0.66f, 0.34f, 0.7f)
                for (i in 0..2) {
                    val y = h * (0.24f + 0.26f * i)
                    cv.drawLine(w * 0.10f, y, w * 0.90f, y, p)
                }
                p.style = Paint.Style.FILL
                for (i in 0..2) cv.drawCircle(w * xs[i], h * (0.24f + 0.26f * i), w * 0.10f, p)
            }
            3 -> {
                val cx = w * 0.5f
                val cy = h * 0.5f
                cv.drawCircle(cx, cy, w * 0.14f, p)
                cv.drawCircle(cx, cy, w * 0.28f, p)
                for (i in 0 until 8) {
                    val a = Math.toRadians(i * 45.0)
                    val c0 = Math.cos(a).toFloat()
                    val s0 = Math.sin(a).toFloat()
                    cv.drawLine(cx + c0 * w * 0.28f, cy + s0 * w * 0.28f, cx + c0 * w * 0.40f, cy + s0 * w * 0.40f, p)
                }
            }
            4 -> {
                path.moveTo(w * 0.06f, h * 0.5f)
                path.quadTo(w * 0.5f, 0f, w * 0.94f, h * 0.5f)
                path.quadTo(w * 0.5f, h, w * 0.06f, h * 0.5f)
                cv.drawPath(path, p)
                cv.drawCircle(w * 0.5f, h * 0.5f, w * 0.13f, p)
            }
            5 -> {
                cv.drawLine(w * 0.24f, h * 0.5f, w * 0.76f, h * 0.2f, p)
                cv.drawLine(w * 0.24f, h * 0.5f, w * 0.76f, h * 0.8f, p)
                p.style = Paint.Style.FILL
                cv.drawCircle(w * 0.76f, h * 0.2f, w * 0.12f, p)
                cv.drawCircle(w * 0.24f, h * 0.5f, w * 0.12f, p)
                cv.drawCircle(w * 0.76f, h * 0.8f, w * 0.12f, p)
            }
            6 -> {
                cv.drawRoundRect(RectF(w * 0.14f, h * 0.32f, w * 0.68f, h * 0.88f), w * 0.08f, w * 0.08f, p)
                path.moveTo(w * 0.32f, h * 0.32f)
                path.lineTo(w * 0.32f, h * 0.12f)
                path.lineTo(w * 0.86f, h * 0.12f)
                path.lineTo(w * 0.86f, h * 0.68f)
                path.lineTo(w * 0.68f, h * 0.68f)
                cv.drawPath(path, p)
            }
            7 -> {
                cv.drawLine(w * 0.12f, h * 0.5f, w * 0.74f, h * 0.5f, p)
                path.moveTo(w * 0.54f, h * 0.28f)
                path.lineTo(w * 0.76f, h * 0.5f)
                path.lineTo(w * 0.54f, h * 0.72f)
                cv.drawPath(path, p)
                cv.drawLine(w * 0.9f, h * 0.24f, w * 0.9f, h * 0.76f, p)
            }
            8 -> {
                path.moveTo(w * 0.18f, h * 0.82f)
                path.lineTo(w * 0.22f, h * 0.62f)
                path.lineTo(w * 0.62f, h * 0.22f)
                path.lineTo(w * 0.78f, h * 0.38f)
                path.lineTo(w * 0.38f, h * 0.78f)
                path.close()
                cv.drawPath(path, p)
                cv.drawLine(w * 0.52f, h * 0.32f, w * 0.68f, h * 0.48f, p)
            }
            9 -> {
                cv.drawCircle(w * 0.5f, h * 0.5f, w * 0.38f, p)
                cv.drawLine(w * 0.5f, h * 0.46f, w * 0.5f, h * 0.72f, p)
                p.style = Paint.Style.FILL
                cv.drawCircle(w * 0.5f, h * 0.31f, w * 0.055f, p)
            }
            10 -> {
                cv.drawRoundRect(RectF(w * 0.14f, h * 0.14f, w * 0.86f, h * 0.86f), w * 0.12f, w * 0.12f, p)
                path.moveTo(w * 0.3f, h * 0.52f)
                path.lineTo(w * 0.45f, h * 0.67f)
                path.lineTo(w * 0.72f, h * 0.36f)
                cv.drawPath(path, p)
            }
            11 -> {
                cv.drawLine(w * 0.16f, h * 0.26f, w * 0.84f, h * 0.26f, p)
                path.moveTo(w * 0.38f, h * 0.26f)
                path.lineTo(w * 0.38f, h * 0.13f)
                path.lineTo(w * 0.62f, h * 0.13f)
                path.lineTo(w * 0.62f, h * 0.26f)
                cv.drawPath(path, p)
                val bin = Path()
                bin.moveTo(w * 0.24f, h * 0.26f)
                bin.lineTo(w * 0.30f, h * 0.88f)
                bin.lineTo(w * 0.70f, h * 0.88f)
                bin.lineTo(w * 0.76f, h * 0.26f)
                cv.drawPath(bin, p)
                cv.drawLine(w * 0.42f, h * 0.42f, w * 0.42f, h * 0.72f, p)
                cv.drawLine(w * 0.58f, h * 0.42f, w * 0.58f, h * 0.72f, p)
            }
            12 -> {
                path.moveTo(w * 0.14f, h * 0.58f)
                path.lineTo(w * 0.14f, h * 0.86f)
                path.lineTo(w * 0.86f, h * 0.86f)
                path.lineTo(w * 0.86f, h * 0.58f)
                cv.drawPath(path, p)
                cv.drawLine(w * 0.5f, h * 0.12f, w * 0.5f, h * 0.66f, p)
                val head = Path()
                head.moveTo(w * 0.3f, h * 0.46f)
                head.lineTo(w * 0.5f, h * 0.68f)
                head.lineTo(w * 0.7f, h * 0.46f)
                cv.drawPath(head, p)
            }
            14 -> {
                cv.drawLine(w * 0.15f, h * 0.5f, w * 0.85f, h * 0.5f, p)
                path.moveTo(w * 0.55f, h * 0.22f)
                path.lineTo(w * 0.85f, h * 0.5f)
                path.lineTo(w * 0.55f, h * 0.78f)
                cv.drawPath(path, p)
            }
            15 -> {
                p.style = Paint.Style.FILL
                cv.drawCircle(w * 0.5f, h * 0.2f, w * 0.08f, p)
                cv.drawCircle(w * 0.5f, h * 0.5f, w * 0.08f, p)
                cv.drawCircle(w * 0.5f, h * 0.8f, w * 0.08f, p)
            }
            16 -> {
                cv.drawLine(w * 0.22f, h * 0.22f, w * 0.78f, h * 0.78f, p)
                cv.drawLine(w * 0.78f, h * 0.22f, w * 0.22f, h * 0.78f, p)
            }
            17 -> {
                cv.drawLine(w * 0.14f, h * 0.32f, w * 0.86f, h * 0.32f, p)
                path.moveTo(w * 0.68f, h * 0.16f)
                path.lineTo(w * 0.86f, h * 0.32f)
                path.lineTo(w * 0.68f, h * 0.48f)
                cv.drawPath(path, p)
                cv.drawLine(w * 0.14f, h * 0.68f, w * 0.86f, h * 0.68f, p)
                val back = Path()
                back.moveTo(w * 0.32f, h * 0.52f)
                back.lineTo(w * 0.14f, h * 0.68f)
                back.lineTo(w * 0.32f, h * 0.84f)
                cv.drawPath(back, p)
            }
            18 -> {
                fun star(cx: Float, cy: Float, r: Float) {
                    val sp = Path()
                    sp.moveTo(cx, cy - r)
                    sp.quadTo(cx, cy, cx + r, cy)
                    sp.quadTo(cx, cy, cx, cy + r)
                    sp.quadTo(cx, cy, cx - r, cy)
                    sp.quadTo(cx, cy, cx, cy - r)
                    cv.drawPath(sp, p)
                }
                star(w * 0.42f, h * 0.58f, w * 0.34f)
                star(w * 0.80f, h * 0.22f, w * 0.15f)
            }
        }
    }
}


private class ConvJob(val label: String, val srcs: List<File>, val outBase: String) {
    var status = 0
    var msg = ""
    var note = ""
    val outs = ArrayList<File>()
    var statusView: TextView? = null
    var bar: ProgressBar? = null
}

private fun wavHeader(rate: Int, ch: Int, dataLen: Long): ByteArray {
    val bb = ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    bb.put("RIFF".toByteArray())
    bb.putInt((36 + dataLen).toInt())
    bb.put("WAVE".toByteArray())
    bb.put("fmt ".toByteArray())
    bb.putInt(16)
    bb.putShort(1)
    bb.putShort(ch.toShort())
    bb.putInt(rate)
    bb.putInt(rate * ch * 2)
    bb.putShort((ch * 2).toShort())
    bb.putShort(16)
    bb.put("data".toByteArray())
    bb.putInt(dataLen.toInt())
    return bb.array()
}

private class AudioConverter(
    val src: File,
    val dst: File,
    val toM4a: Boolean,
    val bitrateK: Int,
    val cancelled: () -> Boolean,
    val onProg: (Float) -> Unit
) {
    private var ex: MediaExtractor? = null
    private var dec: MediaCodec? = null
    private var enc: MediaCodec? = null
    private var mux: MediaMuxer? = null
    private var raf: RandomAccessFile? = null
    private var muxTrack = -1
    private var muxStarted = false
    private var rate = 44100
    private var ch = 2
    private var sinkReady = false
    private var wavBytes = 0L
    private var samples = 0L
    private val ebi = MediaCodec.BufferInfo()

    fun run() {
        val e = MediaExtractor()
        ex = e
        try {
            e.setDataSource(src.path)
            var found: MediaFormat? = null
            var track = -1
            for (i in 0 until e.trackCount) {
                val f = e.getTrackFormat(i)
                if ((f.getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) {
                    found = f
                    track = i
                    break
                }
            }
            val inFmt: MediaFormat = found ?: throw Exception("el archivo no contiene audio que se pueda leer")
            e.selectTrack(track)
            val mime = inFmt.getString(MediaFormat.KEY_MIME) ?: ""
            rate = inFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            ch = inFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            if (ch < 1 || ch > 2) throw Exception("solo se admite audio mono o estéreo")
            val durUs = if (inFmt.containsKey(MediaFormat.KEY_DURATION)) inFmt.getLong(MediaFormat.KEY_DURATION) else 0L
            if (mime == "audio/raw") {
                if (inFmt.containsKey(MediaFormat.KEY_PCM_ENCODING) && inFmt.getInteger(MediaFormat.KEY_PCM_ENCODING) != 2) {
                    throw Exception("este WAV no es de 16 bits y no se puede convertir")
                }
                val bb = ByteBuffer.allocate(1 shl 16)
                while (true) {
                    if (cancelled()) throw Exception("cancelado")
                    bb.clear()
                    val n = e.readSampleData(bb, 0)
                    if (n < 0) break
                    val arr = ByteArray(n)
                    bb.position(0)
                    bb.limit(n)
                    bb.get(arr, 0, n)
                    writePcm(arr, n)
                    progress(e, durUs)
                    e.advance()
                }
            } else {
                val d = try {
                    MediaCodec.createDecoderByType(mime)
                } catch (x: Exception) {
                    throw Exception("este dispositivo no puede leer audio " + mime)
                }
                dec = d
                d.configure(inFmt, null, null, 0)
                d.start()
                val info = MediaCodec.BufferInfo()
                var inDone = false
                var outDone = false
                while (!outDone) {
                    if (cancelled()) throw Exception("cancelado")
                    if (!inDone) {
                        val ii = d.dequeueInputBuffer(10000L)
                        if (ii >= 0) {
                            val ib = d.getInputBuffer(ii)!!
                            val n = e.readSampleData(ib, 0)
                            if (n < 0) {
                                d.queueInputBuffer(ii, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inDone = true
                            } else {
                                d.queueInputBuffer(ii, 0, n, e.sampleTime, 0)
                                progress(e, durUs)
                                e.advance()
                            }
                        }
                    }
                    val oi = d.dequeueOutputBuffer(info, 10000L)
                    if (oi >= 0) {
                        if (info.size > 0) {
                            val ob = d.getOutputBuffer(oi)!!
                            val arr = ByteArray(info.size)
                            ob.position(info.offset)
                            ob.limit(info.offset + info.size)
                            ob.get(arr)
                            writePcm(arr, arr.size)
                        }
                        d.releaseOutputBuffer(oi, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outDone = true
                    } else if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val nf = d.outputFormat
                        if (nf.containsKey(MediaFormat.KEY_PCM_ENCODING) && nf.getInteger(MediaFormat.KEY_PCM_ENCODING) != 2) {
                            throw Exception("este audio usa una profundidad que no se puede convertir")
                        }
                        if (!sinkReady) {
                            rate = nf.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            ch = nf.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            if (ch < 1 || ch > 2) throw Exception("solo se admite audio mono o estéreo")
                        }
                    }
                }
            }
            finishSink()
        } finally {
            release()
        }
    }

    private fun progress(e: MediaExtractor, durUs: Long) {
        val t = e.sampleTime
        if (t >= 0 && durUs > 0) onProg((t.toFloat() / durUs).coerceIn(0f, 1f))
    }

    private fun writePcm(b: ByteArray, len: Int) {
        if (!sinkReady) initSink()
        if (!toM4a) {
            raf!!.write(b, 0, len)
            wavBytes += len
            if (wavBytes > 2000000000L) throw Exception("el WAV resultante superaría los 2 GB")
        } else {
            encodePcm(b, len)
        }
    }

    private fun initSink() {
        sinkReady = true
        if (!toM4a) {
            val r = RandomAccessFile(dst, "rw")
            r.setLength(0)
            r.write(ByteArray(44))
            raf = r
        } else {
            val ef = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, ch)
            ef.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            ef.setInteger(MediaFormat.KEY_BIT_RATE, (if (ch == 1) minOf(bitrateK, 192) else bitrateK) * 1000)
            ef.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 65536)
            val en = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            enc = en
            en.configure(ef, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            en.start()
            mux = MediaMuxer(dst.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        }
    }

    private fun encodePcm(b: ByteArray, len: Int) {
        val en = enc!!
        val frame = 2 * ch
        var off = 0
        while (off < len) {
            if (cancelled()) throw Exception("cancelado")
            val ii = en.dequeueInputBuffer(10000L)
            if (ii < 0) {
                drainEnc(false)
                continue
            }
            val buf = en.getInputBuffer(ii)!!
            buf.clear()
            var n = minOf(buf.capacity(), len - off)
            n -= n % frame
            if (n <= 0) throw Exception("el codificador de este dispositivo no acepta este audio")
            buf.put(b, off, n)
            en.queueInputBuffer(ii, 0, n, samples * 1000000L / rate, 0)
            samples += (n / frame).toLong()
            off += n
            drainEnc(false)
        }
    }

    private fun drainEnc(wait: Boolean) {
        val en = enc ?: return
        var idle = 0
        while (true) {
            val oi = en.dequeueOutputBuffer(ebi, if (wait) 10000L else 0L)
            if (oi == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!wait) return
                idle++
                if (idle > 500) throw Exception("el codificador no responde")
            } else if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                muxTrack = mux!!.addTrack(en.outputFormat)
                mux!!.start()
                muxStarted = true
            } else if (oi >= 0) {
                idle = 0
                val ob = en.getOutputBuffer(oi)!!
                if ((ebi.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) ebi.size = 0
                if (ebi.size > 0 && muxStarted) {
                    ob.position(ebi.offset)
                    ob.limit(ebi.offset + ebi.size)
                    mux!!.writeSampleData(muxTrack, ob, ebi)
                }
                en.releaseOutputBuffer(oi, false)
                if ((ebi.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return
            }
        }
    }

    private fun finishSink() {
        if (!sinkReady) throw Exception("no se obtuvo audio del archivo")
        if (!toM4a) {
            val r = raf!!
            r.seek(0)
            r.write(wavHeader(rate, ch, wavBytes))
        } else {
            val en = enc!!
            var done = false
            var tries = 0
            while (!done) {
                val ii = en.dequeueInputBuffer(10000L)
                if (ii >= 0) {
                    en.queueInputBuffer(ii, 0, 0, samples * 1000000L / rate, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    done = true
                } else {
                    drainEnc(false)
                    tries++
                    if (tries > 1000) throw Exception("el codificador no responde")
                }
            }
            drainEnc(true)
            if (muxStarted) {
                mux!!.stop()
                muxStarted = false
            }
        }
    }

    private fun release() {
        try { dec?.stop() } catch (x: Exception) {}
        try { dec?.release() } catch (x: Exception) {}
        try { enc?.stop() } catch (x: Exception) {}
        try { enc?.release() } catch (x: Exception) {}
        try { if (muxStarted) mux?.stop() } catch (x: Exception) {}
        try { mux?.release() } catch (x: Exception) {}
        try { raf?.close() } catch (x: Exception) {}
        try { ex?.release() } catch (x: Exception) {}
    }
}


private class CompareView(ctx: Context, val before: Bitmap, val after: Bitmap) : View(ctx) {
    private var split = 0.5f
    private var zoom = 1f
    private var tx = 0f
    private var ty = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var dragDivider = false
    private val dens = ctx.resources.displayMetrics.density
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mA = Matrix()
    private val mB = Matrix()

    private val scaleDet = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(det: ScaleGestureDetector): Boolean {
            val nz = (zoom * det.scaleFactor).coerceIn(1f, 12f)
            val k = nz / zoom
            tx = det.focusX - (det.focusX - tx) * k
            ty = det.focusY - (det.focusY - ty) * k
            zoom = nz
            clampPan()
            invalidate()
            return true
        }
    })

    private val tapDet = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (zoom > 1.01f) {
                zoom = 1f
                tx = 0f
                ty = 0f
            } else {
                val nz = 4f
                tx = e.x - (e.x - tx) * nz
                ty = e.y - (e.y - ty) * nz
                zoom = nz
            }
            clampPan()
            invalidate()
            return true
        }
    })

    fun setSplit(f: Float) {
        split = f
        invalidate()
    }

    private fun fit(): Float = minOf(width.toFloat() / after.width, height.toFloat() / after.height)

    private fun clampPan() {
        if (width == 0 || height == 0) return
        val fs = fit()
        val fw = after.width * fs
        val fh = after.height * fs
        val bl = (width - fw) / 2f
        val bt = (height - fh) / 2f
        val cw = fw * zoom
        val chh = fh * zoom
        tx = if (cw <= width) (width - cw) / 2f - zoom * bl else tx.coerceIn(width - cw - zoom * bl, -zoom * bl)
        ty = if (chh <= height) (height - chh) / 2f - zoom * bt else ty.coerceIn(height - chh - zoom * bt, -zoom * bt)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        clampPan()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaleDet.onTouchEvent(e)
        tapDet.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                lastX = e.x
                lastY = e.y
                dragDivider = Math.abs(e.x - split * width) < 28 * dens
            }
            MotionEvent.ACTION_POINTER_DOWN -> dragDivider = false
            MotionEvent.ACTION_POINTER_UP -> {
                val i = if (e.actionIndex == 0) 1 else 0
                lastX = e.getX(i)
                lastY = e.getY(i)
            }
            MotionEvent.ACTION_MOVE -> {
                if (e.pointerCount == 1 && !scaleDet.isInProgress) {
                    if (dragDivider) {
                        split = (e.x / width).coerceIn(0f, 1f)
                    } else if (zoom > 1f) {
                        tx += e.x - lastX
                        ty += e.y - lastY
                        clampPan()
                    }
                    lastX = e.x
                    lastY = e.y
                    invalidate()
                }
            }
        }
        return true
    }

    override fun onDraw(cv: Canvas) {
        val vw = width.toFloat()
        val vh = height.toFloat()
        val fs = fit()
        val fw = after.width * fs
        val fh = after.height * fs
        mA.reset()
        mA.postScale(fs, fs)
        mA.postTranslate((vw - fw) / 2f, (vh - fh) / 2f)
        mA.postScale(zoom, zoom)
        mA.postTranslate(tx, ty)
        mB.set(mA)
        mB.preScale(after.width.toFloat() / before.width, after.height.toFloat() / before.height)
        cv.drawColor(0xFF05070C.toInt())
        cv.drawBitmap(after, mA, paint)
        val sx = split * vw
        cv.save()
        cv.clipRect(0f, 0f, sx, vh)
        cv.drawBitmap(before, mB, paint)
        cv.restore()
        linePaint.color = Color.WHITE
        linePaint.strokeWidth = 2f * dens
        cv.drawLine(sx, 0f, sx, vh, linePaint)
        cv.drawCircle(sx, vh / 2f, 16f * dens, linePaint)
        linePaint.color = 0xFF101828.toInt()
        textPaint.color = linePaint.color
        textPaint.textSize = 14f * dens
        textPaint.textAlign = Paint.Align.CENTER
        cv.drawText("‹›", sx, vh / 2f + 5f * dens, textPaint)
        textPaint.textSize = 11f * dens
        textPaint.color = Color.WHITE
        pill.color = 0xAA000000.toInt()
        if (sx > 70f * dens) {
            cv.drawRoundRect(RectF(10f * dens, 10f * dens, 76f * dens, 32f * dens), 11f * dens, 11f * dens, pill)
            cv.drawText("ANTES", 43f * dens, 25f * dens, textPaint)
        }
        if (vw - sx > 90f * dens) {
            cv.drawRoundRect(RectF(vw - 90f * dens, 10f * dens, vw - 10f * dens, 32f * dens), 11f * dens, 11f * dens, pill)
            cv.drawText("DESPUÉS", vw - 50f * dens, 25f * dens, textPaint)
        }
    }
}


private class ModelError(msg: String) : Exception(msg)
