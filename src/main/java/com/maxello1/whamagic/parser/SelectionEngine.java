package com.maxello1.whamagic.parser;

import com.maxello1.whamagic.magic.SymbolCandidate;
import com.maxello1.whamagic.magic.RecognizedSigil;
import com.maxello1.whamagic.magic.RecognizedSign;
import com.maxello1.whamagic.magic.UnknownSymbol;
import com.maxello1.whamagic.magic.RecognitionAlternative;
import com.maxello1.whamagic.magic.RecognitionRejectionReason;
import com.maxello1.whamagic.magic.SymbolKind;
import com.maxello1.whamagic.magic.CandidateState;
import com.maxello1.whamagic.magic.ElementType;
import com.maxello1.whamagic.magic.RingDetector;
import com.maxello1.whamagic.magic.SymbolRecognitionResult;
import com.maxello1.whamagic.magic.UnknownInkClassification;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.HashSet;
import java.util.Set;

public final class SelectionEngine {
    
    /** Minimum margin by which a super-candidate must beat sub-candidates to be preferred. */
    static final double SUPER_CANDIDATE_WIN_MARGIN = 0.05;

    /**
     * Deterministic quarter-turn orientations for signs. The candidate's angular
     * position supplies its drawing-specific base rotation; additional 15/30/45
     * degree probes duplicated tolerant point-cloud work and exhausted canonical Wind.
     */
    private static final double[] STANDALONE_SIGN_ROTATION_OFFSETS = {0, 90, 180, 270};
    private static final double[] RING_SIGN_ROTATION_OFFSETS = {0, 90, 180, 270, 15, -15, 30, -30, 45, -45};

    private SelectionEngine() {}
    
    public record SelectedSymbols(
        List<RecognizedSigil> sigils,
        List<RecognizedSign> signs,
        List<UnknownSymbol> unknowns,
        List<SymbolCandidate> selectedCandidates,
        int recognitionCalls,
        List<EvaluatedCandidate> allEvaluated,
        boolean recognitionBudgetExhausted,
        int unevaluatedCandidateCount
    ) {}
    
    public static SelectedSymbols select(List<SymbolCandidate> candidates, RingDetector.RingGlyph ring, int maxCalls) {
        return select(candidates, ring, maxCalls, ParseDetail.FULL_DIAGNOSTICS);
    }

    public static SelectedSymbols select(
            List<SymbolCandidate> candidates,
            RingDetector.RingGlyph ring,
            int maxCalls,
            ParseDetail detail) {
        return selectInternal(candidates, ring, maxCalls, detail);
    }

