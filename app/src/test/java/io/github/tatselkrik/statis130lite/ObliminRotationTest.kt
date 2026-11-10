package io.github.tatselkrik.statis130lite

import kotlin.math.abs
import kotlin.math.sqrt
import org.junit.Assert.*
import org.junit.Test

class ObliminRotationTest {
    @Test fun knownCorrelatedSimpleStructureAndReproducedCovarianceAreRecovered() {
        val rho=.4;val residual=sqrt(1-rho*rho)
        val a=arrayOf(doubleArrayOf(.8,0.0),doubleArrayOf(.7,0.0),doubleArrayOf(.6,0.0),
            doubleArrayOf(rho*.8,residual*.8),doubleArrayOf(rho*.7,residual*.7),doubleArrayOf(rho*.6,residual*.6))
        val fit=ReferenceOblimin.rotate(a)
        assertTrue(fit.reason,fit.converged)
        assertEquals(rho,abs(fit.phi[0][1]),5e-5)
        for(i in a.indices)for(j in a.indices) {
            val reproduced=fit.pattern[i].indices.sumOf { k->fit.pattern[j].indices.sumOf { l->fit.pattern[i][k]*fit.phi[k][l]*fit.pattern[j][l] } }
            assertEquals(MatrixMath.dot(a[i],a[j]),reproduced,2e-12)
        }
        for(j in fit.phi.indices)assertEquals(1.0,fit.phi[j][j],2e-15)
        assertTrue(fit.pattern.indices.all { i->abs(fit.pattern[i].minBy { abs(it) })<5e-5 })
    }
    @Test fun zeroRowsAreValidWithoutKaiserNormalizationAndRedundantAxesAreGuarded() {
        val fit=ReferenceOblimin.rotate(arrayOf(doubleArrayOf(0.0,0.0),doubleArrayOf(.8,.1),doubleArrayOf(.7,.2),doubleArrayOf(.1,.8),doubleArrayOf(.2,.7)))
        assertTrue(fit.reason,fit.converged);assertArrayEquals(doubleArrayOf(0.0,0.0),fit.pattern[0],0.0)
        val failure=assertThrows(IllegalArgumentException::class.java) { ReferenceOblimin.rotate(arrayOf(doubleArrayOf(.8,.8),doubleArrayOf(.7,.7),doubleArrayOf(.1,.1))) }
        assertTrue(failure.message!!.contains("redundant"))
    }
    @Test fun anIterationLimitRemainsAnUnconvergedFiniteResult() {
        val a=arrayOf(doubleArrayOf(.8,.1),doubleArrayOf(.7,.2),doubleArrayOf(.1,.8),doubleArrayOf(.2,.7))
        val fit=ReferenceOblimin.rotate(a,maxIterations=0)
        assertFalse(fit.converged);assertEquals("iteration limit",fit.reason)
        assertTrue(fit.gradientNorm>1e-5);assertTrue(fit.pattern.all { it.all(Double::isFinite) })
    }
}
