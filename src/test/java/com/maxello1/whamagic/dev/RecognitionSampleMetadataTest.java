package com.maxello1.whamagic.dev;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RecognitionSampleMetadataTest {

    @Test
    void requiresRingStyleToAgreeWithCircleFlag() {
        assertThrows(IllegalArgumentException.class, () -> metadata(
                true, RecognitionSampleMetadata.RingStyle.NONE,
                RecognitionSampleMetadata.SampleRole.EXPERIMENTAL, false));
        assertThrows(IllegalArgumentException.class, () -> metadata(
                false, RecognitionSampleMetadata.RingStyle.INCOMPLETE,
                RecognitionSampleMetadata.SampleRole.EXPERIMENTAL, false));

        RecognitionSampleMetadata valid = metadata(
                true, RecognitionSampleMetadata.RingStyle.MULTI_STROKE,
                RecognitionSampleMetadata.SampleRole.EXPERIMENTAL, false);
        assertEquals(RecognitionSampleMetadata.RingStyle.MULTI_STROKE, valid.ringStyle());
    }

    @Test
    void holdoutCannotBeMarkedAsInfluencingRecognition() {
        assertThrows(IllegalArgumentException.class, () -> metadata(
                false, RecognitionSampleMetadata.RingStyle.NONE,
                RecognitionSampleMetadata.SampleRole.HOLDOUT, true));
    }

    private static RecognitionSampleMetadata metadata(
            boolean ring,
            RecognitionSampleMetadata.RingStyle style,
            RecognitionSampleMetadata.SampleRole role,
            boolean influenced) {
        return new RecognitionSampleMetadata(
                "", List.of(), ring, style, role, false, "", influenced);
    }
}
