package io.github.tatselkrik.statis130lite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DescriptivesTest {
    private val request = AnalysisRequest(method = AnalysisMethod.DESCRIPTIVES)
    private val fixture = parseDelimited("Score,Weight\n1,1\n2,2\n2,1\n4,3\n5,3")

    @Test fun unweightedSummariesMatchHandCalculatedValues() {
        val result = Statistics.run(fixture, request)
        val summary = result.descriptives!!
        val numeric = summary.numeric!!
        assertNull(result.test)
        assertEquals(5, summary.validCount)
        assertEquals(0, summary.missingCount)
        assertEquals(2.8, numeric.mean, 1e-12)
        assertEquals(2.0, numeric.median, 0.0)
        assertEquals(2.7, numeric.variance!!, 1e-12)
        assertEquals(kotlin.math.sqrt(2.7), numeric.standardDeviation!!, 1e-12)
        assertEquals(listOf("2"), summary.modes)
        assertEquals(2, summary.modeCount)
        assertEquals(listOf(20.0, 40.0, 20.0, 20.0), summary.frequencies.map { it.percent })
    }

    @Test fun populationChoiceChangesOnlyDispersion() {
        val sample = Statistics.run(fixture, request).descriptives!!.numeric!!
        val population = Statistics.run(fixture, request.copy(population = true)).descriptives!!.numeric!!
        assertEquals(2.16, population.variance!!, 1e-12)
        assertEquals(kotlin.math.sqrt(2.16), population.standardDeviation!!, 1e-12)
        assertEquals(sample.mean, population.mean, 0.0)
        assertEquals(sample.percentiles, population.percentiles)
        assertEquals(sample.histogram, population.histogram)
    }

    @Test fun weightsChangeOnlyTheWeightedMean() {
        val plain = Statistics.run(fixture, request).descriptives!!
        val weighted = Statistics.run(fixture, request.copy(weight = 1)).descriptives!!
        assertEquals(3.4, weighted.numeric!!.weightedMean!!.value, 1e-12)
        assertEquals(plain.numeric!!.mean, weighted.numeric.mean, 0.0)
        assertEquals(plain.numeric.variance, weighted.numeric.variance)
        assertEquals(plain.frequencies, weighted.frequencies)
        assertEquals(plain.numeric.histogram, weighted.numeric.histogram)
    }

    @Test fun missingAndZeroWeightsDoNotRemoveObservationsFromOtherSummaries() {
        val data = parseDelimited("Score,Weight\n1,1\n2,NA\n3,0\nNA,-7\n5,2")
        val summary = Statistics.run(data, request.copy(weight = 1)).descriptives!!
        assertEquals(4, summary.validCount)
        assertEquals(1, summary.missingCount)
        assertEquals(2.75, summary.numeric!!.mean, 1e-12)
        val weighted = summary.numeric.weightedMean!!
        assertEquals(11.0 / 3, weighted.value, 1e-12)
        assertEquals(2, weighted.positiveCases)
        assertEquals(1, weighted.missingWeights)
        assertEquals(1, weighted.zeroWeights)
    }

    @Test fun invalidAndAllZeroWeightsProduceActionableErrors() {
        listOf("-1", "Infinity", "invalid").forEach { weight ->
            val data = parseDelimited("Score,Weight\n1,$weight\n2,1")
            assertThrows(IllegalArgumentException::class.java) { Statistics.run(data, request.copy(weight = 1)) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            Statistics.run(parseDelimited("Score,Weight\n1,0\n2,0"), request.copy(weight = 1))
        }
        assertThrows(IllegalArgumentException::class.java) { Statistics.run(fixture, request.copy(weight = 0)) }
        assertThrows(IllegalArgumentException::class.java) { Statistics.run(fixture, request.copy(weight = 9)) }
    }

    @Test fun largeWeightsAreRescaledWithoutChangingTheMean() {
        val data = parseDelimited("Score,Weight\n10,1e308\n20,1e308")
        val weighted = Statistics.run(data, request.copy(weight = 1)).descriptives!!.numeric!!.weightedMean!!
        assertEquals(15.0, weighted.value, 1e-12)
    }

    @Test fun percentilesUseRTypeSevenIncludingEndpoints() {
        val summary = Statistics.run(fixture, request.copy(percentiles = "100; 75,25 0,50,25")).descriptives!!
        assertEquals(listOf(0.0, 25.0, 50.0, 75.0, 100.0), summary.numeric!!.percentiles.map { it.percent })
        assertEquals(listOf(1.0, 2.0, 2.0, 4.0, 5.0), summary.numeric.percentiles.map { it.value })
        val interpolated = Statistics.run(parseDelimited("X\n1\n2\n4\n8"), request).descriptives!!.numeric!!
        assertEquals(listOf(1.75, 3.0, 5.0), interpolated.percentiles.map { it.value })
    }

    @Test fun invalidPercentilesAndHistogramChoicesAreRejected() {
        listOf("-1", "101", "NaN", "25,wrong", (0..20).joinToString(",")).forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { Statistics.run(fixture, request.copy(percentiles = value)) }
        }
        listOf("0", "51", "2.5", "bins").forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { Statistics.run(fixture, request.copy(histogramBins = value)) }
        }
        assertTrue(Statistics.run(fixture, request.copy(percentiles = "")).descriptives!!.numeric!!.percentiles.isEmpty())
    }

    @Test fun histogramCountsIncludeTheUpperEndpointAndUseLeftClosedIntervals() {
        val data = parseDelimited("X\n0\n1\n2\n3\n4")
        val histogram = Statistics.run(data, request.copy(histogramBins = "2")).descriptives!!.numeric!!.histogram
        assertEquals(listOf(HistogramBin(0.0, 2.0, 2), HistogramBin(2.0, 4.0, 3)), histogram)
        assertEquals(5, histogram.sumOf { it.count })
    }

    @Test fun automaticBinsAndConstantDataHaveHonestIntervals() {
        val automatic = Statistics.run(fixture, request).descriptives!!.numeric!!.histogram
        assertEquals(4, automatic.size)
        assertEquals(listOf(1, 2, 0, 2), automatic.map { it.count })
        val constant = Statistics.run(parseDelimited("X\n7\n7\n7"), request).descriptives!!
        assertEquals(listOf("7"), constant.modes)
        assertEquals(0.0, constant.numeric!!.variance!!, 0.0)
        assertEquals(listOf(HistogramBin(7.0, 7.0, 3)), constant.numeric.histogram)
    }

    @Test fun singleObservationDoesNotInventASampleVariance() {
        val data = parseDelimited("X\n7")
        val sample = Statistics.run(data, request).descriptives!!.numeric!!
        assertNull(sample.variance)
        assertNull(sample.standardDeviation)
        val population = Statistics.run(data, request.copy(population = true)).descriptives!!.numeric!!
        assertEquals(0.0, population.variance!!, 0.0)
        assertEquals(0.0, population.standardDeviation!!, 0.0)
        assertEquals(listOf(7.0, 7.0, 7.0), sample.percentiles.map { it.value })
    }

    @Test fun tiesAndUniqueValuesHaveExplicitModeRules() {
        val multiple = Statistics.run(parseDelimited("X\n1\n1\n2\n2\n3"), request).descriptives!!
        assertEquals(listOf("1", "2"), multiple.modes)
        val unique = Statistics.run(parseDelimited("X\n1\n2\n3"), request)
        assertEquals(listOf("1","2","3"),unique.descriptives!!.modes)
        assertEquals("1",unique.descriptives.mode)
        assertTrue(unique.asReport("Practice", true).contains("Tied modes"))
    }

    @Test fun categoricalSummariesPreserveLabelsWithoutInventingNumericStatistics() {
        val data = CourseData(listOf("Category"), listOf("A", "A", "B", "NA", "N/A", "").map { listOf(it) })
        val result = Statistics.run(data, request.copy(descriptiveNumeric = false))
        val summary = result.descriptives!!
        assertNull(result.test)
        assertNull(summary.numeric)
        assertEquals(5, summary.validCount)
        assertEquals(1, summary.missingCount)
        assertEquals(listOf("A"), summary.modes)
        assertEquals(5, summary.frequencies.sumOf { it.count })
        assertTrue(summary.frequencies.any { it.value == "NA" })
        assertFalse(result.asReport("Practice", true).contains("Variance"))
    }

    @Test fun aUniformFrequencyDistributionUsesTheFirstSortedModeAndRetainsAllTies() {
        val result = Statistics.run(parseDelimited("X\n1\n1\n2\n2"), request)
        assertEquals(listOf("1","2"),result.descriptives!!.modes)
        assertEquals("1",result.descriptives.mode)
        assertTrue(result.asReport("Practice", true).contains("Tied modes"))
        assertTrue(result.notes.any { it.contains("jamovi's numeric mode convention") })
    }

    @Test fun missingMarkersAreExcludedAndBadNumericDataIsNotSilentlyAccepted() {
        val data = CourseData(listOf("X"), listOf("1", "", "NA", "N/A", "NaN", "2").map { listOf(it) })
        val result = Statistics.run(data, request)
        assertEquals(2L, result.includedCases)
        assertEquals(4, result.excludedRows)
        listOf("invalid", "Infinity").forEach {
            assertThrows(IllegalArgumentException::class.java) { Statistics.run(parseDelimited("X\n1\n$it"), request) }
        }
        assertThrows(IllegalArgumentException::class.java) { Statistics.run(parseDelimited("X\nNA"), request) }
    }

    @Test fun frequencyLabelsDoNotRoundDifferentValuesIntoTheSameLabel() {
        val summary = Statistics.run(parseDelimited("X\n1.00001\n1.00002\n-0.0\n0"), request).descriptives!!
        assertEquals(listOf("0", "1.00001", "1.00002"), summary.frequencies.map { it.value })
        assertEquals(2, summary.frequencies.first().count)
    }

    @Test fun extremeAndTinyScalesNeverInventInfiniteOrZeroDispersion() {
        val big = Statistics.run(parseDelimited("X\n-1e308\n1e308"), request.copy(population = true)).descriptives!!.numeric!!
        assertEquals(0.0, big.mean, 0.0)
        assertEquals(0.0, big.median, 0.0)
        assertNull(big.variance)
        assertEquals(1.0, big.standardDeviation!! / 1e308, 1e-12)
        assertTrue(big.histogram.all { it.lower.isFinite() && it.upper.isFinite() })
        val tiny = Statistics.run(parseDelimited("X\n0\n1e-300"), request.copy(population = true)).descriptives!!.numeric!!
        assertNull(tiny.variance)
        assertEquals(0.5, tiny.standardDeviation!! / 1e-300, 1e-12)
        val close = Statistics.run(parseDelimited("X\n1e16\n10000000000000002"), request).descriptives!!.numeric!!
        assertEquals(2.0, close.variance!!, 1e-12)
    }

    @Test fun tightlySpacedValuesReduceBinsInsteadOfCreatingDuplicateEdges() {
        val data = CourseData(listOf("X"), listOf(listOf("1"), listOf(Math.nextUp(1.0).toString())))
        val result = Statistics.run(data, request.copy(histogramBins = "50"))
        assertEquals(1, result.descriptives!!.numeric!!.histogram.size)
        assertTrue(result.notes.any { it.contains("reduced") })
    }

    @Test fun textReportsContainCompleteFrequenciesAndBinsWithoutATestPValue() {
        val result = Statistics.run(fixture, request.copy(weight = 1))
        val text = result.asReport("Fictional source", true)
        assertTrue(text.contains("Weighted mean\t3.4"))
        assertTrue(text.contains("Variance (sample)\t2.7"))
        assertTrue(text.contains("Percentiles (linear interpolation, R type 7)"))
        assertTrue(text.contains("Histogram intervals"))
        assertTrue(text.contains("2\t2\t40"))
        assertFalse(text.contains("p ="))
        assertFalse(text.contains("95% CI"))
        assertNotNull(result.descriptives)
    }

    @Test fun fullReportsRetainHighCardinalityFrequencies() {
        val data = CourseData(listOf("X"), (1..1000).map { listOf(it.toString()) })
        val result = Statistics.run(data, request)
        assertEquals(1000, result.descriptives!!.frequencies.size)
        assertEquals(1000, result.descriptives.numeric!!.histogram.sumOf { it.count })
        assertTrue(result.asReport("Practice", true).contains("1000\t1\t0.1"))
    }
}
