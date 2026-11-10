package io.github.tatselkrik.statis130lite

import kotlin.math.sqrt
import org.junit.Assert.*
import org.junit.Test

class VarimaxRotationTest {
    private fun criterion(a:Array<DoubleArray>):Double {
        val z=a.map { row->val norm=sqrt(MatrixMath.dot(row,row));DoubleArray(row.size){row[it]/norm} }
        return a[0].indices.sumOf { j->val squares=z.map { it[j]*it[j] };squares.sumOf { it*it }-squares.sum()*squares.sum()/a.size }
    }
    @Test fun stationaryReferenceSolutionAndTighterMaximumRemainDistinctChoices() {
        val a=arrayOf(doubleArrayOf(.5,.5),doubleArrayOf(.5,-.5),doubleArrayOf(.5,.5),doubleArrayOf(.5,-.5))
        val reference=ReferenceVarimax.rotate(a);val tight=ExploratoryFactors.varimax(a)
        assertEquals(0.0,criterion(reference),1e-12)
        assertTrue(criterion(tight)>1.5)
        for(rotated in listOf(reference,tight))for(i in a.indices)for(j in a.indices)
            assertEquals(MatrixMath.dot(a[i],a[j]),MatrixMath.dot(rotated[i],rotated[j]),1e-12)
    }
    @Test fun zeroKaiserNormHasAnExplainedGuardAndOneFactorIsUnchanged() {
        val e=assertThrows(IllegalArgumentException::class.java){ReferenceVarimax.rotate(arrayOf(doubleArrayOf(0.0,0.0),doubleArrayOf(.8,.2)))}
        assertTrue(e.message!!.contains("nonzero row norms"))
        val a=arrayOf(doubleArrayOf(.8),doubleArrayOf(-.4),doubleArrayOf(0.0))
        val result=ReferenceVarimax.rotate(a)
        a.indices.forEach { assertArrayEquals(a[it],result[it],0.0) }
    }
}
