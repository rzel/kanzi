/*
Copyright 2011, 2012 Frederic Langlet
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

package kanzi.transform;

import kanzi.IntTransform;


// Implementation of Walsh-Hadamard transform of dimension 4 using only additions,
// subtractions and shifts.
public final class WHT4 implements IntTransform
{
    private final int fScale;
    private final int iScale;


    // For perfect reconstruction, forward results are scaled by 4
    public WHT4()
    {
       this.fScale = 0;
       this.iScale = 4;
    }


    // For perfect reconstruction, forward results are scaled by 4 unless the
    // parameter is set to false (in wich case rounding may introduce errors)
    public WHT4(boolean scale)
    {
       this.fScale = (scale == false) ? 2 : 0;
       this.iScale = (scale == false) ? 2 : 4;
    }


    public int[] forward(int[] block)
    {
        return this.compute(block, 0, this.fScale);
    }


    // Thread safe
    @Override
    public int[] forward(int[] block, int blkptr)
    {
        return this.compute(block, blkptr, this.fScale);
    }


    // Result multiplied by 4 if 'scale' is set to false
    private int[] compute(int[] block, int blkptr, int shift)
    {
       int b0, b1, b2, b3, b4, b5, b6, b7;
       int b8, b9, b10, b11, b12, b13, b14, b15;

       // Pass 1: process rows.
       {
         // Aliasing for speed
         final int x0  = block[blkptr];
         final int x1  = block[blkptr+1];
         final int x2  = block[blkptr+2];
         final int x3  = block[blkptr+3];
         final int x4  = block[blkptr+4];
         final int x5  = block[blkptr+5];
         final int x6  = block[blkptr+6];
         final int x7  = block[blkptr+7];
         final int x8  = block[blkptr+8];
         final int x9  = block[blkptr+9];
         final int x10 = block[blkptr+10];
         final int x11 = block[blkptr+11];
         final int x12 = block[blkptr+12];
         final int x13 = block[blkptr+13];
         final int x14 = block[blkptr+14];
         final int x15 = block[blkptr+15];

         final int a0  = x0  + x1;
         final int a1  = x2  + x3;
         final int a2  = x0  - x1;
         final int a3  = x2  - x3;
         final int a4  = x4  + x5;
         final int a5  = x6  + x7;
         final int a6  = x4  - x5;
         final int a7  = x6  - x7;
         final int a8  = x8  + x9;
         final int a9  = x10 + x11;
         final int a10 = x8  - x9;
         final int a11 = x10 - x11;
         final int a12 = x12 + x13;
         final int a13 = x14 + x15;
         final int a14 = x12 - x13;
         final int a15 = x14 - x15;

         b0  = a0  + a1;
         b1  = a2  + a3;
         b2  = a0  - a1;
         b3  = a2  - a3;
         b4  = a4  + a5;
         b5  = a6  + a7;
         b6  = a4  - a5;
         b7  = a6  - a7;
         b8  = a8  + a9;
         b9  = a10 + a11;
         b10 = a8  - a9;
         b11 = a10 - a11;
         b12 = a12 + a13;
         b13 = a14 + a15;
         b14 = a12 - a13;
         b15 = a14 - a15;
       }

       // Pass 2: process columns.
       {
         final int a0  = b0  + b4;
         final int a1  = b8  + b12;
         final int a2  = b0  - b4;
         final int a3  = b8  - b12;
         final int a4  = b1  + b5;
         final int a5  = b9  + b13;
         final int a6  = b1  - b5;
         final int a7  = b9  - b13;
         final int a8  = b2  + b6;
         final int a9  = b10 + b14;
         final int a10 = b2  - b6;
         final int a11 = b10 - b14;
         final int a12 = b3  + b7;
         final int a13 = b11 + b15;
         final int a14 = b3  - b7;
         final int a15 = b11 - b15;

         final int adjust = (1 << shift) >> 1;

         block[blkptr]    = (a0  + a1  + adjust) >> shift;
         block[blkptr+4]  = (a2  + a3  + adjust) >> shift;
         block[blkptr+8]  = (a0  - a1  + adjust) >> shift;
         block[blkptr+12] = (a2  - a3  + adjust) >> shift;
         block[blkptr+1]  = (a4  + a5  + adjust) >> shift;
         block[blkptr+5]  = (a6  + a7  + adjust) >> shift;
         block[blkptr+9]  = (a4  - a5  + adjust) >> shift;
         block[blkptr+13] = (a6  - a7  + adjust) >> shift;
         block[blkptr+2]  = (a8  + a9  + adjust) >> shift;
         block[blkptr+6]  = (a10 + a11 + adjust) >> shift;
         block[blkptr+10] = (a8  - a9  + adjust) >> shift;
         block[blkptr+14] = (a10 - a11 + adjust) >> shift;
         block[blkptr+3]  = (a12 + a13 + adjust) >> shift;
         block[blkptr+7]  = (a14 + a15 + adjust) >> shift;
         block[blkptr+11] = (a12 - a13 + adjust) >> shift;
         block[blkptr+15] = (a14 - a15 + adjust) >> shift;
       }

       return block;
    }


    // The transform is symmetric (except, potentially, for scaling)
    public int[] inverse(int[] block)
    {
        return this.compute(block, 0, this.iScale);
    }


    @Override
    public int[] inverse(int[] block, int blkptr)
    {
        return this.compute(block, blkptr, this.iScale);
    }

}