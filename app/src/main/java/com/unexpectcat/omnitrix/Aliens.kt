package com.unexpectcat.omnitrix

import android.graphics.Path
import kotlin.math.hypot

class Alien(val name: String, val silhouette: Path)

/** Silhouettes are built from simple shapes in a 100x100 box (y down) and merged into one path. */
private class SilhouetteBuilder {
    private val body = Path()
    private val holes = Path()
    private val tmp = Path()

    private fun merge(block: Path.() -> Unit) {
        tmp.rewind()
        tmp.block()
        body.op(tmp, Path.Op.UNION)
    }

    fun circle(x: Float, y: Float, r: Float) = merge { addCircle(x, y, r, Path.Direction.CW) }

    fun oval(l: Float, t: Float, r: Float, b: Float) = merge { addOval(l, t, r, b, Path.Direction.CW) }

    fun poly(vararg p: Float) = merge {
        moveTo(p[0], p[1])
        for (i in 2 until p.size step 2) lineTo(p[i], p[i + 1])
        close()
    }

    /** A limb: a thick segment with rounded ends. */
    fun limb(x1: Float, y1: Float, x2: Float, y2: Float, w: Float) {
        val len = hypot(x2 - x1, y2 - y1)
        val nx = -(y2 - y1) / len * w / 2
        val ny = (x2 - x1) / len * w / 2
        poly(x1 + nx, y1 + ny, x2 + nx, y2 + ny, x2 - nx, y2 - ny, x1 - nx, y1 - ny)
        circle(x1, y1, w / 2)
        circle(x2, y2, w / 2)
    }

    fun hole(x: Float, y: Float, r: Float) = holes.addCircle(x, y, r, Path.Direction.CW)

    fun build(): Path = Path(body).apply { op(holes, Path.Op.DIFFERENCE) }
}

private fun alien(name: String, draw: SilhouetteBuilder.() -> Unit) =
    Alien(name, SilhouetteBuilder().apply(draw).build())

