package com.gestor.archivos

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.*
import java.io.File
import java.util.zip.ZipInputStream

private data class Nota(val code: Int, val version: String, val titulo: String, val puntos: List<String>)

class MainActivity : Activity() {
    private val BG = 0xFF070B18.toInt()
    private val SURF = 0xFF0F1830.toInt()
    private val ACC = 0xFF3D7BFF.toInt()
    private val CYAN = 0xFF22D3EE.toInt()
    private val TXT = 0xFFE8EEFF.toInt()
    private val MUTE = 0xFF7F8DB5.toInt()
    private val VIOLET = 0xFF8B5CF6.toInt()
    private val PINK = 0xFFF472B6.toInt()
    private val AMBER = 0xFFFBBF24.toInt()

    // Para cada actualización: sube versionCode/versionName en build.gradle.kts y agrega una nota aquí.
    private val notas = listOf(
        Nota(2, "1.1", "Nexora estrena look", listOf(
            "Nuevo diseño oscuro con acentos en azul eléctrico",
            "Ahora se llama Nexora y tiene su propio ícono",
            "Este panel de novedades te saldrá en cada actualización"
        ))
    )

    private lateinit var root: File
    private lateinit var dir: File
    private lateinit var pathView: TextView
    private lateinit var list: ListView
    private var items: List<File> = emptyList()
    private var inViewer = false
    @Volatile private var token = 0

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        root = Environment.getExternalStorageDirectory()
        dir = root
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
    private fun alpha(c: Int, a: Int): Int = (c and 0x00FFFFFF) or (a shl 24)

