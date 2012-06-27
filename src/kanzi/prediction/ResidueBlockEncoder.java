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

import kanzi.BitStreamException;
import kanzi.EntropyEncoder;
import kanzi.OutputBitStream;
import kanzi.entropy.ExpGolombEncoder;


// Encoder for residue block used before final entropy coding step
public final class ResidueBlockEncoder implements EntropyEncoder
{
    private final int scoreThreshold;
    private final OutputBitStream stream;
    private final ExpGolombEncoder gEncoder;
    private final ExpGolombEncoder testEncoder;
    private final int[][] scanTables;
    private final int rleThreshold;
    private final int maxNonZeros;
    private final int logThresholdNonZeros;
    private final int blockSize;

    private static final int DEFAULT_SKIP_SCORE = 3;
    private static final int RLE_THRESHOLD = 2;


    public ResidueBlockEncoder(OutputBitStream stream, int blockDim)
    {
        this(stream, blockDim, blockDim*blockDim-1, RLE_THRESHOLD, DEFAULT_SKIP_SCORE);
    }


    public ResidueBlockEncoder(OutputBitStream stream, int blockDim, int skipThreshold)
    {
        this(stream, blockDim, blockDim*blockDim-1, RLE_THRESHOLD, skipThreshold);
    }


    public ResidueBlockEncoder(OutputBitStream stream, int blockDim, int maxNonZeros, int skipThreshold)
    {
        this(stream, blockDim, maxNonZeros, RLE_THRESHOLD, skipThreshold);
    }


