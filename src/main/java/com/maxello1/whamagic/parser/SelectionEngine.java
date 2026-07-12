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
import java.util.List;
import java.util.HashSet;
import java.util.Set;

public class SelectionEngine {
    
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
        
        List<EvaluatedCandidate> evaluated = new ArrayList<>();
        
        for (SymbolCandidate cand : candidates) {
            if (calls >= maxCalls) break;
            
            RasterRecognizer.RecognitionResult sigilRes = RasterRecognizer.recognize(cand.strokes(), SymbolKind.SIGIL);
            calls++;
            
            double sigilScore = sigilRes.score;
            double centralityScore = 1.0 - clamp(cand.radialPosition() / 0.70);
            double sigilRoleScore = sigilScore > 0 ? sigilScore + centralityScore * 0.20 : 0;
            
            double bestSignScore = 0;
            RasterRecognizer.RecognitionResult bestSignRes = null;
            double bestAngle = 0;
            
            if (calls < maxCalls) {
                double baseAngle = cand.angularPosition();
                double[] offsets = {0, 15, -15, 30, -30, 180};
                for (double offset : offsets) {
                    if (calls >= maxCalls) break;
                    double angleToTest = (baseAngle + offset) % 360;
                    if (angleToTest < 0) angleToTest += 360;
                    
                    List<List<Point>> rotatedStrokes = rotateStrokes(cand.strokes(), cand.centroid(), angleToTest);
                    RasterRecognizer.RecognitionResult res = RasterRecognizer.recognize(rotatedStrokes, SymbolKind.SIGN);
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
        
        evaluated.sort((a, b) -> Double.compare(
            Math.max(b.sigilRoleScore, b.signRoleScore), 
            Math.max(a.sigilRoleScore, a.signRoleScore)
        ));
        
        Set<Integer> usedStrokes = new HashSet<>();
        List<RecognizedSigil> outSigils = new ArrayList<>();
        List<RecognizedSign> outSigns = new ArrayList<>();
        List<UnknownSymbol> outUnknowns = new ArrayList<>();
        List<SymbolCandidate> selectedCandidates = new ArrayList<>();
        
        for (EvaluatedCandidate eval : evaluated) {
            boolean overlap = false;
            for (int strokeIdx : eval.cand.sourceStrokeIndices()) {
                if (usedStrokes.contains(strokeIdx)) {
                    overlap = true;
                    break;
                }
            }
            
            if (!overlap) {
                boolean isSigil = eval.sigilRoleScore >= eval.signRoleScore;
                RasterRecognizer.RecognitionResult res = isSigil ? eval.sigilRes : eval.signRes;
                
                if (res != null && res.recognized) {
                    selectedCandidates.add(eval.cand);
                    usedStrokes.addAll(eval.cand.sourceStrokeIndices());
                    
                    // Build alternatives with role scores populated
                    List<RecognitionAlternative> alts = buildAlternatives(res, isSigil ? eval.sigilRoleScore : eval.signRoleScore, eval.bestAngle);
                    
                    if (isSigil) {
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
}
