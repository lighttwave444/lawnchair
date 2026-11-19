package app.glass // troque pelo package do seu launcher

import android.animation.ValueAnimator
import android.app.Activity
import android.app.KeyguardManager
import android.app.WallpaperManager
import android.content.*
import android.graphics.*
import android.net.Uri
import android.os.Build
import android.util.Log
import android.widget.Toast
import java.io.File
import android.os.SystemClock
import android.widget.FrameLayout
import android.graphics.drawable.BitmapDrawable
import androidx.core.graphics.drawable.toBitmap
import com.android.launcher3.CellLayout
import com.android.launcher3.InsettableFrameLayout
import com.android.launcher3.Launcher
import com.android.launcher3.LauncherState
import com.android.launcher3.statemanager.StateManager
import android.util.AttributeSet
import android.view.View
import android.view.animation.OvershootInterpolator
import androidx.annotation.RequiresApi
import kotlin.math.max
import kotlin.math.min

/* ------------------------------------------------------------------ *
 * Vidro líquido próprio (AGSL, Android 13+).
 * Forma = retângulo arredondado + ondulação. Com wobble alto vira bolha,
 * com wobble 0 vira o painel retangular da home.
 * ------------------------------------------------------------------ */
internal fun glassToast(c: Context, m: String) {
    try { Toast.makeText(c, m, Toast.LENGTH_LONG).show() } catch (_: Throwable) {}
}

private const val AGSL = """
uniform shader bgBlur;
uniform shader bgSharp;
uniform float2 center;
uniform float2 halfSize;
uniform float radius;
uniform float wobble;
uniform float time;
uniform float alpha;
uniform float zoom;

float sdf(float2 p) {
    float2 q = p - center;
    float2 b = halfSize - float2(radius);
    float2 d2 = abs(q) - b;
    float d = length(max(d2, 0.0)) + min(max(d2.x, d2.y), 0.0) - radius;
    float a = atan(q.y, q.x);
    float w = sin(a * 3.0 + time * 1.7) * 0.55
            + sin(a * 5.0 - time * 1.1) * 0.30
            + sin(a * 2.0 + time * 0.8) * 0.15;
    return d - wobble * w;
}

float hash21(float2 q) {
    return fract(sin(dot(q, float2(127.1, 311.7))) * 43758.5453);
}

half4 main(float2 p) {
    float d = sdf(p);
    float cov = clamp(0.5 - d, 0.0, 1.0);
    float dropSh = exp(-max(d, 0.0) / 22.0) * 0.28 * (1.0 - cov);

    float bevel = 52.0;
    float t = clamp(-d / bevel, 0.0, 1.0);
    float2 ex = float2(1.0, 0.0);
    float2 ey = float2(0.0, 1.0);
    float2 g = float2(sdf(p + ex) - sdf(p - ex), sdf(p + ey) - sdf(p - ey));
    float2 n = g / max(length(g), 0.0001);

    float rim = pow(1.0 - t, 3.0);
    float2 zp = center + (p - center) / zoom;
    float2 sp = zp - n * rim * 42.0;
    float ca = 4.5 * rim;

    float4 s1 = float4(bgSharp.eval(sp + n * ca));
    float4 s2 = float4(bgSharp.eval(sp));
    float4 s3 = float4(bgSharp.eval(sp - n * ca));
    float3 sharpC = float3(s1.r, s2.g, s3.b);
    float3 blurC = float3(float4(bgBlur.eval(sp)).rgb);
    float3 col = mix(sharpC, blurC, smoothstep(0.0, 0.7, t) * 0.92);

    col = col * 1.05 + float3(0.07);
    float2 L = normalize(float2(-0.6, -0.8));
    float dl = dot(n, L);
    float lit = 0.25 + 0.75 * pow(abs(dl), 2.0);
    float edgeLine = 1.0 - smoothstep(0.0, 2.0, abs(d + 1.0));
    float innerSh = exp(min(d, 0.0) / 7.0);
    col = col * (1.0 - 0.16 * innerSh * (1.0 - lit));
    col = col + float3(edgeLine * lit * 1.15 + rim * lit * 0.16);
    float softSh = exp(min(d, 0.0) / 20.0);
    col = col * (1.0 - 0.12 * softSh * (0.5 - 0.5 * dl));
    col = col + float3(0.10 * softSh * max(dl, 0.0));
    col = col + float3((hash21(p) - 0.5) * 0.035 * smoothstep(0.0, 0.9, t));

    float a = cov * alpha;
    float outA = min(a + dropSh * alpha, 1.0);
    float3 outRgb = col * a;
    return half4(half(outRgb.r), half(outRgb.g), half(outRgb.b), half(outA));
}
"""


