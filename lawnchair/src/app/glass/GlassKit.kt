package app.glass // troque pelo package do seu launcher

import android.animation.ValueAnimator
import android.app.Activity
import android.app.KeyguardManager
import android.app.WallpaperManager
import android.content.*
import android.graphics.*
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
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
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
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
uniform float dark;
uniform float bevel;

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

    float t = clamp(-d / bevel, 0.0, 1.0);
    float2 ex = float2(1.0, 0.0);
    float2 ey = float2(0.0, 1.0);
    float2 g = float2(sdf(p + ex) - sdf(p - ex), sdf(p + ey) - sdf(p - ey));
    float2 n = g / max(length(g), 0.0001);

    float rim = pow(1.0 - t, 2.2);
    float2 zp = center + (p - center) / zoom;
    float2 sp = zp - n * rim * (bevel * 0.85);
    float ca = 1.5 * rim;

    float4 s1 = float4(bgSharp.eval(sp + n * ca));
    float4 s2 = float4(bgSharp.eval(sp));
    float4 s3 = float4(bgSharp.eval(sp - n * ca));
    float3 sharpC = float3(s1.r, s2.g, s3.b);
    float3 blurC = float3(float4(bgBlur.eval(sp)).rgb);
    float3 col = mix(sharpC, blurC, smoothstep(0.0, 0.7, t) * 0.6);

    float lum = dot(col, float3(0.299, 0.587, 0.114));
    col = mix(float3(lum), col, 1.22);
    col = col * 1.04;
    float2 L = normalize(float2(-0.6, -0.8));
    float dl = dot(n, L);
    float lit = 0.25 + 0.75 * pow(abs(dl), 2.0);
    float edgeLine = 1.0 - smoothstep(0.0, 2.0, abs(d + 1.0));
    float innerSh = exp(min(d, 0.0) / 7.0);
    col = col * (1.0 - 0.16 * innerSh * (1.0 - lit));
    col = col + float3(edgeLine * lit * 0.95 + rim * lit * 0.10);
    col = col + sharpC * rim * 0.12;                       // borda brilha com a cor da imagem
    float edge2 = 1.0 - smoothstep(0.0, 2.5, abs(d + 9.0));
    col = col + float3(edge2 * lit * 0.18);                // segunda linha fina por dentro
    float softSh = exp(min(d, 0.0) / 20.0);
    col = col * (1.0 - 0.12 * softSh * (0.5 - 0.5 * dl));
    col = col + float3(0.03 * softSh * max(dl, 0.0));
    col = col * (1.0 - dark);

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
uniform float dark;
uniform float bevel;

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
    private var sharpScale = 1f
    private val loc = IntArray(2)
    private val m = Matrix()

    /** Margem entre a View e o vidro (espaço da sombra). */
    var padPx = 64 * dp
    var cornerPx = 42 * dp

    /** Onde a bolha nasce (centro em px de tela) e metade do tamanho dela. */
    var startCenter = PointF(0f, 0f)
    var startHalf = PointF(110 * dp, 110 * dp)

    /** 0 = bolha, 1 = painel final. Passa de 1 no overshoot (efeito gelatina). */
    /** 0 = vidro claro, 1 = preto. Usado na gaveta pra letras ficarem legíveis. */
    var dark = 0f
    /** Quanto o miolo amplia a imagem (lente). O dock é baixinho, então amplia mais. */
    var zoomAmt = 0.12f
    /** Se definido, a forma final do vidro é este retângulo (coords de TELA), em vez de a View inteira menos a folga. */
    var screenTarget: RectF? = null
    var progress = 1f
        set(v) { field = v; invalidate() }

    /** Use a MESMA imagem que está de wallpaper do sistema. */
    fun setWallpaper(src: Bitmap) {
        val dm = resources.displayMetrics
        val full = centerCrop(src, dm.widthPixels, dm.heightPixels)
        // reduz de 2 em 2 (média exata, sem serrilhado) e borra de verdade
        val half = Bitmap.createScaledBitmap(full, max(1, full.width / 2), max(1, full.height / 2), true)
        val quarter = Bitmap.createScaledBitmap(half, max(1, half.width / 2), max(1, half.height / 2), true)
        val light = boxBlur(half, 2, 2)      // borda: levemente suave, sem ruído
        val strong = boxBlur(quarter, 2, 3)  // miolo: desfoque moderado, imagem ainda reconhecível
        sharp = BitmapShader(light, Shader.TileMode.MIRROR, Shader.TileMode.MIRROR)
            .apply { filterMode = BitmapShader.FILTER_MODE_LINEAR }
        blur = BitmapShader(strong, Shader.TileMode.MIRROR, Shader.TileMode.MIRROR)
            .apply { filterMode = BitmapShader.FILTER_MODE_LINEAR }
        sharpScale = full.width.toFloat() / light.width
        blurScale = full.width.toFloat() / strong.width
        invalidate()
    }

    private fun boxBlur(src: Bitmap, radius: Int, passes: Int): Bitmap {
        val w = src.width; val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val ch = Array(3) { IntArray(w * h) }
        for (i in px.indices) {
            val c = px[i]
            ch[0][i] = (c shr 16) and 255; ch[1][i] = (c shr 8) and 255; ch[2][i] = c and 255
        }
        val tmp = IntArray(w * h)
        for (c in 0..2) repeat(passes) {
            blurLine(ch[c], tmp, w, h, radius, true)
            blurLine(tmp, ch[c], w, h, radius, false)
        }
        for (i in px.indices) px[i] = (255 shl 24) or (ch[0][i] shl 16) or (ch[1][i] shl 8) or ch[2][i]
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    private fun blurLine(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val len = if (horizontal) w else h
        val lines = if (horizontal) h else w
        val div = 2 * r + 1
        for (line in 0 until lines) {
            val base = if (horizontal) line * w else line
            val step = if (horizontal) 1 else w
            var sum = 0
            for (k in -r..r) sum += src[base + k.coerceIn(0, len - 1) * step]
            for (k in 0 until len) {
                dst[base + k * step] = (sum + div / 2) / div
                val add = (k + r + 1).coerceAtMost(len - 1)
                val rem = (k - r).coerceAtLeast(0)
                sum += src[base + add * step] - src[base + rem * step]
            }
        }
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

        m.setScale(sharpScale, sharpScale); m.postTranslate(-lx, -ly); s.setLocalMatrix(m)
        m.setScale(blurScale, blurScale); m.postTranslate(-lx, -ly); b.setLocalMatrix(m)
        rt.setInputShader("bgSharp", s)
        rt.setInputShader("bgBlur", b)

        val k = progress.coerceAtLeast(0f)
        if (!logged) {
            logged = true
            Log.i("GlassView", "primeiro draw k=$k size=${width}x${height} loc=${loc[0]},${loc[1]}")
        }
        val w = width.toFloat(); val h = height.toFloat()
        val tg = screenTarget
        val thx = if (tg != null) tg.width() / 2 else (w - 2 * padPx) / 2
        val thy = if (tg != null) tg.height() / 2 else (h - 2 * padPx) / 2
        val tcx = if (tg != null) tg.centerX() - lx else w / 2
        val tcy = if (tg != null) tg.centerY() - ly else h / 2

        val cx = lerp(startCenter.x - lx, tcx, k)
        val cy = lerp(startCenter.y - ly, tcy, k)
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
        rt.setFloatUniform("alpha", 1f)
        rt.setFloatUniform("zoom", 1.0f + zoomAmt * k.coerceAtMost(1f))
        rt.setFloatUniform("dark", dark)
        rt.setFloatUniform("bevel", min(76f, 0.45f * min(hx, hy)).coerceAtLeast(8f))

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
                Intent.ACTION_SCREEN_OFF -> { GlassHost.noteScreenOff(); reset() }
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
                startDelay = 0L
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
    private var drawerOpen = false
    private var host: Launcher? = null
    private var wallId = -1
    private var appsView: View? = null
    private val drawerRect = Rect()
    private var drawerRadius = 0f
    private var wpReceiver: BroadcastReceiver? = null
    private var lastCapture = 0L
    private var lastSig = ""
    private var leftAt = 0L
    private var screenWasOff = false
    private var pendingForce = false
    private var preDraw: ViewTreeObserver.OnPreDrawListener? = null
    private var posShown = false

    fun install(l: Launcher) {
        if (Build.VERSION.SDK_INT < 33 || installed) return
        val bmp = loadWallpaper(l)
        Log.i("GlassHost", "install: bmp=${bmp?.width}x${bmp?.height}")
        if (bmp == null) { tryRootThenPick(l); return }   // API bloqueada: tenta root, senão pede a imagem
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
            fileWallpaper(l)?.let { bmp ->
                try { WallpaperManager.getInstance(l).setBitmap(bmp) } catch (_: Throwable) {}
                try {
                    val nid = WallpaperManager.getInstance(l).getWallpaperId(WallpaperManager.FLAG_SYSTEM)
                    wallId = nid
                    l.getSharedPreferences("glass", Context.MODE_PRIVATE).edit().putInt("wpid", nid).putInt("asked", nid).apply()
                } catch (_: Throwable) {}
                if (installed) views.forEach { it.setWallpaper(bmp) }
                else try { build(l, bmp); installed = true } catch (t: Throwable) { Log.e("GlassHost", "falhou", t) }
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

        val panel = GlassView(l).apply { setWallpaper(bmp); dark = 0.10f; alpha = 0f; zoomAmt = 0.06f }
        val dock = GlassView(l).apply { setWallpaper(bmp); cornerPx = 34 * d; zoomAmt = 0.35f }
        val drag = l.dragLayer
        listOf(dock, panel).forEach { v ->
            drag.addView(v, 1, FrameLayout.LayoutParams(1, 1))   // atrás do workspace
            (v.layoutParams as? InsettableFrameLayout.LayoutParams)?.ignoreInsets = true
        }
        views = listOf(panel, dock)
        host = l
        try { wallId = WallpaperManager.getInstance(l).getWallpaperId(WallpaperManager.FLAG_SYSTEM) } catch (_: Throwable) {}
        lastSig = wallSig(l)
        try {
            l.workspace.viewTreeObserver.addOnWindowFocusChangeListener { hasFocus ->
                if (!hasFocus) leftAt = SystemClock.uptimeMillis()
            }
        } catch (_: Throwable) {}
        refreshAsync(l, wallId)

        // Gaveta: recorta o conteúdo no formato do vidro (nenhum app fica metade pra fora)
        try {
            val id = l.resources.getIdentifier("apps_view", "id", l.packageName)
            val apps = if (id != 0) l.findViewById<View>(id) else null
            if (apps != null) {
                apps.clipToOutline = true
                apps.outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(v: View, o: Outline) {
                        if (drawerRect.isEmpty) o.setRect(0, 0, v.width, v.height)
                        else o.setRoundRect(drawerRect, drawerRadius)
                    }
                }
                appsView = apps
                // Vidro como PRIMEIRO FILHO da gaveta: o Lawnchair desenha o fundo marrom antes dos filhos,
                // então o vidro fica por cima dele e por baixo dos ícones. Acompanha a gaveta sozinho.
                val oldIdx = drag.indexOfChild(panel)
                drag.removeView(panel)
                if (apps is ViewGroup) {
                    apps.addView(panel, 0, ViewGroup.MarginLayoutParams(1, 1))
                    panel.alpha = 1f
                    panel.translationY = 0f
                } else {
                    drag.addView(panel, oldIdx.coerceAtLeast(0))
                }
            }
        } catch (t: Throwable) { Log.e("GlassHost", "clip da gaveta falhou", t) }

        // Wallpaper trocado em qualquer app (Google Fotos etc.): o vidro acompanha
        try {
            val r = object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) { onWallpaperMaybeChanged(l) }
            }
            wpReceiver = r
            l.registerReceiver(r, IntentFilter(Intent.ACTION_WALLPAPER_CHANGED), Context.RECEIVER_EXPORTED)
        } catch (t: Throwable) { Log.e("GlassHost", "receiver de wallpaper falhou", t) }
        Log.i("GlassHost", "build ok: views adicionadas")

        drag.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> position(l, panel, dock) }
        drag.post { position(l, panel, dock) }

        // Tela inicial: só o dock de vidro. Gaveta aberta: só o vidro grande (fundo da gaveta).
        fun applyState(st: LauncherState) {
            drawerOpen = st == LauncherState.ALL_APPS
            val dockA = if (st == LauncherState.NORMAL) 1f else 0f
            dock.animate().alpha(dockA).setDuration(220).start()
            // Gaveta aberta: esconde a tela inicial (ícones, widgets, dock) pra não aparecer por trás do vidro
            listOf<View>(l.workspace, l.hotseat).forEach { v ->
                if (drawerOpen) {
                    v.animate().alpha(0f).setDuration(200)
                        .withEndAction { if (drawerOpen) v.visibility = View.INVISIBLE }.start()
                } else {
                    v.visibility = View.VISIBLE
                    v.animate().alpha(1f).setDuration(200).start()
                }
            }
        }
        l.stateManager.addStateListener(object : StateManager.StateListener<LauncherState> {
            override fun onStateTransitionStart(toState: LauncherState) { applyState(toState) }
            override fun onStateTransitionComplete(finalState: LauncherState) { applyState(finalState) }
        })

        controller = GlassUnlockController(l, views, listOf(l.workspace, l.hotseat)).also { it.attach() }
    }

    fun onResume() {
        controller?.onResume()
        host?.let { l ->
            onWallpaperMaybeChanged(l)
            val away = if (leftAt == 0L) 0L else SystemClock.uptimeMillis() - leftAt
            if (screenWasOff) screenWasOff = false
            else if (away >= 8000L) pendingForce = true     // voltou de outro app depois de um tempo
            logWp(l, "resume away=${away / 1000}s force=$pendingForce")
            scheduleCapture(l)
        }
    }

    /** Vidro da gaveta acompanha a gaveta: mesma posição vertical e aparece conforme ela sobe. */
    private fun syncPanelWithDrawer() {
        val apps = appsView ?: return
        val panel = views.firstOrNull() ?: return
        val h = apps.height.toFloat()
        if (h <= 0f) return
        panel.alpha = 1f; panel.translationY = 0f   // já é filho da gaveta: anda e some junto com ela
    }

    private fun isLive(c: Context): Boolean =
        try { WallpaperManager.getInstance(c).wallpaperInfo != null } catch (_: Throwable) { false }

    /**
     * Wallpaper animado não tem arquivo de imagem. Então, por root, tira uma foto da tela
     * enquanto só o wallpaper está visível (escondendo ícones e vidro por um instante).
     */
    private fun captureLive(l: Launcher, force: Boolean): Boolean {
        if (views.isEmpty()) return true
        if (!force && !isLive(l)) return true                 // imagem estática: o outro caminho cuida
        val now = SystemClock.uptimeMillis()
        if (!force && now - lastCapture < 45000) return true  // sem piscar toda hora
        if (!l.hasWindowFocus()) return false
        val ws = l.workspace
        if (ws.alpha < 0.99f || drawerOpen) return false      // só com a tela inicial parada e visível
        lastCapture = now
        pendingForce = false

        val ui = l.dragLayer                                  // toda a interface (ícones, widgets, dock, vidro)
        val prev = ui.visibility
        ui.visibility = View.INVISIBLE
        ws.postDelayed({
            Thread {
                val bmp = screenshot()?.let { fixStatusBar(it) }
                logWp(l, "captura force=$force fonte=screencap ok=${bmp != null} $lastShotInfo")
                if (bmp == null) l.runOnUiThread { rootToast(l) }
                l.runOnUiThread {
                    ui.visibility = prev
                    if (bmp != null) { views.forEach { it.setWallpaper(bmp) }; saveJpeg(l, bmp) }
                }
            }.start()
        }, 450L)
        return true
    }

    private var lastShotInfo = ""
    private var lastRootToast = 0L

    private fun screenshot(): Bitmap? = try {
        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "screencap -p"))
        val bytes = p.inputStream.readBytes()
        val err = try { p.errorStream.readBytes().decodeToString().trim().take(120) } catch (_: Throwable) { "" }
        val code = try { p.waitFor() } catch (_: Throwable) { -1 }
        p.destroy()
        lastShotInfo = "exit=$code bytes=${bytes.size} err=[$err]"
        if (bytes.size < 5000) null else BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    } catch (t: Throwable) {
        lastShotInfo = "excecao ${t.javaClass.simpleName}: ${t.message?.take(100)}"
        null
    }

    /** Aviso na tela (no máximo a cada 10 min) quando o root não foi liberado para o launcher. */
    private fun rootToast(l: Launcher) {
        val now = SystemClock.uptimeMillis()
        if (lastRootToast != 0L && now - lastRootToast < 600000L) return
        lastRootToast = now
        glassToast(l, "Vidro: sem root pra atualizar a imagem. Ative o Lawnchair no KernelSU > Superusuário.")
    }

    /** A captura traz os ícones da barra de status no topo; cobre essa faixa com o espelho do que vem logo abaixo. */
    private fun fixStatusBar(src: Bitmap): Bitmap = try {
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val sb = (out.height * 0.052f).toInt().coerceAtLeast(1)
        val c = Canvas(out)
        val m = Matrix(); m.setScale(1f, -1f); m.postTranslate(0f, 2f * sb)
        c.save(); c.clipRect(0, 0, out.width, sb); c.concat(m); c.drawBitmap(src, 0f, 0f, null); c.restore()
        out
    } catch (_: Throwable) { src }

    private fun logWp(c: Context, msg: String) {
        try {
            val f = File(c.filesDir, "glass_wp.txt")
            val old = if (f.exists()) f.readLines().takeLast(7) else emptyList()
            f.writeText((old + "[${System.currentTimeMillis() / 1000}] $msg").joinToString("\n"))
        } catch (_: Throwable) {}
    }

    /** Chamado quando a tela apaga: voltar depois disso é desbloqueio, não "voltei de outro app". */
    fun noteScreenOff() { screenWasOff = true }

    private fun wallSig(c: Context): String = try {
        val wm = WallpaperManager.getInstance(c)
        "${wm.getWallpaperId(WallpaperManager.FLAG_SYSTEM)}|${wm.wallpaperInfo?.serviceName ?: ""}"
    } catch (_: Throwable) { "" }

    private fun onWallpaperMaybeChanged(l: Launcher) {
        try {
            val sg = wallSig(l)
            if (sg == lastSig) return
            lastSig = sg
            pendingForce = true                     // wallpaper trocou: a próxima captura ignora o limite de tempo
            val id = WallpaperManager.getInstance(l).getWallpaperId(WallpaperManager.FLAG_SYSTEM)
            wallId = id
            refreshAsync(l, id)
        } catch (t: Throwable) { Log.e("GlassHost", "troca de wallpaper falhou", t) }
    }

    /** Tenta várias vezes (a cada 0,7 s) até a tela inicial estar parada e com foco, e então captura. */
    private fun scheduleCapture(l: Launcher) {
        var tries = 0
        fun attempt() {
            tries++
            if (tries > 14) { logWp(l, "desistiu: tela inicial sem foco ou em movimento"); return }
            if (!captureLive(l, pendingForce)) l.workspace.postDelayed({ attempt() }, 700)
        }
        l.workspace.postDelayed({ attempt() }, 500)
    }

    /** Lê o wallpaper atual (API do Android; se bloqueada, por root) fora da thread principal. */
    private fun refreshAsync(l: Launcher, id: Int) {
        if (isLive(l)) return    // animado: quem cuida é o captureLive()
        val prefs = l.getSharedPreferences("glass", Context.MODE_PRIVATE)
        val hasFile = File(l.filesDir, "glass_wallpaper.jpg").exists()
        if (hasFile && prefs.getInt("wpid", Int.MIN_VALUE) == id) return   // já é a imagem atual
        Thread {
            var src = "api"
            var bmp = systemWallpaper(l)
            if (bmp == null) { src = "root"; bmp = rootWallpaper() }
            logWp(l, "id=$id fonte=$src ok=${bmp != null}")
            val got = bmp
            if (got != null) {
                saveJpeg(l, got)
                prefs.edit().putInt("wpid", id).apply()
                l.runOnUiThread { views.forEach { it.setWallpaper(got) } }
            } else if (prefs.getInt("asked", Int.MIN_VALUE) != id) {
                prefs.edit().putInt("asked", id).apply()
                l.runOnUiThread { askForImage(l) }
            }
        }.start()
    }

    private fun tryRootThenPick(l: Launcher) {
        Thread {
            val got = rootWallpaper()
            logWp(l, "instalacao fonte=root ok=${got != null}")
            if (got != null) {
                saveJpeg(l, got)
                l.runOnUiThread {
                    try { build(l, got); installed = true } catch (t: Throwable) { Log.e("GlassHost", "falhou", t) }
                }
            } else l.runOnUiThread { askForImage(l) }
        }.start()
    }

    /** /data/system/users/0/wallpaper é a imagem que o Android usa de fundo; precisa de root. */
    private fun rootWallpaper(): Bitmap? = try {
        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "cat /data/system/users/0/wallpaper"))
        val bytes = p.inputStream.readBytes()
        p.destroy()
        if (bytes.size < 2000) null else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var ss = 1
            while (bounds.outWidth / (ss * 2) >= 1600 && bounds.outHeight / (ss * 2) >= 2800) ss *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = ss })
        }
    } catch (t: Throwable) { null }

    private fun saveJpeg(c: Context, b: Bitmap) {
        try {
            val sw0 = if (b.config == Bitmap.Config.HARDWARE) b.copy(Bitmap.Config.ARGB_8888, false) else b
            val sw = fixStatusBar(sw0)
            File(c.filesDir, "glass_wallpaper.jpg").outputStream().use { sw.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        } catch (_: Throwable) {}
    }

    fun uninstall() {
        controller?.detach(); controller = null; installed = false
        try { wpReceiver?.let { host?.unregisterReceiver(it) } } catch (_: Throwable) {}
        try { preDraw?.let { appsView?.viewTreeObserver?.removeOnPreDrawListener(it) } } catch (_: Throwable) {}
        preDraw = null; wpReceiver = null; host = null; appsView = null
    }

    private fun position(l: Launcher, panel: GlassView, dock: GlassView) {
        val drag = l.dragLayer
        val d = l.resources.displayMetrics.density
        if (drag.width == 0) return

        val w = drag.width.toFloat(); val h = drag.height.toFloat()
        // Medidas tiradas do print de referência (720x1612), em proporção da tela
        panel.cornerPx = 0.095f * w
        dock.cornerPx = 0.100f * w
        // Painel = fundo da gaveta de apps (só aparece quando a gaveta abre)
        panel.cornerPx = 0.085f * w
        placeFull(panel, 0.02f * w, 0.065f * h, 0.98f * w, 0.992f * h)
        drawerRect.set((0.02f * w).toInt(), (0.065f * h).toInt(), (0.98f * w).toInt(), (0.992f * h).toInt())
        drawerRadius = panel.cornerPx
        appsView?.invalidateOutline()
        placeFull(dock, 0.056f * w, 0.851f * h, 0.944f * w, 0.969f * h)
        val hs = l.hotseat
        val wp = l.deviceProfile.workspacePadding

        val cx = drag.width / 2f; val cy = drag.height * 0.45f
        panel.startCenter = PointF(cx, cy); panel.startHalf = PointF(drag.width * 0.34f, drag.width * 0.24f)
        dock.startCenter = PointF(cx, cy);  dock.startHalf = PointF(drag.width * 0.30f, drag.width * 0.14f)
        val pl = panel.layoutParams as ViewGroup.MarginLayoutParams
        Log.i("GlassHost", "pos drag=${drag.width}x${drag.height} wp=$wp panel=${pl.leftMargin},${pl.topMargin} ${pl.width}x${pl.height} hotseat=${hs.left},${hs.top},${hs.right},${hs.bottom}")
    }

    private fun placeFull(v: GlassView, l: Float, t: Float, r: Float, b: Float) {
        val lp = v.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        v.screenTarget = RectF(l, t, r, b)
        if (lp.width != ViewGroup.LayoutParams.MATCH_PARENT || lp.height != ViewGroup.LayoutParams.MATCH_PARENT ||
            lp.leftMargin != 0 || lp.topMargin != 0) {
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT; lp.height = ViewGroup.LayoutParams.MATCH_PARENT
            lp.leftMargin = 0; lp.topMargin = 0
            v.layoutParams = lp
        }
        v.invalidate()
    }

    private fun place(v: GlassView, l: Float, t: Float, r: Float, b: Float) {
        val lp = v.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        val pad = v.padPx.toInt()
        val par = v.parent as? ViewGroup
        val offX = if (par != null && par !== host?.dragLayer) par.paddingLeft else 0
        val offY = if (par != null && par !== host?.dragLayer) par.paddingTop else 0
        val nl = l.toInt() - pad - offX; val nt = t.toInt() - pad - offY
        val nw = (r - l).toInt() + 2 * pad; val nh = (b - t).toInt() + 2 * pad
        if (lp.leftMargin == nl && lp.topMargin == nt && lp.width == nw && lp.height == nh) return
        lp.leftMargin = nl; lp.topMargin = nt; lp.width = nw; lp.height = nh
        v.layoutParams = lp   // mesmo objeto (subclasse do Launcher), só dispara novo layout
    }

    private fun systemWallpaper(c: Context): Bitmap? = try {
        val dr = WallpaperManager.getInstance(c).drawable
        (dr as? BitmapDrawable)?.bitmap ?: dr?.toBitmap()
    } catch (t: Throwable) { null }

    private fun fileWallpaper(c: Context): Bitmap? = try {
        val f = File(c.filesDir, "glass_wallpaper.jpg")
        if (f.exists()) BitmapFactory.decodeFile(f.path) else null
    } catch (_: Throwable) { null }

    /** 1) o wallpaper de verdade (de qualquer app); 2) imagem escolhida no seletor do launcher. */
    private fun loadWallpaper(c: Context): Bitmap? = systemWallpaper(c) ?: fileWallpaper(c)
}