    private fun rounded(color: Int, r: Int): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(color)
        g.cornerRadius = dp(r).toFloat()
        return g
    }

    private fun dlg(): AlertDialog.Builder =
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)

    private fun wordmark(size: Float): TextView {
        val t = TextView(this)
        t.text = "NEXORA"
        t.textSize = size
        t.setTypeface(null, Typeface.BOLD)
        t.letterSpacing = 0.2f
        t.setTextColor(CYAN)
        t.paint.shader = LinearGradient(0f, 0f, dp(170).toFloat(), 0f, CYAN, ACC, Shader.TileMode.CLAMP)
        return t
    }

    private fun primaryButton(text: String): Button {
        val b = Button(this)
        b.text = text
        b.isAllCaps = false
        b.setTextColor(Color.WHITE)
        b.setTypeface(null, Typeface.BOLD)
        b.background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(ACC, 0xFF6A5CFF.toInt())).also { it.cornerRadius = dp(14).toFloat() }
        return b
    }

    private fun fmt(b: Long): String =
        if (b < 1048576) maxOf(1L, b / 1024).toString() + " KB" else String.format("%.1f MB", b / 1048576.0)

    // ---------- permisos ----------
    private fun hasAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager()
        else checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    private fun showAskAccess() {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = Gravity.CENTER
        col.setBackgroundColor(BG)
        col.setPadding(dp(28), dp(24), dp(28), dp(24))
        val t = TextView(this)
        t.text = "Para mostrar tus archivos, Nexora necesita acceso a todos los archivos del teléfono."
        t.textSize = 17f
        t.setTextColor(TXT)
        t.gravity = Gravity.CENTER
        val btn = primaryButton("Dar acceso")
        btn.setOnClickListener { askAccess() }
        col.addView(wordmark(30f))
        col.addView(t, LinearLayout.LayoutParams(-2, -2).also { it.topMargin = dp(20); it.bottomMargin = dp(24) })
        col.addView(btn, LinearLayout.LayoutParams(-1, dp(52)))
        setContentView(col)
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
    @Suppress("DEPRECATION")
    private fun pkgCode(): Int = try {
        packageManager.getPackageInfo(packageName, 0).versionCode
    } catch (e: Exception) {
        1
    }

    private fun showWhatsNew() {
        val code = pkgCode()
        val prefs = getSharedPreferences("nexora", MODE_PRIVATE)
        if (prefs.getInt("seen", 0) >= code) return
        val n = notas.lastOrNull { it.code <= code } ?: return
        prefs.edit().putInt("seen", code).apply()

        val d = Dialog(this)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.setPadding(dp(24), dp(24), dp(24), dp(22))
        val bg = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF16286B.toInt(), 0xFF0A1230.toInt()))
        bg.cornerRadius = dp(26).toFloat()
        bg.setStroke(dp(1), alpha(CYAN, 0x66))
        card.background = bg

        val chip = TextView(this)
        chip.text = "NOVEDADES  •  v" + n.version
        chip.textSize = 11f
        chip.letterSpacing = 0.12f
        chip.setTypeface(null, Typeface.BOLD)
        chip.setTextColor(CYAN)
        chip.setPadding(dp(12), dp(6), dp(12), dp(6))
        chip.background = rounded(alpha(CYAN, 0x22), 20)
        card.addView(chip, LinearLayout.LayoutParams(-2, -2))

        val title = TextView(this)
        title.text = n.titulo
        title.textSize = 26f
        title.setTypeface(null, Typeface.BOLD)
        title.setTextColor(Color.WHITE)
        card.addView(title, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = dp(14); it.bottomMargin = dp(10) })

        for (p in n.puntos) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            val dot = TextView(this)
            dot.text = "◆"
            dot.textSize = 12f
            dot.setTextColor(CYAN)
            dot.setPadding(0, dp(3), dp(12), 0)
            val tx = TextView(this)
            tx.text = p
            tx.textSize = 15f
            tx.setTextColor(TXT)
            row.addView(dot)
            row.addView(tx, LinearLayout.LayoutParams(0, -2, 1f))
            card.addView(row, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = dp(10) })
        }

        val go = primaryButton("¡Vamos!")
        go.setOnClickListener { d.dismiss() }
        card.addView(go, LinearLayout.LayoutParams(-1, dp(52)).also { it.topMargin = dp(22) })

        d.setContentView(card)
        d.show()
        d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        d.window?.setLayout((resources.displayMetrics.widthPixels * 0.92).toInt(), WindowManager.LayoutParams.WRAP_CONTENT)
    }

    // ---------- explorador ----------
    private fun showBrowser() {
        inViewer = false
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setBackgroundColor(BG)

        val header = LinearLayout(this)
        header.orientation = LinearLayout.VERTICAL
        header.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF10246B.toInt(), BG))
        header.setPadding(dp(20), dp(18), dp(20), dp(16))
        pathView = TextView(this)
        pathView.textSize = 12f
        pathView.setTextColor(CYAN)
        pathView.setPadding(dp(12), dp(6), dp(12), dp(6))
        pathView.background = rounded(alpha(ACC, 0x33), 12)
        header.addView(wordmark(28f))
        header.addView(pathView, LinearLayout.LayoutParams(-2, -2).also { it.topMargin = dp(10) })

        list = ListView(this)
        list.divider = null
        list.dividerHeight = 0
        list.setBackgroundColor(BG)
        list.selector = ColorDrawable(Color.TRANSPARENT)
        list.setPadding(0, dp(6), 0, dp(12))
        list.clipToPadding = false

        col.addView(header, LinearLayout.LayoutParams(-1, -2))
        col.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(col)
        list.setOnItemClickListener { _, _, i, _ -> tap(items[i]) }
        list.setOnItemLongClickListener { _, _, i, _ -> menu(items[i]); true }
        refresh()
    }

    private fun refresh() {
        items = (dir.listFiles() ?: emptyArray<File>()).sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
        pathView.text = "Almacenamiento interno" + dir.absolutePath.removePrefix(root.absolutePath)
        list.adapter = RowAdapter()
    }

    private fun kindOf(f: File): Pair<String, Int> {
        if (f.isDirectory) return Pair("📁", ACC)
        return when (f.extension.lowercase()) {
            "jpg", "jpeg", "png", "webp", "gif", "bmp" -> Pair("IMG", CYAN)
            "mp4", "mkv", "webm", "3gp", "mov" -> Pair("VID", VIOLET)
            "pdf" -> Pair("PDF", PINK)
            "zip", "rar", "7z" -> Pair("ZIP", AMBER)
            else -> Pair(if (f.extension.isEmpty()) "•" else f.extension.uppercase().take(4), MUTE)
        }
    }

    private inner class RowAdapter : BaseAdapter() {
        override fun getCount(): Int = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int): Long = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val f = items[position]
            val wrap = FrameLayout(this@MainActivity)
            wrap.setPadding(dp(12), dp(4), dp(12), dp(4))
            val row = LinearLayout(this@MainActivity)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(dp(12), dp(11), dp(12), dp(11))
            row.background = rounded(SURF, 16)

            val k = kindOf(f)
            val badge = TextView(this@MainActivity)
            badge.text = k.first
            badge.gravity = Gravity.CENTER
            badge.textSize = if (f.isDirectory) 20f else 12f
            badge.setTypeface(null, Typeface.BOLD)
            badge.setTextColor(k.second)
            badge.background = rounded(alpha(k.second, 0x26), 13)
            row.addView(badge, LinearLayout.LayoutParams(dp(46), dp(46)))

            val col = LinearLayout(this@MainActivity)
            col.orientation = LinearLayout.VERTICAL
            val name = TextView(this@MainActivity)
            name.text = f.name
            name.textSize = 15f
            name.setTextColor(TXT)
            name.isSingleLine = true
            name.ellipsize = TextUtils.TruncateAt.END
            val sub = TextView(this@MainActivity)
            sub.text = if (f.isDirectory) "Carpeta" else fmt(f.length())
            sub.textSize = 12f
            sub.setTextColor(MUTE)
            col.addView(name)
            col.addView(sub)
            row.addView(col, LinearLayout.LayoutParams(0, -2, 1f).also { it.leftMargin = dp(14) })

            wrap.addView(row, FrameLayout.LayoutParams(-1, -2))
            return wrap
        }
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
            "zip" -> askExtract(f)
            else -> toast("Todavía no hay vista previa para este tipo de archivo")
        }
    }

    private fun menu(f: File) {
        val opts = mutableListOf("Renombrar", "Eliminar")
        if (f.extension.lowercase() == "zip") opts.add("Extraer aquí")
        dlg().setTitle(f.name).setItems(opts.toTypedArray()) { _, w ->
            when (opts[w]) {
                "Renombrar" -> rename(f)
                "Eliminar" -> confirmDelete(f)
                else -> extract(f)
            }
        }.show()
    }

    private fun rename(f: File) {
        val et = EditText(this)
        et.setText(f.name)
        et.setTextColor(TXT)
        dlg().setTitle("Renombrar").setView(et)
            .setPositiveButton("Guardar") { _, _ ->
                val n = et.text.toString().trim()
                if (n.isNotEmpty() && f.renameTo(File(f.parentFile, n))) refresh() else toast("No se pudo renombrar")
            }
            .setNegativeButton("Cancelar", null).show()
    }

    private fun confirmDelete(f: File) {
        dlg().setTitle("¿Eliminar " + f.name + "?")
            .setMessage("Esta acción no se puede deshacer.")
            .setPositiveButton("Eliminar") { _, _ ->
                if (f.deleteRecursively()) refresh() else toast("No se pudo eliminar")
            }
            .setNegativeButton("Cancelar", null).show()
    }

    private fun askExtract(f: File) {
        dlg().setTitle("¿Extraer " + f.name + "?")
            .setPositiveButton("Extraer") { _, _ -> extract(f) }
            .setNegativeButton("Cancelar", null).show()
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

    // ---------- visores ----------
    private fun viewer(title: String, content: View) {
        inViewer = true
        token++
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setBackgroundColor(0xFF03050C.toInt())
        val bar = TextView(this)
        bar.text = "‹   " + title
        bar.setTextColor(TXT)
        bar.textSize = 16f
        bar.isSingleLine = true
        bar.ellipsize = TextUtils.TruncateAt.END
        bar.setBackgroundColor(SURF)
        bar.setPadding(dp(18), dp(14), dp(18), dp(14))
        bar.setOnClickListener { closeViewer() }
        col.addView(bar, LinearLayout.LayoutParams(-1, -2))
        col.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
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
                            val lp = LinearLayout.LayoutParams(-1, -2)
                            lp.bottomMargin = dp(8)
                            box.addView(iv, lp)
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
        if (inViewer) {
            closeViewer()
        } else if (hasAccess() && dir != root && parent != null) {
            dir = parent
            refresh()
        } else {
            super.onBackPressed()
        }
    }
}