object Aliens {
    val all: List<Alien> by lazy {
        listOf(
            alien("HEATBLAST") {
                poly(50f, 4f, 56f, 15f, 63f, 8f, 62f, 21f, 68f, 18f, 63f, 32f, 37f, 32f, 32f, 18f, 38f, 21f, 37f, 8f, 44f, 15f)
                poly(37f, 31f, 63f, 31f, 58f, 61f, 42f, 61f)
                circle(36f, 35f, 7f); circle(64f, 35f, 7f)
                limb(37f, 37f, 25f, 60f, 8f); limb(63f, 37f, 75f, 60f, 8f)
                limb(45f, 60f, 40f, 93f, 9f); limb(55f, 60f, 60f, 93f, 9f)
            },
            alien("FOUR ARMS") {
                circle(50f, 15f, 8f)
                poly(26f, 23f, 74f, 23f, 63f, 63f, 37f, 63f)
                limb(30f, 28f, 12f, 48f, 10f); limb(70f, 28f, 88f, 48f, 10f)
                circle(11f, 51f, 7.5f); circle(89f, 51f, 7.5f)
                limb(35f, 42f, 21f, 66f, 8f); limb(65f, 42f, 79f, 66f, 8f)
                circle(20f, 69f, 6.5f); circle(80f, 69f, 6.5f)
                limb(43f, 62f, 38f, 93f, 11f); limb(57f, 62f, 62f, 93f, 11f)
            },
            alien("XLR8") {
                poly(54f, 13f, 76f, 17f, 75f, 28f, 56f, 31f, 49f, 22f)
                limb(55f, 26f, 47f, 52f, 11f)
                poly(46f, 49f, 10f, 70f, 5f, 75f, 46f, 59f)
                limb(50f, 55f, 59f, 72f, 8f); limb(59f, 72f, 53f, 90f, 6f); circle(53f, 91f, 5.5f)
                limb(45f, 56f, 39f, 74f, 7f); limb(39f, 74f, 35f, 90f, 5f); circle(35f, 91f, 5f)
                limb(56f, 35f, 67f, 46f, 4.5f)
                hole(68f, 21f, 2.6f)
            },
            alien("DIAMONDHEAD") {
                poly(50f, 3f, 61f, 14f, 58f, 27f, 42f, 27f, 39f, 14f)
                poly(37f, 31f, 20f, 15f, 30f, 38f); poly(63f, 31f, 80f, 15f, 70f, 38f)
                poly(36f, 28f, 64f, 28f, 58f, 62f, 42f, 62f)
                limb(37f, 34f, 25f, 61f, 8f); limb(63f, 34f, 75f, 61f, 8f)
                poly(21f, 60f, 25f, 79f, 30f, 61f); poly(70f, 61f, 75f, 79f, 79f, 60f)
                limb(45f, 62f, 40f, 94f, 9f); limb(55f, 62f, 60f, 94f, 9f)
            },
            alien("UPGRADE") {
                oval(37f, 6f, 63f, 34f)
                oval(35f, 30f, 65f, 64f)
                limb(39f, 38f, 25f, 64f, 7f); limb(61f, 38f, 75f, 64f, 7f)
                limb(45f, 60f, 42f, 94f, 8f); limb(55f, 60f, 58f, 94f, 8f)
                hole(50f, 19f, 6.5f)
            },
            alien("GHOSTFREAK") {
                oval(34f, 8f, 66f, 42f)
                poly(36f, 30f, 64f, 30f, 71f, 60f, 67f, 90f, 60f, 77f, 54f, 94f, 48f, 77f, 41f, 91f, 36f, 74f, 29f, 85f, 29f, 60f)
                limb(37f, 36f, 18f, 56f, 6f); limb(63f, 36f, 82f, 56f, 6f)
                poly(16f, 54f, 8f, 64f, 14f, 62f, 12f, 70f, 20f, 59f)
                poly(84f, 54f, 92f, 64f, 86f, 62f, 88f, 70f, 80f, 59f)
                hole(50f, 23f, 5.5f)
            },
            alien("STINKFLY") {
                oval(42f, 28f, 58f, 80f)
                circle(50f, 23f, 8f)
                limb(46f, 19f, 36f, 7f, 3f); limb(54f, 19f, 64f, 7f, 3f)
                circle(35f, 6f, 5f); circle(65f, 6f, 5f)
                poly(44f, 35f, 9f, 20f, 3f, 33f, 13f, 46f, 44f, 46f)
                poly(56f, 35f, 91f, 20f, 97f, 33f, 87f, 46f, 56f, 46f)
                poly(44f, 50f, 14f, 59f, 18f, 70f, 44f, 58f)
                poly(56f, 50f, 86f, 59f, 82f, 70f, 56f, 58f)
                poly(46f, 77f, 54f, 77f, 50f, 95f)
                limb(45f, 58f, 32f, 74f, 3f); limb(55f, 58f, 68f, 74f, 3f)
            },
            alien("RIPJAWS") {
                poly(50f, 5f, 67f, 13f, 71f, 31f, 29f, 31f, 33f, 13f)
                poly(46f, 7f, 50f, 0f, 54f, 7f)
                poly(32f, 29f, 68f, 29f, 62f, 58f, 38f, 58f)
                limb(34f, 36f, 19f, 58f, 7f); limb(66f, 36f, 81f, 58f, 7f)
                poly(39f, 56f, 61f, 56f, 56f, 78f, 72f, 95f, 50f, 86f, 28f, 95f, 44f, 78f)
                hole(40f, 18f, 3f); hole(60f, 18f, 3f)
            },
            alien("WILDMUTT") {
                oval(19f, 34f, 79f, 63f)
                poly(73f, 36f, 95f, 43f, 93f, 55f, 75f, 59f)
                poly(28f, 38f, 33f, 23f, 39f, 37f, 43f, 36f, 48f, 21f, 52f, 36f, 56f, 36f, 61f, 24f, 65f, 38f)
                limb(28f, 56f, 24f, 87f, 7f); limb(39f, 58f, 41f, 87f, 7f)
                limb(63f, 57f, 61f, 87f, 7f); limb(72f, 54f, 77f, 87f, 7f)
                limb(22f, 45f, 6f, 36f, 4f)
            },
            alien("GREY MATTER") {
                oval(32f, 30f, 68f, 59f)
                circle(40f, 33f, 8.5f); circle(60f, 33f, 8.5f)
                oval(40f, 54f, 60f, 77f)
                limb(42f, 61f, 31f, 72f, 3.5f); limb(58f, 61f, 69f, 72f, 3.5f)
                limb(45f, 74f, 38f, 89f, 4.5f); limb(55f, 74f, 62f, 89f, 4.5f)
                oval(30f, 87f, 41f, 92f); oval(59f, 87f, 70f, 92f)
                hole(40f, 33f, 3.5f); hole(60f, 33f, 3.5f)
            },
        )
    }
}