    private static SelectedSymbols selectInternal(
            List<SymbolCandidate> candidates,
            RingDetector.RingGlyph ring,
            int maxCalls,
            ParseDetail detail) {
        int calls = 0;
        boolean recognitionBudgetExhausted = false;
        int unevaluatedCandidateCount = 0;
        
        List<EvaluatedCandidate> evaluated = new ArrayList<>();
        
        for (int candidateIndex = 0; candidateIndex < candidates.size(); candidateIndex++) {
            SymbolCandidate cand = candidates.get(candidateIndex);
            
            // Skip recognition for obvious noise.
            if (UnknownInkClassifier.isNoise(cand.strokes())) {
                // Create a stub result for noise candidates
                SymbolRecognitionResult noiseRes = SymbolRecognitionResult.rejected(
                        "Noise", RecognitionRejectionReason.NOISE_DISCARDED, 0.0);
                evaluated.add(new EvaluatedCandidate(cand, noiseRes, 0, null, 0, 0));
                continue;
            }
            
            // Limit role checks using the candidate's radial position.
            // Candidates near center are likely sigils; candidates near edge are likely signs.
            // In multi-symbol spells, sigils may be placed anywhere inside the ring,
            // so the sigil zone extends up to 0.85 of the ring radius.
            // Overlap zone (0.25-0.85) and standalone candidates (no ring, position 0) test as both.
            boolean likelySigil = cand.radialPosition() < 0.85;
            boolean likelySign = cand.radialPosition() > 0.25 || cand.radialPosition() < 0.05;
            double[] signRotationOffsets = ring == null
                    ? STANDALONE_SIGN_ROTATION_OFFSETS
                    : RING_SIGN_ROTATION_OFFSETS;

            int requiredCalls = (likelySigil ? 1 : 0)
                    + (likelySign ? signRotationOffsets.length : 0);
            if (requiredCalls > maxCalls - calls) {
                recognitionBudgetExhausted = true;
                unevaluatedCandidateCount = candidates.size() - candidateIndex;
                break;
            }
            PointCloudRecognizer.PreparedCandidate prepared =
                    PointCloudRecognizer.prepareCandidate(cand.strokes());
            PointCloudRecognizer.RotationWorkspace workspace = prepared.newWorkspace();
            
            SymbolRecognitionResult sigilRes = null;
            double sigilScore = 0;
            double sigilRoleScore = 0;
            
            if (likelySigil) {
                sigilRes = PointCloudRecognizer.recognizePrepared(
                        prepared, 0.0, SymbolKind.SIGIL, detail, workspace);
                calls++;
                sigilScore = sigilRes.score();
                double centralityScore = 1.0 - clamp(cand.radialPosition() / 0.70);
                sigilRoleScore = sigilScore > 0 ? sigilScore + centralityScore * 0.20 : 0;
            } else {
                sigilRes = SymbolRecognitionResult.rejected(
                        "Unknown", RecognitionRejectionReason.SCORE_BELOW_THRESHOLD, 0.0);
            }
            
            double bestSignScore = 0;
            SymbolRecognitionResult bestSignRes = null;
            double bestAngle = 0;
            
            // Clearly central candidates do not need sign rotation probes.
            if (likelySign) {
                double baseAngle = cand.angularPosition();
                for (double offset : signRotationOffsets) {
                    double angleToTest = (baseAngle + offset) % 360;
                    if (angleToTest < 0) angleToTest += 360;
                    
                    SymbolRecognitionResult res = PointCloudRecognizer.recognizePrepared(
                            prepared, angleToTest, SymbolKind.SIGN, detail, workspace);
                    calls++;
                    
                    if (bestSignRes == null || res.score() > bestSignScore) {
                        bestSignScore = res.score();
                        bestSignRes = res;
                        bestAngle = angleToTest;
                    }
                }
            }
            
            double outerPlacementScore = clamp((cand.radialPosition() - 0.45) / 0.55);
            double signRoleScore = bestSignScore > 0 ? bestSignScore + outerPlacementScore * 0.20 : 0;
            
            evaluated.add(new EvaluatedCandidate(cand, sigilRes, sigilRoleScore, bestSignRes, signRoleScore, bestAngle));
        }
        
        // Deterministic multi-level comparator: role score → raw score → stroke coverage → symbol ID → candidate ID
        Comparator<EvaluatedCandidate> byScore = (a, b) -> {
            double roleA = Math.max(a.sigilRoleScore, a.signRoleScore);
            double roleB = Math.max(b.sigilRoleScore, b.signRoleScore);
            int cmp = Double.compare(roleB, roleA);
            if (cmp != 0) return cmp;
            // Higher raw recognition confidence
            double rawA = Math.max(a.sigilRes != null ? a.sigilRes.score() : 0, a.signRes != null ? a.signRes.score() : 0);
            double rawB = Math.max(b.sigilRes != null ? b.sigilRes.score() : 0, b.signRes != null ? b.signRes.score() : 0);
            cmp = Double.compare(rawB, rawA);
            if (cmp != 0) return cmp;
            // Greater explained-stroke coverage (more strokes)
            cmp = Integer.compare(b.cand.sourceStrokeIndices().size(), a.cand.sourceStrokeIndices().size());
            if (cmp != 0) return cmp;
            // Symbol ID lexicographically
            String idA = bestId(a);
            String idB = bestId(b);
            cmp = idA.compareTo(idB);
            if (cmp != 0) return cmp;
            // Candidate ID
            return Integer.compare(a.cand.id(), b.cand.id());
        };
        evaluated.sort(byScore);
        
        // Classify candidates into categories
        List<EvaluatedCandidate> recognised = new ArrayList<>();
        List<EvaluatedCandidate> ambiguous = new ArrayList<>();
        List<EvaluatedCandidate> unknown = new ArrayList<>();
        List<EvaluatedCandidate> noise = new ArrayList<>();
        
        for (EvaluatedCandidate eval : evaluated) {
            SymbolRecognitionResult bestRes = pickBestResult(eval);
            if (bestRes != null && bestRes.rejectionReason() == RecognitionRejectionReason.NOISE_DISCARDED) {
                noise.add(eval);
            } else if (bestRes != null && bestRes.recognized() && bestRes.confidenceGap() >= 0.05) {
                recognised.add(eval);
            } else if (bestRes != null && bestRes.recognized()) {
                ambiguous.add(eval);
            } else {
                unknown.add(eval);
            }
        }
        
        Set<Integer> usedStrokes = new HashSet<>();
        List<RecognizedSigil> outSigils = new ArrayList<>();
        List<RecognizedSign> outSigns = new ArrayList<>();
        List<UnknownSymbol> outUnknowns = new ArrayList<>();
        List<SymbolCandidate> selectedCandidates = new ArrayList<>();
        
        // --- Super-candidate comparison ---
        // If a recognised super-candidate exists, compare it against the combined
        // non-overlapping recognised sub-candidates. Only prefer the super-candidate
        // if it wins by SUPER_CANDIDATE_WIN_MARGIN.
        EvaluatedCandidate superEval = null;
        List<EvaluatedCandidate> subRecognised = new ArrayList<>();
        for (EvaluatedCandidate eval : recognised) {
            if (eval.cand.isSuperCandidate()) {
                superEval = eval;
            } else {
                subRecognised.add(eval);
            }
        }
        
        boolean preferSuper = false;
        if (superEval != null) {
            SymbolRecognitionResult superRes = pickBestResult(superEval);
            double superScore = superRes != null ? Math.max(superEval.sigilRoleScore, superEval.signRoleScore) : 0;
            
            // Collect non-overlapping sub-candidates and compute weighted average
            Set<Integer> subUsed = new HashSet<>();
            double subScoreSum = 0;
            int subStrokeCount = 0;
            for (EvaluatedCandidate sub : subRecognised) {
                boolean overlap = false;
                for (int idx : sub.cand.sourceStrokeIndices()) {
                    if (subUsed.contains(idx)) { overlap = true; break; }
                }
                if (!overlap) {
                    subUsed.addAll(sub.cand.sourceStrokeIndices());
                    double subScore = Math.max(sub.sigilRoleScore, sub.signRoleScore);
                    int strokes = sub.cand.sourceStrokeIndices().size();
                    subScoreSum += subScore * strokes;
                    subStrokeCount += strokes;
                }
            }
            double subWeightedAvg = subStrokeCount > 0 ? subScoreSum / subStrokeCount : 0;
            preferSuper = superScore >= subWeightedAvg + SUPER_CANDIDATE_WIN_MARGIN;
        }
        
        // Build the ordered selection list:
        // Recognized non-ambiguous candidates, with the super-candidate handled specially.
        List<EvaluatedCandidate> selectionOrder = new ArrayList<>();
        if (preferSuper && superEval != null) {
            // Super-candidate wins — place it first, then any non-overlapping sub-candidates
            selectionOrder.add(superEval);
            selectionOrder.addAll(subRecognised);
        } else {
            // Sub-candidates preferred — place them first, then super-candidate last
            selectionOrder.addAll(subRecognised);
            if (superEval != null) {
                selectionOrder.add(superEval);
            }
        }
        // Ambiguous candidates may claim remaining strokes.
        selectionOrder.addAll(ambiguous);
        // Unknown symbols may claim only still-unclaimed strokes.
        selectionOrder.addAll(unknown);
        // Noise is considered last and discarded below.
        selectionOrder.addAll(noise);
        
        for (EvaluatedCandidate eval : selectionOrder) {
            boolean overlap = false;
            for (int strokeIdx : eval.cand.sourceStrokeIndices()) {
                if (usedStrokes.contains(strokeIdx)) {
                    overlap = true;
                    break;
                }
            }
            
            if (!overlap) {
                boolean isSigil = eval.sigilRoleScore >= eval.signRoleScore;
                SymbolRecognitionResult primaryRes = isSigil ? eval.sigilRes : eval.signRes;
                SymbolRecognitionResult fallbackRes = isSigil ? eval.signRes : eval.sigilRes;
                
                // Try primary interpretation first, then fallback
                SymbolRecognitionResult res = null;
                boolean selectedAsSigil = isSigil;
                if (primaryRes != null && primaryRes.recognized()) {
                    res = primaryRes;
                } else if (fallbackRes != null && fallbackRes.recognized()
                        && fallbackRes.confidenceGap() >= 0.05
                        && fallbackRes.score() >= 0.70) {
                    // Only use fallback if it has a clear confidence gap
                    // AND a solid confidence score. Without these checks,
                    // random shapes can match via the weaker interpretation.
                    res = fallbackRes;
                    selectedAsSigil = !isSigil;
                }
                
                // Noise-discarded candidates are skipped entirely.
                // They don't claim strokes — if diagnostics are needed later,
                // a separate discardedStrokeIndices collection can be added.
                SymbolRecognitionResult bestRes = pickBestResult(eval);
                if (bestRes != null
                        && bestRes.rejectionReason() == RecognitionRejectionReason.NOISE_DISCARDED) {
                    continue;
                }
                
                // An unknown super-candidate can NEVER claim strokes before recognised sub-candidates
                if (res == null && eval.cand.isSuperCandidate()) {
                    continue;
                }
                
                if (res != null) {
                    selectedCandidates.add(eval.cand);
                    usedStrokes.addAll(eval.cand.sourceStrokeIndices());
                    
                    if (selectedAsSigil) {
                        ElementType el = null;
                        try { if (res.element() != null) el = ElementType.valueOf(res.element().toUpperCase()); } catch (Exception ignored) {}
                        List<RecognitionAlternative> alternatives = detail.retainsAlternatives()
                                ? buildAlternatives(res, eval.sigilRoleScore, eval.bestAngle)
                                : List.of();
                        
                        outSigils.add(new RecognizedSigil(
                            Identifier.tryParse(res.id()),
                            res.matchedTemplateId(),
                            res.displayName(),
                            el,
                            res.sigilSemantic(),
                            res.score(),
                            res.qualityMetrics(),
                            eval.cand.centroid(),
                            eval.cand.bounds(),
                            0,
                            eval.cand.sourceStrokeIndices(),
                            alternatives,
                            RecognitionRejectionReason.NONE
                        ));
                    } else {
                        outSigns.add(new RecognizedSign(
                            eval.cand.id(),
                            res.id(),
                            res.matchedTemplateId(),
                            res.score(),
                            res.qualityMetrics(),
                            eval.cand.angularPosition(),
                            eval.bestAngle,
                            "sign",
                            res.signSemantic(),
                            eval.cand.sourceStrokeIndices(),
                            eval.cand.centroid(),
                            eval.cand.bounds(),
                            detail.retainsAlternatives() ? mergeAlternatives(eval) : List.of(),
                            RecognitionRejectionReason.NONE
                        ));
                    }
                } else {
                    selectedCandidates.add(eval.cand);
                    usedStrokes.addAll(eval.cand.sourceStrokeIndices());
                    
                    RecognitionRejectionReason reason = determineRejectionReason(eval);
                    UnknownInkClassification classification =
                            UnknownInkClassifier.classify(eval.cand.strokes(), reason);
                    
                    outUnknowns.add(new UnknownSymbol(
                        eval.cand.id(),
                        eval.cand.sourceStrokeIndices(),
                        detail.retainsFullDiagnostics() ? eval.cand.strokes() : List.of(),
                        eval.cand.bounds(),
                        CandidateState.UNKNOWN,
                        classification,
                        detail.retainsAlternatives() ? mergeAlternatives(eval) : List.of(),
                        reason
                    ));
                }
            }
        }
        
        return new SelectedSymbols(
                outSigils,
                outSigns,
                outUnknowns,
                detail.retainsFullDiagnostics() ? selectedCandidates : List.of(),
                calls,
                detail.retainsFullDiagnostics() ? evaluated : List.of(),
                recognitionBudgetExhausted, unevaluatedCandidateCount);
    }
    
