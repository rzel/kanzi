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


// Implementation of Walsh-Hadamard transform of dimension 32 using only additions,
// subtractions and shifts.
public final class WHT32 implements IntTransform
{
    private final int[] data;
    private final int fScale;
    private final int iScale;


    // For perfect reconstruction, forward results are scaled by 16*sqrt(2)
    public WHT32()
    {
       this.fScale = 0;
       this.iScale = 10;
       this.data = new int[1024];
    }


    // For perfect reconstruction, forward results are scaled by 16*sqrt(2) unless
    // the parameter is set to false (scaled by sqrt(2), in wich case rounding
    // may introduce errors)
    public WHT32(boolean scale)
    {
       this.fScale = (scale == false) ? 5 : 0;
       this.iScale = (scale == false) ? 5 : 10;
       this.data = new int[1024];
    }


    public int[] forward(int[] block)
    {
        return this.compute(block, 0, this.fScale);
    }


    // Not thread safe
    @Override
    public int[] forward(int[] block, int blkptr)
    {
        return this.compute(block, blkptr, this.fScale);
    }


    // Not thread safe
    // Result multiplied by sqrt(2) or 16*sqrt(2) if 'scale' is set to false
    private int[] compute(int[] block, int blkptr, int shift)
    {
		this.processRows(block, blkptr);
		this.processColumns(block, blkptr, shift);
		return block;
	}



