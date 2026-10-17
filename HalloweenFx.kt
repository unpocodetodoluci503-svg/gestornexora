package com.gestor.archivos

import android.app.Activity
import android.app.Application
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.PowerManager
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

// ============================================================================
//  Nexora · Halloween (v2)
//  - Fondo nocturno animado detrás de la app: luna, estrellas, murciélagos,
//    nubes, niebla, cementerio, árboles muertos y relámpagos.
//  - Capa de efectos encima: calaveras con fuego de partículas, calavera
//    voladora, ojos en la oscuridad, calabazas, sangre que sigue a las
//    tarjetas al deslizar, brasas, telarañas y viñeta.
//  No toca MainActivity y no recibe toques.
// ============================================================================

private const val TAU = 6.2831855f
private const val DEG = 0.017453292f

// Ciclo de la luna (segundos): intacta -> grietas -> carga -> explosión -> renace
private const val CYC = 38f
private const val CRACK_T0 = 4f
private const val CRACK_T1 = 26f
private const val BLAST_T = 27.6f

private fun hwLerp(a: Int, b: Int, t: Float): Int {
    val k = t.coerceIn(0f, 1f)
    val aa = ((a ushr 24) and 255) + (((b ushr 24) and 255) - ((a ushr 24) and 255)) * k
    val rr = ((a shr 16) and 255) + (((b shr 16) and 255) - ((a shr 16) and 255)) * k
    val gg = ((a shr 8) and 255) + (((b shr 8) and 255) - ((a shr 8) and 255)) * k
    val bb = (a and 255) + ((b and 255) - (a and 255)) * k
    return (aa.toInt() shl 24) or (rr.toInt() shl 16) or (gg.toInt() shl 8) or bb.toInt()
}

private fun hc(v: Long): Int = v.toInt()

private fun hwHash(seed: Int, i: Int): Float {
    var x = seed * 374761393 + i * 668265263
    x = (x xor (x ushr 13)) * 1274126177
    x = x xor (x ushr 16)
    return (x and 0xFFFFFF) / 16777216f
}

private fun hwEase(t: Float): Float {
    val c = t.coerceIn(0f, 1f)
    return c * c * (3f - 2f * c)
}

private fun hwBack(t: Float): Float {
    val c = t.coerceIn(0f, 1f)
    val s = 1.70158f
    val u = c - 1f
    return 1f + u * u * ((s + 1f) * u + s)
}

class NexoraApp : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            val p = getSharedPreferences("nexora", Context.MODE_PRIVATE)
            if (!p.getBoolean("hwTema", false)) {
                val ed = p.edit()
                // Solo si nunca personalizaste: acento Ámbar + fondo Nebulosa (naranja y violeta)
                if (!p.contains("accent")) ed.putInt("accent", 4)
                if (!p.contains("bg")) ed.putInt("bg", 2)
                ed.putBoolean("hwTema", true)
                ed.apply()
            }
        } catch (e: Exception) {
        }
        registerActivityLifecycleCallbacks(HwHost())
    }
}

private class HwHost : Application.ActivityLifecycleCallbacks {
    private fun find(a: Activity): HwView? {
        val decor = a.window.decorView as? ViewGroup ?: return null
        for (i in 0 until decor.childCount) {
            val c = decor.getChildAt(i)
            if (c is HwView) return c
        }
        return null
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityResumed(activity: Activity) {
        if (activity !is MainActivity) return
        try {
            val decor = activity.window.decorView as? ViewGroup ?: return
            var fx = find(activity)
            if (fx == null) {
                fx = HwView(activity)
                decor.addView(fx, FrameLayout.LayoutParams(-1, -1))
            } else {
                fx.bringToFront()
            }
            fx.setActive(true)
        } catch (e: Exception) {
        }
    }

    override fun onActivityPaused(activity: Activity) {
        if (activity !is MainActivity) return
        try {
            find(activity)?.setActive(false)
        } catch (e: Exception) {
        }
    }

    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}

// Fondo que se pone detrás de cada pantalla (reemplaza el degradado azul)
private class HwBg(val fx: HwView) : Drawable() {
    override fun draw(canvas: Canvas) {
        val b = bounds
        fx.drawBackground(canvas, b.width().toFloat(), b.height().toFloat())
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}

private class HwPumpkin {
    var state = 0
    var wait = 0f
    var tm = 0f
    var dur = 1f
    var edge = 0
    var along = 0.5f
    var r = 0f
    var x = 0f
    var y = 0f
    var rot = 0f
    var sq = 0f
    var ph = 0f
    var show = 0.5f
    var sx = 0f
    var ex = 0f
    var yb = 0f
    var amp = 0f
    var fq = 1f
    var bounce = false
    var laugh = 0f
    var laughAmt = 0f
    var laughDone = false
    var open = 0.1f
    var dir = 1f
    var baseRot = 0f
}

private class HwLurk {
    var x = 0f
    var y = 0f
    var age = 0f
    var life = 0f
    var wait = 0f
    var col = 0
    var sz = 1f
    var ph = 0f
}

private class HwView(ctx: Context) : View(ctx), Choreographer.FrameCallback {
    private val d = ctx.resources.displayMetrics.density
    private val rnd = java.util.Random(31102026L)
    private var active = false
    private var posted = false
    private var lite = false
    private var lastNs = 0L
    private var minStepNs = 33000000L
    var t = 0f
    private var W = 0f
    private var H = 0f
    private var botInset = 0f

    // ---- pinceles y formas reutilizables (sin crear objetos por cuadro) ----
    private val pFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pGlow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pStroke = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pFire = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pBg = Paint()
    private val rect = RectF()
    private val tmp = Path()
    private val clipP = Path()
    private var webPath = Path()

    // ---- sombreadores en coordenadas unitarias (se escalan con el lienzo) ----
    private val clamp = Shader.TileMode.CLAMP
    private val shGlowOrange = RadialGradient(0f, 0f, 1f, intArrayOf(hc(0xAAFFB43C), hc(0x66FF6A1A), hc(0x00FF4A00)), floatArrayOf(0f, 0.4f, 1f), clamp)
    private val shGlowRed = RadialGradient(0f, 0f, 1f, intArrayOf(hc(0xAAFF3A1A), hc(0x55B3000F), hc(0x00800010)), floatArrayOf(0f, 0.45f, 1f), clamp)
    private val shGlowViolet = RadialGradient(0f, 0f, 1f, intArrayOf(hc(0x99C084FC), hc(0x558B3DFF), hc(0x005B21B6)), floatArrayOf(0f, 0.45f, 1f), clamp)
    private val shEye = RadialGradient(0f, 0f, 1f, intArrayOf(hc(0xFFFFF4C2), hc(0xFFFFB02E), hc(0xCCFF3B00), hc(0x00B3001A)), floatArrayOf(0f, 0.3f, 0.65f, 1f), clamp)
    private val shEyeV = RadialGradient(0f, 0f, 1f, intArrayOf(hc(0xFFF3E1FF), hc(0xFFB45CFF), hc(0xCC7A1CFF), hc(0x00300060)), floatArrayOf(0f, 0.3f, 0.65f, 1f), clamp)
    private val shSmoke = RadialGradient(0f, 0f, 1f, intArrayOf(hc(0x66B9A9D8), hc(0x338E7BB8), hc(0x00443366)), floatArrayOf(0f, 0.5f, 1f), clamp)
    private val shBone = RadialGradient(-0.3f, -0.4f, 1.6f, intArrayOf(hc(0xFFF4EEDC), hc(0xFFCFC3A8), hc(0xFF7A6B55)), floatArrayOf(0f, 0.55f, 1f), clamp)
    private val shPumpkin = RadialGradient(-0.25f, -0.3f, 1.4f, intArrayOf(hc(0xFFFFA62B), hc(0xFFE8620C), hc(0xFFA93A06), hc(0xFF5C1A04)), floatArrayOf(0f, 0.4f, 0.8f, 1f), clamp)
    private val shLobe = RadialGradient(0f, -0.1f, 1.2f, intArrayOf(hc(0xFFE86A10), hc(0xFFB8410A), hc(0xFF6B1F05)), floatArrayOf(0f, 0.55f, 1f), clamp)
    private val shBloodTop = LinearGradient(0f, 0f, 0f, 1f, intArrayOf(hc(0xE0A1070B), hc(0x00A1070B)), null, clamp)
    private val shFireHot = RadialGradient(0f, 0f, 1f, intArrayOf(hc(0xFFFFFFE6), hc(0xFFFFD25A), hc(0x77FF8A1F), hc(0x00FF4A00)), floatArrayOf(0f, 0.25f, 0.6f, 1f), clamp)
    private val shFireMid = RadialGradient(0f, 0f, 1f, intArrayOf(hc(0xFFFFB02E), hc(0xCCFF6A1A), hc(0x55D8330A), hc(0x00B3000F)), floatArrayOf(0f, 0.3f, 0.65f, 1f), clamp)
    private val shFireOld = RadialGradient(0f, 0f, 1f, intArrayOf(hc(0xAAFF4A1A), hc(0x66B3140A), hc(0x00500008)), floatArrayOf(0f, 0.5f, 1f), clamp)
    private val shMoon = RadialGradient(-0.3f, -0.3f, 1.4f, intArrayOf(hc(0xFFFFE2A8), hc(0xFFFFB35C), hc(0xFFE0782A)), floatArrayOf(0f, 0.55f, 1f), clamp)
    private val shFog = RadialGradient(0f, 0f, 1f, intArrayOf(hc(0x55A090C8), hc(0x30806AA8), hc(0x00604A88)), floatArrayOf(0f, 0.5f, 1f), clamp)
    private var shVig: Shader? = null
    private var shHaze: Shader? = null
    private var shFloor: Shader? = null
    private var shSky: Shader? = null

