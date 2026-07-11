package com.maxello1.whamagic.magic;
import com.maxello1.whamagic.parser.BoundingBox;
import com.maxello1.whamagic.parser.Point;
import java.util.List;
public record UnknownSymbol(int candidateId, List<Integer> sourceStrokeIndices, List<List<Point>> strokes, BoundingBox bounds, CandidateState state, List<RecognitionAlternative> alternatives) {}