    private void processRows(int[] block, int blkptr)
    {
        int dataptr = 0;
        final int end = blkptr + 1024;
        final int[] buffer = this.data;

        // Pass 1: process rows.
        for (int i=blkptr; i<end; i+=32)
        {
            // Aliasing for speed
            final int x0  = block[i];
            final int x1  = block[i+1];
            final int x2  = block[i+2];
            final int x3  = block[i+3];
            final int x4  = block[i+4];
            final int x5  = block[i+5];
            final int x6  = block[i+6];
            final int x7  = block[i+7];
            final int x8  = block[i+8];
            final int x9  = block[i+9];
            final int x10 = block[i+10];
            final int x11 = block[i+11];
            final int x12 = block[i+12];
            final int x13 = block[i+13];
            final int x14 = block[i+14];
            final int x15 = block[i+15];
            final int x16 = block[i+16];
            final int x17 = block[i+17];
            final int x18 = block[i+18];
            final int x19 = block[i+19];
            final int x20 = block[i+20];
            final int x21 = block[i+21];
            final int x22 = block[i+22];
            final int x23 = block[i+23];
            final int x24 = block[i+24];
            final int x25 = block[i+25];
            final int x26 = block[i+26];
            final int x27 = block[i+27];
            final int x28 = block[i+28];
            final int x29 = block[i+29];
            final int x30 = block[i+30];
            final int x31 = block[i+31];

            int a0  = x0  + x1;
            int a1  = x2  + x3;
            int a2  = x4  + x5;
            int a3  = x6  + x7;
            int a4  = x8  + x9;
            int a5  = x10 + x11;
            int a6  = x12 + x13;
            int a7  = x14 + x15;
            int a8  = x16 + x17;
            int a9  = x18 + x19;
            int a10 = x20 + x21;
            int a11 = x22 + x23;
            int a12 = x24 + x25;
            int a13 = x26 + x27;
            int a14 = x28 + x29;
            int a15 = x30 + x31;
            int a16 = x0  - x1;
            int a17 = x2  - x3;
            int a18 = x4  - x5;
            int a19 = x6  - x7;
            int a20 = x8  - x9;
            int a21 = x10 - x11;
            int a22 = x12 - x13;
            int a23 = x14 - x15;
            int a24 = x16 - x17;
            int a25 = x18 - x19;
            int a26 = x20 - x21;
            int a27 = x22 - x23;
            int a28 = x24 - x25;
            int a29 = x26 - x27;
            int a30 = x28 - x29;
            int a31 = x30 - x31;

            int b0  = a0  + a1;
            int b1  = a2  + a3;
            int b2  = a4  + a5;
            int b3  = a6  + a7;
            int b4  = a8  + a9;
            int b5  = a10 + a11;
            int b6  = a12 + a13;
            int b7  = a14 + a15;
            int b8  = a16 + a17;
            int b9  = a18 + a19;
            int b10 = a20 + a21;
            int b11 = a22 + a23;
            int b12 = a24 + a25;
            int b13 = a26 + a27;
            int b14 = a28 + a29;
            int b15 = a30 + a31;
            int b16 = a0  - a1;
            int b17 = a2  - a3;
            int b18 = a4  - a5;
            int b19 = a6  - a7;
            int b20 = a8  - a9;
            int b21 = a10 - a11;
            int b22 = a12 - a13;
            int b23 = a14 - a15;
            int b24 = a16 - a17;
            int b25 = a18 - a19;
            int b26 = a20 - a21;
            int b27 = a22 - a23;
            int b28 = a24 - a25;
            int b29 = a26 - a27;
            int b30 = a28 - a29;
            int b31 = a30 - a31;

            a0  = b0  + b1;
            a1  = b2  + b3;
            a2  = b4  + b5;
            a3  = b6  + b7;
            a4  = b8  + b9;
            a5  = b10 + b11;
            a6  = b12 + b13;
            a7  = b14 + b15;
            a8  = b16 + b17;
            a9  = b18 + b19;
            a10 = b20 + b21;
            a11 = b22 + b23;
            a12 = b24 + b25;
            a13 = b26 + b27;
            a14 = b28 + b29;
            a15 = b30 + b31;
            a16 = b0  - b1;
            a17 = b2  - b3;
            a18 = b4  - b5;
            a19 = b6  - b7;
            a20 = b8  - b9;
            a21 = b10 - b11;
            a22 = b12 - b13;
            a23 = b14 - b15;
            a24 = b16 - b17;
            a25 = b18 - b19;
            a26 = b20 - b21;
            a27 = b22 - b23;
            a28 = b24 - b25;
            a29 = b26 - b27;
            a30 = b28 - b29;
            a31 = b30 - b31;

            b0  = a0  + a1;
            b1  = a2  + a3;
            b2  = a4  + a5;
            b3  = a6  + a7;
            b4  = a8  + a9;
            b5  = a10 + a11;
            b6  = a12 + a13;
            b7  = a14 + a15;
            b8  = a16 + a17;
            b9  = a18 + a19;
            b10 = a20 + a21;
            b11 = a22 + a23;
            b12 = a24 + a25;
            b13 = a26 + a27;
            b14 = a28 + a29;
            b15 = a30 + a31;
            b16 = a0  - a1;
            b17 = a2  - a3;
            b18 = a4  - a5;
            b19 = a6  - a7;
            b20 = a8  - a9;
            b21 = a10 - a11;
            b22 = a12 - a13;
            b23 = a14 - a15;
            b24 = a16 - a17;
            b25 = a18 - a19;
            b26 = a20 - a21;
            b27 = a22 - a23;
            b28 = a24 - a25;
            b29 = a26 - a27;
            b30 = a28 - a29;
            b31 = a30 - a31;

            buffer[dataptr]    = b0  + b1;
            buffer[dataptr+1]  = b2  + b3;
            buffer[dataptr+2]  = b4  + b5;
            buffer[dataptr+3]  = b6  + b7;
            buffer[dataptr+4]  = b8  + b9;
            buffer[dataptr+5]  = b10 + b11;
            buffer[dataptr+6]  = b12 + b13;
            buffer[dataptr+7]  = b14 + b15;
            buffer[dataptr+8]  = b16 + b17;
            buffer[dataptr+9]  = b18 + b19;
            buffer[dataptr+10] = b20 + b21;
            buffer[dataptr+11] = b22 + b23;
            buffer[dataptr+12] = b24 + b25;
            buffer[dataptr+13] = b26 + b27;
            buffer[dataptr+14] = b28 + b29;
            buffer[dataptr+15] = b30 + b31;
            buffer[dataptr+16] = b0  - b1;
            buffer[dataptr+17] = b2  - b3;
            buffer[dataptr+18] = b4  - b5;
            buffer[dataptr+19] = b6  - b7;
            buffer[dataptr+20] = b8  - b9;
            buffer[dataptr+21] = b10 - b11;
            buffer[dataptr+22] = b12 - b13;
            buffer[dataptr+23] = b14 - b15;
            buffer[dataptr+24] = b16 - b17;
            buffer[dataptr+25] = b18 - b19;
            buffer[dataptr+26] = b20 - b21;
            buffer[dataptr+27] = b22 - b23;
            buffer[dataptr+28] = b24 - b25;
            buffer[dataptr+29] = b26 - b27;
            buffer[dataptr+30] = b28 - b29;
            buffer[dataptr+31] = b30 - b31;

            dataptr += 32;
        }
     }


