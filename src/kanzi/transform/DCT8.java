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


// Implementation of Discrete Cosine Transform of dimension 8 
// Due to rounding errors, the recosntruction may not be perfect
public final class DCT8 implements IntTransform
{
    // Weights
    private static final int W0  = 64;
    private static final int W1  = 64;
    private static final int W8  = 89;
    private static final int W9  = 75;
    private static final int W10 = 50;
    private static final int W11 = 18;
    private static final int W16 = 83;
    private static final int W17 = 36;
    private static final int W24 = 75;
    private static final int W25 = -18;
    private static final int W26 = -89;
    private static final int W27 = -50;
    private static final int W32 = 64;
    private static final int W33 = -64;
    private static final int W40 = 50;
    private static final int W41 = -89;
    private static final int W42 = 18;
    private static final int W43 = 75;
    private static final int W48 = 36;
    private static final int W49 = -83;
    private static final int W56 = 18;
    private static final int W57 = -50;
    private static final int W58 = 75;
    private static final int W59 = -89;
    
    private static final int MAX_VAL = 1<<16;
    private static final int MIN_VAL = -(MAX_VAL+1);            

    private final int fShift;
    private final int iShift;
    private final int[] data;
 

    public DCT8()
    {
       this.fShift = 10;
       this.iShift = 20;
       this.data = new int[64];
    }
    

    public int[] forward(int[] block)
    {
       return this.forward(block, 0);
    }


    @Override
    public int[] forward(int[] block, int blkptr)
    {
       this.computeForward(block, blkptr, this.data, 0, 5);
       this.computeForward(this.data, 0, block, blkptr, this.fShift-5);
       return block;
    }
    
    
    private int[] computeForward(int[] input, int iIdx, int[] output, int oIdx, int shift)
    {
       final int round = (shift == 0) ? 0 : 1 << (shift - 1);
       
       for (int i=0; i<8; i++)
       {
          final int x0  = input[iIdx];
          final int x1  = input[iIdx+1];
          final int x2  = input[iIdx+2];
          final int x3  = input[iIdx+3];
          final int x4  = input[iIdx+4];
          final int x5  = input[iIdx+5];
          final int x6  = input[iIdx+6];
          final int x7  = input[iIdx+7];
       
          final int a0 = x0 + x7;
          final int a1 = x1 + x6;
          final int a2 = x0 - x7;
          final int a3 = x1 - x6;
          final int a4 = x2 + x5;
          final int a5 = x3 + x4;
          final int a6 = x2 - x5;
          final int a7 = x3 - x4;
 
          final int b0 = a0 + a5;
          final int b1 = a1 + a4;
          final int b2 = a0 - a5;
          final int b3 = a1 - a4;
          
          final int j = oIdx + i;

          output[j]    = ((W0* b0) + (W1 *b1) + round) >> shift;
          output[j+8]  = ((W8* a2) + (W9 *a3) + (W10*a6) + (W11*a7) + round) >> shift;
          output[j+16] = ((W16*b2) + (W17*b3) + round) >> shift;
          output[j+24] = ((W24*a2) + (W25*a3) + (W26*a6) + (W27*a7) + round) >> shift;
          output[j+32] = ((W32*b0) + (W33*b1) + round) >> shift;
          output[j+40] = ((W40*a2) + (W41*a3) + (W42*a6) + (W43*a7) + round) >> shift;
          output[j+48] = ((W48*b2) + (W49*b3) + round) >> shift;
          output[j+56] = ((W56*a2) + (W57*a3) + (W58*a6) + (W59*a7) + round) >> shift;
        
          iIdx += 8;
       }
    
       return output;
    }


    public int[] inverse(int[] block)
    {
       return this.inverse(block, 0);
    }


    @Override
    public int[] inverse(int[] block, int blkptr)
    {
       this.computeInverse(block, blkptr, this.data, 0, 10);
       this.computeInverse(this.data, 0, block, blkptr, this.iShift-10);
       return block;
    }
    
    
    private int[] computeInverse(int[] input, int iIdx, int[] output, int oIdx, int shift)
    {
       final int round = (shift == 0) ? 0 : 1 << (shift - 1);
       
       for (int i=0; i<8; i++)
       {
          final int j = iIdx + i;
          final int x0 = input[j];
          final int x1 = input[j+8];
          final int x2 = input[j+16];
          final int x3 = input[j+24];
          final int x4 = input[j+32];
          final int x5 = input[j+40];
          final int x6 = input[j+48];
          final int x7 = input[j+56];
          
          final int a0 = (W8 *x1) + (W24*x3) + (W40*x5) + (W56*x7);
          final int a1 = (W9 *x1) + (W25*x3) + (W41*x5) + (W57*x7);
          final int a2 = (W10*x1) + (W26*x3) + (W42*x5) + (W58*x7);
          final int a3 = (W11*x1) + (W27*x3) + (W43*x5) + (W59*x7);
          final int a4 = (W16*x2) + (W48*x6);
          final int a5 = (W17*x2) + (W49*x6);
          final int a6 = (W0 *x0) + (W32*x4);
          final int a7 = (W1 *x0) + (W33*x4);

          final int b0 = a6 + a4;
          final int b1 = a7 + a5;
          final int b2 = a6 - a4;
          final int b3 = a7 - a5;

          final int c0 = (b0 + a0 + round) >> shift;
          final int c1 = (b1 + a1 + round) >> shift;
          final int c2 = (b3 + a2 + round) >> shift;
          final int c3 = (b2 + a3 + round) >> shift;
          final int c4 = (b2 - a3 + round) >> shift;
          final int c5 = (b3 - a2 + round) >> shift;
          final int c6 = (b1 - a1 + round) >> shift;
          final int c7 = (b0 - a0 + round) >> shift;
          
          output[oIdx]   = (c0 >= MAX_VAL) ? MAX_VAL : ((c0 <= MIN_VAL) ? MIN_VAL : c0);
          output[oIdx+1] = (c1 >= MAX_VAL) ? MAX_VAL : ((c1 <= MIN_VAL) ? MIN_VAL : c1);
          output[oIdx+2] = (c2 >= MAX_VAL) ? MAX_VAL : ((c2 <= MIN_VAL) ? MIN_VAL : c2);
          output[oIdx+3] = (c3 >= MAX_VAL) ? MAX_VAL : ((c3 <= MIN_VAL) ? MIN_VAL : c3);
          output[oIdx+4] = (c4 >= MAX_VAL) ? MAX_VAL : ((c4 <= MIN_VAL) ? MIN_VAL : c4);
          output[oIdx+5] = (c5 >= MAX_VAL) ? MAX_VAL : ((c5 <= MIN_VAL) ? MIN_VAL : c5);
          output[oIdx+6] = (c6 >= MAX_VAL) ? MAX_VAL : ((c6 <= MIN_VAL) ? MIN_VAL : c6);
          output[oIdx+7] = (c7 >= MAX_VAL) ? MAX_VAL : ((c7 <= MIN_VAL) ? MIN_VAL : c7);

          oIdx += 8;
       }
       
       return output;
    }

}