/* Shader de reserva: o mais simples possível, só pra ter vidro se o completo não compilar. */
private const val AGSL_SIMPLE = """
uniform shader bgBlur;
uniform shader bgSharp;
uniform float2 center;
uniform float2 halfSize;
uniform float radius;
uniform float wobble;
uniform float time;
uniform float alpha;
uniform float zoom;

half4 main(float2 p) {
    float2 q = p - center;
    float2 b = halfSize - float2(radius);
    float2 d2 = abs(q) - b;
    float d = length(max(d2, float2(0.0))) + min(max(d2.x, d2.y), 0.0) - radius;
    float cov = clamp(0.5 - d, 0.0, 1.0) * alpha;
    half4 c = bgBlur.eval(p);
    return half4(c.r * half(cov), c.g * half(cov), c.b * half(cov), half(cov));
}
"""

private fun makeShader(c: Context): RuntimeShader {
    val errs = StringBuilder()
    for ((name, src) in listOf("completo" to AGSL, "simples" to AGSL_SIMPLE)) {
        try {
            val sh = RuntimeShader(src)
            if (errs.isNotEmpty()) glassToast(c, "Glass: shader $name em uso (o completo falhou)")
            return sh
        } catch (t: Throwable) {
            errs.append("[").append(name).append("] ").append(t.message).append("\n\n")
            try { File(c.filesDir, "glass_error.txt").writeText(errs.toString()) } catch (_: Throwable) {}
        }
    }
    throw IllegalStateException(errs.toString())
}

@RequiresApi(33)
class GlassView @JvmOverloads constructor(c: Context, attrs: AttributeSet? = null) : View(c, attrs) {

    private val dp = resources.displayMetrics.density
    private val rt = makeShader(c)
    private val paint = Paint().apply { shader = rt }
    private var sharp: BitmapShader? = null
    private var blur: BitmapShader? = null
    private var blurScale = 1f
    private val loc = IntArray(2)
    private val m = Matrix()

    /** Margem entre a View e o vidro (espaço da sombra). */
    var padPx = 64 * dp
    var cornerPx = 42 * dp

    /** Onde a bolha nasce (centro em px de tela) e metade do tamanho dela. */
    var startCenter = PointF(0f, 0f)
    var startHalf = PointF(110 * dp, 110 * dp)

    /** 0 = bolha, 1 = painel final. Passa de 1 no overshoot (efeito gelatina). */
    var progress = 1f
        set(v) { field = v; invalidate() }

    /** Use a MESMA imagem que está de wallpaper do sistema. */
    fun setWallpaper(src: Bitmap) {
        val dm = resources.displayMetrics
        val full = centerCrop(src, dm.widthPixels, dm.heightPixels)
        val small = fastBlur(full)
        sharp = BitmapShader(full, Shader.TileMode.MIRROR, Shader.TileMode.MIRROR)
            .apply { filterMode = BitmapShader.FILTER_MODE_LINEAR }
        blur = BitmapShader(small, Shader.TileMode.MIRROR, Shader.TileMode.MIRROR)
            .apply { filterMode = BitmapShader.FILTER_MODE_LINEAR }
        blurScale = full.width.toFloat() / small.width
        invalidate()
    }

    private var broken = false
    private var logged = false

    override fun onDraw(canvas: Canvas) {
        if (broken) return
        try { drawGlass(canvas) } catch (t: Throwable) {
            broken = true; Log.e("GlassView", "shader falhou", t)
            val msg = "${t.javaClass.simpleName}: ${t.message}"
            try { File(context.filesDir, "glass_error.txt").writeText(msg) } catch (_: Throwable) {}
            glassToast(context, "Glass ERRO: ${msg.take(350)}")
        }
    }

