package com.gestor.archivos

import android.app.Activity
import android.app.Application
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.LinearGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

// ============================================================================
//  Nexora · Halloween
//  Capa de efectos que se dibuja ENCIMA de la app sin tocar MainActivity:
//  calaveras con fuego y humo, ojos que brillan en la oscuridad, calabazas
//  embrujadas, sangre sobre las tarjetas, brasas, telarañas y viñeta.
//  No recibe toques (todo pasa a los botones de abajo).
// ============================================================================

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
    private var still = false
    private var lite = false
    private var lastNs = 0L
    private var minStepNs = 33000000L
    private var t = 0f
    private var W = 0f
    private var H = 0f
    private var botInset = 0f

    // ---- pinceles y formas reutilizables (sin crear objetos por cuadro) ----
    private val pFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pGlow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pStroke = Paint(Paint.ANTI_ALIAS_FLAG)
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
    private var shVig: Shader? = null
    private var shHaze: Shader? = null
    private var shFloor: Shader? = null

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

    private val lurks = arrayOf(HwLurk(), HwLurk(), HwLurk())
    private val pumps = arrayOf(HwPumpkin(), HwPumpkin(), HwPumpkin())

    private val maxCards = 16
    private val cardF = FloatArray(maxCards * 9)
    private val cardSeed = IntArray(maxCards)
    private var cardN = 0

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        pStroke.style = Paint.Style.STROKE
        pStroke.strokeCap = Paint.Cap.ROUND
        pStroke.strokeJoin = Paint.Join.ROUND
        pumps[0].wait = 1.5f
        pumps[1].wait = 4.5f
        pumps[2].wait = 8f
        for (l in lurks) l.wait = rf(0.5f, 5f)
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
            val sc = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
            still = sc == 0f
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            lite = pm.isPowerSaveMode
            minStepNs = if (lite) 66000000L else 33000000L
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
        if (still) {
            invalidate()
            return
        }
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
        }
        schedule()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        lastNs = 0L
        readInsets()
        if (active) schedule()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        Choreographer.getInstance().removeFrameCallback(this)
        posted = false
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean = false
    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean = false

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
        eph[i] = rf(0f, 6.2831855f)
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
        stepSmoke(dt)
        stepLurks(dt)
        stepPumpkins(dt)
    }

    private fun skullS(): Float = 38f * d
    private fun skullX(i: Int): Float = if (i == 0) skullS() * 0.95f else W - skullS() * 0.95f
    private fun skullY(): Float = H - botInset + skullS() * 0.12f

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
            for (k in 0..1) emitSmoke(skullX(k) + rf(-8f, 8f) * d, skullY() - skullS() * 1.1f, if (k == 0) 5f * d else -5f * d)
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
                    l.wait = rf(2f, 7f)
                }
            }
        }
    }

    private fun stepPumpkins(dt: Float) {
        val maxActive = if (lite) 1 else 2
        val n = if (lite) 2 else 3
        var act = 0
        for (k in 0 until n) if (pumps[k].state != 0) act++
        for (k in 0 until n) {
            val p = pumps[k]
            if (p.state == 0) {
                p.wait -= dt
                if (p.wait <= 0f) {
                    if (act >= maxActive) {
                        p.wait = 1.5f
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
                    p.wait = rf(2.5f, 8f)
                    p.laugh = 0f
                    p.laughAmt = 0f
                }
            }
        }
    }

    private fun spawnPumpkin(p: HwPumpkin) {
        p.tm = 0f
        p.ph = rf(0f, 6.2831855f)
        p.r = rf(24f, 36f) * d
        p.laugh = 0f
        p.laughAmt = 0f
        p.laughDone = false
        if (rnd.nextFloat() < 0.58f) {
            p.state = 1
            p.edge = when (rnd.nextInt(6)) {
                0, 2 -> 0
                1, 3 -> 1
                4 -> 2
                else -> 3
            }
            p.along = if (p.edge <= 1) rf(0.14f, 0.86f) else rf(0.22f, 0.78f)
            p.dur = rf(5.8f, 8.2f)
            p.show = rf(0.42f, 0.62f)
            p.baseRot = when (p.edge) {
                0 -> -22f
                1 -> 22f
                2 -> 180f
                else -> rf(-6f, 6f)
            }
        } else {
            p.state = 2
            p.dir = if (rnd.nextBoolean()) 1f else -1f
            p.dur = rf(9f, 15f)
            p.sx = if (p.dir > 0f) -1.8f * p.r else W + 1.8f * p.r
            p.ex = if (p.dir > 0f) W + 1.8f * p.r else -1.8f * p.r
            val rr = rnd.nextFloat()
            p.yb = if (rr < 0.15f) H * rf(-0.01f, 0.02f) else if (rr < 0.30f) H * rf(0.97f, 1f) - botInset * 0.5f else H * rf(0.14f, 0.86f)
            p.amp = rf(10f, 34f) * d
            p.fq = rf(0.9f, 1.7f)
            p.bounce = rnd.nextFloat() < 0.35f
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
        val uu = u + 0.035f * sin(6.2831855f * u * 2f + p.ph)
        p.x = p.sx + (p.ex - p.sx) * uu
        var yy = p.yb + p.amp * sin(p.tm * p.fq + p.ph) + p.amp * 0.35f * sin(p.tm * p.fq * 2.3f)
        if (p.bounce) yy -= abs(sin(p.tm * 3f + p.ph)) * p.r * 0.45f
        p.y = yy
        p.rot = sin(p.tm * p.fq * 1.4f + p.ph) * 11f + p.dir * 7f
        if (p.laugh <= 0f && p.x > p.r * 0.5f && p.x < W - p.r * 0.5f && rnd.nextFloat() < 0.35f * dt) {
            p.laugh = rf(1f, 1.8f)
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
        val par = parent as? ViewGroup
        if (par != null) {
            for (i in 0 until par.childCount) {
                val ch = par.getChildAt(i)
                if (ch !== this) scan(ch, 0f, 0f, 0f, 0f, W, H)
            }
        }
        drawBlood(c)
        drawSmoke(c)
        for (k in 0..1) drawSkullWithFire(c, k)
        drawLurks(c)
        for (p in pumps) if (p.state != 0) drawPumpkin(c, p)
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

    private fun flame(c: Canvas, bx: Float, by: Float, hh: Float, ww: Float, sway: Float, col: Int) {
        pFill.shader = null
        pFill.color = col
        tmp.reset()
        tmp.moveTo(bx - ww, by)
        tmp.quadTo(bx - ww * 1.1f, by - hh * 0.55f, bx + sway, by - hh)
        tmp.quadTo(bx + ww * 1.1f, by - hh * 0.5f, bx + ww, by)
        tmp.close()
        c.drawPath(tmp, pFill)
    }

    private fun drawSkullWithFire(c: Canvas, k: Int) {
        val s = skullS()
        val cx = skullX(k)
        val cy = skullY() + sin(t * 1.3f + k * 2f) * 1.5f * d
        val tilt = if (k == 0) 12f else -12f
        val ph = k * 1.9f
        // resplandor
        c.save()
        c.translate(cx, cy)
        c.scale(s * 2.5f, s * 2.5f)
        pGlow.shader = shGlowRed
        pGlow.alpha = ((0.55f + 0.3f * sin(t * 2.6f + ph)) * 255f).toInt()
        c.drawCircle(0f, 0f, 1f, pGlow)
        c.restore()
        // calavera
        c.save()
        c.translate(cx, cy)
        c.rotate(tilt)
        c.scale(s, s)
        pFill.shader = shBone
        pFill.alpha = 255
        rect.set(-0.56f, 0.5f, 0.56f, 1.32f)
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
            c.scale(0.33f, 0.33f)
            pGlow.shader = if (k == 0) shEye else shEyeV
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
            c.drawLine(x, 0.8f, x, 1.26f, pStroke)
        }
        c.drawLine(-0.5f, 0.92f, 0.5f, 0.92f, pStroke)
        // grieta y sangre
        c.drawLine(0.2f, -0.95f, 0.06f, -0.55f, pStroke)
        val bl = 0.3f + 0.08f * sin(t * 1.1f + ph)
        pStroke.color = BLOOD
        pStroke.strokeWidth = 0.05f
        c.drawLine(-0.4f, 0.3f, -0.42f, 0.3f + bl, pStroke)
        pFill.color = BLOOD
        c.drawCircle(-0.42f, 0.3f + bl, 0.05f, pFill)
        c.restore()
        // fuego sobre la cabeza
        for (i in 0..5) {
            val th = (-150f + 120f * i / 5f) * 0.017453292f
            val lx = cos(th) * 0.92f
            val ly = sin(th) * 0.92f
            val tr = tilt * 0.017453292f
            val wx = cx + (lx * cos(tr) - ly * sin(tr)) * s
            val wy = cy + (lx * sin(tr) + ly * cos(tr)) * s
            val hh = s * (0.85f + 0.55f * hwHash(i, k)) * (0.75f + 0.25f * sin(t * (6f + i) + ph + i))
            val ww = s * 0.2f * (0.8f + 0.4f * hwHash(i + 9, k))
            val sway = sin(t * 3.1f + i * 1.7f + ph) * ww * 0.8f
            flame(c, wx, wy, hh, ww, sway, hc(0xB8E02A0A))
            flame(c, wx, wy, hh * 0.75f, ww * 0.72f, sway * 0.8f, hc(0xC8FF7A12))
            flame(c, wx, wy, hh * 0.5f, ww * 0.42f, sway * 0.6f, hc(0xD8FFD24A))
        }
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
            if (hwHash(seed, 1) > 0.78f) continue
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
            // gotas que cuelgan
            pFill.shader = null
            val nd = 1 + (hwHash(seed, 5) * 2.99f).toInt()
            val maxLen = min(h * 0.22f, 14f * d)
            val sd = (seed and 7).toFloat()
            for (j in 0 until nd) {
                val fx = sx0 + sw * (0.1f + 0.8f * hwHash(seed, 10 + j))
                val base = 0.45f + 0.55f * hwHash(seed, 20 + j)
                val len = maxLen * base * (0.88f + 0.12f * sin(t * 0.7f + j * 2.1f + sd))
                val wd = (2.6f + 1.8f * hwHash(seed, 30 + j)) * d
                pFill.color = BLOOD
                c.drawRoundRect(fx - wd * 0.5f, y, fx + wd * 0.5f, y + len, wd * 0.5f, wd * 0.5f, pFill)
                c.drawCircle(fx, y + len, wd * 0.72f, pFill)
                pFill.color = BLOOD_HI
                c.drawRect(fx - wd * 0.28f, y, fx - wd * 0.1f, y + len * 0.8f, pFill)
                val ph = (t * 0.22f + hwHash(seed, 40 + j) * 5f) % 1f
                if (ph > 0.62f) {
                    val u = (ph - 0.62f) / 0.38f
                    pFill.color = BLOOD
                    pFill.alpha = ((1f - u) * 220f).toInt()
                    c.drawCircle(fx, y + len + wd + u * 10f * d, wd * 0.38f, pFill)
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
                    val ang = hwHash(seed, 50 + m) * 6.2831855f
                    val dist = br * (1.3f + 2.8f * hwHash(seed, 60 + m))
                    val rr = (0.8f + 1.7f * hwHash(seed, 70 + m)) * d
                    c.drawCircle(px + cos(ang) * dist, py + sin(ang) * dist, rr, pFill)
                }
                pStroke.shader = null
                pStroke.color = BLOOD
                pStroke.strokeWidth = 1.5f * d
                for (m in 0 until 3) {
                    val ang = hwHash(seed, 80 + m) * 6.2831855f
                    val ln = br * (2f + 2f * hwHash(seed, 90 + m))
                    c.drawLine(px, py, px + cos(ang) * ln, py + sin(ang) * ln, pStroke)
                }
            }
            c.restore()
        }
    }
}
