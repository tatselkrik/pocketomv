package io.github.tatselkrik.statis130lite

import org.junit.Assert.*
import org.junit.Test

class BoundedQuasiNewtonTest {
    @Test fun coupledQuadraticHandlesInteriorAndActiveBounds() {
        val fit=BoundedQuasiNewton.minimize(doubleArrayOf(.9,.1),doubleArrayOf(0.0,0.0),doubleArrayOf(1.0,1.0),{ x ->
            val a=x[0]+1;val b=x[1]-.5
            BoundedQuasiNewton.Evaluation(2*a*a+2*a*b+3*b*b,doubleArrayOf(4*a+2*b,2*a+6*b))
        })
        assertTrue(fit.reason,fit.converged)
        assertArrayEquals(doubleArrayOf(0.0,1.0/6),fit.x,2e-7)
    }
    @Test fun boundedRosenbrockReachesTheConstrainedMinimum() {
        val fit=BoundedQuasiNewton.minimize(doubleArrayOf(1.4,1.0),doubleArrayOf(1.2,0.0),doubleArrayOf(2.0,1.5),{ x ->
            val a=1-x[0];val b=x[1]-x[0]*x[0]
            BoundedQuasiNewton.Evaluation(a*a+100*b*b,doubleArrayOf(-2*a-400*x[0]*b,200*b))
        })
        assertTrue(fit.reason,fit.converged);assertArrayEquals(doubleArrayOf(1.2,1.44),fit.x,3e-6)
        assertEquals(.04,fit.value,1e-10)
    }
}