    private fun drawGlass(canvas: Canvas) {
        val s = sharp ?: return
        val b = blur ?: return
        getLocationOnScreen(loc)
        val lx = loc[0].toFloat(); val ly = loc[1].toFloat()

        m.setTranslate(-lx, -ly); s.setLocalMatrix(m)
        m.setScale(blurScale, blurScale); m.postTranslate(-lx, -ly); b.setLocalMatrix(m)
        rt.setInputShader("bgSharp", s)
        rt.setInputShader("bgBlur", b)

        val k = progress.coerceAtLeast(0f)
        if (!logged) {
            logged = true
            Log.i("GlassView", "primeiro draw k=$k size=${width}x${height} loc=${loc[0]},${loc[1]}")
        }
        val w = width.toFloat(); val h = height.toFloat()
        val thx = (w - 2 * padPx) / 2; val thy = (h - 2 * padPx) / 2

        val cx = lerp(startCenter.x - lx, w / 2, k)
        val cy = lerp(startCenter.y - ly, h / 2, k)
        val hx = lerp(startHalf.x, thx, k)
        val hy = lerp(startHalf.y, thy, k)
        val rStart = min(startHalf.x, startHalf.y)
        val r = lerp(rStart, cornerPx, k).coerceIn(0f, min(hx, hy))
        val wob = (1f - k).coerceAtLeast(0f) * rStart * 0.14f

        rt.setFloatUniform("center", cx, cy)
        rt.setFloatUniform("halfSize", hx, hy)
        rt.setFloatUniform("radius", r)
        rt.setFloatUniform("wobble", wob)
        rt.setFloatUniform("time", (SystemClock.uptimeMillis() % 100000L) / 1000f)
        rt.setFloatUniform("alpha", (k / 0.25f).coerceIn(0f, 1f))
        rt.setFloatUniform("zoom", 1.0f + 0.12f * k.coerceAtMost(1f))

        canvas.drawRect(0f, 0f, w, h, paint)
        if (k < 1f) postInvalidateOnAnimation()   // mantém a bolha "viva"
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    private fun centerCrop(src: Bitmap, w: Int, h: Int): Bitmap {
        val s0 = if (src.config == Bitmap.Config.HARDWARE)
            src.copy(Bitmap.Config.ARGB_8888, false) else src
        val sc = max(w / s0.width.toFloat(), h / s0.height.toFloat())
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            translate((w - s0.width * sc) / 2, (h - s0.height * sc) / 2)
            scale(sc, sc)
            drawBitmap(s0, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        }
        return out
    }

    /** Desfoque barato: reduz 1/16 e sobe um pouco (o filtro linear faz o resto). */
    private fun fastBlur(src: Bitmap): Bitmap {
        var b = src
        repeat(3) { b = Bitmap.createScaledBitmap(b, max(1, b.width / 3), max(1, b.height / 3), true) }
        repeat(2) { b = Bitmap.createScaledBitmap(b, b.width * 2, b.height * 2, true) }
        return b
    }
}

/* ------------------------------------------------------------------ *
 * Animação de desbloqueio: tela apaga -> bolha; usuário desbloqueia ->
 * bolha vira painel, dock vem depois, ícones entram por cima.
 * ------------------------------------------------------------------ */
@RequiresApi(33)
class GlassUnlockController(
    private val act: Activity,
    private val glass: List<GlassView>,   // [painel, dock]
    private val content: List<View>       // workspace + hotseat
) {
    private val rx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_SCREEN_OFF -> reset()
                Intent.ACTION_USER_PRESENT -> play()
                Intent.ACTION_SCREEN_ON -> act.window.decorView.postDelayed({ onResume() }, 2500)
            }
        }
    }

    fun attach() {
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        act.registerReceiver(rx, f, Context.RECEIVER_EXPORTED)
    }

    fun detach() = act.unregisterReceiver(rx)

    private fun reset() {
        Log.i("GlassCtl", "reset (tela apagou)")
        glass.forEach { it.progress = 0f }
        content.forEach { it.alpha = 0f }
    }

    /** Segurança: se o launcher voltou e os ícones ficaram escondidos sem bloqueio, mostra. */
    fun onResume() {
        val km = act.getSystemService(KeyguardManager::class.java)
        if (!km.isKeyguardLocked && (content.any { it.alpha < 0.99f } || glass.any { it.progress < 1f })) play()
    }

    private var playing = false

    private fun play() {
        if (playing) return
        playing = true
        Log.i("GlassCtl", "play (desbloqueou)")
        act.window.decorView.postDelayed({
            content.forEach { it.alpha = 1f }
            glass.forEach { it.progress = 1f }
            playing = false
        }, 1500)   // rede de segurança: depois de 1,5 s tudo fica visível
        glass.forEachIndexed { i, g ->
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 750
                startDelay = i * 120L
                interpolator = OvershootInterpolator(0.9f)
                addUpdateListener { g.progress = it.animatedValue as Float }
            }.start()
        }
        content.forEach { it.animate().alpha(1f).setStartDelay(380).setDuration(320).start() }
    }
}

