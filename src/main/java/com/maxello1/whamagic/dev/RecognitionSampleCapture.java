package com.maxello1.whamagic.dev;

import com.maxello1.whamagic.parser.Point;
import com.maxello1.whamagic.parser.ParseDetail;
import com.maxello1.whamagic.parser.SpellParser;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

/** One pending F5 capture. Saving and cancelling are mutually exclusive. */
public final class RecognitionSampleCapture {
    private final List<List<Point>> rawStrokes;
    private final SpellParser.ParseResult actualResult;
    private boolean completed;

    public RecognitionSampleCapture(
            List<List<Point>> rawStrokes,
            SpellParser.ParseResult actualResult) {
        this.rawStrokes = rawStrokes == null
                ? List.of()
                : rawStrokes.stream().map(List::copyOf).toList();
        if (actualResult != null && actualResult.detail != ParseDetail.FULL_DIAGNOSTICS) {
            throw new IllegalArgumentException(
                    "Recognition sample capture requires full parser diagnostics");
        }
        this.actualResult = actualResult;
    }

    public synchronized String save(RecognitionSampleMetadata metadata) {
        ensurePending();
        String path = SampleRecorder.saveSample(rawStrokes, actualResult, metadata);
        if (path != null) completed = true;
        return path;
    }

    synchronized Path save(
            Path directory,
            RecognitionSampleMetadata metadata,
            LocalDateTime recordedAt) throws IOException {
        ensurePending();
        Path path = SampleRecorder.writeSample(directory, rawStrokes, actualResult, metadata, recordedAt);
        completed = true;
        return path;
    }

    public synchronized void cancel() {
        completed = true;
    }

    public SpellParser.ParseResult actualResult() {
        return actualResult;
    }

    private void ensurePending() {
        if (completed) throw new IllegalStateException("This sample capture is already complete.");
    }
}
