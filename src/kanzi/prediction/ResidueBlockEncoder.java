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

import kanzi.EntropyEncoder;
import kanzi.OutputBitStream;
import kanzi.entropy.ExpGolombEncoder;


// Encoder for residue block used before final entropy coding step
public final class ResidueBlockEncoder implements EntropyEncoder
{
    private final int scoreThreshold;
    private final OutputBitStream stream;
    private final ExpGolombEncoder gEncoder;
    private final int rleThreshold;
    private final int maxNonZeros;

    private static final int MAX_NON_ZEROS = 30;// enough ?
    private static final int LOG_THRESHOLD_NZ = 4;
    private static final int RLE_THRESHOLD = 9;
    private static final int BLOCK_DIM = 8;
    private static final int BLOCK_SIZE = BLOCK_DIM * BLOCK_DIM;
    private static final byte SCAN_H  = 0;
    private static final byte SCAN_V  = 1;
    private static final byte SCAN_Z  = 2;
    private static final byte SCAN_HV = 3;


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

    private static final int[] SCAN_TABLE_H  = SCAN_TABLES[SCAN_H];
    private static final int[] SCAN_TABLE_V  = SCAN_TABLES[SCAN_V];
    private static final int[] SCAN_TABLE_Z  = SCAN_TABLES[SCAN_Z];
    private static final int[] SCAN_TABLE_HV = SCAN_TABLES[SCAN_HV];



    public ResidueBlockEncoder(int skipThreshold, OutputBitStream stream)
    {
        this(skipThreshold, stream, MAX_NON_ZEROS);
    }


    public ResidueBlockEncoder(int skipThreshold, OutputBitStream stream, int maxNonZeros)
    {
        this(skipThreshold, stream, maxNonZeros, RLE_THRESHOLD);
    }


    protected ResidueBlockEncoder(int skipThreshold, OutputBitStream stream, 
            int maxNonZeros, int rleThreshold)
    {
        this.scoreThreshold = skipThreshold;
        this.stream = stream;
        this.gEncoder = new ExpGolombEncoder(this.stream, false);
        this.rleThreshold = rleThreshold;
        this.maxNonZeros = maxNonZeros;
    }


    // Find scan order that minimizes the size of the output
    // Bit encode the residue data
    @Override
    public int encode(byte[] data, int blkptr, int len)
    {
       if (len != BLOCK_SIZE)
          return -1;

       // Test horizontal scan order
       final int resH = this.getStatistics(data, blkptr, SCAN_TABLE_H);
       final int max = (resH >> 16) & 0xFF;
       final int nonZeros = (resH >> 24) & 0xFF;
       final int scoreH = (resH >> 8) & 0xFF;
       final int skipBlockBits = LOG_THRESHOLD_NZ;

       if ((max <= 1) && (scoreH < this.scoreThreshold))
          return this.stream.writeBits(0, skipBlockBits);

       // Test vertical scan order
       final int endH = resH & 0xFF;
       final int resV = this.getStatistics(data, blkptr, SCAN_TABLE_V);
       final int scoreV = (resV >> 8) & 0xFF;

       if ((max <= 1) && (scoreV < this.scoreThreshold))
          return this.stream.writeBits(0, skipBlockBits);

       // Test zigzag scan order
       final int endV = resV & 0xFF;
       final int resZ = this.getStatistics(data, blkptr, SCAN_TABLE_Z);
       final int scoreZ = (resZ >> 8) & 0xFF;

       if ((max <= 1) && (scoreZ < this.scoreThreshold))
          return this.stream.writeBits(0, skipBlockBits);

       // Test horizontal+vertical scan order
       final int endZ = resZ & 0xFF;
       final int resHV = this.getStatistics(data, blkptr, SCAN_TABLE_HV);
       final int scoreHV = (resHV >> 8) & 0xFF;

       if ((max <= 1) && (scoreHV < this.scoreThreshold))
          return this.stream.writeBits(0, skipBlockBits);

       final int endHV = resHV & 0xFF;
       final int min1 = (endH < endV) ? endH : endV;
       final int min2 = (endZ < endHV) ? endZ : endHV;
       final int min = (min1 < min2) ? min1 : min2;
       byte scan_order = SCAN_HV;

       if (min == endH)
          scan_order = SCAN_H;
       else if (min == endV)
          scan_order = SCAN_V;
       else if (min == endZ)
          scan_order = SCAN_Z;

       return this.encodeDirectional(data, blkptr, nonZeros, scan_order, max);
    }