/* ------------------------------------------------------------------ *
 * Grid ajustado ao vidro: os ícones só usam a área interna do painel.
 * ------------------------------------------------------------------ */
object GlassGrid {
    fun cellWidth(panelW: Int, innerPad: Int, cols: Int = 5) = (panelW - 2 * innerPad) / cols
    fun rows(panelH: Int, innerPad: Int, cellH: Int) = (panelH - 2 * innerPad) / cellH
}

/* ------------------------------------------------------------------ *
 * Liga tudo no Lawnchair: cria o painel e o dock de vidro atrás do
 * workspace e acompanha o tamanho real da home.
 * ------------------------------------------------------------------ */
@RequiresApi(33)
object GlassHost {
    private var controller: GlassUnlockController? = null
    private var views: List<GlassView> = emptyList()

    private const val REQ_PICK = 7391
    private var installed = false
    private var posShown = false

    fun install(l: Launcher) {
        if (Build.VERSION.SDK_INT < 33 || installed) return
        val bmp = loadWallpaper(l)
        Log.i("GlassHost", "install: bmp=${bmp?.width}x${bmp?.height}")
        if (bmp == null) { askForImage(l); return }   // Android bloqueou o wallpaper: pede a imagem
        try { build(l, bmp); installed = true } catch (t: Throwable) {
            Log.e("GlassHost", "falhou", t); glassToast(l, "Glass ERRO build: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun askForImage(l: Launcher) {
        try {
            l.startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = "image/*"
            }, REQ_PICK)
        } catch (t: Throwable) { Log.e("GlassHost", "sem seletor", t) }
    }

    /** Chamado pelo onActivityResult do launcher. Retorna true se tratou. */
    fun onActivityResult(l: Launcher, req: Int, res: Int, data: Intent?): Boolean {
        if (req != REQ_PICK) return false
        val uri = data?.data
        if (res == Activity.RESULT_OK && uri != null && saveFromUri(l, uri)) {
            loadWallpaper(l)?.let { bmp ->
                try { WallpaperManager.getInstance(l).setBitmap(bmp) } catch (_: Throwable) {}
                try { build(l, bmp); installed = true } catch (t: Throwable) { Log.e("GlassHost", "falhou", t) }
            }
        }
        return true
    }