    private val BLOOD = hc(0xFFA3070D)
    private val BLOOD_DARK = hc(0xFF5B0309)
    private val BLOOD_HI = hc(0x66FF6B5E)

    // ---- estado de la escena ----
    private val maxEmb = 22
    private var nEmb = 22
    private val ex = FloatArray(maxEmb)
    private val ey = FloatArray(maxEmb)
    private val evy = FloatArray(maxEmb)
    private val eph = FloatArray(maxEmb)
    private val er = FloatArray(maxEmb)
    private val ety = IntArray(maxEmb)

    private val maxSm = 16
    private val sx = FloatArray(maxSm)
    private val sy = FloatArray(maxSm)
    private val sdx = FloatArray(maxSm)
    private val sage = FloatArray(maxSm)
    private val slife = FloatArray(maxSm)
    private val sph = FloatArray(maxSm)
    private var smokeT = 0f

    // fuego de partículas
    private val maxFire = 140
    private val fpx = FloatArray(maxFire)
    private val fpy = FloatArray(maxFire)
    private val fvx = FloatArray(maxFire)
    private val fvy = FloatArray(maxFire)
    private val fage = FloatArray(maxFire)
    private val flife = FloatArray(maxFire)
    private val fsz = FloatArray(maxFire)
    private var fireAcc = 0f
    private var emberAcc = 0f

    private val lurks = arrayOf(HwLurk(), HwLurk(), HwLurk())
    private val pumps = arrayOf(HwPumpkin(), HwPumpkin(), HwPumpkin())
    private val flyer = HwPumpkin()

    private val maxCards = 16
    private val cardF = FloatArray(maxCards * 9)
    private val cardSeed = IntArray(maxCards)
    private var cardN = 0

    // fondo
    private var bgW = 0f
    private var bgH = 0f
    private val groundPath = Path()
    private val stonePath = Path()
    private val boltPath = Path()
    private val treeSeg = FloatArray(5 * 40)
    private var treeN = 0
    private var boltWait = 6f
    private var boltAge = 99f
    private var bgRoot: View? = null
    private var vto: ViewTreeObserver? = null

    // calaveras: anclajes dinámicos para no quedar bajo los botones
    private val skY = FloatArray(2)
    private val skTarget = FloatArray(2)
    private var anchorT = 0f
    private var anchorInit = false
    private val maxBtn = 48
    private val btnF = FloatArray(maxBtn * 4)
    private var btnN = 0

    // luna: grietas y posición del fondo en la ventana
    private val crackPts = FloatArray(15 * 7 * 2)
    private val crackStart = FloatArray(15)
    private val locBuf = IntArray(2)
    private var bgOffX = 0f
    private var bgOffY = 0f

    private val preDraw = ViewTreeObserver.OnPreDrawListener {
        hookBackground()
        true
    }
    private val scrollL = ViewTreeObserver.OnScrollChangedListener {
        if (active) invalidate()
    }

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        pStroke.style = Paint.Style.STROKE
        pStroke.strokeCap = Paint.Cap.ROUND
        pStroke.strokeJoin = Paint.Join.ROUND
        pFire.xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
        pumps[0].wait = 0.8f
        pumps[1].wait = 2.8f
        pumps[2].wait = 5f
        flyer.wait = 4f
        buildCracks()
        for (l in lurks) l.wait = rf(0.5f, 4f)
    }

    private fun rf(a: Float, b: Float): Float = a + (b - a) * rnd.nextFloat()

    // ---------------------------------------------------------------- ciclo
    fun setActive(on: Boolean) {
        active = on
        if (on) {
            readEnv()
            readInsets()
            schedule()
        }
    }

    private fun readEnv() {
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            lite = pm.isPowerSaveMode
            minStepNs = if (lite) 50000000L else 33000000L
            nEmb = if (lite) 10 else maxEmb
        } catch (e: Exception) {
        }
    }

    @Suppress("DEPRECATION")
    private fun readInsets() {
        botInset = try {
            (rootWindowInsets?.stableInsetBottom ?: 0).toFloat()
        } catch (e: Exception) {
            0f
        }
    }

