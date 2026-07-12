# Third-Party Notices

This project incorporates code adapted from the following third-party sources.
These third-party portions remain subject to their original licenses and are
not governed by the WHA Magic Restricted Use License.

---

## WHA Spell Simulator

The gesture recognition algorithms, raster-based template matching, stroke
normalization, ring detection logic (Kåsa circle fit), and dictionary template
parsing are ported or adapted from
[WHA Spell Simulator](https://github.com/ytnrvdf/wha-spell-simulator).

The following source files contain substantial adaptations of upstream code:

- `src/main/java/com/maxello1/whamagic/parser/RasterRecognizer.java`
- `src/main/java/com/maxello1/whamagic/parser/TemplateNormalizer.java`
- `src/main/java/com/maxello1/whamagic/parser/RingDetector.java`
- `src/main/java/com/maxello1/whamagic/parser/SpellDictionary.java`

### MIT License

Copyright (c) 2026 Nervadof

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