    private fun saveFromUri(l: Launcher, uri: Uri): Boolean = try {
        val dm = l.resources.displayMetrics
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        l.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var ss = 1
        while (bounds.outWidth / (ss * 2) >= dm.widthPixels && bounds.outHeight / (ss * 2) >= dm.heightPixels) ss *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = ss }
        val bmp = l.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        if (bmp == null) false else {
            File(l.filesDir, "glass_wallpaper.jpg").outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            true
        }
    } catch (t: Throwable) { false }

    private fun build(l: Launcher, bmp: Bitmap) {
        val d = l.resources.displayMetrics.density

        val panel = GlassView(l).apply { setWallpaper(bmp) }
        val dock = GlassView(l).apply { setWallpaper(bmp); cornerPx = 34 * d }
        val drag = l.dragLayer
        listOf(dock, panel).forEach { v ->
            drag.addView(v, 1, FrameLayout.LayoutParams(1, 1))   // atrás do workspace
            (v.layoutParams as? InsettableFrameLayout.LayoutParams)?.ignoreInsets = true
        }
        views = listOf(panel, dock)
        Log.i("GlassHost", "build ok: views adicionadas")

        drag.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> position(l, panel, dock) }
        drag.post { position(l, panel, dock) }

        l.stateManager.addStateListener(object : StateManager.StateListener<LauncherState> {
            override fun onStateTransitionStart(toState: LauncherState) {
                val a = if (toState == LauncherState.NORMAL) 1f else 0f
                views.forEach { it.animate().alpha(a).setDuration(200).start() }
            }
            override fun onStateTransitionComplete(finalState: LauncherState) {}
        })

        controller = GlassUnlockController(l, views, listOf(l.workspace, l.hotseat)).also { it.attach() }
    }

    fun onResume() { controller?.onResume() }

    fun uninstall() { controller?.detach(); controller = null; installed = false }

    private fun position(l: Launcher, panel: GlassView, dock: GlassView) {
        val drag = l.dragLayer
        val d = l.resources.displayMetrics.density
        if (drag.width == 0) return

        val w = drag.width.toFloat(); val h = drag.height.toFloat()
        // Medidas tiradas do print de referência (720x1612), em proporção da tela
        panel.cornerPx = 0.095f * w
        dock.cornerPx = 0.100f * w
        // Painel acompanha a grade real de ícones (se falhar, usa as medidas do print)
        var pL = 0.053f * w; var pT = 0.099f * h; var pR = 0.947f * w; var pB = 0.775f * h
        try {
            val cl = l.workspace.getChildAt(l.workspace.currentPage) as? CellLayout
            val sc = cl?.shortcutsAndWidgets
            if (sc != null && sc.width > 0 && sc.height > 0) {
                val a = IntArray(2); val b = IntArray(2)
                sc.getLocationInWindow(a); drag.getLocationInWindow(b)
                val x = (a[0] - b[0]).toFloat(); val y = (a[1] - b[1]).toFloat()
                pL = max(0.02f * w, x - 10 * d)
                pR = min(0.98f * w, x + sc.width + 10 * d)
                pT = max(0.04f * h, y - 14 * d)
                pB = min(0.845f * h - 6 * d, y + sc.height + 14 * d)
            }
        } catch (_: Throwable) {}
        place(panel, pL, pT, pR, pB)
        place(dock, 0.056f * w, 0.851f * h, 0.944f * w, 0.969f * h)
        val hs = l.hotseat
        val wp = l.deviceProfile.workspacePadding

        val cx = drag.width / 2f; val cy = drag.height * 0.45f
        panel.startCenter = PointF(cx, cy); panel.startHalf = PointF(drag.width * 0.34f, drag.width * 0.24f)
        dock.startCenter = PointF(cx, cy);  dock.startHalf = PointF(drag.width * 0.20f, drag.width * 0.10f)
        val pl = panel.layoutParams as FrameLayout.LayoutParams
        Log.i("GlassHost", "pos drag=${drag.width}x${drag.height} wp=$wp panel=${pl.leftMargin},${pl.topMargin} ${pl.width}x${pl.height} hotseat=${hs.left},${hs.top},${hs.right},${hs.bottom}")
    }

    private fun place(v: GlassView, l: Float, t: Float, r: Float, b: Float) {
        val lp = v.layoutParams as? FrameLayout.LayoutParams ?: return
        val pad = v.padPx.toInt()
        val nl = l.toInt() - pad; val nt = t.toInt() - pad
        val nw = (r - l).toInt() + 2 * pad; val nh = (b - t).toInt() + 2 * pad
        if (lp.leftMargin == nl && lp.topMargin == nt && lp.width == nw && lp.height == nh) return
        lp.leftMargin = nl; lp.topMargin = nt; lp.width = nw; lp.height = nh
        v.layoutParams = lp   // mesmo objeto (subclasse do Launcher), só dispara novo layout
    }

    private fun loadWallpaper(c: Context): Bitmap? {
        try {   // 1) imagem escolhida por você
            val f = File(c.filesDir, "glass_wallpaper.jpg")
            if (f.exists()) BitmapFactory.decodeFile(f.path)?.let { return it }
        } catch (_: Throwable) {}
        return try {   // 2) wallpaper do sistema (pode ser bloqueado pelo Android)
            val dr = WallpaperManager.getInstance(c).drawable
            (dr as? BitmapDrawable)?.bitmap ?: dr?.toBitmap()
        } catch (t: Throwable) { null }
    }
}
