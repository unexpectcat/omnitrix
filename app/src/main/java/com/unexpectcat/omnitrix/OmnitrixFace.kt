package com.unexpectcat.omnitrix

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * The Omnitrix face as drawn by the app. Idle: the hourglass is a window
 * into a glowing portal whose vanishing point swings with the watch's tilt (gravity sensor) and
 * wrist motion (gyroscope); two arrows on the bezel tell the time. Tap to bring up the alien
 * hologram, tap the sides to cycle aliens, tap the middle to transform.
 */
class OmnitrixFace(context: Context) : SensorEventListener {

    private enum class Mode { IDLE, SELECT, TRANSFORM }

    private val aliens = Aliens.all
    private var mode = Mode.IDLE
    private var index = 0
    private var prevIndex = 0
    private var slide = 0f          // 1 → current alien still off to the right, 0 → centered
    private var slideDir = 1
    private var sel = 0f            // hologram overlay amount, 0..1
    private var dialAngle = 0f
    private var transformTime = 0f
    private var lastInteraction = 0f

    // ---- geometry (canvas is translated so the face center is 0,0) ----
    private var cx = 0f
    private var cy = 0f
    private var outerR = 0f
    private var r = 0f
    private val faceClip = Path()
    private val hourglass = Path()
    private val hourglassClip = Path()
    private val alienPath = Path()
    private val alienMatrix = Matrix()
    private val arrow = Path()