    // Extract statistics of the coefficients in the residue block
    private int getStatistics(byte[] data, int blkptr, int[] scanTable)
    {
       int end = BLOCK_SIZE - 1;
       int max = 0;
       int idx = 1; // exclude DC coefficient
       int score = (data[blkptr] == 0) ? 0 : 5; // DC coefficient
       int nonZeros = (data[blkptr] == 0) ? 0 : 1; // DC coefficient

       // Find last non zero coefficient
       while ((end > 0) && (data[blkptr+scanTable[end]] == 0))
          end--;

       while (idx <= end)
       {
          int val = data[blkptr+scanTable[idx++]];

          if (val == 0)
          {
              // Detect runs of 0
              while ((idx < end) && (data[blkptr+scanTable[idx]] == 0))
                 idx++;
          }
          else
          {
             val = (val + (val >> 31)) ^ (val >> 31); //abs
             score += val;
             nonZeros++;

             if (val > max)
                max = val;

             // Limit number of non zeros coefficients, ignore others
             if (nonZeros >= this.maxNonZeros)
             {
                end = idx;
                break;
             }
          }
       }

       return ((nonZeros & 0xFF) << 24) | ((max & 0xFF) << 16) | ((score & 0xFF)  << 8) | end;
    }


    private int encodeDirectional(byte[] data, int blkptr, int nonZeros, byte scan_order, int max)
    {
       // Encode number of non-zero coefficients:
       // If the number of coefficients is [0..14], use 4 bits
       // If the number of coefficients is in [15..30], add 4 bits for the difference
       // EG: N=7  => 0111
       // EG: N=15 => 1111 0000      N=30 => 1111 1111
       final int thresholdNonZeros = (1 << LOG_THRESHOLD_NZ) - 1;

       if (nonZeros < thresholdNonZeros)
       {
          if (this.stream.writeBits(nonZeros, LOG_THRESHOLD_NZ) != LOG_THRESHOLD_NZ)
             return -1;
       }
       else
       {
          // Write threshold
          if (this.stream.writeBits(thresholdNonZeros, LOG_THRESHOLD_NZ) != LOG_THRESHOLD_NZ)
            return -1;
          
          // Write difference
          if (this.stream.writeBits(nonZeros-thresholdNonZeros, LOG_THRESHOLD_NZ) != LOG_THRESHOLD_NZ) 
            return -1;
       }

       // Select mode
       // mode = 0 => abs(x) = 0 or 1
       // mode = 1 => abs(x) = 0 or 1 or 2
       // mode = 2 => abs(x) = 0 or 1 or 2 or 3 or 4
       // mode = 3 => abs(x) is unbounded
       final int mode = (max <= 1) ? 0 : ((max <= 2) ? 1 : ((max <= 4) ? 2 : 3));

       // Encode mode
       if (this.stream.writeBits(mode, 2) != 2)
          return -1;

       // Encode scan order
       if (this.stream.writeBits(scan_order, 2) != 2)
          return -1;

       // Encode DC coefficient
       int val = data[blkptr];

       if (val == 0)
       {
          this.gEncoder.encodeByte((byte) val);
       }
       else
       {
          final int sign = val >>> 31;
          val = (val + (val >> 31)) ^ (val >> 31); //abs
          this.gEncoder.encodeByte((byte) val);
          this.stream.writeBit(sign);
          nonZeros--;
       }
       
       boolean res = true;
       int run = 0;
       int idx = 1;
       final int[] scanTable = SCAN_TABLES[scan_order];

       // In binary mode: encode 00..0x as a run, then the sign of x (x=1 or x=-1)
       // Otherwise, encode 00..0x as a run of 0s, then encode abs(x)-1 with
       // exp-golomb codes, then the sign of x
       // The run is encoded as length+1 zeros if the run length is less than a
       // threshold else length+1 zeros followed by remainder (exp golomb encoded).
       while ((nonZeros > 0) && (res == true))
       {
          val = data[blkptr+scanTable[idx]];
          idx++;

          if (val == 0)
          {
             run++;
             continue;
          }

          final int sign = val >>> 31;
          final int remaining = run - this.rleThreshold;          

          // Write run length
          if (remaining >= 0)
          {
             res &= (this.stream.writeBits(0, this.rleThreshold) == this.rleThreshold);
             res &= this.gEncoder.encodeByte((byte) remaining);
          }
          else
          {
             while (run-- > 0)
                res &= this.stream.writeBit(0);

             // Signal end of run
             this.stream.writeBit(1);
          }

          if (mode != 0) // non binary: must encode value
          {
             val = (val + (val >> 31)) ^ (val >> 31); //abs
             val--;

             if (mode == 3) // / Exp golomb : encoded as single bit '1' if x=1 (=> val=0)
                res &= this.gEncoder.encodeByte((byte) val);
             else // encoded as n bits
                res &= (this.stream.writeBits(val, mode) == mode);
          }

          // Write sign
          res &= this.stream.writeBit(sign);
          run = 0;
          nonZeros--;
       }

       return BLOCK_SIZE;
    }


    @Override
    public boolean encodeByte(byte val)
    {
       throw new UnsupportedOperationException("Operation not supported.");
    }


    @Override
    public OutputBitStream getBitStream()
    {
       return this.stream;
    }


    @Override
    public void dispose()
    {
    }

}