    /** Build alternatives list from a RecognitionResult, setting the role score. */
    private static List<RecognitionAlternative> buildAlternatives(SymbolRecognitionResult res, double roleScore, double rotationDeg) {
        List<RecognitionAlternative> alts = new ArrayList<>();
        for (RecognitionAlternative alt : res.alternatives()) {
            alts.add(new RecognitionAlternative(
                alt.id(), alt.displayName(), alt.kind(),
                alt.rawScore(), roleScore, alt.templateCoverage(),
                alt.candidateExplainedRatio(), alt.unexplainedInkRatio(),
                alt.structuralScore(), rotationDeg
            ));
        }
        return alts;
    }
    
    /** Merge alternatives from both sigil and sign recognition results. */
    private static List<RecognitionAlternative> mergeAlternatives(EvaluatedCandidate eval) {
        List<RecognitionAlternative> alts = new ArrayList<>();
        if (eval.sigilRes != null) {
            for (RecognitionAlternative alt : eval.sigilRes.alternatives()) {
                alts.add(new RecognitionAlternative(
                    alt.id(), alt.displayName(), alt.kind(),
                    alt.rawScore(), eval.sigilRoleScore, alt.templateCoverage(),
                    alt.candidateExplainedRatio(), alt.unexplainedInkRatio(),
                    alt.structuralScore(), 0
                ));
            }
        }
        if (eval.signRes != null) {
            for (RecognitionAlternative alt : eval.signRes.alternatives()) {
                alts.add(new RecognitionAlternative(
                    alt.id(), alt.displayName(), alt.kind(),
                    alt.rawScore(), eval.signRoleScore, alt.templateCoverage(),
                    alt.candidateExplainedRatio(), alt.unexplainedInkRatio(),
                    alt.structuralScore(), eval.bestAngle
                ));
            }
        }
        // Sort by raw score descending and keep top 5
        alts.sort((a, b) -> Double.compare(b.rawScore(), a.rawScore()));
        if (alts.size() > 5) {
            alts = new ArrayList<>(alts.subList(0, 5));
        }
        return alts;
    }
    
