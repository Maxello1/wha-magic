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
- `src/main/java/com/maxello1/whamagic/magic/RingDetector.java`
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

---

## $P Point-Cloud Recognizer with $P+ Turning Angles

The point-cloud gesture recognition algorithm in
`src/main/java/com/maxello1/whamagic/parser/PointCloudRecognizer.java`
is a hybrid matcher combining $P's weighted one-to-one greedy cloud assignment
with $P+-inspired absolute turning-angle distance. It is not a complete
implementation of $P+'s one-to-many matching procedure.

Based on:
- $P: Radu-Daniel Vatavu, Lisa Anthony, Jacob O. Wobbrock.
  "Gestures as Point Clouds: A $P Recognizer for User Interface Prototypes."
  Proc. ICMI 2012.
- $P+: Radu-Daniel Vatavu.
  "Improving Gesture Recognition Accuracy on Touch Screens for Users
  with Low Vision." Proc. CHI 2017.
- Reference implementation: https://depts.washington.edu/madlab/proj/dollar/pdollar.html

### New BSD License

Copyright (c) 2012-2017, Radu-Daniel Vatavu, Lisa Anthony, Jacob O. Wobbrock.
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice,
   this list of conditions and the following disclaimer.
2. Redistributions in binary form must reproduce the above copyright notice,
   this list of conditions and the following disclaimer in the documentation
   and/or other materials provided with the distribution.
3. Neither the names of the copyright holders nor the names of its
   contributors may be used to endorse or promote products derived from this
   software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
POSSIBILITY OF SUCH DAMAGE.
