package rangetarget.core.card

import kotlinx.serialization.Serializable
import org.opencv.core.Point
import rangetarget.core.geometry.PointMm
import kotlin.math.abs
import kotlin.math.hypot

/** Anything that maps card mm (+y up) to photo pixels (+y down). */
interface CardToPhoto {
    fun map(x: Double, y: Double): Pair<Double, Double>
    fun toPx(p: PointMm): Point = map(p.x, p.y).let { Point(it.first, it.second) }
    fun toMm(px: Point): PointMm

    /** Pixels per millimetre near card point [p]. */
    fun pxPerMmAt(p: PointMm = PointMm.ORIGIN): Double {
        val o = toPx(p)
        val dx = toPx(PointMm(p.x + 1.0, p.y)); val dy = toPx(PointMm(p.x, p.y + 1.0))
        return (hypot(dx.x - o.x, dx.y - o.y) + hypot(dy.x - o.x, dy.y - o.y)) / 2.0
    }
}

/**
 * The card-to-photo map used by the pipeline: a perspective map (flat paper,
 * ideal camera) plus a gentle, smooth bend on top, in pixels. The bend soaks
 * up paper that doesn't lie quite flat and the camera lens's slight bending
 * of straight lines. It is a cubic in card position, fitted to the ring lines
 * and kept small by design.
 */
@Serializable
data class CardMap(
    val homography: Homography,
    /** 10 coefficients for the x bend then 10 for the y bend, in px; empty = no bend. */
    val bendPx: List<Double> = emptyList(),
) : CardToPhoto {
    init { require(bendPx.isEmpty() || bendPx.size == 2 * TERMS) }

    override fun map(x: Double, y: Double): Pair<Double, Double> {
        val (u, v) = homography.map(x, y)
        if (bendPx.isEmpty()) return Pair(u, v)
        val b = basis(x, y)
        var dx = 0.0; var dy = 0.0
        for (k in 0 until TERMS) { dx += bendPx[k] * b[k]; dy += bendPx[TERMS + k] * b[k] }
        return Pair(u + dx, v + dy)
    }

    override fun toMm(px: Point): PointMm {
        var p = homography.toMm(px)
        if (bendPx.isEmpty()) return p
        // Newton steps: the bend is small, so this converges in a few.
        repeat(8) {
            val (u, v) = map(p.x, p.y)
            val ex = px.x - u; val ey = px.y - v
            if (abs(ex) < 1e-4 && abs(ey) < 1e-4) return p
            val (ux, vx) = map(p.x + 0.5, p.y); val (uy, vy) = map(p.x, p.y + 0.5)
            val a = (ux - u) / 0.5; val c = (vx - v) / 0.5; val b = (uy - u) / 0.5; val d = (vy - v) / 0.5
            val det = a * d - b * c
            p = PointMm(p.x + (d * ex - b * ey) / det, p.y + (-c * ex + a * ey) / det)
        }
        return p
    }

    /** Largest bend anywhere within [radiusMm] of the centre, in mm. */
    fun largestBendMm(radiusMm: Double = 260.0): Double {
        if (bendPx.isEmpty()) return 0.0
        var worst = 0.0
        var x = -radiusMm
        while (x <= radiusMm) {
            var y = -radiusMm
            while (y <= radiusMm) {
                val (u0, v0) = homography.map(x, y); val (u, v) = map(x, y)
                worst = maxOf(worst, hypot(u - u0, v - v0) / homography.pxPerMmAt(PointMm(x, y)))
                y += 20.0
            }
            x += 20.0
        }
        return worst
    }

    companion object {
        const val TERMS = 10
        private const val SCALE_MM = 250.0

        /** Beyond this radius the bend stops growing (no rings out there to support it). */
        private const val REACH_MM = 260.0

        fun basis(xIn: Double, yIn: Double): DoubleArray {
            val r = hypot(xIn, yIn)
            val k = if (r > REACH_MM) REACH_MM / r else 1.0
            val u = xIn * k / SCALE_MM; val v = yIn * k / SCALE_MM
            return doubleArrayOf(1.0, u, v, u * u, u * v, v * v, u * u * u, u * u * v, u * v * v, v * v * v)
        }

        /**
         * Fits the bend so [homography] plus bend takes each card point to its
         * pixel, by least squares with a small penalty on large coefficients
         * (so areas with few points don't swing wildly).
         */
        fun fitBend(homography: Homography, mm: List<PointMm>, px: List<Point>, penalty: Double = 1.0): CardMap {
            require(mm.size == px.size)
            if (mm.size < 3 * TERMS) return CardMap(homography)
            val ata = Array(TERMS) { DoubleArray(TERMS) }
            val atx = DoubleArray(TERMS); val aty = DoubleArray(TERMS)
            for (i in mm.indices) {
                val b = basis(mm[i].x, mm[i].y)
                val (u, v) = homography.map(mm[i].x, mm[i].y)
                val rx = px[i].x - u; val ry = px[i].y - v
                for (r in 0 until TERMS) {
                    atx[r] += b[r] * rx; aty[r] += b[r] * ry
                    for (c in 0 until TERMS) ata[r][c] += b[r] * b[c]
                }
            }
            for (r in 0 until TERMS) ata[r][r] += penalty
            val cx = solve(ata, atx); val cy = solve(ata, aty)
            return CardMap(homography, (cx + cy).toList())
        }

        /** Solves a small symmetric system by Gaussian elimination with pivoting. */
        private fun solve(aIn: Array<DoubleArray>, bIn: DoubleArray): DoubleArray {
            val n = bIn.size
            val a = Array(n) { aIn[it].copyOf() }; val b = bIn.copyOf()
            for (col in 0 until n) {
                val piv = (col until n).maxBy { abs(a[it][col]) }
                a[col] = a[piv].also { a[piv] = a[col] }; b[col] = b[piv].also { b[piv] = b[col] }
                for (r in col + 1 until n) {
                    val f = a[r][col] / a[col][col]
                    for (c in col until n) a[r][c] -= f * a[col][c]
                    b[r] -= f * b[col]
                }
            }
            val x = DoubleArray(n)
            for (r in n - 1 downTo 0) {
                var s = b[r]
                for (c in r + 1 until n) s -= a[r][c] * x[c]
                x[r] = s / a[r][r]
            }
            return x
        }
    }
}
