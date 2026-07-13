package com.maxello1.whamagic.parser;

import java.util.List;

/** A stroke paired with its index in the original submitted drawing. */
public record IndexedStroke(int originalIndex, List<Point> points) {}
