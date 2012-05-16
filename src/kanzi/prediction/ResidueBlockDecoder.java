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

package kanzi.prediction;

import kanzi.EntropyDecoder;
import kanzi.InputBitStream;
import kanzi.entropy.ExpGolombDecoder;


// Decoder for residue block used after initial entropy coding step
public final class ResidueBlockDecoder implements EntropyDecoder
{
    private final InputBitStream stream;
    private final ExpGolombDecoder gDecoder;
    private final int rleThreshold;

    private static final int LOG_THRESHOLD_NZ = 4;
    private static final int BLOCK_DIM = 8;
    private static final int BLOCK_SIZE = BLOCK_DIM * BLOCK_DIM;

    // 8x8 block scan tables
    public static final int[][] SCAN_TABLES =
    {
        // SCAN_H : horizontal
        {  0,  1,  2,  3,  4,  5,  6,  7,
           8,  9, 10, 11, 12, 13, 14, 15,
          16, 17, 18, 19, 20, 21, 22, 23,
          24, 25, 26, 27, 28, 29, 30, 31,
          32, 33, 34, 35, 36, 37, 38, 39,
          40, 41, 42, 43, 44, 45, 46, 47,
          48, 49, 50, 51, 52, 53, 54, 55,
          56, 57, 58, 59, 60, 61, 62, 63
        },
        // SCAN_V : vertical
        {  0,  8, 16, 24, 32, 40, 48, 56,
           1,  9, 17, 25, 33, 41, 49, 57,
           2, 10, 18, 26, 34, 42, 50, 58,
           3, 11, 19, 27, 35, 43, 51, 59,
           4, 12, 20, 28, 36, 44, 52, 60,
           5, 13, 21, 29, 37, 45, 53, 61,
           6, 14, 22, 30, 38, 46, 54, 62,
           7, 15, 23, 31, 39, 47, 55, 63
        },
        // SCAN_Z : zigzag
        {  0,  1,  8,  2, 16,  3, 24,  4,
          32,  5, 40,  6, 48,  7, 56,  9,
          10, 17, 11, 25, 12, 33, 13, 41,
          14, 49, 15, 57, 18, 19, 26, 20,
          34, 21, 42, 22, 50, 23, 58, 27,
          28, 35, 29, 43, 30, 51, 31, 59,
          36, 37, 44, 38, 52, 39, 60, 45,
          46, 53, 47, 61, 54, 55, 62, 63
        },
        // SCAN_VH : mix vertical + horizontal
        {  0,  1,  8, 16,  9,  2,  3, 10,
          17, 24, 32, 25, 18, 11,  4,  5,
          12, 19, 26, 33, 40, 48, 41, 34,
          27, 20, 13,  6,  7, 14, 21, 28,
          35, 42, 49, 56, 57, 50, 43, 36,
          29, 22, 15, 23, 30, 37, 44, 51,
          58, 59, 52, 45, 38, 31, 39, 46,
          53, 60, 61, 54, 47, 55, 62, 63
        }
    };


    public ResidueBlockDecoder(InputBitStream stream)
    {
        this.stream = stream;
        this.gDecoder = new ExpGolombDecoder(this.stream, false);
        this.rleThreshold = 5;
    }


    // Decode the residue data, output to provided array
    @Override
    public int decode(byte[] data, int blkptr, int len)
    {
       if (len != BLOCK_SIZE)
          return -1;
       
       final int end = blkptr + BLOCK_SIZE;

       while (blkptr < end)
       {
          // Decode number of non-zero coefficients
          int nonZeros = (int) this.stream.readBits(LOG_THRESHOLD_NZ);

          if (nonZeros == 0)
          {
              // Block skipped
              final int endi = blkptr + BLOCK_SIZE;

              for (int i=blkptr; i<endi; i+=8)
              {
                 data[i]   = 0;
                 data[i+1] = 0;
                 data[i+2] = 0;
                 data[i+3] = 0;
                 data[i+4] = 0;
                 data[i+5] = 0;
                 data[i+6] = 0;
                 data[i+7] = 0;
              }

              blkptr = endi;
              return BLOCK_SIZE;
          }

          if (nonZeros == (1 << LOG_THRESHOLD_NZ) - 1)
              nonZeros += (int) this.stream.readBits(LOG_THRESHOLD_NZ);

          // Decode binary mode (residue contains only -1, 0, 1) or not
          final boolean binaryMode = (this.stream.readBit() == 1) ? true : false;

          // Decode scan order
          final int scan_order = (int) this.stream.readBits(2);

          int idx = 0;
          final int[] scanTable = SCAN_TABLES[scan_order];

          // IfF non binary mode, decode DC coefficient
          if (binaryMode == false)
          {
             idx = 1;
             data[blkptr] = this.gDecoder.decodeByte();

             if (data[blkptr] != 0)
                nonZeros--;
          }

          int run = 0;

          if (binaryMode == true)
          {
             while (nonZeros > 0)
             {
                while (this.stream.readBit() == 0)
                   run++;

                while (run-- > 0)
                   data[blkptr+scanTable[idx++]] = 0;

                final int sign = this.stream.readBit();
                data[blkptr+scanTable[idx++]] = (byte) (1 - (sign << 1));
                nonZeros--;
             }
          }
          else // regular mode (abs(max) > 1)
          {
             while (nonZeros > 0)
             {
                while ((this.stream.readBit() == 0) && (run < this.rleThreshold))
                   run++;

                // Add remainder to get full value of run length
                if (run == this.rleThreshold)
                   run += this.gDecoder.decodeByte();

                while (run-- > 0)
                   data[blkptr+scanTable[idx++]] = 0;

                int val = this.gDecoder.decodeByte();
                val++;

                if (this.stream.readBit() == 1)
                   val = -val;

                data[blkptr+scanTable[idx++]] = (byte) val;
                nonZeros--;
             }
          }

          // Add remaining 0s (not encoded)
          while (idx < BLOCK_SIZE)
             data[blkptr+scanTable[idx++]] = 0;

          blkptr += BLOCK_SIZE;
       }

       return BLOCK_SIZE;
    }


    @Override
    public byte decodeByte()
    {
       throw new UnsupportedOperationException("Operation not supported");
    }


    @Override
    public InputBitStream getBitStream()
    {
       return this.stream;
    }


    @Override
    public void dispose()
    {
    }
}
