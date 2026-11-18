package app.glass // troque pelo package do seu launcher

import android.animation.ValueAnimator
import android.app.Activity
import android.content.*
import android.graphics.*
import android.os.SystemClock
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
private const val AGSL = """
uniform shader bgBlur;
uniform shader bgSharp;
uniform float2 center;
uniform float2 halfSize;
uniform float radius;
uniform float wobble;
uniform float time;
uniform float alpha;

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

half4 main(float2 p) {
    float d = sdf(p);
    float cov = clamp(0.5 - d, 0.0, 1.0);          // borda suave (AA)
    float shadow = exp(-max(d, 0.0) / 22.0) * 0.28 * (1.0 - cov);

    float bevel = 30.0;
    float t = clamp(-d / bevel, 0.0, 1.0);          // 0 na borda, 1 no miolo
    float2 e = float2(1.0, 0.0);
    float2 n = normalize(float2(sdf(p + e.xy) - sdf(p - e.xy),
                                sdf(p + e.yx) - sdf(p - e.yx)) + 1e-5);

    float rim = pow(1.0 - t, 3.0);
    float2 sp = p - n * rim * 26.0;                 // refração na borda
    float ca = 3.0 * rim;                           // aberração cromática
    half3 sharp = half3(eval(bgSharp, sp + n * ca).r,
                        eval(bgSharp, sp).g,
                        eval(bgSharp, sp - n * ca).b);
    half3 blur = eval(bgBlur, sp).rgb;
    half3 col = mix(sharp, blur, smoothstep(0.0, 0.6, t));   // miolo fosco

    col = col * 1.04 + 0.05;                        // leve tint claro
    float2 L = normalize(float2(-0.6, -0.8));
    float lit = 0.25 + 0.75 * pow(abs(dot(n, L)), 2.0);
    float line = 1.0 - smoothstep(0.0, 2.0, abs(d + 1.0));   // fio de luz
    col += line * lit * 0.7 + rim * lit * 0.12;

    float a = cov * alpha;
    return half4(col * a, a + shadow * alpha);
}
"""

@RequiresApi(33)
class GlassView @JvmOverloads constructor(c: Context, attrs: AttributeSet? = null) : View(c, attrs) {

    private val dp = resources.displayMetrics.density
    private val rt = RuntimeShader(AGSL)
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
        sharp = BitmapShader(full, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            .apply { filterMode = BitmapShader.FILTER_MODE_LINEAR }
        blur = BitmapShader(small, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            .apply { filterMode = BitmapShader.FILTER_MODE_LINEAR }
        blurScale = full.width.toFloat() / small.width
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val s = sharp ?: return
        val b = blur ?: return
        getLocationOnScreen(loc)
        val lx = loc[0].toFloat(); val ly = loc[1].toFloat()

        m.setTranslate(-lx, -ly); s.setLocalMatrix(m)
        m.setScale(blurScale, blurScale); m.postTranslate(-lx, -ly); b.setLocalMatrix(m)
        rt.setInputShader("bgSharp", s)
        rt.setInputShader("bgBlur", b)

        val k = progress.coerceAtLeast(0f)
        val w = width.toFloat(); val h = height.toFloat()
        val thx = (w - 2 * padPx) / 2; val thy = (h - 2 * padPx) / 2

        val cx = lerp(startCenter.x - lx, w / 2, k)
        val cy = lerp(startCenter.y - ly, h / 2, k)
        val hx = lerp(startHalf.x, thx, k)
        val hy = lerp(startHalf.y, thy, k)
        val rStart = min(startHalf.x, startHalf.y)
        val r = lerp(rStart, cornerPx, k).coerceIn(0f, min(hx, hy))
        val wob = (1f - k).coerceAtLeast(0f) * rStart * 0.10f

        rt.setFloatUniform("center", cx, cy)
        rt.setFloatUniform("halfSize", hx, hy)
        rt.setFloatUniform("radius", r)
        rt.setFloatUniform("wobble", wob)
        rt.setFloatUniform("time", (SystemClock.uptimeMillis() % 100000L) / 1000f)
        rt.setFloatUniform("alpha", (k / 0.25f).coerceIn(0f, 1f))

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
        repeat(2) { b = Bitmap.createScaledBitmap(b, max(1, b.width / 4), max(1, b.height / 4), true) }
        return Bitmap.createScaledBitmap(b, b.width * 2, b.height * 2, true)
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
    private val content: View             // workspace + ícones
) {
    private val rx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_SCREEN_OFF -> reset()
                Intent.ACTION_USER_PRESENT -> play()
            }
        }
    }

    fun attach() {
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        act.registerReceiver(rx, f, Context.RECEIVER_NOT_EXPORTED)
    }

    fun detach() = act.unregisterReceiver(rx)

    private fun reset() {
        glass.forEach { it.progress = 0f }
        content.alpha = 0f
    }

    private fun play() {
        glass.forEachIndexed { i, g ->
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 750
                startDelay = i * 120L
                interpolator = OvershootInterpolator(0.9f)
                addUpdateListener { g.progress = it.animatedValue as Float }
            }.start()
        }
        content.animate().alpha(1f).scaleX(1f).scaleY(1f)
            .setStartDelay(380).setDuration(320).start()
        content.scaleX = 0.94f; content.scaleY = 0.94f
    }
}

/* ------------------------------------------------------------------ *
 * Grid ajustado ao vidro: os ícones só usam a área interna do painel.
 * ------------------------------------------------------------------ */
object GlassGrid {
    fun cellWidth(panelW: Int, innerPad: Int, cols: Int = 5) = (panelW - 2 * innerPad) / cols
    fun rows(panelH: Int, innerPad: Int, cellH: Int) = (panelH - 2 * innerPad) / cellH
}