    public ResidueBlockEncoder(OutputBitStream stream, int blockDim, int maxNonZeros,
            int rleThreshold, int skipThreshold)
    {
        if (stream == null)
          throw new NullPointerException("Invalid null stream parameter");

        if ((blockDim != 4) && (blockDim != 8))
          throw new IllegalArgumentException("Invalid block dimension parameter (must be 4 or 8)");

        if (skipThreshold < 1) // skipThreshold = 0 => skip all blocks
          throw new IllegalArgumentException("Invalid skipThreshold parameter (must be a least 1)");

        if ((maxNonZeros < 1) || (maxNonZeros >= blockDim*blockDim))
          throw new IllegalArgumentException("Invalid maxNonZeros parameter (must be in [1.."+(blockDim*blockDim-1)+"])");

        this.scoreThreshold = skipThreshold;
        this.stream = stream;
        this.gEncoder = new ExpGolombEncoder(this.stream, false);
        this.testEncoder = new ExpGolombEncoder(new EmptyOutputBitStream(), false);
        this.rleThreshold = rleThreshold;
        this.maxNonZeros = maxNonZeros;
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
    public int encode(byte[] data, int blkptr, int len)
    {
       if (len != this.blockSize)
          return -1;

       long before, after;
       int min = Integer.MAX_VALUE;
       int max = 0;
       byte scan_order = Scan.ORDER_H;
       int nonZeros = 0;

       if (min != this.logThresholdNonZeros)
       {
          final int res = this.getStatistics(data, blkptr, this.scanTables[Scan.ORDER_H]);
          final int maxCoeff = (res >> 16) & 0xFF;
          final int nz = (res >> 24) & 0xFF;
          final int score = (res >> 8) & 0xFF;

          // Skip block if too little energy
          if ((maxCoeff <= 1) && (score < this.scoreThreshold))
             return this.stream.writeBits(0, this.logThresholdNonZeros);

          before = this.testEncoder.getBitStream().written();
          this.encodeDirectional(this.testEncoder, data, blkptr, nz, Scan.ORDER_H, maxCoeff);
          after = this.testEncoder.getBitStream().written();

          if ((after - before) < min)
          {
             min = (int) (after - before);
             scan_order = Scan.ORDER_H;
             max = maxCoeff;
             nonZeros = nz;
          }
       }

       if (min != this.logThresholdNonZeros)
       {
          final int res = this.getStatistics(data, blkptr, this.scanTables[Scan.ORDER_H]);
          final int maxCoeff = (res >> 16) & 0xFF;
          final int nz = (res >> 24) & 0xFF;
          final int score = (res >> 8) & 0xFF;

          // Skip block if too little energy
          if ((maxCoeff <= 1) && (score < this.scoreThreshold))
             return this.stream.writeBits(0, this.logThresholdNonZeros);

          before = this.testEncoder.getBitStream().written();
          this.encodeDirectional(this.testEncoder, data, blkptr, nz, Scan.ORDER_V, maxCoeff);
          after = this.testEncoder.getBitStream().written();

          if ((after - before) < min)
          {
             min = (int) (after - before);
             scan_order = Scan.ORDER_V;
             max = maxCoeff;
             nonZeros = nz;
          }
       }

       if (min != this.logThresholdNonZeros)
       {
          final int res = this.getStatistics(data, blkptr, this.scanTables[Scan.ORDER_Z]);
          final int maxCoeff = (res >> 16) & 0xFF;
          final int nz = (res >> 24) & 0xFF;
          final int score = (res >> 8) & 0xFF;

          // Skip block if too little energy
          if ((maxCoeff <= 1) && (score < this.scoreThreshold))
             return this.stream.writeBits(0, this.logThresholdNonZeros);

          before = this.testEncoder.getBitStream().written();
          this.encodeDirectional(this.testEncoder, data, blkptr, nz, Scan.ORDER_Z, maxCoeff);
          after = this.testEncoder.getBitStream().written();

          if ((after - before) < min)
          {
             min = (int) (after - before);
             scan_order = Scan.ORDER_Z;
             max = maxCoeff;
             nonZeros = nz;
          }
       }

       if (min != this.logThresholdNonZeros)
       {
          final int res = this.getStatistics(data, blkptr, this.scanTables[Scan.ORDER_HV]);
          final int maxCoeff = (res >> 16) & 0xFF;
          final int nz = (res >> 24) & 0xFF;
          final int score = (res >> 8) & 0xFF;

          // Skip block if too little energy
          if ((maxCoeff <= 1) && (score < this.scoreThreshold))
             return this.stream.writeBits(0, this.logThresholdNonZeros);

          before = this.testEncoder.getBitStream().written();
          this.encodeDirectional(this.testEncoder, data, blkptr, nz, Scan.ORDER_HV, maxCoeff);
          after = this.testEncoder.getBitStream().written();

          if ((after - before) < min)
          {
             min = (int) (after - before);
             scan_order = Scan.ORDER_HV;
             max = maxCoeff;
             nonZeros = nz;
          }
       }

       return this.encodeDirectional(this.gEncoder, data, blkptr, nonZeros, scan_order, max);
    }


    // Extract statistics of the coefficients in the residue block
    private int getStatistics(byte[] data, int blkptr, int[] scanTable)
    {
       int end = this.blockSize - 1;
       int max = 0;
       int idx = 1; // exclude DC coefficient
       int score = (data[blkptr] == 0) ? 0 : 5; // DC coefficient
       int nonZeros = (data[blkptr] == 0) ? 0 : 1; // DC coefficient
       int prevCoeffLocation = -1;

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

             // If the last coefficient is 1 or -1 and it comes after many 0s,
             // stop encoding at previous coefficient location.
             if ((val == 1) && (idx == end) && (prevCoeffLocation >= 0) &&
                     (idx-prevCoeffLocation >= 5))
             {
                end = prevCoeffLocation;
                break;
             }

             score += val;
             nonZeros++;
             prevCoeffLocation = idx;

             if (val > max)
                max = val;

             // Max number of non zero coefficients reached => ignore others
             if (nonZeros >= this.maxNonZeros)
             {
                end = idx;
                break;
             }
          }
       }