    /** Determine the primary rejection reason from the best result. */
    private static RecognitionRejectionReason determineRejectionReason(EvaluatedCandidate eval) {
        // Use the better result's rejection reason
        SymbolRecognitionResult best = eval.sigilRoleScore >= eval.signRoleScore ? eval.sigilRes : eval.signRes;
        if (best != null && best.rejectionReason() != null) {
            return best.rejectionReason();
        }
        return RecognitionRejectionReason.SCORE_BELOW_THRESHOLD;
    }
    
    /** Get the best symbol ID from an evaluated candidate, for deterministic tie-breaking. */
    private static String bestId(EvaluatedCandidate eval) {
        String sigilId = eval.sigilRes != null && eval.sigilRes.id() != null ? eval.sigilRes.id() : "";
        String signId = eval.signRes != null && eval.signRes.id() != null ? eval.signRes.id() : "";
        if (eval.sigilRoleScore >= eval.signRoleScore) {
            return sigilId.isEmpty() ? signId : sigilId;
        }
        return signId.isEmpty() ? sigilId : signId;
    }
    
    /** Pick the best recognition result from an evaluated candidate. */
    private static SymbolRecognitionResult pickBestResult(EvaluatedCandidate eval) {
        boolean isSigil = eval.sigilRoleScore >= eval.signRoleScore;
        SymbolRecognitionResult primary = isSigil ? eval.sigilRes : eval.signRes;
        SymbolRecognitionResult fallback = isSigil ? eval.signRes : eval.sigilRes;
        if (primary != null && (primary.recognized() || primary.rejectionReason() == RecognitionRejectionReason.NOISE_DISCARDED)) {
            return primary;
        }
        if (fallback != null && (fallback.recognized() || fallback.rejectionReason() == RecognitionRejectionReason.NOISE_DISCARDED)) {
            return fallback;
        }
        return primary != null ? primary : fallback;
    }
    
    private static double clamp(double v) {
        return Math.max(0, Math.min(1, v));
    }
    
    /** Diagnostic data for a single evaluated candidate. */
    public static class EvaluatedCandidate {
        public final SymbolCandidate cand;
        public final SymbolRecognitionResult sigilRes;
        public final double sigilRoleScore;
        public final SymbolRecognitionResult signRes;
        public final double signRoleScore;
        public final double bestAngle;
        
        public EvaluatedCandidate(SymbolCandidate cand, SymbolRecognitionResult sigilRes, double sigilRoleScore, SymbolRecognitionResult signRes, double signRoleScore, double bestAngle) {
            this.cand = cand;
            this.sigilRes = sigilRes;
            this.sigilRoleScore = sigilRoleScore;
            this.signRes = signRes;
            this.signRoleScore = signRoleScore;
            this.bestAngle = bestAngle;
        }
    }
    
}
