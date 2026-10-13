package com.gestor.archivos

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.*
import java.io.File
import java.util.zip.ZipInputStream

class MainActivity : Activity() {
    private lateinit var root: File
    private lateinit var dir: File
    private lateinit var pathView: TextView
    private lateinit var list: ListView
    private var items: List<File> = emptyList()
    private var inViewer = false
    @Volatile private var token = 0

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        root = Environment.getExternalStorageDirectory()
        dir = root
    }

    override fun onResume() {
        super.onResume()
        if (hasAccess()) {
            if (!inViewer) showBrowser()
        } else {
            showAskAccess()
        }
    }

    private fun dp(n: Int): Int = (n * resources.displayMetrics.density).toInt()
    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()

    private fun hasAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager()
        else checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    private fun showAskAccess() {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = Gravity.CENTER
        col.setPadding(dp(24), dp(24), dp(24), dp(24))
        val t = TextView(this)
        t.text = "Para ver tus archivos, la app necesita acceso a todos los archivos del teléfono."
        t.textSize = 18f
        t.gravity = Gravity.CENTER
        val btn = Button(this)
        btn.text = "Dar acceso"
        btn.setOnClickListener { askAccess() }
        col.addView(t)
        col.addView(btn)
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

    private fun showBrowser() {
        inViewer = false
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setBackgroundColor(Color.WHITE)
        pathView = TextView(this)
        pathView.setPadding(dp(16), dp(36), dp(16), dp(12))
        pathView.textSize = 14f
        pathView.setBackgroundColor(0xFFEEF0F3.toInt())
        pathView.setTextColor(0xFF1B2430.toInt())
        list = ListView(this)
        col.addView(pathView, LinearLayout.LayoutParams(-1, -2))
        col.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(col)
        list.setOnItemClickListener { _, _, i, _ -> tap(items[i]) }
        list.setOnItemLongClickListener { _, _, i, _ -> menu(items[i]); true }
        refresh()
    }

    private fun refresh() {
        items = (dir.listFiles() ?: emptyArray<File>()).sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
        val rel = dir.absolutePath.removePrefix(root.absolutePath)
        pathView.text = "Almacenamiento interno" + rel
        val labels = items.map { f ->
            if (f.isDirectory) "📁  " + f.name else "📄  " + f.name + "\n      " + fmt(f.length())
        }
        list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
    }

    private fun fmt(b: Long): String =
        if (b < 1048576) maxOf(1L, b / 1024).toString() + " KB" else String.format("%.1f MB", b / 1048576.0)

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
        AlertDialog.Builder(this).setTitle(f.name).setItems(opts.toTypedArray()) { _, w ->
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
        AlertDialog.Builder(this).setTitle("Renombrar").setView(et)
            .setPositiveButton("Guardar") { _, _ ->
                val n = et.text.toString().trim()
                if (n.isNotEmpty() && f.renameTo(File(f.parentFile, n))) refresh() else toast("No se pudo renombrar")
            }
            .setNegativeButton("Cancelar", null).show()
    }

    private fun confirmDelete(f: File) {
        AlertDialog.Builder(this).setTitle("¿Eliminar " + f.name + "?")
            .setMessage("Esta acción no se puede deshacer.")
            .setPositiveButton("Eliminar") { _, _ ->
                if (f.deleteRecursively()) refresh() else toast("No se pudo eliminar")
            }
            .setNegativeButton("Cancelar", null).show()
    }

    private fun askExtract(f: File) {
        AlertDialog.Builder(this).setTitle("¿Extraer " + f.name + "?")
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

    private fun viewer(title: String, content: View) {
        inViewer = true
        token++
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setBackgroundColor(Color.BLACK)
        val bar = TextView(this)
        bar.text = "‹  " + title
        bar.setTextColor(Color.WHITE)
        bar.textSize = 16f
        bar.setPadding(dp(16), dp(36), dp(16), dp(14))
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