       return ((nonZeros & 0xFF) << 24) | ((max & 0xFF) << 16) | ((score & 0xFF)  << 8) | end;
    }


    private int encodeDirectional(EntropyEncoder ee, byte[] data, int blkptr,
            int nonZeros, byte scan_order, int max)
    {
       final int thresholdNonZeros = (1 << this.logThresholdNonZeros) - 1;
       int threshold = thresholdNonZeros;
       int log = this.logThresholdNonZeros;
       int nz = nonZeros;
       OutputBitStream obs = ee.getBitStream();

       while (nz >= threshold)
       {
          // Write threshold
          if (obs.writeBits(threshold, log) != log)
            return -1;

          nz -= threshold;
          log = this.logThresholdNonZeros - 1;
          threshold = thresholdNonZeros >> 1;
       }

       // Write difference
       if (obs.writeBits(nz, log) != log)
         return -1;

       // Encode DC coefficient
       int val = data[blkptr];

       if (val == 0)
       {
          ee.encodeByte((byte) val);
       }
       else
       {
          final int sign = val >>> 31;
          val = (val + (val >> 31)) ^ (val >> 31); //abs
          ee.encodeByte((byte) val);
          obs.writeBit(sign);
          nonZeros--;
       }

       // If DC is the only non 0 coefficient, exit now
       if (nonZeros == 0)
          return this.blockSize;

       // Select encoding mode (for AC coefficients)
       // mode = 0 => abs(x) = 0 or 1
       // mode = 1 => abs(x) = 0 or 1 or 2
       // mode = 2 => abs(x) = 0 or 1 or 2 or 3 or 4
       // mode = 3 => abs(x) is any value
       final int mode = (max <= 1) ? 0 : ((max <= 2) ? 1 : ((max <= 4) ? 2 : 3));

       // Encode mode
       if (obs.writeBits(mode, 2) != 2)
          return -1;

       // Encode scan order
       if (obs.writeBits(scan_order, 2) != 2)
          return -1;

       boolean res = true;
       int run = 0;
       int idx = 1;
       final int[] scanTable = this.scanTables[scan_order];

       // In binary mode: encode run of 0s, then the sign of x (x=1 or x=-1)
       // Otherwise, encode run of 0s, then encode abs(x)-1, then the sign of x
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

          if (mode == 0)
          {
             res &= ee.encodeByte((byte) run);
          }
          else
          {
             // Write run length
             if (remaining >= 0)
             {
                res &= (obs.writeBits(0, this.rleThreshold) == this.rleThreshold);
                res &= ee.encodeByte((byte) remaining);
             }
             else
             {
                if (run > 0)
                   res &= (obs.writeBits(0, run) == run);

                // Signal end of run
                obs.writeBit(1);
             }

             val = (val + (val >> 31)) ^ (val >> 31); //abs
             val--;

             if (mode == 3) // / Exp golomb : encoded as single bit '1' if x=1 (=> val=0)
                res &= ee.encodeByte((byte) val);
             else // encoded as n bits
                res &= (obs.writeBits(val, mode) == mode);
          }

          // Write sign
          res &= obs.writeBit(sign);
          run = 0;
          nonZeros--;
       }

       return this.blockSize;
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


    // Does nothing on write but incrementing the number of bits written
    private static class EmptyOutputBitStream implements OutputBitStream
    {
      private boolean closed;
      private long size;

      @Override
      public boolean writeBit(int bit) throws BitStreamException
      {
         if (this.closed == true)
            throw new BitStreamException("Stream closed", BitStreamException.STREAM_CLOSED);

         this.size++;
         return true;
      }

      @Override
      public int writeBits(long bits, int length) throws BitStreamException
      {
         if (this.closed == true)
            throw new BitStreamException("Stream closed", BitStreamException.STREAM_CLOSED);

         this.size += length;
         return length;
      }

      @Override
      public void flush() throws BitStreamException
      {
         if (this.closed == true)
            throw new BitStreamException("Stream closed", BitStreamException.STREAM_CLOSED);
      }

      @Override
      public void close() throws BitStreamException
      {
         this.closed = true;
      }

      @Override
      public long written()
      {
         return this.size;
      }
    }
}
