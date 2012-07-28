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
    private static final int RUN_IDX   = 0;
    private static final int COEFF_IDX = 1;

    private final InputBitStream stream;
    private final EntropyDecoder[] decoders;
    private final int[][] scanTables;
    private final int logThresholdNonZeros;
    private final int blockSize;


    public ResidueBlockDecoder(InputBitStream stream, int blockDim)
    {
        this(stream, blockDim, blockDim*blockDim-1);
    }


    public ResidueBlockDecoder(InputBitStream stream, int blockDim, int maxNonZeros)
    {
        if (stream == null)
          throw new NullPointerException("Invalid null stream parameter");

        if ((blockDim != 4) && (blockDim != 8))
          throw new IllegalArgumentException("Invalid block dimension parameter (must be 4 or 8)");

        if ((maxNonZeros < 1) || (maxNonZeros >= blockDim*blockDim))
          throw new IllegalArgumentException("Invalid maxNonZeros parameter (must be in [1.."+(blockDim*blockDim-1)+"]");

        this.stream = stream;
        this.decoders = new EntropyDecoder[] { new ExpGolombDecoder(this.stream, false),
           new ExpGolombDecoder(this.stream, true) };
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


    @Override
    // Output array contains byte encoded shorts (16 bits)
    public int decode(byte[] data, int blkptr, int len)
    {
       if (len != (this.blockSize << 1))
          return -1;
       
       final int end = blkptr + len;
       final EntropyDecoder runDecoder = decoders[RUN_IDX];
       final EntropyDecoder coeffDecoder = decoders[COEFF_IDX];

       while (blkptr < end)
       {
          // Decode number of non-zero coefficients
          int nz = (int) this.stream.readBits(this.logThresholdNonZeros);

          if (nz == 0)
          {
              // Block skipped
              while (blkptr < end)
                 data[blkptr++] = 0;

              return len;
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
          int val = (coeffDecoder.decodeByte() << 8) | (coeffDecoder.decodeByte() & 0xFF);

          if (val != 0)
          {
             if (this.stream.readBit() == 1)
                val = -val;

             nonZeros--;
          }

          data[blkptr]   = (byte) (val >> 8);
          data[blkptr+1] = (byte) (val & 0xFF);

          // Early exit if DC is the only non zero coefficient
          if (nonZeros == 0)
          {
             for (int i=blkptr+2; i<end; i++)
                data[i] = 0;

             return len;
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
                run = runDecoder.decodeByte();
             }
             else
             {
                while ((run < 2) && (this.stream.readBit() == 0))
                   run++;

                // Add remainder to get full value of run length
                if (run == 2)
                   run += runDecoder.decodeByte();
             }

             while (run-- > 0)
             {
                data[blkptr+(scanTable[idx]<<1)] = 0;
                data[blkptr+(scanTable[idx]<<1)+1] = 0;
                idx++;
             }

             val = 1;

             if (mode == 3) //decoded as exp-golomb
                val += ((coeffDecoder.decodeByte() << 8) | (coeffDecoder.decodeByte() & 0xFF));
             else if (mode != 0) // decoded as n bits
                val += this.stream.readBits(mode);

             if (this.stream.readBit() == 1)
                val = -val;

             data[blkptr+(scanTable[idx]<<1)] = (byte) (val >> 8);
             data[blkptr+(scanTable[idx]<<1)+1] = (byte) (val & 0xFF);
             idx++;
             run = 0;
             nonZeros--;
          }

          // Add remaining 0s (not encoded)
          while (idx < this.blockSize)
          {
             data[blkptr+(scanTable[idx]<<1)] = 0;
             data[blkptr+(scanTable[idx]<<1)+1] = 0;
             idx++;
          }

          blkptr += len;
       }

       return len;
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