/* ------------------------------------------------------------------ *
 * Wallpaper animado SÓ PARA A TELA DE BLOQUEIO: desenha a imagem da tela inicial
 * (a última captura) com a bolha de vidro ondulando no centro. Ao desbloquear,
 * o launcher continua a animação da mesma posição e a bolha vira o dock.
 * ------------------------------------------------------------------ */
@RequiresApi(33)
class GlassLockWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = GlassEngine()

    inner class GlassEngine : Engine() {
        private val handler = Handler(Looper.getMainLooper())
        private var visibleNow = false
        private var rt: RuntimeShader? = null
        private val paint = Paint()
        private var full: Bitmap? = null
        private var sharpShader: BitmapShader? = null
        private var blurShader: BitmapShader? = null
        private var sharpScale = 1f
        private var blurScale = 1f
        private var w = 0
        private var h = 0
        private var imgStamp = -1L

        private val tick = object : Runnable {
            override fun run() {
                drawFrame()
                if (visibleNow) handler.postDelayed(this, 16L)
            }
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            w = width; h = height
            loadImage()
            drawFrame()
        }

        override fun onVisibilityChanged(visible: Boolean) {
            visibleNow = visible
            handler.removeCallbacks(tick)
            if (visible) { loadImage(); handler.post(tick) }
        }

        override fun onDestroy() {
            handler.removeCallbacks(tick)
            super.onDestroy()
        }

        private fun loadImage() {
            if (w <= 0 || h <= 0) return
            val f = File(applicationContext.filesDir, "glass_wallpaper.jpg")
            if (!f.exists()) { full = null; return }
            if (f.lastModified() == imgStamp && full != null) return
            try {
                val src = BitmapFactory.decodeFile(f.path) ?: return
                val img = glassCenterCrop(src, w, h)
                val half = Bitmap.createScaledBitmap(img, max(1, w / 2), max(1, h / 2), true)
                val quarter = Bitmap.createScaledBitmap(half, max(1, half.width / 2), max(1, half.height / 2), true)
                val light = glassBoxBlur(half, 2, 2)
                val strong = glassBoxBlur(quarter, 2, 3)
                sharpShader = BitmapShader(light, Shader.TileMode.MIRROR, Shader.TileMode.MIRROR)
                    .apply { filterMode = BitmapShader.FILTER_MODE_LINEAR }
                blurShader = BitmapShader(strong, Shader.TileMode.MIRROR, Shader.TileMode.MIRROR)
                    .apply { filterMode = BitmapShader.FILTER_MODE_LINEAR }
                sharpScale = w.toFloat() / light.width
                blurScale = w.toFloat() / strong.width
                full = img
                imgStamp = f.lastModified()
            } catch (_: Throwable) {}
        }

        private fun drawFrame() {
            val holder = surfaceHolder
            var canvas: Canvas? = null
            try {
                canvas = holder.lockHardwareCanvas()
                if (canvas == null) return
                val img = full
                if (img == null) { canvas.drawColor(Color.BLACK); return }
                canvas.drawBitmap(img, 0f, 0f, null)
                drawGlass(canvas)
            } catch (_: Throwable) {
            } finally {
                try { if (canvas != null) holder.unlockCanvasAndPost(canvas) } catch (_: Throwable) {}
            }
        }

        private fun drawGlass(canvas: Canvas) {
            val s = sharpShader ?: return
            val b = blurShader ?: return
            val sh = rt ?: try { makeShader(applicationContext).also { rt = it } } catch (_: Throwable) { return }
            val m = Matrix()
            m.setScale(sharpScale, sharpScale); s.setLocalMatrix(m)
            m.setScale(blurScale, blurScale); b.setLocalMatrix(m)
            sh.setInputShader("bgSharp", s)
            sh.setInputShader("bgBlur", b)

            val wf = w.toFloat(); val hf = h.toFloat()
            // mesma bolha de partida do dock no launcher (centro da tela, 0,45 da altura)
            val hx = 0.30f * wf; val hy = 0.14f * wf
            val r = min(hx, hy)
            sh.setFloatUniform("center", wf / 2f, hf * 0.45f)
            sh.setFloatUniform("halfSize", hx, hy)
            sh.setFloatUniform("radius", r)
            sh.setFloatUniform("wobble", r * 0.14f)
            sh.setFloatUniform("time", (SystemClock.uptimeMillis() % 100000L) / 1000f)
            sh.setFloatUniform("alpha", 1f)
            sh.setFloatUniform("zoom", 1.0f)
            sh.setFloatUniform("dark", 0f)
            sh.setFloatUniform("bevel", min(76f, 0.45f * min(hx, hy)).coerceAtLeast(8f))
            paint.shader = sh
            canvas.drawRect(0f, 0f, wf, hf, paint)
        }
    }
}

