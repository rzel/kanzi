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

package kanzi.function.wavelet;

import kanzi.IndexedIntArray;
import kanzi.IntFunction;


// Very simple filter that removes coefficients in the wavelet domain
// The filter removes coefficients in the outer ring of the high frequency bands
public class WaveletRingFilter implements IntFunction
{
    private final int dimImage;
    private final int levels;
    private final int ringWidth;

    
    public WaveletRingFilter(int dimImage, int levels, int ringWidth)
    {
        if (dimImage < 8)
            throw new IllegalArgumentException("The dimension of the image must be at least 8");

        if ((dimImage & (dimImage-1)) != 0)
            throw new IllegalArgumentException("Invalid dimImage parameter (must be a power of 2)");

        if (levels < 1)
            throw new IllegalArgumentException("The number of wavelet sub-band levels must be at least 1");

        if (levels >= 4)
           throw new IllegalArgumentException("The number of wavelet sub-band levels must be at most 3");

        if (ringWidth < 1)
           throw new IllegalArgumentException("The width of the ring must be at least 1");

        if (ringWidth > (dimImage >> 1))
           throw new IllegalArgumentException("The width of the ring must be at most "+(dimImage >> 1));

        this.dimImage = dimImage;
        this.levels = levels;
        this.ringWidth = ringWidth;
    }


    // Remove a ring of coefficients around the borders in the high frequency levels
    // (nullify the details in the outer ring of the frame).
    public boolean forward(IndexedIntArray source, IndexedIntArray destination)
    {
       int dim = this.dimImage;
       int srcIdx = source.index;
       int[] dst = destination.array;
       int width = this.ringWidth;

       for (int level=0; level<this.levels; level++)
       {
          int halfDim = dim >> 1;
          int startHL = srcIdx + halfDim;
          int startLH = srcIdx + ((halfDim - 1) * this.dimImage);
          int startHH = startLH + halfDim;
          int endHL = startHH;
          int endLH = srcIdx + ((dim - 1) * this.dimImage);
          int endHH = endLH + halfDim;
          int offs = 0;

          for (int j=0; j<halfDim; j++)
          {
             if (j < width)
             {
                for (int i=halfDim-1; i>=0; i--)
                {
                   int idx1 = i + offs;
                   int idx2 = i - offs;

                   // HL quadrant: first and last lines (2 per iteration)
                   dst[startHL+idx1] = 0;
                   dst[endHL+idx2] = 0;

                   // HH quadrant: first and last lines (2 per iteration)
                   dst[startHH+idx1] = 0;
                   dst[endHH+idx2] = 0;

                   // LH quadrant: first and last lines (2 per iteration)
                   dst[startLH+idx1] = 0;
                   dst[endLH+idx2] = 0;
                }
             }
             else
             {
                for (int i=0; i<width; i++)
                {
                   int idx1 = offs + i;
                   int idx2 = offs + halfDim - 1 - i;

                   // HL quadrant: first and last columns (2 per iteration)
                   dst[startHL+idx1] = 0;
                   dst[startHL+idx2] = 0;

                   // HH quadrant: first and last columns (2 per iteration)
                   dst[startHH+idx1] = 0;
                   dst[startHH+idx2] = 0;

                   // LH quadrant: first and last columns (2 per iteration)
                   dst[startLH+idx1] = 0;
                   dst[startLH+idx2] = 0;
                }
             }

             offs += this.dimImage;
          }

          width >>= 2;
          dim >>= 1;

          if (width == 0)
             break;
       }

       source.index = this.dimImage * this.dimImage;
       destination.index = this.dimImage * this.dimImage;
       return true;
    }


    // Cannot be reversed
    public boolean inverse(IndexedIntArray source, IndexedIntArray destination)
    {
       return false;
    }
}
