package io.github.tatselkrik.statis130lite

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class StatisticalOptionsTest {
    @Test fun modelQQBasisPersistsAndLegacyGraphsRetainRawResiduals() {
        assertEquals(ModelQQBasis.STANDARDIZED,AnalysisRequest().modelQQBasis)
        for(basis in ModelQQBasis.entries) {
            val request=AnalysisRequest(modelQQBasis=basis,showQQPlot=true)
            val bytes=SessionCodec.encode(SavedSession(AnalysisScreenState(request=request),false))
            assertEquals(request,SessionCodec.decode(bytes).screen.request)
            val old=JSONObject(String(bytes));old.getJSONObject("request").remove("modelQQBasis")
            assertEquals(ModelQQBasis.RAW,SessionCodec.decode(old.toString().toByteArray()).screen.request.modelQQBasis)
        }
    }
    @Test fun obliminDefaultAndExplicitRotationsPersistWhileLegacyDefaultsStayVarimax() {
        assertEquals(FactorRotation.OBLIMIN,AnalysisRequest().factorRotation)
        for(rotation in FactorRotation.entries) {
            val request=AnalysisRequest(factorRotation=rotation)
            val bytes=SessionCodec.encode(SavedSession(AnalysisScreenState(request=request),false))
            assertEquals(request,SessionCodec.decode(bytes).screen.request)
            val old=JSONObject(String(bytes));old.getJSONObject("request").remove("factorRotation")
            assertEquals(FactorRotation.VARIMAX,SessionCodec.decode(old.toString().toByteArray()).screen.request.factorRotation)
        }
    }
    @Test fun varimaxStoppingPersistsAndOldSessionsKeepTightPlanes() {
        assertEquals(VarimaxConvergence.REFERENCE,AnalysisRequest().varimaxConvergence)
        for(choice in VarimaxConvergence.entries) {
            val req=AnalysisRequest(varimaxConvergence=choice)
            val bytes=SessionCodec.encode(SavedSession(AnalysisScreenState(request=req),false))
            assertEquals(req,SessionCodec.decode(bytes).screen.request)
            val root=JSONObject(String(bytes));root.getJSONObject("request").remove("varimaxConvergence")
            assertEquals(VarimaxConvergence.TIGHT,SessionCodec.decode(root.toString().toByteArray()).screen.request.varimaxConvergence)
        }
    }
    @Test fun parallelProtocolAndSeedPersistAndOldSessionsKeepTheirWishartSequence() {
        assertEquals(FactorParallelMethod.COMMON_FACTOR,AnalysisRequest().factorParallelMethod)
        for(method in FactorParallelMethod.entries) {
            val req=AnalysisRequest(factorParallelMethod=method,factorParallelSeed="-17")
            val bytes=SessionCodec.encode(SavedSession(AnalysisScreenState(request=req),false))
            assertEquals(req,SessionCodec.decode(bytes).screen.request)
            val root=JSONObject(String(bytes));val r=root.getJSONObject("request");r.remove("factorParallelMethod");r.remove("factorParallelSeed")
            val legacy=SessionCodec.decode(root.toString().toByteArray()).screen.request
            assertEquals(FactorParallelMethod.PCA_WISHART,legacy.factorParallelMethod);assertEquals("20261109",legacy.factorParallelSeed)
        }
    }
    @Test fun commonEigenvalueDefaultAndLegacyCorrelationRuleSurviveRecovery() {
        assertEquals("0",AnalysisRequest().factorEigenvalue)
        assertEquals(EigenvalueBasis.COMMON_FACTOR,AnalysisRequest().factorEigenvalueBasis)
        for(basis in EigenvalueBasis.entries) {
            val req=AnalysisRequest(factorCountMethod=FactorCountMethod.EIGENVALUE,factorEigenvalueBasis=basis,factorEigenvalue="0.75")
            val bytes=SessionCodec.encode(SavedSession(AnalysisScreenState(request=req),false))
            assertEquals(req,SessionCodec.decode(bytes).screen.request)
            val root=JSONObject(String(bytes));val r=root.getJSONObject("request");r.remove("factorEigenvalueBasis");r.remove("factorEigenvalue")
            val old=SessionCodec.decode(root.toString().toByteArray()).screen.request
            assertEquals(EigenvalueBasis.CORRELATION,old.factorEigenvalueBasis);assertEquals("1",old.factorEigenvalue)
        }
    }
    @Test fun minresStoppingChoiceAndLegacySpectralFitPersist() {
        assertEquals(MinresConvergence.REFERENCE,AnalysisRequest().minresConvergence)
        for(choice in MinresConvergence.entries) {
            val req=AnalysisRequest(minresConvergence=choice)
            val bytes=SessionCodec.encode(SavedSession(AnalysisScreenState(request=req),false))
            assertEquals(req,SessionCodec.decode(bytes).screen.request)
            val root=JSONObject(String(bytes));root.getJSONObject("request").remove("minresConvergence")
            assertEquals(MinresConvergence.TIGHT,SessionCodec.decode(root.toString().toByteArray()).screen.request.minresConvergence)
        }
    }
    @Test fun principalAxisStoppingChoiceAndLegacyTightRecoveryPersist() {
        assertEquals(PrincipalAxisConvergence.REFERENCE,AnalysisRequest().principalAxisConvergence)
        for(choice in PrincipalAxisConvergence.entries) {
            val req=AnalysisRequest(principalAxisConvergence=choice)
            val bytes=SessionCodec.encode(SavedSession(AnalysisScreenState(request=req),false))
            assertEquals(req,SessionCodec.decode(bytes).screen.request)
            val root=JSONObject(String(bytes));root.getJSONObject("request").remove("principalAxisConvergence")
            assertEquals(PrincipalAxisConvergence.TIGHT,SessionCodec.decode(root.toString().toByteArray()).screen.request.principalAxisConvergence)
        }
    }
    @Test fun countMissingPolicyRoundTripsAndOldSessionsRetainRowOmission() {
        assertEquals(CountMissingPolicy.REJECT,AnalysisRequest().countMissingPolicy)
        for(policy in CountMissingPolicy.entries) {
            val req=AnalysisRequest(countMissingPolicy=policy)
            val bytes=SessionCodec.encode(SavedSession(AnalysisScreenState(request=req),false))
            assertEquals(req,SessionCodec.decode(bytes).screen.request)
            val root=JSONObject(String(bytes));root.getJSONObject("request").remove("countMissingPolicy")
            assertEquals(CountMissingPolicy.OMIT,SessionCodec.decode(root.toString().toByteArray()).screen.request.countMissingPolicy)
        }
    }
    @Test fun friedmanDefaultAndSavedCorrectionChoicesKeepTheirScientificMeaning() {
        assertEquals(FriedmanAdjustment.NONE,AnalysisRequest().friedmanAdjustment)
        for(adjustment in FriedmanAdjustment.entries) {
            val req=AnalysisRequest(friedmanAdjustment=adjustment)
            val bytes=SessionCodec.encode(SavedSession(AnalysisScreenState(request=req),false))
            assertEquals(req,SessionCodec.decode(bytes).screen.request)
            val root=JSONObject(String(bytes));root.getJSONObject("request").remove("friedmanAdjustment")
            assertEquals(FriedmanAdjustment.HOLM,SessionCodec.decode(root.toString().toByteArray()).screen.request.friedmanAdjustment)
        }
    }
    @Test fun modelAdjustmentsRoundTripAndLegacyHolmRemainsDistinctFromNewTukey() {
        assertEquals(ModelAdjustment.TUKEY,AnalysisRequest().modelAdjustment)
        for(adjustment in ModelAdjustment.entries) {
            val request=AnalysisRequest(modelAdjustment=adjustment)
            val bytes=SessionCodec.encode(SavedSession(AnalysisScreenState(request=request),false))
            assertEquals(request,SessionCodec.decode(bytes).screen.request)
            val root=JSONObject(String(bytes));root.getJSONObject("request").remove("modelAdjustment")
            assertEquals(ModelAdjustment.HOLM,SessionCodec.decode(root.toString().toByteArray()).screen.request.modelAdjustment)
        }
    }
    @Test fun explicitDiagnosticChoicesSurviveRecoveryAndAbsentLegacyFieldsKeepTheirMeaning() {
        for(basis in NormalityBasis.entries)for(center in LeveneCenter.entries)for(model in ModelHomogeneity.entries)for(family in ModelComparisonFamily.entries) {
            val req=AnalysisRequest(independentNormality=basis,anovaNormality=basis,meanLeveneCenter=center,modelHomogeneity=model,modelComparisonFamily=family)
            val bytes=SessionCodec.encode(SavedSession(AnalysisScreenState(request=req),false))
            assertEquals(req,SessionCodec.decode(bytes).screen.request)
            val root=JSONObject(String(bytes));val r=root.getJSONObject("request")
            listOf("independentNormality","anovaNormality","meanLeveneCenter","modelHomogeneity","modelComparisonFamily","modelAdjustment").forEach(r::remove)
            val legacy=SessionCodec.decode(root.toString().toByteArray()).screen.request
            assertEquals(NormalityBasis.GROUPS,legacy.independentNormality);assertEquals(NormalityBasis.GROUPS,legacy.anovaNormality)
            assertEquals(LeveneCenter.MEDIAN,legacy.meanLeveneCenter);assertEquals(ModelHomogeneity.CELL_MEDIAN,legacy.modelHomogeneity)
            assertEquals(ModelComparisonFamily.ALL_FACTORS,legacy.modelComparisonFamily)
            assertEquals(ModelAdjustment.HOLM,legacy.modelAdjustment)
        }
    }
}