    private fun schedule() {
        if (!active || posted || !isAttachedToWindow) return
        posted = true
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun doFrame(frameTimeNanos: Long) {
        posted = false
        if (!active) return
        if (lastNs == 0L) lastNs = frameTimeNanos
        val el = frameTimeNanos - lastNs
        if (el >= minStepNs) {
            var dt = el / 1000000000f
            if (dt > 0.1f) dt = 0.1f
            lastNs = frameTimeNanos
            t += dt
            step(dt)
            invalidate()
            bgRoot?.invalidate()
        }
        schedule()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        lastNs = 0L
        readInsets()
        val o = viewTreeObserver
        vto = o
        o.addOnPreDrawListener(preDraw)
        o.addOnScrollChangedListener(scrollL)
        if (active) schedule()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        Choreographer.getInstance().removeFrameCallback(this)
        posted = false
        val o = vto
        if (o != null && o.isAlive) {
            o.removeOnPreDrawListener(preDraw)
            o.removeOnScrollChangedListener(scrollL)
        }
        vto = null
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean = false
    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean = false

    // Cambia el degradado azul de cada pantalla por el fondo de Halloween
    private fun hookBackground() {
        try {
            val par = parent as? ViewGroup ?: return
            val content = par.findViewById<ViewGroup>(android.R.id.content) ?: return
            if (content.childCount == 0) return
            val root = content.getChildAt(0)
            val bg = root.background
            if (bg is HwBg) {
                bgRoot = root
            } else if (bg is GradientDrawable) {
                root.background = HwBg(this)
                bgRoot = root
            }
            if (bgRoot === root) {
                root.getLocationInWindow(locBuf)
                bgOffX = locBuf[0].toFloat()
                bgOffY = locBuf[1].toFloat()
            }
        } catch (e: Exception) {
        }
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        W = w.toFloat()
        H = h.toFloat()
        if (W <= 0f || H <= 0f) return
        readInsets()
        val rad = sqrt(W * W + H * H) * 0.62f
        shVig = RadialGradient(W / 2f, H / 2f, rad, intArrayOf(hc(0x00180012), hc(0x00180012), hc(0x70200014)), floatArrayOf(0f, 0.58f, 1f), clamp)
        shHaze = LinearGradient(0f, 0f, 0f, 170f * d, hc(0x336B21A8), hc(0x006B21A8), clamp)
        shFloor = LinearGradient(0f, H, 0f, H - 140f * d, hc(0x30FF5A12), hc(0x00FF5A12), clamp)
        buildWeb()
        for (i in 0 until maxEmb) respawnEmber(i, true)
    }

    private fun buildWeb() {
        val R = 74f * d
        val p = Path()
        val n = 6
        for (i in 0..n) {
            val a = 1.5707964f * i / n
            p.moveTo(0f, 0f)
            p.lineTo(R * cos(a), R * sin(a))
        }
        for (k in 1..4) {
            val rr = R * k / 4f
            for (i in 0 until n) {
                val a1 = 1.5707964f * i / n
                val a2 = 1.5707964f * (i + 1) / n
                val x1 = rr * cos(a1)
                val y1 = rr * sin(a1)
                val x2 = rr * cos(a2)
                val y2 = rr * sin(a2)
                if (i == 0) p.moveTo(x1, y1)
                p.quadTo((x1 + x2) * 0.41f, (y1 + y2) * 0.41f, x2, y2)
            }
        }
        webPath = p
    }

    private fun respawnEmber(i: Int, anywhere: Boolean) {
        ex[i] = rf(0f, max(W, 1f))
        ey[i] = if (anywhere) rf(0f, max(H, 1f)) else H + rf(0f, 40f * d)
        evy[i] = rf(18f, 48f) * d
        eph[i] = rf(0f, TAU)
        er[i] = rf(1.1f, 2.8f) * d
        ety[i] = rnd.nextInt(3)
    }

    // ------------------------------------------------------------ simulación
    private fun step(dt: Float) {
        if (W <= 0f) return
        for (i in 0 until nEmb) {
            ey[i] -= evy[i] * dt
            ex[i] += sin(t * 1.4f + eph[i]) * 14f * d * dt
            if (ey[i] < -12f * d) respawnEmber(i, false)
        }
        updateAnchors(dt)
        stepSmoke(dt)
        stepLurks(dt)
        stepPumpkins(dt)
        stepFlyer(dt)
        stepFire(dt)
        stepBolt(dt)
    }

    private fun skullS(): Float = 34f * d
    private fun skullX(i: Int): Float = if (i == 0) skullS() * 0.55f else W - skullS() * 0.55f
    private fun skullY(i: Int): Float = skY[i]

    // Elige a qué altura se asoma cada calavera: lejos de los botones y de la barra de abajo
    private fun updateAnchors(dt: Float) {
        if (W <= 0f) return
        anchorT += dt
        if (anchorT >= 0.25f || !anchorInit) {
            anchorT = 0f
            val s = skullS()
            val top = H * 0.26f + s * 2.2f
            val bot = H - botInset - s * 1.7f
            val pref0 = H * 0.7f
            val pref1 = H * 0.46f
            var best0 = pref0
            for (k in 0..1) {
                var bestY = if (k == 0) pref0 else pref1
                if (bot > top) {
                    var bestC = Float.MAX_VALUE
                    for (j in 0..13) {
                        val y = top + (bot - top) * j / 13f
                        val x0 = if (k == 0) 0f else W - s * 2.1f
                        val x1 = if (k == 0) s * 2.1f else W
                        val y0 = y - s * 2.2f
                        val y1 = y + s * 1.35f
                        var cost = 0f
                        for (i in 0 until btnN) {
                            val ox = min(x1, btnF[i * 4 + 2]) - max(x0, btnF[i * 4])
                            val oy = min(y1, btnF[i * 4 + 3]) - max(y0, btnF[i * 4 + 1])
                            if (ox > 0f && oy > 0f) cost += ox * oy / (d * d)
                        }
                        cost += abs(y - (if (k == 0) pref0 else pref1)) / H * 300f
                        if (k == 1 && abs(y - best0) < H * 0.22f) cost += 2000f
                        if (cost < bestC) {
                            bestC = cost
                            bestY = y
                        }
                    }
                }
                skTarget[k] = bestY
                if (k == 0) best0 = bestY
            }
            if (!anchorInit) {
                skY[0] = skTarget[0]
                skY[1] = skTarget[1]
                anchorInit = true
            }
        }
        for (k in 0..1) skY[k] += (skTarget[k] - skY[k]) * min(1f, dt * 3f)
    }

    private fun stepSmoke(dt: Float) {
        for (i in 0 until maxSm) {
            if (slife[i] > 0f) {
                sage[i] += dt
                if (sage[i] >= slife[i]) {
                    slife[i] = 0f
                } else {
                    sy[i] -= 26f * d * dt
                    sx[i] += (sin(sage[i] * 2f + sph[i]) * 12f * d + sdx[i]) * dt
                }
            }
        }
        smokeT += dt
        if (smokeT > (if (lite) 0.5f else 0.26f)) {
            smokeT = 0f
            for (k in 0..1) emitSmoke(skullX(k) + rf(-8f, 8f) * d, skullY(k) - skullS() * 1.5f, if (k == 0) 5f * d else -5f * d)
        }
    }

    private fun emitSmoke(x: Float, y: Float, drift: Float) {
        for (i in 0 until maxSm) {
            if (slife[i] <= 0f) {
                sx[i] = x
                sy[i] = y
                sdx[i] = drift
                sage[i] = 0f
                slife[i] = rf(2.4f, 3.8f)
                sph[i] = rf(0f, 6.28f)
                return
            }
        }
    }

    // ---- fuego: partículas suaves que suben, se achican y cambian de color ----
    private fun emitFire(x: Float, y: Float, size: Float, rise: Float, life: Float) {
        for (i in 0 until maxFire) {
            if (flife[i] <= 0f) {
                fpx[i] = x
                fpy[i] = y
                fvx[i] = rf(-14f, 14f) * d
                fvy[i] = -rf(60f, 125f) * d * rise
                fage[i] = 0f
                flife[i] = rf(0.45f, 0.95f) * life
                fsz[i] = size * rf(0.7f, 1.2f)
                return
            }
        }
    }

    private fun emitSkullFire(cx: Float, cy: Float, s: Float, tilt: Float) {
        val th = rf(-155f, -25f) * DEG
        val lx = cos(th) * 0.88f
        val ly = sin(th) * 0.88f
        val tr = tilt * DEG
        val wx = cx + (lx * cos(tr) - ly * sin(tr)) * s
        val wy = cy + (lx * sin(tr) + ly * cos(tr)) * s
        emitFire(wx, wy, s * rf(0.3f, 0.5f), 1f, 1f)
    }

    private fun stepFire(dt: Float) {
        val rate = if (lite) 18f else 34f
        fireAcc += dt * rate
        val s = skullS()
        var guard = 0
        while (fireAcc >= 1f && guard < 6) {
            fireAcc -= 1f
            guard++
            emitSkullFire(skullX(0), skullY(0), s, 14f)
            emitSkullFire(skullX(1), skullY(1), s, -14f)
            if (flyer.state != 0) emitSkullFire(flyer.x, flyer.y, flyer.r, flyer.rot)
        }
        if (fireAcc > 3f) fireAcc = 0f
        // llamita sobre cada calabaza
        emberAcc += dt * (if (lite) 8f else 16f)
        var g2 = 0
        while (emberAcc >= 1f && g2 < 4) {
            emberAcc -= 1f
            g2++
            for (p in pumps) {
                if (p.state != 0 && p.x > -p.r && p.x < W + p.r && p.y > -p.r && p.y < H + p.r) {
                    emitFire(p.x + rf(-0.2f, 0.2f) * p.r, p.y - p.r * 1.15f, p.r * rf(0.14f, 0.22f), 0.55f, 0.7f)
                }
            }
        }
        if (emberAcc > 2f) emberAcc = 0f
        for (i in 0 until maxFire) {
            if (flife[i] > 0f) {
                fage[i] += dt
                if (fage[i] >= flife[i]) {
                    flife[i] = 0f
                } else {
                    fpx[i] += (fvx[i] + sin(fage[i] * 10f + fpx[i] * 0.07f) * 14f * d) * dt
                    fpy[i] += fvy[i] * dt
                    fvy[i] *= (1f - 0.9f * dt)
                }
            }
        }
    }

    private fun stepLurks(dt: Float) {
        val n = if (lite) 1 else 3
        for (k in 0 until n) {
            val l = lurks[k]
            if (l.life <= 0f) {
                l.wait -= dt
                if (l.wait <= 0f) {
                    l.life = rf(3f, 6f)
                    l.age = 0f
                    l.col = rnd.nextInt(3)
                    l.x = if (rnd.nextBoolean()) rf(0.04f, 0.16f) * W else rf(0.84f, 0.96f) * W
                    l.y = rf(0.2f, 0.78f) * H
                    l.sz = rf(0.8f, 1.3f) * d
                    l.ph = rf(0f, 2f)
                }
            } else {
                l.age += dt
                if (l.age >= l.life) {
                    l.life = 0f
                    l.wait = rf(1.5f, 5f)
                }
            }
        }
    }

    private fun stepPumpkins(dt: Float) {
        val maxActive = if (lite) 2 else 3
        var act = 0
        for (k in 0..2) if (pumps[k].state != 0) act++
        for (k in 0..2) {
            val p = pumps[k]
            if (p.state == 0) {
                p.wait -= dt
                if (p.wait <= 0f) {
                    if (act >= maxActive) {
                        p.wait = 1.2f
                    } else {
                        spawnPumpkin(p)
                        act++
                    }
                }
            } else {
                p.tm += dt
                if (p.state == 1) updatePeek(p) else updateRoam(p, dt)
                if (p.laugh > 0f) {
                    p.laugh -= dt
                    p.laughAmt += (1f - p.laughAmt) * min(1f, dt * 10f)
                } else {
                    p.laughAmt += (0f - p.laughAmt) * min(1f, dt * 6f)
                }
                p.open = 0.1f + p.laughAmt * (0.45f + 0.45f * sin(t * 38f + p.ph))
                p.sq = p.laughAmt * 0.06f * sin(t * 38f + p.ph + 1.2f)
                p.rot += p.laughAmt * sin(t * 52f) * 5f
                if (p.tm >= p.dur) {
                    p.state = 0
                    p.wait = rf(1.2f, 4.5f)
                    p.laugh = 0f
                    p.laughAmt = 0f
                }
            }
        }
    }

    private fun spawnPumpkin(p: HwPumpkin) {
        p.tm = 0f
        p.ph = rf(0f, TAU)
        p.r = rf(28f, 42f) * d
        p.laugh = 0f
        p.laughAmt = 0f
        p.laughDone = false
        if (rnd.nextFloat() < 0.55f) {
            p.state = 1
            p.edge = when (rnd.nextInt(6)) {
                0, 2 -> 0
                1, 3 -> 1
                4 -> 2
                else -> 3
            }
            p.along = if (p.edge <= 1) rf(0.14f, 0.86f) else rf(0.22f, 0.78f)
            p.dur = rf(5.8f, 8.2f)
            p.show = rf(0.45f, 0.65f)
            p.baseRot = when (p.edge) {
                0 -> -22f
                1 -> 22f
                2 -> 180f
                else -> rf(-6f, 6f)
            }
        } else {
            p.state = 2
            p.dir = if (rnd.nextBoolean()) 1f else -1f
            p.dur = rf(8f, 13f)
            p.sx = if (p.dir > 0f) -1.8f * p.r else W + 1.8f * p.r
            p.ex = if (p.dir > 0f) W + 1.8f * p.r else -1.8f * p.r
            val rr = rnd.nextFloat()
            p.yb = if (rr < 0.15f) H * rf(-0.01f, 0.02f) else if (rr < 0.30f) H * rf(0.97f, 1f) - botInset * 0.5f else H * rf(0.14f, 0.86f)
            p.amp = rf(10f, 34f) * d
            p.fq = rf(0.9f, 1.7f)
            p.bounce = rnd.nextFloat() < 0.4f
        }
    }

    private fun updatePeek(p: HwPumpkin) {
        val tin = 0.85f
        val tout = 0.7f
        val pr = if (p.tm < tin) hwBack(p.tm / tin) else if (p.tm > p.dur - tout) 1f - hwEase((p.tm - (p.dur - tout)) / tout) else 1f
        val hidden = -1.7f * p.r
        val peekIn = p.r * (2f * p.show - 1f)
        val inset = hidden + (peekIn - hidden) * pr
        val hold = p.tm > tin && p.tm < p.dur - tout
        val lat = if (hold) sin(p.tm * 1.2f + p.ph) * p.r * 0.4f else 0f
        when (p.edge) {
            0 -> {
                p.x = inset
                p.y = H * p.along + lat
            }
            1 -> {
                p.x = W - inset
                p.y = H * p.along + lat
            }
            2 -> {
                p.x = W * p.along + lat
                p.y = inset
            }
            else -> {
                p.x = W * p.along + lat
                p.y = (H - botInset) - inset
            }
        }
        p.rot = p.baseRot + sin(p.tm * 1.7f + p.ph) * 5f * pr
        if (!p.laughDone && p.tm > 1.0f) {
            p.laughDone = true
            p.laugh = rf(1.4f, 2.2f)
        }
    }

    private fun updateRoam(p: HwPumpkin, dt: Float) {
        val u = (p.tm / p.dur).coerceIn(0f, 1f)
        val uu = u + 0.035f * sin(TAU * u * 2f + p.ph)
        p.x = p.sx + (p.ex - p.sx) * uu
        var yy = p.yb + p.amp * sin(p.tm * p.fq + p.ph) + p.amp * 0.35f * sin(p.tm * p.fq * 2.3f)
        if (p.bounce) yy -= abs(sin(p.tm * 3f + p.ph)) * p.r * 0.45f
        p.y = yy
        p.rot = sin(p.tm * p.fq * 1.4f + p.ph) * 11f + p.dir * 7f
        if (p.laugh <= 0f && p.x > p.r * 0.5f && p.x < W - p.r * 0.5f && rnd.nextFloat() < 0.35f * dt) {
            p.laugh = rf(1f, 1.8f)
        }
    }

    // calavera voladora en llamas que cruza la pantalla
    private fun stepFlyer(dt: Float) {
        val p = flyer
        if (p.state == 0) {
            p.wait -= dt
            if (p.wait <= 0f) {
                p.state = 2
                p.tm = 0f
                p.ph = rf(0f, TAU)
                p.r = 30f * d
                p.dir = if (rnd.nextBoolean()) 1f else -1f
                p.dur = rf(7f, 11f)
                p.sx = if (p.dir > 0f) -2.4f * p.r else W + 2.4f * p.r
                p.ex = if (p.dir > 0f) W + 2.4f * p.r else -2.4f * p.r
                p.yb = H * rf(0.18f, 0.7f)
                p.amp = rf(14f, 30f) * d
                p.fq = rf(1f, 1.6f)
                p.bounce = false
            }
        } else {
            p.tm += dt
            updateRoam(p, dt)
            if (p.tm >= p.dur) {
                p.state = 0
                p.wait = rf(10f, 20f)
            }
        }
    }

    private fun stepBolt(dt: Float) {
        if (boltAge < 1f) boltAge += dt
        boltWait -= dt
        if (boltWait <= 0f && bgW > 0f) {
            boltWait = rf(8f, 16f)
            boltAge = 0f
            boltPath.reset()
            var x = rf(0.15f, 0.85f) * bgW
            var y = 0f
            boltPath.moveTo(x, y)
            val segs = 9
            for (i in 1..segs) {
                x += rf(-0.07f, 0.07f) * bgW
                y = bgH * 0.6f * i / segs
                boltPath.lineTo(x, y)
            }
        }
    }

    // ------------------------------------------------------------------ fondo
    private fun buildBg(w: Float, h: Float) {
        bgW = w
        bgH = h
        shSky = LinearGradient(0f, 0f, 0f, h, intArrayOf(hc(0xFF1A0B33), hc(0xFF0E0720), hc(0xFF0A0614), hc(0xFF1C0509)), floatArrayOf(0f, 0.4f, 0.75f, 1f), clamp)
        groundPath.reset()
        groundPath.moveTo(0f, h)
        groundPath.lineTo(0f, h * 0.935f)
        groundPath.cubicTo(w * 0.25f, h * 0.9f, w * 0.45f, h * 0.975f, w * 0.7f, h * 0.925f)
        groundPath.cubicTo(w * 0.85f, h * 0.9f, w * 0.95f, h * 0.94f, w, h * 0.925f)
        groundPath.lineTo(w, h)
        groundPath.close()
        stonePath.reset()
        val sxs = floatArrayOf(0.2f, 0.33f, 0.58f, 0.72f, 0.86f)
        val sys = floatArrayOf(0.918f, 0.935f, 0.925f, 0.945f, 0.915f)
        val sws = floatArrayOf(0.05f, 0.04f, 0.05f, 0.04f, 0.045f)
        for (i in 0..4) {
            val tw = w * sws[i]
            rect.set(w * sxs[i], h * sys[i], w * sxs[i] + tw, h * sys[i] + tw * 1.6f)
            stonePath.addRoundRect(rect, tw * 0.5f, tw * 0.5f, Path.Direction.CW)
        }
        treeN = 0
        addBranch(w * 0.08f, h * 0.95f, -1.5707964f + 0.1f, h * 0.07f, 6f * d, 3, 5)
        addBranch(w * 0.93f, h * 0.95f, -1.5707964f - 0.12f, h * 0.06f, 5f * d, 3, 11)
    }

    private fun addBranch(x: Float, y: Float, ang: Float, len: Float, wid: Float, depth: Int, seed: Int) {
        if (treeN >= 40) return
        val x2 = x + cos(ang) * len
        val y2 = y + sin(ang) * len
        val i = treeN * 5
        treeSeg[i] = x
        treeSeg[i + 1] = y
        treeSeg[i + 2] = x2
        treeSeg[i + 3] = y2
        treeSeg[i + 4] = wid
        treeN++
        if (depth <= 0) return
        val a1 = ang - (0.35f + 0.5f * hwHash(seed, depth * 3))
        val a2 = ang + (0.3f + 0.5f * hwHash(seed, depth * 3 + 1))
        addBranch(x2, y2, a1, len * (0.68f + 0.15f * hwHash(seed, depth * 3 + 2)), wid * 0.66f, depth - 1, seed + 7)
        addBranch(x2, y2, a2, len * 0.66f, wid * 0.62f, depth - 1, seed + 13)
    }

    private fun drawBat(c: Canvas, bx: Float, by: Float, s: Float, fl: Float) {
        c.save()
        c.translate(bx, by)
        pFill.shader = null
        pFill.color = hc(0xFF08040E)
        rect.set(-0.35f * s, -0.5f * s, 0.35f * s, 0.6f * s)
        c.drawOval(rect, pFill)
        for (side in 0..1) {
            c.save()
            if (side == 1) c.scale(-1f, 1f)
            tmp.reset()
            tmp.moveTo(0.2f * s, -0.2f * s)
            tmp.quadTo(1f * s, -0.9f * s * fl - 0.5f * s, 2f * s, -0.7f * s * fl + 0.1f * s)
            tmp.lineTo(1.55f * s, 0.3f * s)
            tmp.lineTo(1.2f * s, 0.15f * s)
            tmp.lineTo(0.9f * s, 0.45f * s)
            tmp.lineTo(0.3f * s, 0.3f * s)
            tmp.close()
            c.drawPath(tmp, pFill)
            c.restore()
        }
        pFill.color = hc(0xFFFF3030)
        c.drawCircle(-0.15f * s, -0.3f * s, 0.08f * s, pFill)
        c.drawCircle(0.15f * s, -0.3f * s, 0.08f * s, pFill)
        c.restore()
    }

    fun drawBackground(c: Canvas, w: Float, h: Float) {
        if (w <= 0f || h <= 0f) return
        if (w != bgW || h != bgH) buildBg(w, h)
        // cielo
        pBg.shader = shSky
        pBg.alpha = 255
        c.drawRect(0f, 0f, w, h, pBg)
        pBg.shader = null
        val ct = t % CYC
        val eBlast = if (ct >= BLAST_T) ct - BLAST_T else -1f
        c.save()
        if (eBlast >= 0f && eBlast < 0.8f) {
            val sk = 1f - eBlast / 0.8f
            c.translate(sin(t * 83f) * 9f * d * sk, cos(t * 71f) * 7f * d * sk)
        }
        // estrellas
        pFill.shader = null
        for (i in 0 until 46) {
            val tw = 0.4f + 0.6f * sin(t * (0.8f + hwHash(i, 3) * 2f) + i)
            pFill.color = hc(0xFFE6D8FF)
            pFill.alpha = (tw.coerceIn(0f, 1f) * 190f).toInt()
            c.drawCircle(hwHash(i, 1) * w, hwHash(i, 2) * h * 0.6f, (0.6f + 1f * hwHash(i, 4)) * d, pFill)
        }
        drawMoon(c, w, h, ct)
        // nubes oscuras
        for (k in 0..1) {
            val span = w + 260f * d
            val cx = ((t * (6f + k * 4f) * d + k * w * 0.6f) % span) - 130f * d
            val cy = h * (0.13f + 0.09f * k)
            pFill.color = hc(0x8A1A0F2E)
            rect.set(cx - 70f * d, cy - 10f * d, cx + 70f * d, cy + 12f * d)
            c.drawOval(rect, pFill)
            rect.set(cx - 40f * d, cy - 20f * d, cx + 30f * d, cy + 6f * d)
            c.drawOval(rect, pFill)
            rect.set(cx - 10f * d, cy - 14f * d, cx + 60f * d, cy + 10f * d)
            c.drawOval(rect, pFill)
        }
        // murciélagos
        for (i in 0..4) {
            val sp = (28f + 22f * hwHash(i, 11)) * d
            val span = w + 160f * d
            var bx = (t * sp + hwHash(i, 12) * span) % span
            if (i % 2 == 1) bx = span - bx
            bx -= 80f * d
            val by = h * (0.07f + 0.4f * hwHash(i, 13)) + sin(t * 1.6f + i * 1.7f) * 16f * d
            val s = (7f + 7f * hwHash(i, 14)) * d
            drawBat(c, bx, by, s, sin(t * (11f + i) + i))
        }
        if (eBlast >= 0f) drawBlast(c, w, h, eBlast)
        // relámpago
        if (boltAge < 0.5f) {
            val k = if (boltAge < 0.08f) 1f else if (boltAge < 0.14f) 0.2f else if (boltAge < 0.22f) 0.9f else max(0f, 1f - (boltAge - 0.22f) / 0.28f)
            pFill.shader = null
            pFill.color = hc(0xFFD8C8FF)
            pFill.alpha = (k * 80f).toInt()
            c.drawRect(0f, 0f, w, h, pFill)
            pStroke.shader = null
            pStroke.color = hc(0xFFEDE4FF)
            pStroke.alpha = (k * 90f).toInt()
            pStroke.strokeWidth = 7f * d
            c.drawPath(boltPath, pStroke)
            pStroke.color = hc(0xFFFFFFFF)
            pStroke.alpha = (k * 255f).toInt()
            pStroke.strokeWidth = 2.2f * d
            c.drawPath(boltPath, pStroke)
        }
        // cementerio y árboles muertos
        pFill.shader = null
        pFill.color = hc(0xF006020C)
        c.drawPath(groundPath, pFill)
        c.drawPath(stonePath, pFill)
        pStroke.shader = null
        pStroke.color = hc(0xF006020C)
        for (i in 0 until treeN) {
            val j = i * 5
            pStroke.strokeWidth = treeSeg[j + 4]
            c.drawLine(treeSeg[j], treeSeg[j + 1], treeSeg[j + 2], treeSeg[j + 3], pStroke)
        }
        // niebla
        pGlow.shader = shFog
        for (k in 0..3) {
            val fx = w * (0.2f + 0.2f * k) + sin(t * 0.25f + k * 1.7f) * w * 0.15f
            val fy = h * (0.84f + 0.04f * k)
            c.save()
            c.translate(fx, fy)
            c.scale(w * 0.45f, h * 0.07f)
            pGlow.alpha = (200f * (0.7f + 0.3f * sin(t * 0.5f + k))).toInt().coerceIn(0, 255)
            c.drawCircle(0f, 0f, 1f, pGlow)
            c.restore()
        }
        c.restore()
    }

    // ------------------------------------------------------------ luna y explosión
    private fun buildCracks() {
        for (i in 0 until 10) {
            val a = i * TAU / 10f + (hwHash(i, 91) - 0.5f) * 0.5f
            val r0 = 0.04f + 0.2f * hwHash(i, 92)
            genCrack(i, cos(a) * r0, sin(a) * r0, a, 0.95f - r0, i * 17)
            crackStart[i] = 0.5f * hwHash(i, 93)
        }
        for (b in 0 until 5) {
            val parent = b * 2
            val px = crackPts[(parent * 7 + 3) * 2]
            val py = crackPts[(parent * 7 + 3) * 2 + 1]
            val pa = atan2(py - crackPts[(parent * 7 + 2) * 2 + 1], px - crackPts[(parent * 7 + 2) * 2])
            val ang = pa + (if (b % 2 == 0) 0.7f else -0.7f)
            genCrack(10 + b, px, py, ang, 0.55f, 200 + b * 13)
            crackStart[10 + b] = 0.35f + 0.3f * hwHash(b, 94)
        }
    }

    private fun genCrack(idx: Int, sx0: Float, sy0: Float, ang: Float, len: Float, seed: Int) {
        var x = sx0
        var y = sy0
        var a = ang
        for (k in 0..6) {
            crackPts[(idx * 7 + k) * 2] = x
            crackPts[(idx * 7 + k) * 2 + 1] = y
            a += (hwHash(seed + k, 95) - 0.5f) * 0.9f
            x += cos(a) * len / 6f
            y += sin(a) * len / 6f
        }
    }

    private fun drawMoon(c: Canvas, w: Float, h: Float, ct: Float) {
        val mr = 34f * d
        val crackP = if (ct < CRACK_T0) 0f else if (ct < CRACK_T1) (ct - CRACK_T0) / (CRACK_T1 - CRACK_T0) else 1f
        val charge = if (ct >= CRACK_T1 && ct < BLAST_T) (ct - CRACK_T1) / (BLAST_T - CRACK_T1) else 0f
        val e = if (ct >= BLAST_T) ct - BLAST_T else -1f
        val reb = if (e >= 6.5f) ((e - 6.5f) / 3.9f).coerceIn(0f, 1f) else 0f
        if (e >= 0f && reb <= 0f) return
        val intensity = crackP * crackP
        val pulse = sin(t * (0.9f + 6f * crackP + 8f * charge))
        var mx = w * 0.78f
        var my = h * 0.17f
        if (e < 0f && crackP > 0.25f) {
            val amp = (0.3f + 2.2f * intensity + 4f * charge) * d
            mx += sin(t * 61f) * amp
            my += cos(t * 47f) * amp
        }
        val sc = if (e < 0f) 1f else 0.55f + 0.45f * hwEase(reb)
        val al = if (e < 0f) 1f else hwEase(reb)
        // halo
        c.save()
        c.translate(mx, my)
        val hr = mr * (3.4f + 1.2f * intensity + 2f * charge) * sc
        c.scale(hr, hr)
        pGlow.shader = shGlowOrange
        pGlow.alpha = ((0.75f + 0.2f * pulse) * al * 255f).toInt().coerceIn(0, 255)
        c.drawCircle(0f, 0f, 1f, pGlow)
        c.restore()
        if (crackP > 0.3f || charge > 0f) {
            c.save()
            c.translate(mx, my)
            c.scale(mr * (1.8f + 1.5f * charge), mr * (1.8f + 1.5f * charge))
            pFire.shader = shFireHot
            pFire.alpha = ((intensity * 0.7f + charge * 0.3f) * (0.7f + 0.3f * pulse) * al * 255f).toInt().coerceIn(0, 255)
            c.drawCircle(0f, 0f, 1f, pFire)
            c.restore()
        }
        // disco
        c.save()
        c.translate(mx, my)
        c.scale(mr * sc, mr * sc)
        pFill.shader = shMoon
        pFill.alpha = (al * 255f).toInt().coerceIn(0, 255)
        c.drawCircle(0f, 0f, 1f, pFill)
        pFill.shader = null
        pFill.color = hc(0x30B4601C)
        c.drawCircle(-0.3f, -0.2f, 0.22f, pFill)
        c.drawCircle(0.35f, 0.1f, 0.28f, pFill)
        c.drawCircle(-0.05f, 0.45f, 0.16f, pFill)
        // grietas que brillan
        if (e < 0f && crackP > 0f) {
            val gl = (0.35f + 0.65f * crackP + 0.3f * charge) * (0.8f + 0.2f * pulse)
            for (i in 0 until 15) {
                val g = ((crackP - crackStart[i]) / 0.3f).coerceIn(0f, 1f)
                if (g <= 0f) continue
                val total = g * 6f
                val full = total.toInt()
                tmp.reset()
                tmp.moveTo(crackPts[i * 14], crackPts[i * 14 + 1])
                for (k in 1..min(full, 6)) tmp.lineTo(crackPts[(i * 7 + k) * 2], crackPts[(i * 7 + k) * 2 + 1])
                if (full < 6) {
                    val fr = total - full
                    val a0 = (i * 7 + full) * 2
                    val b0 = (i * 7 + full + 1) * 2
                    tmp.lineTo(crackPts[a0] + (crackPts[b0] - crackPts[a0]) * fr, crackPts[a0 + 1] + (crackPts[b0 + 1] - crackPts[a0 + 1]) * fr)
                }
                pStroke.shader = null
                pStroke.color = hc(0xCC2A0E00)
                pStroke.strokeWidth = 3.4f * d / mr
                c.drawPath(tmp, pStroke)
                pStroke.color = hc(0xFFFFB43C)
                pStroke.alpha = (gl * 0.55f * 255f).toInt().coerceIn(0, 255)
                pStroke.strokeWidth = 6f * d / mr
                c.drawPath(tmp, pStroke)
                pStroke.color = hc(0xFFFFF1B8)
                pStroke.alpha = (gl * 255f).toInt().coerceIn(0, 255)
                pStroke.strokeWidth = 1.7f * d / mr
                c.drawPath(tmp, pStroke)
            }
            // energía que se escapa por las grietas
            pFill.shader = null
            for (j in 0 until 14) {
                val ci = j % 10
                val u = (t * 0.9f + hwHash(j, 96)) % 1f
                val ex0 = crackPts[(ci * 7 + 6) * 2]
                val ey0 = crackPts[(ci * 7 + 6) * 2 + 1]
                pFill.color = hc(0xFFFFE08A)
                pFill.alpha = ((1f - u) * intensity * 255f).toInt().coerceIn(0, 255)
                c.drawCircle(ex0 * (1f + u * 0.5f), ey0 * (1f + u * 0.5f), 0.05f * (1f - u), pFill)
            }
        }
        // sobrecalentamiento antes de estallar
        if (charge > 0f) {
            pFire.shader = shFireHot
            pFire.alpha = (charge * 255f).toInt().coerceIn(0, 255)
            c.drawCircle(0f, 0f, 1.05f, pFire)
        }
        c.restore()
    }

    private fun drawBlast(c: Canvas, w: Float, h: Float, e: Float) {
        val mx = w * 0.78f
        val my = h * 0.17f
        val mr = 34f * d
        // nebulosa de energía
        if (e < 7.5f) {
            val na = if (e < 0.4f) e / 0.4f else max(0f, 1f - (e - 0.4f) / 7f)
            val ns = mr * (2.5f + 7f * (1f - exp(-0.9f * e)))
            c.save()
            c.translate(mx, my)
            c.scale(ns, ns)
            pGlow.shader = shGlowViolet
            pGlow.alpha = (na * 0.8f * 255f).toInt().coerceIn(0, 255)
            c.drawCircle(0f, 0f, 1f, pGlow)
            c.scale(0.6f, 0.6f)
            pGlow.shader = shGlowOrange
            pGlow.alpha = (na * 255f).toInt().coerceIn(0, 255)
            c.drawCircle(0f, 0f, 1f, pGlow)
            c.restore()
        }
        // núcleo que queda brillando
        if (e < 6.5f) {
            val ac = (1f - e / 6.5f) * 0.55f
            val cr = mr * (0.4f + 0.2f * sin(t * 3f))
            c.save()
            c.translate(mx, my)
            c.scale(cr, cr)
            pFire.shader = shFireMid
            pFire.alpha = (ac * 255f).toInt().coerceIn(0, 255)
            c.drawCircle(0f, 0f, 1f, pFire)
            c.restore()
        }
        // rayos de luz
        if (e < 1.6f) {
            val ra = 1f - e / 1.6f
            val rl = mr * 10f * (1f - exp(-3.5f * e))
            pFire.shader = null
            pFire.color = hc(0xFFFFD27A)
            pFire.alpha = (ra * ra * 200f).toInt().coerceIn(0, 255)
            val rot = e * 0.6f
            for (i in 0 until 14) {
                val a = rot + i * TAU / 14f
                val wd = 0.045f * (1f + (i % 3) * 0.5f)
                val len = rl * (0.6f + 0.4f * hwHash(i, 97))
                tmp.reset()
                tmp.moveTo(mx, my)
                tmp.lineTo(mx + cos(a - wd) * len, my + sin(a - wd) * len)
                tmp.lineTo(mx + cos(a + wd) * len, my + sin(a + wd) * len)
                tmp.close()
                c.drawPath(tmp, pFire)
            }
        }
        // ondas de choque
        for (r in 0..2) {
            val ee = e - r * 0.12f
            if (ee < 0f || ee > 2.2f) continue
            val u = ee / 2.2f
            val eo = 1f - (1f - u) * (1f - u) * (1f - u)
            val rad = mr * (0.8f + 12f * eo)
            val a = (1f - u) * (1f - u)
            val wd = (10f - 8f * u) * d
            pStroke.shader = null
            pStroke.color = if (r == 1) hc(0xFFB45CFF) else hc(0xFFFF9A2E)
            pStroke.alpha = (a * 0.35f * 255f).toInt().coerceIn(0, 255)
            pStroke.strokeWidth = wd * 2.5f
            c.drawCircle(mx, my, rad, pStroke)
            pStroke.color = hc(0xFFFFF4D0)
            pStroke.alpha = (a * 255f).toInt().coerceIn(0, 255)
            pStroke.strokeWidth = wd
            c.drawCircle(mx, my, rad, pStroke)
        }
        // fragmentos de la luna
        if (e < 6.2f) {
            val heat = max(0f, 1f - e / 3.2f)
            val fade = if (e < 3.5f) 1f else max(0f, 1f - (e - 3.5f) / 2.7f)
            for (i in 0 until 16) {
                val big = i < 6
                val ang0 = hwHash(i, 101) * TAU
                val r0 = (if (big) 0.25f else 0.6f) * mr * hwHash(i, 102)
                val dirA = ang0 + (hwHash(i, 103) - 0.5f) * 0.8f
                val speed = (if (big) 150f else 260f + 260f * hwHash(i, 104)) * d
                val travel = speed * (1f - exp(-1.5f * e)) / 1.5f
                val px = mx + cos(ang0) * r0 + cos(dirA) * travel
                val py = my + sin(ang0) * r0 + sin(dirA) * travel + 16f * d * e * e
                val sz = mr * (if (big) 0.42f + 0.2f * hwHash(i, 105) else 0.14f + 0.12f * hwHash(i, 106))
                val rot = (hwHash(i, 107) - 0.5f) * 16f * e * 57.2958f
                c.save()
                c.translate(px, py)
                c.rotate(rot)
                if (big && heat > 0f) {
                    c.save()
                    c.scale(sz * 2.2f, sz * 2.2f)
                    pFire.shader = shFireHot
                    pFire.alpha = (heat * fade * 170f).toInt().coerceIn(0, 255)
                    c.drawCircle(0f, 0f, 1f, pFire)
                    c.restore()
                }
                tmp.reset()
                for (v in 0..3) {
                    val av = v * TAU / 4f + (hwHash(i * 4 + v, 108) - 0.5f) * 1.3f
                    val rv = sz * (0.65f + 0.35f * hwHash(i * 4 + v, 109))
                    if (v == 0) tmp.moveTo(cos(av) * rv, sin(av) * rv) else tmp.lineTo(cos(av) * rv, sin(av) * rv)
                }
                tmp.close()
                pFill.shader = null
                pFill.color = hwLerp(hc(0xFF5A3418), hc(0xFFFFC27A), heat)
                pFill.alpha = (fade * 255f).toInt().coerceIn(0, 255)
                c.drawPath(tmp, pFill)
                pStroke.shader = null
                pStroke.color = hwLerp(hc(0xFFB43A0A), hc(0xFFFFF0C0), heat)
                pStroke.alpha = (fade * 255f).toInt().coerceIn(0, 255)
                pStroke.strokeWidth = (1.6f + 2f * heat) * d
                c.drawPath(tmp, pStroke)
                c.restore()
            }
        }
        // chispas de energía
        if (e < 3.8f) {
            for (i in 0 until 40) {
                val life = 1.2f + 2.4f * hwHash(i, 111)
                if (e > life) continue
                val u = e / life
                val a = hwHash(i, 112) * TAU
                val sp = (220f + 420f * hwHash(i, 113)) * d
                val trav = sp * (1f - exp(-1.8f * e)) / 1.8f
                val tt = max(0f, e - 0.05f)
                val trav2 = sp * (1f - exp(-1.8f * tt)) / 1.8f
                pStroke.shader = null
                pStroke.color = hwLerp(hc(0xFFFFF4D0), hc(0xFFFF4A1A), u)
                pStroke.alpha = ((1f - u) * 255f).toInt().coerceIn(0, 255)
                pStroke.strokeWidth = (2.4f - 1.4f * u) * d
                c.drawLine(mx + cos(a) * trav2, my + sin(a) * trav2 + 30f * d * tt * tt, mx + cos(a) * trav, my + sin(a) * trav + 30f * d * e * e, pStroke)
            }
        }
        // renacimiento: la energía vuelve a juntarse en la luna
        if (e >= 6.5f) {
            val reb = ((e - 6.5f) / 3.9f).coerceIn(0f, 1f)
            pFill.shader = null
            for (i in 0 until 28) {
                val a = hwHash(i, 120) * TAU
                val r0 = mr * (3f + 5f * hwHash(i, 121))
                val q = hwEase(min(1f, max(0f, reb * 1.3f - hwHash(i, 122) * 0.3f)))
                val dist = r0 * (1f - q)
                pFill.color = hc(0xFFE9D6FF)
                pFill.alpha = (q * (1f - q) * 4f * 255f).toInt().coerceIn(0, 255)
                c.drawCircle(mx + cos(a) * dist, my + sin(a) * dist, 2.2f * d, pFill)
            }
            if (reb > 0.85f) {
                val u = (reb - 0.85f) / 0.15f
                pStroke.shader = null
                pStroke.color = hc(0xFFFFE2A8)
                pStroke.alpha = ((1f - u) * 200f).toInt().coerceIn(0, 255)
                pStroke.strokeWidth = 3f * d
                c.drawCircle(mx, my, mr * (1f + u * 2.5f), pStroke)
            }
        }
    }

    // destello y onda que cruzan toda la pantalla (sin tapar nada)
    private fun drawBlastOverlay(c: Canvas) {
        val ct = t % CYC
        if (ct < BLAST_T) return
        val e = ct - BLAST_T
        if (e < 0.6f) {
            val k = 1f - e / 0.6f
            pFill.shader = null
            pFill.color = hc(0xFFFFE6C0)
            pFill.alpha = (k * k * 70f).toInt().coerceIn(0, 255)
            c.drawRect(0f, 0f, W, H, pFill)
        }
        if (e < 1.4f && bgRoot != null) {
            val u = e / 1.4f
            val rad = W * 1.3f * (1f - (1f - u) * (1f - u))
            pStroke.shader = null
            pStroke.color = hc(0xFFFFD27A)
            pStroke.alpha = ((1f - u) * 110f).toInt().coerceIn(0, 255)
            pStroke.strokeWidth = 3f * d
            c.drawCircle(bgOffX + bgW * 0.78f, bgOffY + bgH * 0.17f, rad, pStroke)
        }
    }

    // ------------------------------------------------------------------ dibujo
    override fun onDraw(canvas: Canvas) {
        if (W <= 0f || H <= 0f) return
        val c = canvas
        pBg.shader = shVig
        c.drawRect(0f, 0f, W, H, pBg)
        pBg.shader = shHaze
        c.drawRect(0f, 0f, W, 170f * d, pBg)
        pBg.shader = shFloor
        pBg.alpha = ((0.75f + 0.25f * sin(t * 2.3f)) * 255f).toInt()
        c.drawRect(0f, H - 140f * d, W, H, pBg)
        pBg.alpha = 255
        drawWebs(c)
        drawEmbers(c)
        cardN = 0
        btnN = 0
        val par = parent as? ViewGroup
        if (par != null) {
            for (i in 0 until par.childCount) {
                val ch = par.getChildAt(i)
                if (ch !== this) scan(ch, 0f, 0f, 0f, 0f, W, H)
            }
        }
        drawBlood(c)
        drawSmoke(c)
        for (k in 0..1) {
            drawSkullBody(c, skullX(k), skullY(k) + sin(t * 1.3f + k * 2f) * 1.5f * d, skullS(), if (k == 0) 14f else -14f, k * 1.9f, k, skullJaw(k * 1.9f))
        }
        if (flyer.state != 0) {
            drawSkullBody(c, flyer.x, flyer.y, flyer.r, flyer.rot, flyer.ph, 2, 0.08f + 0.08f * sin(t * 9f))
        }
        drawFire(c)
        drawLurks(c)
        for (p in pumps) if (p.state != 0) drawPumpkin(c, p)
        drawBlastOverlay(c)
    }

    private fun skullJaw(ph: Float): Float {
        val j = sin(t * 2.3f + ph)
        return if (j > 0.7f) (j - 0.7f) * 0.5f else 0f
    }

    private fun drawWebs(c: Canvas) {
        pStroke.shader = null
        pStroke.color = hc(0x38FFFFFF)
        pStroke.strokeWidth = 1f * d
        c.drawPath(webPath, pStroke)
        c.save()
        c.translate(W, 0f)
        c.scale(-1f, 1f)
        c.drawPath(webPath, pStroke)
        c.restore()
    }

    private fun drawEmbers(c: Canvas) {
        pFill.shader = null
        for (i in 0 until nEmb) {
            val fl = 0.55f + 0.45f * sin(t * (4f + (i % 5)) + eph[i])
            val col = when (ety[i]) {
                0 -> hc(0xFFFF8A1F)
                1 -> hc(0xFFB26BFF)
                else -> hc(0xFFFF3B30)
            }
            pFill.color = col
            pFill.alpha = (fl * 55f).toInt()
            c.drawCircle(ex[i], ey[i], er[i] * 3.2f, pFill)
            pFill.alpha = (fl * 215f).toInt()
            c.drawCircle(ex[i], ey[i], er[i], pFill)
        }
    }

    private fun drawSmoke(c: Canvas) {
        pGlow.shader = shSmoke
        for (i in 0 until maxSm) {
            if (slife[i] <= 0f) continue
            val u = sage[i] / slife[i]
            val a = (1f - u) * (1f - u) * 0.9f
            val sz = (8f + 24f * u) * d
            c.save()
            c.translate(sx[i], sy[i])
            c.scale(sz, sz)
            pGlow.alpha = (a * 255f).toInt().coerceIn(0, 255)
            c.drawCircle(0f, 0f, 1f, pGlow)
            c.restore()
        }
    }

    private fun drawFire(c: Canvas) {
        for (i in 0 until maxFire) {
            if (flife[i] <= 0f) continue
            val u = fage[i] / flife[i]
            pFire.shader = if (u < 0.28f) shFireHot else if (u < 0.62f) shFireMid else shFireOld
            pFire.alpha = ((1f - u) * (1f - u * 0.3f) * 255f).toInt().coerceIn(0, 255)
            val r = fsz[i] * (1f - 0.75f * u)
            c.save()
            c.translate(fpx[i], fpy[i])
            c.scale(r, r)
            c.drawCircle(0f, 0f, 1f, pFire)
            c.restore()
        }
    }

    private fun drawSkullBody(c: Canvas, cx: Float, cy: Float, s: Float, tilt: Float, ph: Float, k: Int, jaw: Float) {
        if (cx < -3f * s || cx > W + 3f * s || cy < -3f * s || cy > H + 3f * s) return
        // resplandor
        c.save()
        c.translate(cx, cy)
        c.scale(s * 2.6f, s * 2.6f)
        pGlow.shader = when (k) {
            0 -> shGlowRed
            1 -> shGlowViolet
            else -> shGlowOrange
        }
        pGlow.alpha = ((0.55f + 0.3f * sin(t * 2.6f + ph)) * 255f).toInt()
        c.drawCircle(0f, 0f, 1f, pGlow)
        c.restore()
        // calavera
        c.save()
        c.translate(cx, cy)
        c.rotate(tilt)
        c.scale(s, s)
        // boca oscura detrás de la mandíbula
        pFill.shader = null
        pFill.color = hc(0xFF120A14)
        rect.set(-0.46f, 0.5f, 0.46f, 0.66f + jaw)
        c.drawRect(rect, pFill)
        pFill.shader = shBone
        pFill.alpha = 255
        rect.set(-0.56f, 0.5f + jaw, 0.56f, 1.32f + jaw)
        c.drawRoundRect(rect, 0.24f, 0.24f, pFill)
        c.drawCircle(0f, 0f, 1f, pFill)
        pStroke.shader = null
        pStroke.color = hc(0xAA2A2118)
        pStroke.strokeWidth = 0.035f
        c.drawCircle(0f, 0f, 1f, pStroke)
        // mejillas
        pFill.shader = null
        pFill.color = hc(0x30000000)
        rect.set(-0.62f, 0.3f, -0.18f, 0.62f)
        c.drawOval(rect, pFill)
        rect.set(0.18f, 0.3f, 0.62f, 0.62f)
        c.drawOval(rect, pFill)
        // cuencas
        pFill.color = hc(0xFF120A14)
        rect.set(-0.62f, -0.22f, -0.14f, 0.32f)
        c.drawOval(rect, pFill)
        rect.set(0.14f, -0.22f, 0.62f, 0.32f)
        c.drawOval(rect, pFill)
        // ojos que brillan
        val ea = ((0.7f + 0.3f * sin(t * 3f + ph)) * 255f).toInt()
        for (side in 0..1) {
            c.save()
            c.translate(if (side == 0) -0.38f else 0.38f, 0.05f)
            c.scale(0.36f, 0.36f)
            pGlow.shader = if (k == 1) shEyeV else shEye
            pGlow.alpha = ea
            c.drawCircle(0f, 0f, 1f, pGlow)
            c.restore()
        }
        // nariz
        pFill.shader = null
        pFill.color = hc(0xFF120A14)
        tmp.reset()
        tmp.moveTo(0f, 0.3f)
        tmp.lineTo(-0.1f, 0.54f)
        tmp.lineTo(0.1f, 0.54f)
        tmp.close()
        c.drawPath(tmp, pFill)
        // dientes
        pStroke.color = hc(0xCC2A2118)
        pStroke.strokeWidth = 0.035f
        for (i in 0..4) {
            val x = -0.4f + i * 0.2f
            c.drawLine(x, 0.8f + jaw, x, 1.26f + jaw, pStroke)
        }
        c.drawLine(-0.5f, 0.92f + jaw, 0.5f, 0.92f + jaw, pStroke)
        // grieta y sangre
        c.drawLine(0.2f, -0.95f, 0.06f, -0.55f, pStroke)
        val bl = 0.3f + 0.08f * sin(t * 1.1f + ph)
        pStroke.color = BLOOD
        pStroke.strokeWidth = 0.05f
        c.drawLine(-0.4f, 0.3f, -0.42f, 0.3f + bl, pStroke)
        pFill.color = BLOOD
        c.drawCircle(-0.42f, 0.3f + bl, 0.05f, pFill)
        c.restore()
    }

    private fun drawLurks(c: Canvas) {
        val n = if (lite) 1 else 3
        for (k in 0 until n) {
            val l = lurks[k]
            if (l.life <= 0f) continue
            val u = l.age / l.life
            val a = min(1f, u / 0.15f) * min(1f, (1f - u) / 0.2f)
            val blink = if (((l.age + l.ph) % 2.3f) < 0.14f) 0.12f else 1f
            val dx = 11f * l.sz
            for (side in 0..1) {
                val x = l.x + (if (side == 0) -dx else dx)
                c.save()
                c.translate(x, l.y)
                c.scale(13f * l.sz, 13f * l.sz)
                pGlow.shader = when (l.col) {
                    0 -> shGlowRed
                    1 -> shGlowOrange
                    else -> shGlowViolet
                }
                pGlow.alpha = (a * 230f).toInt().coerceIn(0, 255)
                c.drawCircle(0f, 0f, 1f, pGlow)
                c.restore()
                pFill.shader = null
                pFill.color = when (l.col) {
                    0 -> hc(0xFFFF4B3A)
                    1 -> hc(0xFFFFC23A)
                    else -> hc(0xFFD6A8FF)
                }
                pFill.alpha = (a * 255f).toInt().coerceIn(0, 255)
                val ew = 5.2f * l.sz
                val eh = 2.6f * l.sz * blink
                c.drawOval(x - ew, l.y - eh, x + ew, l.y + eh, pFill)
                pFill.color = hc(0xFF14060A)
                pFill.alpha = (a * 255f).toInt().coerceIn(0, 255)
                c.drawRect(x - 0.6f * l.sz, l.y - eh, x + 0.6f * l.sz, l.y + eh, pFill)
            }
        }
    }

    // ---------------------------------------------------------------- calabazas
    private fun drawPumpkin(c: Canvas, p: HwPumpkin) {
        if (p.x < -3f * p.r || p.x > W + 3f * p.r || p.y < -3f * p.r || p.y > H + 3f * p.r) return
        val glowA = (0.55f + 0.25f * sin(t * 2.2f + p.ph) + p.laughAmt * 0.35f).coerceIn(0f, 1f)
        c.save()
        c.translate(p.x, p.y)
        c.save()
        c.scale(p.r * 2.3f, p.r * 2.3f)
        pGlow.shader = shGlowOrange
        pGlow.alpha = (glowA * 255f).toInt()
        c.drawCircle(0f, 0f, 1f, pGlow)
        c.restore()
        c.rotate(p.rot)
        c.scale(p.r * (1f + p.sq), p.r * (1f - p.sq))
        // gajos
        pFill.alpha = 255
        pFill.shader = shLobe
        rect.set(-1.05f, -0.82f, -0.2f, 0.88f)
        c.drawOval(rect, pFill)
        rect.set(0.2f, -0.82f, 1.05f, 0.88f)
        c.drawOval(rect, pFill)
        pFill.shader = shPumpkin
        rect.set(-0.8f, -0.88f, 0.05f, 0.93f)
        c.drawOval(rect, pFill)
        rect.set(-0.05f, -0.88f, 0.8f, 0.93f)
        c.drawOval(rect, pFill)
        rect.set(-0.5f, -0.9f, 0.5f, 0.95f)
        c.drawOval(rect, pFill)
        // surcos
        pStroke.shader = null
        pStroke.color = hc(0x66420F02)
        pStroke.strokeWidth = 0.04f
        for (sgn in 0..1) {
            val f = if (sgn == 0) -1f else 1f
            for (q in 0..1) {
                val xx = if (q == 0) 0.28f else 0.62f
                tmp.reset()
                tmp.moveTo(f * xx, -0.86f)
                tmp.quadTo(f * xx * 1.12f, 0.05f, f * xx, 0.9f)
                c.drawPath(tmp, pStroke)
            }
        }
        // brillo
        pFill.shader = null
        pFill.color = hc(0x33FFFFFF)
        rect.set(-0.62f, -0.62f, -0.2f, -0.28f)
        c.drawOval(rect, pFill)
        // tallo
        pFill.color = hc(0xFF3F5A1E)
        tmp.reset()
        tmp.moveTo(-0.12f, -0.86f)
        tmp.lineTo(-0.16f, -1.18f)
        tmp.quadTo(-0.02f, -1.28f, 0.12f, -1.12f)
        tmp.lineTo(0.14f, -0.86f)
        tmp.close()
        c.drawPath(tmp, pFill)
        // ojos
        val squint = 1f - p.laughAmt * 0.35f
        for (side in 0..1) {
            val f = if (side == 0) -1f else 1f
            c.save()
            c.translate(f * 0.34f, -0.22f)
            c.save()
            c.scale(0.5f, 0.5f)
            pGlow.shader = shEye
            pGlow.alpha = (glowA * 255f).toInt()
            c.drawCircle(0f, 0f, 1f, pGlow)
            c.restore()
            c.scale(1f, squint)
            pFill.shader = null
            pFill.color = hc(0xFFFFE08A)
            tmp.reset()
            tmp.moveTo(f * 0.21f, 0.14f)
            tmp.lineTo(f * -0.2f, 0.08f)
            tmp.lineTo(f * 0.02f, -0.3f)
            tmp.close()
            c.drawPath(tmp, pFill)
            c.restore()
        }
        // nariz
        pFill.shader = null
        pFill.color = hc(0xFFFFC040)
        tmp.reset()
        tmp.moveTo(0f, 0.02f)
        tmp.lineTo(-0.1f, 0.2f)
        tmp.lineTo(0.1f, 0.2f)
        tmp.close()
        c.drawPath(tmp, pFill)
        // boca dentada
        val op = p.open
        tmp.reset()
        for (i in 0..8) {
            val x = -0.66f + i * 0.165f
            val u = x / 0.66f
            val yt = 0.4f - 0.2f * u * u + (if (i % 2 == 1) 0.1f else 0f)
            if (i == 0) tmp.moveTo(x, yt) else tmp.lineTo(x, yt)
        }
        for (i in 8 downTo 0) {
            val x = -0.66f + i * 0.165f
            val u = x / 0.66f
            val yt0 = 0.4f - 0.2f * u * u
            val th = (0.1f + op * 0.3f) * (1f - 0.7f * u * u) + 0.03f
            val yb = yt0 + th - (if (i % 2 == 0 && i != 0 && i != 8) 0.09f else 0f)
            tmp.lineTo(x, yb)
        }
        tmp.close()
        pFill.color = hc(0xFFFFB21F)
        c.drawPath(tmp, pFill)
        pStroke.color = hc(0xFF3B1002)
        pStroke.strokeWidth = 0.03f
        c.drawPath(tmp, pStroke)
        c.restore()
    }

    // -------------------------------------------------------------------- sangre
    private fun scan(v: View, ox: Float, oy: Float, cl: Float, ct: Float, cr: Float, cb: Float) {
        if (v === this || v.visibility != View.VISIBLE || v.alpha < 0.3f) return
        val x = ox + v.left + v.translationX
        val y = oy + v.top + v.translationY
        val wv = v.width.toFloat()
        val hv = v.height.toFloat()
        if (wv <= 0f || hv <= 0f) return
        if (x > cr || y > cb || x + wv < cl || y + hv < ct) return
        if (btnN < maxBtn && wv < W * 0.95f && v.hasOnClickListeners()) {
            val bi = btnN * 4
            btnF[bi] = max(x, cl)
            btnF[bi + 1] = max(y, ct)
            btnF[bi + 2] = min(x + wv, cr)
            btnF[bi + 3] = min(y + hv, cb)
            btnN++
        }
        val bg = v.background
        if (cardN < maxCards && bg is GradientDrawable && wv >= 140f * d && hv >= 40f * d) {
            val cs = bg.getColor()
            if (cs != null) {
                val col = cs.defaultColor
                val a = (col ushr 24) and 0xFF
                val sum = ((col shr 16) and 0xFF) + ((col shr 8) and 0xFF) + (col and 0xFF)
                if (a >= 0xB0 && sum < 230 && bg.cornerRadius >= 10f * d) {
                    val i = cardN * 9
                    cardF[i] = x
                    cardF[i + 1] = y
                    cardF[i + 2] = wv
                    cardF[i + 3] = hv
                    cardF[i + 4] = bg.cornerRadius
                    cardF[i + 5] = cl
                    cardF[i + 6] = ct
                    cardF[i + 7] = cr
                    cardF[i + 8] = cb
                    cardSeed[cardN] = System.identityHashCode(v)
                    cardN++
                }
            }
        }
        if (v is ViewGroup) {
            val nl = max(cl, x)
            val nt = max(ct, y)
            val nr = min(cr, x + wv)
            val nb = min(cb, y + hv)
            val cx = x - v.scrollX
            val cy = y - v.scrollY
            for (i in 0 until v.childCount) scan(v.getChildAt(i), cx, cy, nl, nt, nr, nb)
        }
    }

    private fun drawBlood(c: Canvas) {
        for (k in 0 until cardN) {
            val i = k * 9
            val x = cardF[i]
            val y = cardF[i + 1]
            val w = cardF[i + 2]
            val h = cardF[i + 3]
            val rad = cardF[i + 4]
            val seed = cardSeed[k]
            if (hwHash(seed, 1) > 0.8f) continue
            c.save()
            c.clipRect(cardF[i + 5], cardF[i + 6], cardF[i + 7], cardF[i + 8])
            rect.set(x, y, x + w, y + h)
            clipP.reset()
            clipP.addRoundRect(rect, rad, rad, Path.Direction.CW)
            c.clipPath(clipP)
            // charco en el borde superior
            val sx0 = x + w * hwHash(seed, 3) * 0.5f
            val sw = w * (0.25f + 0.35f * hwHash(seed, 4))
            val th = 3.2f * d
            c.save()
            c.translate(sx0, y)
            c.scale(sw, th)
            pFill.shader = shBloodTop
            pFill.alpha = 255
            c.drawRect(0f, 0f, 1f, 1f, pFill)
            c.restore()
            // gotas: crecen, se desprenden, caen y vuelven a empezar
            pFill.shader = null
            val nd = 1 + (hwHash(seed, 5) * 2.99f).toInt()
            val maxLen = min(h * 0.3f, 18f * d)
            for (j in 0 until nd) {
                val fx = sx0 + sw * (0.1f + 0.8f * hwHash(seed, 10 + j))
                val base = 0.55f + 0.45f * hwHash(seed, 20 + j)
                val spd = 0.09f + 0.07f * hwHash(seed, 25 + j)
                val u = (t * spd + hwHash(seed, 40 + j)) % 1f
                val lf = if (u < 0.8f) 0.2f + 0.8f * hwEase(u / 0.8f) else 1f - 0.8f * hwEase((u - 0.8f) / 0.2f)
                val len = maxLen * base * lf
                val wd = (2.6f + 1.8f * hwHash(seed, 30 + j)) * d
                pFill.color = BLOOD
                c.drawRoundRect(fx - wd * 0.5f, y, fx + wd * 0.5f, y + len, wd * 0.5f, wd * 0.5f, pFill)
                c.drawCircle(fx, y + len, wd * 0.72f, pFill)
                pFill.color = BLOOD_HI
                c.drawRect(fx - wd * 0.28f, y, fx - wd * 0.1f, y + len * 0.8f, pFill)
                if (u > 0.8f) {
                    val v = (u - 0.8f) / 0.2f
                    pFill.color = BLOOD
                    pFill.alpha = ((1f - v) * 230f).toInt()
                    c.drawCircle(fx, y + len + wd * 0.7f + v * v * 16f * d, wd * 0.4f, pFill)
                }
            }
            // salpicadura en una esquina
            if (hwHash(seed, 6) < 0.6f) {
                val corner = (hwHash(seed, 7) * 2.99f).toInt()
                val px = if (corner == 2) x + 14f * d else x + w - 14f * d
                val py = if (corner == 0) y + 14f * d else y + h - 14f * d
                val br = (4.5f + 3.5f * hwHash(seed, 8)) * d
                pFill.color = BLOOD
                c.drawCircle(px, py, br, pFill)
                pFill.color = BLOOD_DARK
                pFill.alpha = 130
                c.drawCircle(px + br * 0.2f, py + br * 0.15f, br * 0.55f, pFill)
                pFill.color = BLOOD
                for (m in 0 until 7) {
                    val ang = hwHash(seed, 50 + m) * TAU
                    val dist = br * (1.3f + 2.8f * hwHash(seed, 60 + m))
                    val rr = (0.8f + 1.7f * hwHash(seed, 70 + m)) * d
                    c.drawCircle(px + cos(ang) * dist, py + sin(ang) * dist, rr, pFill)
                }
                pStroke.shader = null
                pStroke.color = BLOOD
                pStroke.strokeWidth = 1.5f * d
                for (m in 0 until 3) {
                    val ang = hwHash(seed, 80 + m) * TAU
                    val ln = br * (2f + 2f * hwHash(seed, 90 + m))
                    c.drawLine(px, py, px + cos(ang) * ln, py + sin(ang) * ln, pStroke)
                }
            }
            c.restore()
        }
    }
}
