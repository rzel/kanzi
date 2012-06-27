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
    private final int[][] scanTables;
    private final int rleThreshold;
    private final int logThresholdNonZeros;
    private final int blockSize;

    private static final int RLE_THRESHOLD = 2;


    public ResidueBlockDecoder(InputBitStream stream, int blockDim)
    {
        this(stream, blockDim, blockDim*blockDim-1, RLE_THRESHOLD);
    }


    public ResidueBlockDecoder(InputBitStream stream, int blockDim, int maxNonZeros)
    {
        this(stream, blockDim, maxNonZeros, RLE_THRESHOLD);
    }


    public ResidueBlockDecoder(InputBitStream stream, int blockDim, int maxNonZeros, int rleThreshold)
    {
        if (stream == null)
          throw new NullPointerException("Invalid null stream parameter");

        if ((blockDim != 4) && (blockDim != 8))
          throw new IllegalArgumentException("Invalid block dimension parameter (must be 4 or 8)");

        if ((maxNonZeros < 1) || (maxNonZeros >= blockDim*blockDim))
          throw new IllegalArgumentException("Invalid maxNonZeros parameter (must be in [1.."+(blockDim*blockDim-1)+"]");

        this.stream = stream;
        this.gDecoder = new ExpGolombDecoder(this.stream, false);
        this.rleThreshold = rleThreshold;
        this.blockSize = blockDim*blockDim;
        this.scanTables = (this.blockSize == 64) ? Scan.TABLES_64 : Scan.TABLES_16;

        int log = 5;

        if (maxNonZeros <= 6)
           log = 2;
        else if (maxNonZeros <= 12)
           log = 3;
        else if (maxNonZeros <= 34)
           log = 4;

        this.logThresholdNonZeros = log;
    }


    public int getLogNonZeroCoefficients()
    {
       return this.logThresholdNonZeros;
    }


    // Decode the residue data, output to provided array
    @Override
    public int decode(byte[] data, int blkptr, int len)
    {
       if (len != this.blockSize)
          return -1;
       
       final int end = blkptr + this.blockSize;

       while (blkptr < end)
       {
          // Decode number of non-zero coefficients
          int nz = (int) this.stream.readBits(this.logThresholdNonZeros);

          if (nz == 0)
          {
              // Block skipped
              final int endi = blkptr + this.blockSize;

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

              return this.blockSize;
          }

          final int thresholdNonZeros = (1 << this.logThresholdNonZeros) - 1;
          int threshold = thresholdNonZeros ;
          int log = this.logThresholdNonZeros;
          int nonZeros = nz;

          while (nz == threshold)
          {
             threshold = thresholdNonZeros >> 1;
             log = this.logThresholdNonZeros - 1;
             nz = (int) this.stream.readBits(log);
             nonZeros += nz;
          }          

          // Decode DC coefficient
          int val = this.gDecoder.decodeByte();
          //int val = (int) this.stream.readBits(7);

          if (val != 0)
          {
             if (this.stream.readBit() == 1)
                val = -val;

             nonZeros--;
          }

          data[blkptr] = (byte) val;

          // Early exit if DC is the only non zero coefficient
          if (nonZeros == 0)
          {
             final int endi = blkptr + this.blockSize;

             for (int i=blkptr+1; i<endi; i++)
                data[i] = 0;

             return this.blockSize;
          }

          // Decode decoding mode (for AC coefficients)
          // mode = 1 => abs(x) = 0 or 1
          // mode = 2 => abs(x) = 0 or 1 or 2
          // mode = 2 => abs(x) = 0 or 1 or 2 or 3 or 4
          // mode = 3 => abs(x) is any value
          final int mode = (int) this.stream.readBits(2);

          // Decode scan order
          final int scan_order = (int) this.stream.readBits(2);

          int idx = 1;
          int run = 0;
          final int[] scanTable = this.scanTables[scan_order];

          while (nonZeros > 0)
          {
             if (mode == 0)
             {
                run = this.gDecoder.decodeByte();
             }
             else
             {
                while ((run < this.rleThreshold) && (this.stream.readBit() == 0))
                   run++;

                // Add remainder to get full value of run length
                if (run == this.rleThreshold)
                   run += this.gDecoder.decodeByte();
             }

             while (run-- > 0)
                data[blkptr+scanTable[idx++]] = 0;

             val = 1;

             if (mode == 3) //decoded as exp-golomb
                val += this.gDecoder.decodeByte();
             else if (mode != 0) // decoded as n bits
                val += this.stream.readBits(mode);

             if (this.stream.readBit() == 1)
                val = -val;

             data[blkptr+scanTable[idx++]] = (byte) val;
             run = 0;
             nonZeros--;
          }

          // Add remaining 0s (not encoded)
          while (idx < this.blockSize)
             data[blkptr+scanTable[idx++]] = 0;

          blkptr += this.blockSize;
       }

       return this.blockSize;
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
