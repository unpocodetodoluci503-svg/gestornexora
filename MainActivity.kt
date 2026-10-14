package com.gestor.archivos

import android.Manifest
import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
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
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.pdf.PdfRenderer
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.webkit.MimeTypeMap
import android.widget.*
import java.io.File
import java.io.FileNotFoundException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipInputStream

private fun c(v: Long): Int = v.toInt()

private class Item(val f: File, val size: Long, val mod: Long, val cat: Int) {
    val key: String = f.name.lowercase()
    val ext: String = f.extension.lowercase()
}

private data class Seccion(val titulo: String, val puntos: List<String>)
private data class Entrada(val version: String, val fecha: String?, val titulo: String, val resumen: String, val secciones: List<Seccion>)
private data class Fondo(val nombre: String, val top: Int, val bottom: Int, val card: Int)
private data class Acento(val nombre: String, val c1: Int, val c2: Int)
private class Plan(val orig: File, val newName: String, val status: String, val ok: Boolean)

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
                    "Con un solo archivo seleccionado, Renombrar ya aparece directamente en la barra"))))
    )

    private val pendientes = listOf(
        "Perfil: en desarrollo, todavía sin funciones.",
        "Ordenar por fecha de creación: Android no entrega ese dato de forma fiable.",
        "Renombre múltiple de carpetas: se evaluará como mejora futura.",
        "Copiar, mover y compartir carpetas: por ahora solo funcionan con archivos.",
        "Miniaturas de videos: por ahora solo se muestran las de imágenes.",
        "Vista en cuadrícula: aún no existe.",
        "Extraer RAR y 7z: por ahora solo se extraen ZIP."
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
    private var foldersFirst = true
    private var catIdx = 0
    private var bgBitmap: Bitmap? = null
    private val selected = LinkedHashSet<File>()
    private lateinit var selBar: LinearLayout
    private lateinit var selWrap: HorizontalScrollView
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
        sortIdx = prefs.getInt("sort", 0).coerceIn(0, SORTS.size - 1)
        foldersFirst = prefs.getBoolean("folders", true)
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

    private fun optionsSheet(title: String, labels: List<String>, onPick: (Int) -> Unit) {
        showSheet { d ->
            val col = LinearLayout(this)
            col.orientation = LinearLayout.VERTICAL
            col.addView(txt(title, 18f, TXT, true))
            for (i in labels.indices) {
                val r = optionRow(labels[i], false)
                r.setOnClickListener { d.dismiss(); onPick(i) }
                col.addView(r, lp(-1, -2).also { it.topMargin = dp(6) })
            }
            col
        }
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
        col.addView(bar, lp(-1, -2))
        col.addView(sv, lp(-1, 0, 1f))
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

    private fun menuSheet() {
        showSheet { d ->
            val row = LinearLayout(this)
            val defs = listOf(Triple("👤", "Perfil", 0), Triple("✨", "Novedades", 1), Triple("🎨", "Personalización", 2), Triple("⚙️", "Configuración", 3))
            for (df in defs) {
                val cell = LinearLayout(this)
                cell.orientation = LinearLayout.VERTICAL
                cell.gravity = Gravity.CENTER_HORIZONTAL
                val ic = txt(df.first, 24f, TXT)
                ic.gravity = Gravity.CENTER
                ic.background = rounded(alpha(ACC, 0x2E), 16)
                cell.addView(ic, lp(dp(54), dp(54)))
                val lab = LinearLayout(this)
                lab.gravity = Gravity.CENTER_VERTICAL
                val lt = txt(df.second, 10f, TXT)
                lt.isSingleLine = true
                lab.addView(lt)
                if (df.third == 1 && hasUnseen()) lab.addView(dot(), lp(dp(8), dp(8)).also { it.leftMargin = dp(3) })
                cell.addView(lab, lp(-2, -2).also { it.topMargin = dp(8) })
                cell.setOnClickListener {
                    d.dismiss()
                    when (df.third) {
                        0 -> showProfile()
                        1 -> showNews()
                        2 -> showPersonalization()
                        else -> showSettings()
                    }
                }
                row.addView(cell, lp(0, -2, 1f))
            }
            row
        }
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
        dots.setOnClickListener { menuSheet() }
        top.addView(dots, lp(dp(44), dp(44)))
        head.addView(top, lp(-1, -2))
        pathView = txt("", 12f, ACC2)
        pathView.setPadding(dp(12), dp(6), dp(12), dp(6))
        pathView.background = rounded(alpha(ACC, 0x33), 12)
        head.addView(pathView, lp(-2, -2).also { it.topMargin = dp(8) })

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
        val sbg = rounded(alpha(CARD, 0xF5), 20)
        sbg.setStroke(dp(1), alpha(ACC2, 0x55))
        selWrap.background = sbg
        selWrap.visibility = View.GONE
        selWrap.addView(selBar)
        col.addView(selWrap, lp(-1, -2).also { it.leftMargin = dp(12); it.rightMargin = dp(12); it.bottomMargin = dp(10) })
        setContentView(withBg(col))
        list.setOnItemClickListener { _, _, i, _ -> onTap(items[i].f) }
        list.setOnItemLongClickListener { _, _, i, _ -> onLong(items[i].f); true }
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

    private fun refresh() {
        val all = (dir.listFiles() ?: emptyArray<File>()).map { f ->
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
        pathView.text = "Almacenamiento interno" + dir.absolutePath.removePrefix(root.absolutePath)
        emptyView.text = if (all.isEmpty()) "Esta carpeta está vacía" else "No hay archivos de esta categoría aquí"
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
                    prefs.edit().putInt("sort", i).apply()
                    sortIdx = i
                    d.dismiss()
                    refresh()
                }
                col.addView(r, lp(-1, -2).also { it.topMargin = dp(4) })
            }
            val tg = optionRow((if (foldersFirst) "✓  " else "○  ") + "Mostrar carpetas primero", false)
            tg.setOnClickListener {
                foldersFirst = !foldersFirst
                prefs.edit().putBoolean("folders", foldersFirst).apply()
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

    private fun onLong(f: File) {
        if (f.isDirectory) {
            if (selected.isEmpty()) menu(f) else toast("Las carpetas no se pueden seleccionar")
        } else {
            toggle(f)
        }
    }

    private fun toggle(f: File) {
        if (!selected.add(f)) selected.remove(f)
        refreshSel()
    }

    private fun refreshSel() {
        (list.adapter as? BaseAdapter)?.notifyDataSetChanged()
        updateSelBar()
    }

    private fun actionChip(label: String, danger: Boolean, onClick: () -> Unit): TextView {
        val red = c(0xFFFB7185)
        val t = txt(label, 13f, if (danger) red else TXT, true)
        t.setPadding(dp(14), dp(10), dp(14), dp(10))
        t.background = rounded(if (danger) alpha(red, 0x26) else alpha(ACC, 0x33), 16)
        t.setOnClickListener { onClick() }
        return t
    }

    private fun updateSelBar() {
        selBar.removeAllViews()
        if (selected.isEmpty()) {
            selWrap.visibility = View.GONE
            return
        }
        selWrap.visibility = View.VISIBLE
        val files = selected.toList()
        val one = files.size == 1
        fun add(label: String, danger: Boolean, onClick: () -> Unit) {
            selBar.addView(actionChip(label, danger, onClick), lp(-2, -2).also { it.rightMargin = dp(6) })
        }
        add("✕ Cancelar", false) {
            selected.clear()
            refreshSel()
        }
        val cnt = txt(files.size.toString() + (if (one) " seleccionado" else " seleccionados"), 13f, MUTE, true)
        selBar.addView(cnt, lp(-2, -2).also { it.leftMargin = dp(4); it.rightMargin = dp(10) })
        if (one) add("✏️ Renombrar", false) { rename(files[0]) } else add("✏️ Renombre múltiple", false) { renameMultiSheet() }
        add("📋 Copiar", false) { chooseDest(files, false) }
        add("✂️ Mover", false) { chooseDest(files, true) }
        add("📤 Compartir", false) { shareFiles(files) }
        add("ℹ️ Información", false) { infoSheet(files) }
        if (one && files[0].extension.lowercase() == "zip") {
            add("🗜 Extraer", false) { confirmSheet("¿Extraer " + files[0].name + "?", "", "Extraer") { extract(files[0]) } }
        }
        if (items.count { it.cat >= 0 } > selected.size) add("☑ Seleccionar todo", false) { selectAll() }
        add("🗑 Eliminar", true) { confirmDeleteFiles(files) }
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

    private fun chooseDest(files: List<File>, move: Boolean) {
        val verb = if (move) "Mover" else "Copiar"
        val src = files[0].parentFile
        showSheet { d ->
            var cur: File = dir
            val col = LinearLayout(this)
            col.orientation = LinearLayout.VERTICAL
            col.addView(txt(verb + " " + (if (files.size == 1) files[0].name else files.size.toString() + " archivos") + " a…", 18f, TXT, true))
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
                pathT.text = "Almacenamiento interno" + cur.absolutePath.removePrefix(root.absolutePath)
                listBox.removeAllViews()
                if (cur != root && cur.parentFile != null) {
                    val up = optionRow("⬆  Subir", false)
                    up.setOnClickListener {
                        cur = cur.parentFile ?: root
                        render()
                    }
                    listBox.addView(up, lp(-1, -2).also { it.topMargin = dp(4) })
                }
                val subs = (cur.listFiles() ?: emptyArray<File>()).filter { it.isDirectory }.sortedBy { it.name.lowercase() }
                for (sf in subs) {
                    val r = optionRow("📁  " + sf.name, false)
                    r.setOnClickListener {
                        cur = sf
                        render()
                    }
                    listBox.addView(r, lp(-1, -2).also { it.topMargin = dp(4) })
                }
                if (subs.isEmpty()) listBox.addView(txt("No hay subcarpetas aquí", 13f, MUTE), lp(-2, -2).also { it.topMargin = dp(8) })
            }

            val btns = LinearLayout(this)
            val no = ghostButton("Cancelar")
            no.setOnClickListener { d.dismiss() }
            val yes = primaryButton(verb + " aquí")
            yes.setOnClickListener {
                if (cur.canonicalPath == src?.canonicalPath) {
                    toast("Elige una carpeta distinta a la actual")
                } else {
                    d.dismiss()
                    transfer(files, cur, move)
                }
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

    private fun menu(f: File) {
        val opts = mutableListOf("Renombrar", "Eliminar")
        if (f.extension.lowercase() == "zip") opts.add("Extraer aquí")
        optionsSheet(f.name, opts) { w ->
            when (opts[w]) {
                "Renombrar" -> rename(f)
                "Eliminar" -> confirmSheet("¿Eliminar " + f.name + "?", "Esta acción no se puede deshacer.", "Eliminar") {
                    if (f.deleteRecursively()) refresh() else toast("No se pudo eliminar")
                }
                else -> extract(f)
            }
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
        token++
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