    // ---- paints ----
    private val grayPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tunnelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val farGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fogPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fogMatrix = Matrix()
    private val mistPaints = Array(2) { Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG) }
    private val mistMatrix = Matrix()
    private val spillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tunnelMatrix = Matrix()
    private val puffPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val puffMatrix = Matrix()
    private var puffs: Array<Bitmap> = emptyArray()
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.MITER
        strokeMiter = 10f
        color = Color.BLACK
    }
    private val bezelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.BLACK
    }
    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF050800.toInt() }
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val holoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = LIME }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = LIME }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = LIME
    }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arrowEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        color = Color.BLACK
    }
    private val ambientPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }
    private val scanPaint = Paint()
    private val scanMatrix = Matrix()
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = LIME
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        letterSpacing = 0.12f
    }
    private val flashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFEFFFC0.toInt() }
    private val transformBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF0A1500.toInt() }

    // ---- tunnel motion ----
    private var time = 0f
    private var lastNanos = 0L
    private var flow = 0f           // how far we've travelled down the tunnel, in ring spacings
    private var vpX = 0f            // vanishing point offset from the face center, px
    private var vpY = 0f
    private var vpVX = 0f
    private var vpVY = 0f
    private var restX = 0f          // slowly-adapting "neutral" wrist pose
    private var restY = 9.8f
    private var restKnown = false
    private var energy = 0.3f       // wobble and speed driver, pumped by gyroscope motion


    // ---- sensors / input ----
    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val gravitySensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_GRAVITY)
    private val accelSensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroSensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val gravity = floatArrayOf(0f, 9.8f, 0f)
    private val gyro = FloatArray(3)

    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= 31) context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        else @Suppress("DEPRECATION") context.getSystemService(Vibrator::class.java)

    fun start() {
        lastNanos = 0L
        restKnown = false
        sensorManager?.let { sm ->
            (gravitySensor ?: accelSensor)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
            gyroSensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        }
    }

    fun stop() {
        sensorManager?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_GRAVITY -> event.values.copyInto(gravity, endIndex = 3)
            Sensor.TYPE_ACCELEROMETER -> for (i in 0..2) gravity[i] += (event.values[i] - gravity[i]) * 0.15f
        }
        // Start centered on however the watch is being held when the app opens.
        if (!restKnown && event.sensor.type != Sensor.TYPE_GYROSCOPE) {
            restX = event.values[0]
            restY = event.values[1]
            gravity[0] = restX
            gravity[1] = restY
            restKnown = true
        }
        when (event.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> event.values.copyInto(gyro, endIndex = 3)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    fun setSize(w: Int, h: Int) {
        cx = w / 2f
        cy = h / 2f
        outerR = min(w, h) / 2f
        r = outerR * 0.95f

        faceClip.rewind()
        faceClip.addCircle(0f, 0f, r, Path.Direction.CW)

        // Hourglass edges run from the pinch (±0.22r, 0) out past the rim; the face clip trims them.
        hourglass.rewind()
        hourglass.moveTo(-0.985f * r, -1.2f * r)
        hourglass.lineTo(0.985f * r, -1.2f * r)
        hourglass.lineTo(0.22f * r, 0f)
        hourglass.lineTo(0.985f * r, 1.2f * r)
        hourglass.lineTo(-0.985f * r, 1.2f * r)
        hourglass.lineTo(-0.22f * r, 0f)
        hourglass.close()
        hourglassClip.set(hourglass)
        hourglassClip.op(faceClip, Path.Op.INTERSECT)

        val pr = PORTAL_R * r

        grayPaint.shader = RadialGradient(
            0f, 0f, r,
            intArrayOf(0xFFF7F7F5.toInt(), 0xFFEDEDEA.toInt(), 0xFFD6D6D2.toInt()),
            floatArrayOf(0f, 0.7f, 1f), Shader.TileMode.CLAMP,
        )
        // The portal: luminous green fog, brightest toward the far light.
        tunnelPaint.shader = RadialGradient(
            0f, 0f, 1.3f * pr,
            intArrayOf(
                0xFFFBFFE0.toInt(), 0xFFDCFF66.toInt(), 0xFF9AD82E.toInt(),
                0xFF5AA818.toInt(), 0xFF3E8410.toInt(),
            ),
            floatArrayOf(0f, 0.05f, 0.18f, 0.45f, 0.8f), Shader.TileMode.CLAMP,
        )
        // Light bleeding inward from the rim.
        spillPaint.shader = RadialGradient(
            0f, 0f, pr,
            intArrayOf(0x00D8FF70, 0x00D8FF70, 0x80D8FF70.toInt()),
            floatArrayOf(0f, 0.7f, 1f), Shader.TileMode.CLAMP,
        )
        corePaint.shader = RadialGradient(
            0f, 0f, 0.22f * r,
            intArrayOf(0xFFFFFFF0.toInt(), 0x80F0FF90.toInt(), 0x00D8FF50),
            floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP,
        )
        // Larger, softer bloom around the far light.
        farGlowPaint.shader = RadialGradient(
            0f, 0f, 0.6f * r,
            intArrayOf(0x90F8FFD0.toInt(), 0x40E0FF70, 0x00D8FF50),
            floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP,
        )
        // Glowing veil laid inside each ring; scaled per ring, so the radius here is 1 ring = r.
        fogPaint.shader = RadialGradient(
            0f, 0f, r,
            intArrayOf(0x38F4FFC4, 0x24E9FF86, 0x00E9FF86),
            floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP,
        )
        puffs = Array(3) { smokePuff(Random(40 + it)) }
        mistPaints[0].shader = mistShader(Random(3))
        mistPaints[1].shader = mistShader(Random(17))
        haloPaint.shader = RadialGradient(
            0f, 0f, r * 0.8f,
            intArrayOf(0x66B6F020, 0x00B6F020), null, Shader.TileMode.CLAMP,
        )

        borderPaint.strokeWidth = 0.06f * r
        bezelPaint.strokeWidth = outerR - r + 2f
        tickPaint.strokeWidth = 0.014f * r
        arrowEdgePaint.strokeWidth = max(1f, 0.008f * r)
        ambientPaint.strokeWidth = max(1f, 0.012f * r)
        glowPaint.maskFilter = BlurMaskFilter(0.06f * r, BlurMaskFilter.Blur.NORMAL)

        val scan = Bitmap.createBitmap(1, 5, Bitmap.Config.ARGB_8888)
        scan.setPixel(0, 0, 0x70000000)
        scan.setPixel(0, 1, 0x40000000)
        scanPaint.shader = BitmapShader(scan, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }

    // ------------------------------------------------------------------ simulation

    private fun step(dt: Float) {
        time += dt

        // Whatever pose the wrist holds becomes "looking straight in" after a couple of seconds;
        // tilting away from it swings the far end of the tunnel toward the lower side.
        val follow = 1f - exp(-dt * 0.5f)
        restX += (gravity[0] - restX) * follow
        restY += (gravity[1] - restY) * follow
        val targetX = (-(gravity[0] - restX) / 9.8f * 2.4f).coerceIn(-1f, 1f) * MAX_SWING * r
        val targetY = ((gravity[1] - restY) / 9.8f * 2.4f).coerceIn(-1f, 1f) * MAX_SWING * r

        // Underdamped spring so the portal swings and settles; wrist rotation kicks it directly.
        vpVX += ((targetX - vpX) * 38f - vpVX * 6f) * dt + gyro[1] * 1.8f * r * dt
        vpVY += ((targetY - vpY) * 38f - vpVY * 6f) * dt - gyro[0] * 1.8f * r * dt
        vpX += vpVX * dt
        vpY += vpVY * dt
        val swing = hypot(vpX, vpY)
        if (swing > MAX_SWING * r) {
            vpX *= MAX_SWING * r / swing
            vpY *= MAX_SWING * r / swing
        }

        val spin = sqrt(gyro[0] * gyro[0] + gyro[1] * gyro[1] + gyro[2] * gyro[2])
        energy = min(1.6f, energy + spin * dt * 1.1f)
        energy = 0.25f + (energy - 0.25f) * exp(-dt * 0.9f)
        flow += dt * (0.35f + 0.9f * energy)

        val selTarget = if (mode == Mode.SELECT || (mode == Mode.TRANSFORM && transformTime < 0.6f)) 1f else 0f
        sel += (selTarget - sel) * (1f - exp(-dt * 9f))
        slide *= exp(-dt * 10f)
        if (abs(slide) < 0.002f) slide = 0f
        val dialTarget = -index * 360f / aliens.size
        dialAngle += (dialTarget - dialAngle) * (1f - exp(-dt * 10f))

        if (mode == Mode.SELECT && time - lastInteraction > SELECT_TIMEOUT) mode = Mode.IDLE
        if (mode == Mode.TRANSFORM) {
            transformTime += dt
            if (transformTime > TRANSFORM_LENGTH) mode = Mode.IDLE
        }
    }

    /** Perspective scale for a point `d` of the way down the tunnel (0 = at the glass, 1 = far end). */
    private fun depthScale(d: Float) = exp(-3.6f * d)

    // ------------------------------------------------------------------ drawing

    /** Draws one frame. In [ambient] the face is a still, dim outline and the simulation pauses. */
    fun draw(canvas: Canvas, hour: Int, minute: Int, second: Int, ambient: Boolean) {
        if (ambient) {
            mode = Mode.IDLE
            sel = 0f
            lastNanos = 0L
        } else {
            val now = System.nanoTime()
            val dt = if (lastNanos == 0L) 1f / 60f else ((now - lastNanos) / 1e9f).coerceIn(0f, 0.05f)
            lastNanos = now
            step(dt)
        }

        canvas.drawColor(if (ambient) Color.BLACK else 0xFF1B1B1B.toInt())
        canvas.save()
        canvas.translate(cx, cy)
        if (ambient) {
            drawAmbient(canvas)
        } else {
            drawSymbol(canvas)
            if (sel > 0.003f) drawHologram(canvas)
            if (mode == Mode.TRANSFORM) drawTransform(canvas)
        }
        val minuteAngle = (minute + second / 60f) * 6f
        val hourAngle = (hour % 12 + minute / 60f) * 30f
        drawArrow(canvas, hourAngle, 0.035f * r, 0.99f * r, if (ambient) 0xFFB0B0B0.toInt() else Color.WHITE, ambient)
        drawArrow(canvas, minuteAngle, 0.045f * r, 0.965f * r, if (ambient) 0xFF8FB872.toInt() else MINUTE_GREEN, ambient)
        canvas.restore()
    }

    /** A small arrow on the bezel pointing inward, its tip just crossing the face's edge. */
    private fun drawArrow(c: Canvas, deg: Float, halfWidth: Float, tipR: Float, color: Int, ambient: Boolean) {
        val a = deg * DEG
        val ux = sin(a)
        val uy = -cos(a)
        val baseR = outerR * 0.995f
        arrow.rewind()
        arrow.moveTo(ux * tipR, uy * tipR)
        arrow.lineTo(ux * baseR - uy * halfWidth, uy * baseR + ux * halfWidth)
        arrow.lineTo(ux * baseR + uy * halfWidth, uy * baseR - ux * halfWidth)
        arrow.close()
        if (!ambient) {
            glowPaint.alpha = 160
            c.drawPath(arrow, glowPaint)
        }
        arrowPaint.color = color
        c.drawPath(arrow, arrowPaint)
        c.drawPath(arrow, arrowEdgePaint)
    }

    /** Low-power look: outlines only, nothing moving, mostly black pixels. */
    private fun drawAmbient(c: Canvas) {
        ambientPaint.color = 0xFF3A3A3A.toInt()
        c.save()
        c.clipPath(faceClip)
        c.drawPath(hourglass, ambientPaint)
        c.restore()
        c.drawCircle(0f, 0f, r, ambientPaint)
    }

    private fun drawSymbol(c: Canvas) {
        c.drawCircle(0f, 0f, r, grayPaint)

        c.save()
        c.clipPath(hourglassClip)
        drawTunnel(c)
        c.restore()

        c.save()
        c.clipPath(faceClip)
        c.drawPath(hourglass, borderPaint)
        c.restore()

        c.drawCircle(0f, 0f, (outerR + r) / 2f, bezelPaint)
    }

    /** The portal fills the whole hourglass window. */
    private fun drawTunnel(c: Canvas) {
        tunnelMatrix.setTranslate(vpX, vpY)
        tunnelPaint.shader.setLocalMatrix(tunnelMatrix)
        c.drawPaint(tunnelPaint)

        val mouth = PORTAL_R * r

        // Rings of smoke clouds receding forever: each shrinks toward the far end and wraps back to the glass.
        // Drawn far to near, each with a veil of fog, so deeper rings sit behind more fog.
        val wobble = 0.006f + 0.028f * energy
        val farthest = (RINGS - 1 - kotlin.math.floor(flow).toInt()).mod(RINGS)
        for (n in 0 until RINGS) {
            val i = (farthest - n).mod(RINGS)
            val d = frac((i + flow) / RINGS)
            val s = depthScale(d)
            val rad = mouth * s
            val ox = vpX * (1f - s)
            val oy = vpY * (1f - s)
            val a = fade(d)
            // Neighbouring rings drift in opposite directions so the smoke churns.
            val drift = time * 0.25f * if (i % 2 == 0) 1f else -1f
            val size = rad * 0.8f
            puffPaint.alpha = (a * 200).toInt()
            for (j in 0 until PUFFS) {
                val th = (j + 0.5f * (i % 2)) * 2f * PI.toFloat() / PUFFS + drift
                val rr = rad * (1f + wobble * sin(4f * th + i * 1.7f + time * 2.2f) +
                    wobble * 0.5f * sin(7f * th - time * 3.1f + i))
                val puff = puffs[(i + j) % puffs.size]
                puffMatrix.setTranslate(-puff.width / 2f, -puff.height / 2f)
                puffMatrix.postScale(size / puff.width, size / puff.width)
                puffMatrix.postRotate(th / DEG + time * if (j % 2 == 0) 15f else -15f)
                puffMatrix.postTranslate(ox + cos(th) * rr, oy + sin(th) * rr)
                c.drawBitmap(puff, puffMatrix, puffPaint)
            }

            fogMatrix.setScale(s * mouth / r, s * mouth / r)
            fogMatrix.postTranslate(ox, oy)
            fogPaint.shader.setLocalMatrix(fogMatrix)
            fogPaint.alpha = (a * 255).toInt()
            c.drawCircle(ox, oy, rad, fogPaint)
        }

        // Two layers of mist swirling in opposite directions around the far end.
        for (m in 0..1) {
            val k = (if (m == 0) 2.0f else 1.7f) * mouth / MIST_SIZE
            mistMatrix.setTranslate(-MIST_SIZE / 2f, -MIST_SIZE / 2f)
            mistMatrix.postScale(k, k)
            mistMatrix.postRotate(if (m == 0) time * 9f else -time * 6f + 40f)
            mistMatrix.postTranslate(vpX, vpY)
            mistPaints[m].shader.setLocalMatrix(mistMatrix)
            mistPaints[m].alpha = ((0.55f + 0.15f * sin(time * 0.7f + m * 2f)) * 255).toInt()
            c.drawPaint(mistPaints[m])
        }

        // Pulsing light at the far end.
        farGlowPaint.shader.setLocalMatrix(tunnelMatrix)
        farGlowPaint.alpha = ((0.8f + 0.2f * sin(time * 2.4f)) * 255).toInt()
        c.drawCircle(vpX, vpY, 0.6f * r, farGlowPaint)
        corePaint.shader.setLocalMatrix(tunnelMatrix)
        corePaint.alpha = ((0.75f + 0.25f * sin(time * 2.4f)) * 255).toInt()
        c.drawCircle(vpX, vpY, 0.22f * r, corePaint)

        c.drawCircle(0f, 0f, mouth, spillPaint)
    }

    /** One cloud of smoke: a few overlapping soft blobs, brightest where they pile up. */
    private fun smokePuff(rnd: Random): Bitmap {
        val bmp = Bitmap.createBitmap(PUFF_SIZE, PUFF_SIZE, Bitmap.Config.ARGB_8888)
        val bc = Canvas(bmp)
        val blob = Paint(Paint.ANTI_ALIAS_FLAG)
        val half = PUFF_SIZE / 2f
        repeat(7) {
            val ang = rnd.nextFloat() * 2f * PI.toFloat()
            val dist = rnd.nextFloat() * 0.2f * PUFF_SIZE
            val br = (0.14f + rnd.nextFloat() * 0.14f) * PUFF_SIZE
            val bx = half + cos(ang) * dist
            val by = half + sin(ang) * dist
            blob.shader = RadialGradient(
                bx, by, br, intArrayOf(0x58F0FFC8, 0x00F0FFC8), null, Shader.TileMode.CLAMP,
            )
            bc.drawCircle(bx, by, br, blob)
        }
        return bmp
    }

    /** Soft blobs of mist, denser toward the middle, fading to nothing before the bitmap edge. */
    private fun mistShader(rnd: Random): Shader {
        val bmp = Bitmap.createBitmap(MIST_SIZE, MIST_SIZE, Bitmap.Config.ARGB_8888)
        val bc = Canvas(bmp)
        val blob = Paint(Paint.ANTI_ALIAS_FLAG)
        val half = MIST_SIZE / 2f
        repeat(36) {
            val ang = rnd.nextFloat() * 2f * PI.toFloat()
            val dist = sqrt(rnd.nextFloat()) * 0.3f * MIST_SIZE
            val br = (0.06f + rnd.nextFloat() * 0.12f) * MIST_SIZE
            val bx = half + cos(ang) * dist
            val by = half + sin(ang) * dist
            blob.shader = RadialGradient(
                bx, by, br, intArrayOf(0x40EEFFB0, 0x00EEFFB0), null, Shader.TileMode.CLAMP,
            )
            bc.drawCircle(bx, by, br, blob)
        }
        return BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
    }

    private fun drawHologram(c: Canvas) {
        val a = sel.coerceIn(0f, 1f)
        dimPaint.alpha = (a * 225).toInt()
        c.drawCircle(0f, 0f, outerR + 2f, dimPaint)

        haloPaint.alpha = (a * 255).toInt()
        c.drawCircle(0f, 0f, r * 0.8f, haloPaint)

        // Rotating dial ticks; the dial turns one notch per alien.
        c.save()
        c.rotate(dialAngle)
        for (i in 0 until 60) {
            val major = i % 6 == 0
            val ang = i * 6f * DEG
            val inner = if (major) 0.86f * r else 0.91f * r
            tickPaint.alpha = (a * if (major) 230 else 110).toInt()
            c.drawLine(sin(ang) * inner, -cos(ang) * inner, sin(ang) * 0.97f * r, -cos(ang) * 0.97f * r, tickPaint)
        }
        c.restore()
        tickPaint.alpha = (a * 255).toInt()
        c.drawLine(0f, -0.99f * r, 0f, -0.82f * r, tickPaint)

        val size = 1.1f * r
        val shift = 1.3f * r
        if (slide != 0f) drawAlien(c, aliens[prevIndex], (slide - slideDir) * shift, -0.08f * r, size, a * abs(slide))
        drawAlien(c, aliens[index], slide * shift, -0.08f * r, size, a * (1f - abs(slide)))

        textPaint.textSize = 0.12f * r
        textPaint.alpha = (a * (1f - abs(slide)) * 255).toInt()
        c.drawText(aliens[index].name, slide * shift, 0.72f * r, textPaint)

        scanMatrix.setTranslate(0f, time * 12f)
        scanPaint.shader.setLocalMatrix(scanMatrix)
        scanPaint.alpha = (a * 255).toInt()
        c.drawCircle(0f, 0f, 0.84f * r, scanPaint)
    }

    private fun drawAlien(c: Canvas, alien: Alien, x: Float, y: Float, size: Float, alpha: Float) {
        if (alpha <= 0.003f) return
        alienMatrix.setScale(size / 100f, size / 100f)
        alienMatrix.postTranslate(x - size / 2f, y - size / 2f)
        alien.silhouette.transform(alienMatrix, alienPath)
        glowPaint.alpha = (alpha * 200).toInt()
        c.drawPath(alienPath, glowPaint)
        holoPaint.alpha = (alpha * 255).toInt()
        c.drawPath(alienPath, holoPaint)
    }

    private fun drawTransform(c: Canvas) {
        val t = transformTime
        val shown = smooth(0.35f, 0.6f, t) * (1f - smooth(TRANSFORM_LENGTH - 0.6f, TRANSFORM_LENGTH, t))
        if (shown > 0f) {
            transformBgPaint.alpha = (shown * 255).toInt()
            c.drawCircle(0f, 0f, outerR + 2f, transformBgPaint)
            haloPaint.alpha = (shown * 255).toInt()
            c.drawCircle(0f, 0f, r * 0.8f, haloPaint)
            val grow = 1.15f + 0.15f * smooth(0.4f, 1.4f, t)
            drawAlien(c, aliens[index], 0f, -0.1f * r, grow * r, shown)
            textPaint.textSize = 0.15f * r
            textPaint.alpha = (shown * 255).toInt()
            c.drawText(aliens[index].name, 0f, 0.78f * r, textPaint)
        }

        if (t < 0.45f) {
            val p = t / 0.45f
            flashPaint.alpha = 255
            c.drawCircle(0f, 0f, outerR * 1.2f * (1f - (1f - p) * (1f - p) * (1f - p)), flashPaint)
        } else {
            val fade = 1f - (t - 0.45f) / 0.45f
            if (fade > 0f) {
                flashPaint.alpha = (fade * 255).toInt()
                c.drawCircle(0f, 0f, outerR + 2f, flashPaint)
            }
        }
    }

    // ------------------------------------------------------------------ input

    /** A tap at view coordinates: wakes the hologram, then the sides cycle aliens and the middle transforms. */
    fun tap(x: Float, y: Float) {
        lastInteraction = time
        when (mode) {
            Mode.IDLE -> {
                mode = Mode.SELECT
                buzz(VibrationEffect.EFFECT_CLICK)
            }
            Mode.SELECT -> {
                val dx = x - cx
                if (abs(dx) > 0.35f * outerR) {
                    swipe(if (dx > 0) 1 else -1)
                } else {
                    mode = Mode.TRANSFORM
                    transformTime = 0f
                    energy = 1.6f
                    vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 60, 80, 180), -1))
                }
            }
            Mode.TRANSFORM -> Unit
        }
    }

    /** Step to the next (1) or previous (-1) alien. */
    fun swipe(dir: Int) {
        if (mode == Mode.TRANSFORM) return
        lastInteraction = time
        mode = Mode.SELECT
        prevIndex = index
        index = (index + dir).mod(aliens.size)
        slideDir = dir
        slide = dir.toFloat()
        energy = min(1.6f, energy + 0.4f)
        buzz(VibrationEffect.EFFECT_TICK)
    }

    private fun buzz(effect: Int) {
        vibrator?.vibrate(VibrationEffect.createPredefined(effect))
    }

    companion object {
        private const val LIME = 0xFFC6F432.toInt()
        private const val MINUTE_GREEN = 0xFFB4F58C.toInt()
        private const val MAX_SWING = 0.4f
        private const val RINGS = 14
        private const val PUFFS = 10
        private const val PUFF_SIZE = 96
        private const val MIST_SIZE = 256
        private const val PORTAL_R = 1f
        private const val SELECT_TIMEOUT = 8f
        private const val TRANSFORM_LENGTH = 3.4f
        private const val DEG = (PI / 180).toFloat()

        private fun frac(x: Float) = x - kotlin.math.floor(x)

        /** Fade in just past the glass and out toward the far end so rings never pop. */
        private fun fade(d: Float) = smooth(0f, 0.08f, d) * (1f - smooth(0.7f, 1f, d))

        private fun smooth(e0: Float, e1: Float, x: Float): Float {
            val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }
    }
}