     private void processColumns(int[] block, int blkptr, int shift)
     {
        int dataptr = 0;
        final int end = blkptr + 32;
        final int[] buffer = this.data;
        final int adjust = (1 << shift) >> 1;

        // Pass 2: process columns.
        for (int i=blkptr; i<end; i++)
        {
            // Aliasing for speed
            final int x0  = buffer[dataptr];
            final int x1  = buffer[dataptr+32];
            final int x2  = buffer[dataptr+64];
            final int x3  = buffer[dataptr+96];
            final int x4  = buffer[dataptr+128];
            final int x5  = buffer[dataptr+160];
            final int x6  = buffer[dataptr+192];
            final int x7  = buffer[dataptr+224];
            final int x8  = buffer[dataptr+256];
            final int x9  = buffer[dataptr+288];
            final int x10 = buffer[dataptr+320];
            final int x11 = buffer[dataptr+352];
            final int x12 = buffer[dataptr+384];
            final int x13 = buffer[dataptr+416];
            final int x14 = buffer[dataptr+448];
            final int x15 = buffer[dataptr+480];
            final int x16 = buffer[dataptr+512];
            final int x17 = buffer[dataptr+544];
            final int x18 = buffer[dataptr+576];
            final int x19 = buffer[dataptr+608];
            final int x20 = buffer[dataptr+640];
            final int x21 = buffer[dataptr+672];
            final int x22 = buffer[dataptr+704];
            final int x23 = buffer[dataptr+736];
            final int x24 = buffer[dataptr+768];
            final int x25 = buffer[dataptr+800];
            final int x26 = buffer[dataptr+832];
            final int x27 = buffer[dataptr+864];
            final int x28 = buffer[dataptr+896];
            final int x29 = buffer[dataptr+928];
            final int x30 = buffer[dataptr+960];
            final int x31 = buffer[dataptr+992];

            int a0  = x0  + x1;
            int a1  = x2  + x3;
            int a2  = x4  + x5;
            int a3  = x6  + x7;
            int a4  = x8  + x9;
            int a5  = x10 + x11;
            int a6  = x12 + x13;
            int a7  = x14 + x15;
            int a8  = x16 + x17;
            int a9  = x18 + x19;
            int a10 = x20 + x21;
            int a11 = x22 + x23;
            int a12 = x24 + x25;
            int a13 = x26 + x27;
            int a14 = x28 + x29;
            int a15 = x30 + x31;
            int a16 = x0  - x1;
            int a17 = x2  - x3;
            int a18 = x4  - x5;
            int a19 = x6  - x7;
            int a20 = x8  - x9;
            int a21 = x10 - x11;
            int a22 = x12 - x13;
            int a23 = x14 - x15;
            int a24 = x16 - x17;
            int a25 = x18 - x19;
            int a26 = x20 - x21;
            int a27 = x22 - x23;
            int a28 = x24 - x25;
            int a29 = x26 - x27;
            int a30 = x28 - x29;
            int a31 = x30 - x31;

            int b0  = a0  + a1;
            int b1  = a2  + a3;
            int b2  = a4  + a5;
            int b3  = a6  + a7;
            int b4  = a8  + a9;
            int b5  = a10 + a11;
            int b6  = a12 + a13;
            int b7  = a14 + a15;
            int b8  = a16 + a17;
            int b9  = a18 + a19;
            int b10 = a20 + a21;
            int b11 = a22 + a23;
            int b12 = a24 + a25;
            int b13 = a26 + a27;
            int b14 = a28 + a29;
            int b15 = a30 + a31;
            int b16 = a0  - a1;
            int b17 = a2  - a3;
            int b18 = a4  - a5;
            int b19 = a6  - a7;
            int b20 = a8  - a9;
            int b21 = a10 - a11;
            int b22 = a12 - a13;
            int b23 = a14 - a15;
            int b24 = a16 - a17;
            int b25 = a18 - a19;
            int b26 = a20 - a21;
            int b27 = a22 - a23;
            int b28 = a24 - a25;
            int b29 = a26 - a27;
            int b30 = a28 - a29;
            int b31 = a30 - a31;

            a0  = b0  + b1;
            a1  = b2  + b3;
            a2  = b4  + b5;
            a3  = b6  + b7;
            a4  = b8  + b9;
            a5  = b10 + b11;
            a6  = b12 + b13;
            a7  = b14 + b15;
            a8  = b16 + b17;
            a9  = b18 + b19;
            a10 = b20 + b21;
            a11 = b22 + b23;
            a12 = b24 + b25;
            a13 = b26 + b27;
            a14 = b28 + b29;
            a15 = b30 + b31;
            a16 = b0  - b1;
            a17 = b2  - b3;
            a18 = b4  - b5;
            a19 = b6  - b7;
            a20 = b8  - b9;
            a21 = b10 - b11;
            a22 = b12 - b13;
            a23 = b14 - b15;
            a24 = b16 - b17;
            a25 = b18 - b19;
            a26 = b20 - b21;
            a27 = b22 - b23;
            a28 = b24 - b25;
            a29 = b26 - b27;
            a30 = b28 - b29;
            a31 = b30 - b31;

            b0  = a0  + a1;
            b1  = a2  + a3;
            b2  = a4  + a5;
            b3  = a6  + a7;
            b4  = a8  + a9;
            b5  = a10 + a11;
            b6  = a12 + a13;
            b7  = a14 + a15;
            b8  = a16 + a17;
            b9  = a18 + a19;
            b10 = a20 + a21;
            b11 = a22 + a23;
            b12 = a24 + a25;
            b13 = a26 + a27;
            b14 = a28 + a29;
            b15 = a30 + a31;
            b16 = a0  - a1;
            b17 = a2  - a3;
            b18 = a4  - a5;
            b19 = a6  - a7;
            b20 = a8  - a9;
            b21 = a10 - a11;
            b22 = a12 - a13;
            b23 = a14 - a15;
            b24 = a16 - a17;
            b25 = a18 - a19;
            b26 = a20 - a21;
            b27 = a22 - a23;
            b28 = a24 - a25;
            b29 = a26 - a27;
            b30 = a28 - a29;
            b31 = a30 - a31;

            block[i]      = (b0  + b1  + adjust) >> shift;
            block[i+32]   = (b2  + b3  + adjust) >> shift;
            block[i+64]   = (b4  + b5  + adjust) >> shift;
            block[i+96]   = (b6  + b7  + adjust) >> shift;
            block[i+128]  = (b8  + b9  + adjust) >> shift;
            block[i+160]  = (b10 + b11 + adjust) >> shift;
            block[i+192]  = (b12 + b13 + adjust) >> shift;
            block[i+224]  = (b14 + b15 + adjust) >> shift;
            block[i+256]  = (b16 + b17 + adjust) >> shift;
            block[i+288]  = (b18 + b19 + adjust) >> shift;
            block[i+320]  = (b20 + b21 + adjust) >> shift;
            block[i+352]  = (b22 + b23 + adjust) >> shift;
            block[i+384]  = (b24 + b25 + adjust) >> shift;
            block[i+416]  = (b26 + b27 + adjust) >> shift;
            block[i+448]  = (b28 + b29 + adjust) >> shift;
            block[i+480]  = (b30 + b31 + adjust) >> shift;
            block[i+512]  = (b0  - b1  + adjust) >> shift;
            block[i+544]  = (b2  - b3  + adjust) >> shift;
            block[i+576]  = (b4  - b5  + adjust) >> shift;
            block[i+608]  = (b6  - b7  + adjust) >> shift;
            block[i+640]  = (b8  - b9  + adjust) >> shift;
            block[i+672]  = (b10 - b11 + adjust) >> shift;
            block[i+704]  = (b12 - b13 + adjust) >> shift;
            block[i+736]  = (b14 - b15 + adjust) >> shift;
            block[i+768]  = (b16 - b17 + adjust) >> shift;
            block[i+800]  = (b18 - b19 + adjust) >> shift;
            block[i+832]  = (b20 - b21 + adjust) >> shift;
            block[i+864]  = (b22 - b23 + adjust) >> shift;
            block[i+896]  = (b24 - b25 + adjust) >> shift;
            block[i+928]  = (b26 - b27 + adjust) >> shift;
            block[i+960]  = (b28 - b29 + adjust) >> shift;
            block[i+992]  = (b30 - b31 + adjust) >> shift;

            dataptr++;
        }
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