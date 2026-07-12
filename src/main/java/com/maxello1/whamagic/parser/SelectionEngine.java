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
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.HashSet;
import java.util.Set;

public class SelectionEngine {
    
    /** Minimum margin by which a super-candidate must beat sub-candidates to be preferred. */
    static final double SUPER_CANDIDATE_WIN_MARGIN = 0.05;
    
    public record SelectedSymbols(
        List<RecognizedSigil> sigils,
        List<RecognizedSign> signs,
        List<UnknownSymbol> unknowns,
        List<SymbolCandidate> selectedCandidates,
        int recognitionCalls,
        List<EvaluatedCandidate> allEvaluated
    ) {}
    
    public static SelectedSymbols select(List<SymbolCandidate> candidates, RingDetector.RingGlyph ring, int maxCalls) {
        int calls = 0;
        // Noise rejection thresholds
        double MIN_PATH_LENGTH = 0.10; // minimum path length as fraction of canvas
        int MIN_POINT_COUNT = 4;       // minimum total points
        double MIN_DIMENSION = 0.07;   // minimum width or height
        
        List<EvaluatedCandidate> evaluated = new ArrayList<>();
        
        for (SymbolCandidate cand : candidates) {
            if (calls >= maxCalls) break;
            
            // Phase 2: Early noise rejection — skip recognition for obvious noise
            if (isNoise(cand, MIN_PATH_LENGTH, MIN_POINT_COUNT, MIN_DIMENSION)) {
                // Create a stub result for noise candidates
                RasterRecognizer.RecognitionResult noiseRes = new RasterRecognizer.RecognitionResult(
                        false, null, "Noise", null, null, 0, null, null,
                        RecognitionRejectionReason.NOISE_DISCARDED);
                evaluated.add(new EvaluatedCandidate(cand, noiseRes, 0, null, 0, 0));
                continue;
            }
            
            // Optimization: skip irrelevant tests based on radial position.
            // Candidates near center are likely sigils; candidates near edge are likely signs.
            // In multi-symbol spells, sigils may be placed anywhere inside the ring,
            // so the sigil zone extends up to 0.85 of the ring radius.
            // Overlap zone (0.25-0.85) and standalone candidates (no ring, position 0) test as both.
            boolean likelySigil = cand.radialPosition() < 0.85;
            boolean likelySign = cand.radialPosition() > 0.25 || cand.radialPosition() < 0.05;
            
            RasterRecognizer.RecognitionResult sigilRes = null;
            double sigilScore = 0;
            double sigilRoleScore = 0;
            
            if (likelySigil) {
                sigilRes = PointCloudRecognizer.INSTANCE.recognize(cand.strokes(), SymbolKind.SIGIL);
                calls++;
                sigilScore = sigilRes.score;
                double centralityScore = 1.0 - clamp(cand.radialPosition() / 0.70);
                sigilRoleScore = sigilScore > 0 ? sigilScore + centralityScore * 0.20 : 0;
            } else {
                sigilRes = new RasterRecognizer.RecognitionResult(
                        false, null, "Unknown", null, null, 0, null, null,
                        RecognitionRejectionReason.SCORE_BELOW_THRESHOLD);
            }
            
            double bestSignScore = 0;
            RasterRecognizer.RecognitionResult bestSignRes = null;
            double bestAngle = 0;
            
            // Optimization: skip sign rotation tests for clearly central candidates
            if (likelySign && calls < maxCalls) {
                double baseAngle = cand.angularPosition();
                double[] offsets = {0, 90, 180, 270, 15, -15, 30, -30, 45, -45};
                for (double offset : offsets) {
                    if (calls >= maxCalls) break;
                    double angleToTest = (baseAngle + offset) % 360;
                    if (angleToTest < 0) angleToTest += 360;
                    
                    List<List<Point>> rotatedStrokes = rotateStrokes(cand.strokes(), cand.centroid(), angleToTest);
                    RasterRecognizer.RecognitionResult res = PointCloudRecognizer.INSTANCE.recognize(rotatedStrokes, SymbolKind.SIGN);
                    calls++;
                    
                    if (bestSignRes == null || res.score > bestSignScore) {
                        bestSignScore = res.score;
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
            double rawA = Math.max(a.sigilRes != null ? a.sigilRes.score : 0, a.signRes != null ? a.signRes.score : 0);
            double rawB = Math.max(b.sigilRes != null ? b.sigilRes.score : 0, b.signRes != null ? b.signRes.score : 0);
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
            RasterRecognizer.RecognitionResult bestRes = pickBestResult(eval);
            if (bestRes != null && bestRes.rejectionReason == RecognitionRejectionReason.NOISE_DISCARDED) {
                noise.add(eval);
            } else if (bestRes != null && bestRes.recognized && bestRes.confidenceGap >= 0.05) {
                recognised.add(eval);
            } else if (bestRes != null && bestRes.recognized) {
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
            RasterRecognizer.RecognitionResult superRes = pickBestResult(superEval);
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
        // Phase 1: Recognised non-ambiguous candidates (super-candidate handled specially)
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
        // Phase 2: Ambiguous candidates for remaining strokes
        selectionOrder.addAll(ambiguous);
        // Phase 3: Unknown symbols only from still-unclaimed strokes
        selectionOrder.addAll(unknown);
        // Phase 4: Noise
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
                RasterRecognizer.RecognitionResult primaryRes = isSigil ? eval.sigilRes : eval.signRes;
                RasterRecognizer.RecognitionResult fallbackRes = isSigil ? eval.signRes : eval.sigilRes;
                
                // Try primary interpretation first, then fallback
                RasterRecognizer.RecognitionResult res = null;
                boolean selectedAsSigil = isSigil;
                if (primaryRes != null && primaryRes.recognized) {
                    res = primaryRes;
                } else if (fallbackRes != null && fallbackRes.recognized
                        && fallbackRes.confidenceGap >= 0.05
                        && fallbackRes.score >= 0.70) {
                    // Only use fallback if it has a clear confidence gap
                    // AND a solid confidence score. Without these checks,
                    // random shapes can match via the weaker interpretation.
                    res = fallbackRes;
                    selectedAsSigil = !isSigil;
                }
                
                // Noise-discarded candidates are skipped entirely.
                // They don't claim strokes — if diagnostics are needed later,
                // a separate discardedStrokeIndices collection can be added.
                RasterRecognizer.RecognitionResult bestRes = pickBestResult(eval);
                if (bestRes != null
                        && bestRes.rejectionReason == RecognitionRejectionReason.NOISE_DISCARDED) {
                    continue;
                }
                
                // An unknown super-candidate can NEVER claim strokes before recognised sub-candidates
                if (res == null && eval.cand.isSuperCandidate()) {
                    continue;
                }
                
                if (res != null) {
                    selectedCandidates.add(eval.cand);
                    usedStrokes.addAll(eval.cand.sourceStrokeIndices());
                    
                    // Build alternatives with role scores populated
                    double roleScore = selectedAsSigil ? eval.sigilRoleScore : eval.signRoleScore;
                    List<RecognitionAlternative> alts = buildAlternatives(res, roleScore, eval.bestAngle);
                    
                    if (selectedAsSigil) {
                        ElementType el = null;
                        try { if (res.element != null) el = ElementType.valueOf(res.element.toUpperCase()); } catch (Exception ignored) {}
                        
                        outSigils.add(new RecognizedSigil(
                            Identifier.tryParse(res.id),
                            el,
                            res.score,
                            eval.cand.centroid(),
                            eval.cand.bounds(),
                            0,
                            eval.cand.sourceStrokeIndices(),
                            alts,
                            RecognitionRejectionReason.NONE
                        ));
                    } else {
                        outSigns.add(new RecognizedSign(
                            res.id,
                            res.score,
                            eval.cand.angularPosition(),
                            eval.bestAngle,
                            "sign",
                            res.signSemantic
                        ));
                    }
                } else {
                    selectedCandidates.add(eval.cand);
                    usedStrokes.addAll(eval.cand.sourceStrokeIndices());
                    
                    // Merge alternatives from both sigil and sign evaluations
                    List<RecognitionAlternative> alts = mergeAlternatives(eval);
                    RecognitionRejectionReason reason = determineRejectionReason(eval);
                    
                    outUnknowns.add(new UnknownSymbol(
                        eval.cand.id(),
                        eval.cand.sourceStrokeIndices(),
                        eval.cand.strokes(),
                        eval.cand.bounds(),
                        CandidateState.UNKNOWN,
                        alts,
                        reason
                    ));
                }
            }
        }
        
        return new SelectedSymbols(outSigils, outSigns, outUnknowns, selectedCandidates, calls, evaluated);
    }
    
    /** Build alternatives list from a RecognitionResult, setting the role score. */
    private static List<RecognitionAlternative> buildAlternatives(RasterRecognizer.RecognitionResult res, double roleScore, double rotationDeg) {
        List<RecognitionAlternative> alts = new ArrayList<>();
        for (RecognitionAlternative alt : res.alternatives) {
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
            for (RecognitionAlternative alt : eval.sigilRes.alternatives) {
                alts.add(new RecognitionAlternative(
                    alt.id(), alt.displayName(), alt.kind(),
                    alt.rawScore(), eval.sigilRoleScore, alt.templateCoverage(),
                    alt.candidateExplainedRatio(), alt.unexplainedInkRatio(),
                    alt.structuralScore(), 0
                ));
            }
        }
        if (eval.signRes != null) {
            for (RecognitionAlternative alt : eval.signRes.alternatives) {
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
        RasterRecognizer.RecognitionResult best = eval.sigilRoleScore >= eval.signRoleScore ? eval.sigilRes : eval.signRes;
        if (best != null && best.rejectionReason != null) {
            return best.rejectionReason;
        }
        return RecognitionRejectionReason.SCORE_BELOW_THRESHOLD;
    }
    
    /** Get the best symbol ID from an evaluated candidate, for deterministic tie-breaking. */
    private static String bestId(EvaluatedCandidate eval) {
        String sigilId = eval.sigilRes != null && eval.sigilRes.id != null ? eval.sigilRes.id : "";
        String signId = eval.signRes != null && eval.signRes.id != null ? eval.signRes.id : "";
        if (eval.sigilRoleScore >= eval.signRoleScore) {
            return sigilId.isEmpty() ? signId : sigilId;
        }
        return signId.isEmpty() ? sigilId : signId;
    }
    
    /** Pick the best recognition result from an evaluated candidate. */
    private static RasterRecognizer.RecognitionResult pickBestResult(EvaluatedCandidate eval) {
        boolean isSigil = eval.sigilRoleScore >= eval.signRoleScore;
        RasterRecognizer.RecognitionResult primary = isSigil ? eval.sigilRes : eval.signRes;
        RasterRecognizer.RecognitionResult fallback = isSigil ? eval.signRes : eval.sigilRes;
        if (primary != null && (primary.recognized || primary.rejectionReason == RecognitionRejectionReason.NOISE_DISCARDED)) {
            return primary;
        }
        if (fallback != null && (fallback.recognized || fallback.rejectionReason == RecognitionRejectionReason.NOISE_DISCARDED)) {
            return fallback;
        }
        return primary != null ? primary : fallback;
    }
    
    private static double clamp(double v) {
        return Math.max(0, Math.min(1, v));
    }
    
    private static List<List<Point>> rotateStrokes(List<List<Point>> strokes, Point centroid, double angleDeg) {
        List<List<Point>> rotatedStrokes = new ArrayList<>();
        double rad = Math.toRadians(angleDeg);
        double cos = Math.cos(rad);
        double sin = Math.sin(rad);
        
        for (List<Point> stroke : strokes) {
            List<Point> rotated = new ArrayList<>();
            for (Point p : stroke) {
                double rx = (p.x - centroid.x) * cos - (p.y - centroid.y) * sin + centroid.x;
                double ry = (p.x - centroid.x) * sin + (p.y - centroid.y) * cos + centroid.y;
                rotated.add(new Point(rx, ry));
            }
            rotatedStrokes.add(rotated);
        }
        return rotatedStrokes;
    }
    
    /** Diagnostic data for a single evaluated candidate. */
    public static class EvaluatedCandidate {
        public final SymbolCandidate cand;
        public final RasterRecognizer.RecognitionResult sigilRes;
        public final double sigilRoleScore;
        public final RasterRecognizer.RecognitionResult signRes;
        public final double signRoleScore;
        public final double bestAngle;
        
        public EvaluatedCandidate(SymbolCandidate cand, RasterRecognizer.RecognitionResult sigilRes, double sigilRoleScore, RasterRecognizer.RecognitionResult signRes, double signRoleScore, double bestAngle) {
            this.cand = cand;
            this.sigilRes = sigilRes;
            this.sigilRoleScore = sigilRoleScore;
            this.signRes = signRes;
            this.signRoleScore = signRoleScore;
            this.bestAngle = bestAngle;
        }
    }
    
    /** Check if a candidate is obviously noise that should skip full recognition. */
    private static boolean isNoise(SymbolCandidate cand, double minPathLength, int minPointCount, double minDimension) {
        // Count total points
        int totalPoints = 0;
        double pathLen = 0;
        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
        double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        
        for (List<Point> stroke : cand.strokes()) {
            totalPoints += stroke.size();
            for (int i = 0; i < stroke.size(); i++) {
                Point p = stroke.get(i);
                minX = Math.min(minX, p.x);
                maxX = Math.max(maxX, p.x);
                minY = Math.min(minY, p.y);
                maxY = Math.max(maxY, p.y);
                if (i > 0) {
                    Point prev = stroke.get(i - 1);
                    pathLen += Math.hypot(p.x - prev.x, p.y - prev.y);
                }
            }
        }
        
        double w = maxX - minX;
        double h = maxY - minY;
        
        // Too few points
        if (totalPoints < minPointCount) return true;
        
        // Negligible path length
        if (pathLen < minPathLength) return true;
        
        // Near-zero dimensions (dot-like)
        if (w < minDimension && h < minDimension) return true;
        
        return false;
    }
}
