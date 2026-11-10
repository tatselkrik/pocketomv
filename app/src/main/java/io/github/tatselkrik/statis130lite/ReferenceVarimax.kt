package io.github.tatselkrik.statis130lite

import kotlin.math.sqrt

/** Kaiser-normalized polar iteration with relative singular-sum stopping 1e-5.
 * Independently composed from matrix definitions; no reference implementation copied.
 * A stationary rotation need not maximize the criterion; the tight plane option remains.
 */
internal object ReferenceVarimax {
    fun rotate(a: Array<DoubleArray>): Array<DoubleArray> {
        val p=a.size;val m=a[0].size
        if(m==1)return Array(p){a[it].copyOf()}
        val scales=DoubleArray(p){sqrt(MatrixMath.dot(a[it],a[it]))}
        require(scales.all { it>0 && it.isFinite() }) { "Kaiser normalization needs nonzero row norms" }
        val x=Array(p){i->DoubleArray(m){a[i][it]/scales[i]}}
        var t=Array(m){i->DoubleArray(m){if(i==it)1.0 else 0.0}}
        var previous=0.0
        repeat(1000) {
            val z=Array(p){i->DoubleArray(m){j->MatrixMath.sum(DoubleArray(m){x[i][it]*t[it][j]})}}
            val ss=DoubleArray(m){j->MatrixMath.sum(DoubleArray(p){z[it][j]*z[it][j]})}
            val b=Array(m){i->DoubleArray(m){j->MatrixMath.sum(DoubleArray(p){r->x[r][i]*(z[r][j]*z[r][j]*z[r][j]-z[r][j]*ss[j]/p)})}}
            val gram=Array(m){i->DoubleArray(m){j->MatrixMath.sum(DoubleArray(m){b[it][i]*b[it][j]})}}
            val e=MatrixMath.eigen(gram);val singular=DoubleArray(m){sqrt(e.values[it].coerceAtLeast(0.0))}
            val u=Array(m){DoubleArray(m)}
            for(k in 0 until m) {
                var col=DoubleArray(m){i->MatrixMath.sum(DoubleArray(m){b[i][it]*e.vectors[it][k]})}
                fun orthogonalize(v:DoubleArray):DoubleArray {
                    val out=v.copyOf()
                    repeat(2){for(j in 0 until k){val d=MatrixMath.sum(DoubleArray(m){out[it]*u[it][j]});for(i in 0 until m)out[i]-=d*u[i][j]}}
                    return out
                }
                col=orthogonalize(col)
                if(sqrt(MatrixMath.dot(col,col))<=maxOf(1e-15,singular[0]*1e-12)) {
                    col=(0 until m).map { axis->orthogonalize(DoubleArray(m){if(it==axis)1.0 else 0.0}) }.maxBy { MatrixMath.dot(it,it) }
                }
                val norm=sqrt(MatrixMath.dot(col,col));require(norm>1e-15) { "Varimax could not form a stable orthogonal rotation. Try tight plane rotations or fewer factors." }
                for(i in 0 until m)u[i][k]=col[i]/norm
            }
            t=Array(m){i->DoubleArray(m){j->MatrixMath.sum(DoubleArray(m){u[i][it]*e.vectors[j][it]})}}
            val next=MatrixMath.sum(singular)
            // The zero polar matrix has no unique orientation; choose the identity
            // completion, which remains stationary through every further update.
            if(next==0.0 || next<previous*(1+1e-5))return Array(p){i->DoubleArray(m){j->scales[i]*MatrixMath.sum(DoubleArray(m){x[i][it]*t[it][j]})}}
            previous=next
        }
        throw IllegalArgumentException("Varimax polar iteration did not converge within 1,000 iterations. Try tight plane rotations or fewer factors.")
    }
}