private fun glassCenterCrop(src: Bitmap, w: Int, h: Int): Bitmap {
    val sc = max(w / src.width.toFloat(), h / src.height.toFloat())
    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    Canvas(out).apply {
        translate((w - src.width * sc) / 2, (h - src.height * sc) / 2)
        scale(sc, sc)
        drawBitmap(src, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
    }
    return out
}

private fun glassBoxBlur(src: Bitmap, radius: Int, passes: Int): Bitmap {
    val w = src.width; val h = src.height
    val px = IntArray(w * h)
    src.getPixels(px, 0, w, 0, 0, w, h)
    val ch = Array(3) { IntArray(w * h) }
    for (i in px.indices) {
        val c = px[i]
        ch[0][i] = (c shr 16) and 255; ch[1][i] = (c shr 8) and 255; ch[2][i] = c and 255
    }
    val tmp = IntArray(w * h)
    for (c in 0..2) repeat(passes) {
        glassBlurLine(ch[c], tmp, w, h, radius, true)
        glassBlurLine(tmp, ch[c], w, h, radius, false)
    }
    for (i in px.indices) px[i] = (255 shl 24) or (ch[0][i] shl 16) or (ch[1][i] shl 8) or ch[2][i]
    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    out.setPixels(px, 0, w, 0, 0, w, h)
    return out
}

private fun glassBlurLine(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
    val len = if (horizontal) w else h
    val lines = if (horizontal) h else w
    val div = 2 * r + 1
    for (line in 0 until lines) {
        val base = if (horizontal) line * w else line
        val step = if (horizontal) 1 else w
        var sum = 0
        for (k in -r..r) sum += src[base + k.coerceIn(0, len - 1) * step]
        for (k in 0 until len) {
            dst[base + k * step] = (sum + div / 2) / div
            val add = (k + r + 1).coerceAtMost(len - 1)
            val rem = (k - r).coerceAtLeast(0)
            sum += src[base + add * step] - src[base + rem * step]
        }
    }
}
