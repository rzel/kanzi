/*
Copyright 2011 Frederic Langlet
Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
you may obtain a copy of the License at

                http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/

package kanzi.util;


// Evaluates if a residue block should be discarded or kept.
// Decimation algo from X264
// Used in inter macroblock (luma and chroma)
// luma: for a 8x8 block: if score < 4 -> null
//       for the complete mb: if score < 6 -> null
// chroma: for the complete mb: if score < 7 -> null
public final class ResidueBlockDecimation
{
    private final int threshold;

    private static final int[] DECIMATION_WEIGHTS =
    {
        3, 3, 3, 3, 2, 2, 2, 2, 2, 2, 2, 2, 1, 1, 1, 1,
        1, 1, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0,
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0
    };


    public ResidueBlockDecimation(int threshold)
    {
       if (threshold <= 0)
           throw new IllegalArgumentException("The decimation threshold must be positive");

       this.threshold = threshold;
    }


    public boolean isSignificant(int[] data, int blkptr, int length)
    {
//    if ((data == null) || (length <= 0) || (length >= data.length))
//        return false;

        int idx = blkptr + length - 1;

        while ((idx >= 0) && (data[idx] == 0))
            idx--;

        int score = 0;

        while (idx >= 0)
        {
            int val = data[idx--];

            if (val > 1)
                return false;

            if (-val > 1)
                return false;

            int run = 0;

            while ((idx >= 0) && (data[idx] == 0))
            {
                idx--;
                run++;
            }

            score += DECIMATION_WEIGHTS[run];
        }

        return (score < this.threshold) ? true : false;
    }

}
