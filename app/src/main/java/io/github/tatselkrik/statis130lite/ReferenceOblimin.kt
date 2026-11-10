package io.github.tatselkrik.statis130lite

import kotlin.math.sqrt

/** Unnormalized quartimin (Oblimin gamma=0) on unit-column transformations.
 * Independent matrix-calculus implementation of oblique gradient projection.
 * L=A(T inverse) transpose, Phi=T transpose T; see statistical-methods.md.
 */
internal object ReferenceOblimin {
    data class Fit(val pattern:Array<DoubleArray>,val phi:Array<DoubleArray>,val converged:Boolean,
        val iterations:Int,val gradientNorm:Double,val trace:List<List<Double>>,val reason:String)
    private data class Evaluation(val pattern:Array<DoubleArray>,val gradient:Array<DoubleArray>,val value:Double)
    private fun evaluate(a:Array<DoubleArray>,t:Array<DoubleArray>):Evaluation {
        val p=a.size;val m=t.size
        val qr=MatrixMath.qr((0 until m).map { j->DoubleArray(m){t[it][j]} })
        val inverseColumns=(0 until m).map { j->qr.solve(DoubleArray(m){if(it==j)1.0 else 0.0}) }
        val inverse=Array(m){i->DoubleArray(m){j->inverseColumns[j][i]}}
        val l=Array(p){i->DoubleArray(m){MatrixMath.dot(a[i],inverse[it])}}
        val g=Array(p){i->DoubleArray(m){j->l[i][j]*MatrixMath.sum(DoubleArray(m){k->if(k==j)0.0 else l[i][k]*l[i][k]})}}
        val value=.25*MatrixMath.sum(DoubleArray(p){i->MatrixMath.dot(l[i],g[i])})
        val lg=Array(m){i->DoubleArray(m){j->MatrixMath.sum(DoubleArray(p){l[it][i]*g[it][j]})}}
        val gradient=Array(m){i->DoubleArray(m){j->-MatrixMath.sum(DoubleArray(m){lg[j][it]*inverse[it][i]})}}
        return Evaluation(l,gradient,value)
    }
    fun rotate(a:Array<DoubleArray>,epsilon:Double=1e-5,maxIterations:Int=1000,traceEnabled:Boolean=false):Fit {
        require(a.isNotEmpty() && a[0].isNotEmpty() && a.all { it.size==a[0].size && it.all(Double::isFinite) }) { "Oblimin needs a finite rectangular loading matrix." }
        require(epsilon>0 && epsilon.isFinite() && maxIterations in 0..1000)
        val m=a[0].size
        try { MatrixMath.qr((0 until m).map { j->DoubleArray(a.size){a[it][j]} }) }
        catch(_:IllegalArgumentException) { throw IllegalArgumentException("The loading columns are redundant or nearly redundant. Reduce the factor count before Oblimin rotation.") }
        var t=Array(m){i->DoubleArray(m){if(i==it)1.0 else 0.0}}
        var state=evaluate(a,t);var step=1.0
        val trace=mutableListOf<List<Double>>()
        fun result(ok:Boolean,iteration:Int,norm:Double,reason:String):Fit {
            val phi=Array(m){i->DoubleArray(m){j->MatrixMath.sum(DoubleArray(m){t[it][i]*t[it][j]})}}
            return Fit(state.pattern,phi,ok,iteration,norm,trace,reason)
        }
        for(iteration in 0..maxIterations) {
            val along=DoubleArray(m){j->MatrixMath.sum(DoubleArray(m){t[it][j]*state.gradient[it][j]})}
            val projected=Array(m){i->DoubleArray(m){j->state.gradient[i][j]-t[i][j]*along[j]}}
            val norm=sqrt(MatrixMath.sum(DoubleArray(m){MatrixMath.dot(projected[it],projected[it])}))
            if(traceEnabled)trace.add(listOf(iteration.toDouble(),state.value,norm,step))
            if(!norm.isFinite())return result(false,iteration,norm,"nonfinite projected gradient")
            if(norm<epsilon)return result(true,iteration,norm,"projected gradient")
            if(iteration==maxIterations)return result(false,iteration,norm,"iteration limit")
            step*=2
            var accepted=false
            for(halving in 0 until 12) {
                val raw=Array(m){i->DoubleArray(m){j->t[i][j]-step*projected[i][j]}}
                val lengths=DoubleArray(m){j->sqrt(MatrixMath.sum(DoubleArray(m){raw[it][j]*raw[it][j]}))}
                val candidate=Array(m){i->DoubleArray(m){j->raw[i][j]/lengths[j]}}
                val next=try{evaluate(a,candidate)}catch(_:IllegalArgumentException){null}
                if(next!=null && next.value.isFinite() && next.value<state.value-.5*step*norm*norm) {
                    t=candidate;state=next;accepted=true;break
                }
                step*=.5
            }
            if(!accepted)return result(false,iteration,norm,"backtracking limit")
        }
        error("unreachable")
    }
}
