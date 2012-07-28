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
    private static final int RUN_IDX   = 0;
    private static final int COEFF_IDX = 1;

    private final OutputBitStream stream;
    private final OutputBitStream testStream;
    private final EntropyEncoder[] encoders;
    private final EntropyEncoder[] testEncoders;
    private final int[][] scanTables;
    private final int maxNonZeros;
    private final int logThresholdNonZeros;
    private final int blockSize;
    private final int scoreThreshold;

    private static final int DEFAULT_SKIP_SCORE = 0;


    public ResidueBlockEncoder(OutputBitStream stream, int blockDim)
    {
        this(stream, blockDim, blockDim*blockDim, DEFAULT_SKIP_SCORE);
    }


    public ResidueBlockEncoder(OutputBitStream stream, int blockDim, int skipThreshold)
    {
        this(stream, blockDim, blockDim*blockDim, skipThreshold);
    }


    public ResidueBlockEncoder(OutputBitStream stream, int blockDim, int maxNonZeros, int skipThreshold)
    {
        if (stream == null)
          throw new NullPointerException("Invalid null stream parameter");

        if ((blockDim != 4) && (blockDim != 8))
          throw new IllegalArgumentException("Invalid block dimension parameter (must be 4 or 8)");

        if (skipThreshold < 0) // skipThreshold = 0 => skip all blocks
          throw new IllegalArgumentException("Invalid skipThreshold parameter (must be a least 0)");

        if ((maxNonZeros < 1) || (maxNonZeros > blockDim*blockDim))
          throw new IllegalArgumentException("Invalid maxNonZeros parameter (must be in [1.."+(blockDim*blockDim)+"])");

        this.scoreThreshold = skipThreshold;
        this.stream = stream;
        this.encoders = new EntropyEncoder[] { new ExpGolombEncoder(this.stream, false),
            new ExpGolombEncoder(this.stream, true) };
        this.testStream = new NullOutputBitStream();
        this.testEncoders = new EntropyEncoder[] { new ExpGolombEncoder(this.testStream, false),
            new ExpGolombEncoder(this.testStream, true) };
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
    // Input array contains byte encoded shorts (16 bits)
    public int encode(byte[] data, int blkptr, int len)
    {
       if (len != (this.blockSize << 1))
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
          if ((maxCoeff <= 1) && (score <= this.scoreThreshold))
             return this.stream.writeBits(0, this.logThresholdNonZeros);

          before = this.testStream.written();
          this.encodeDirectional(this.testEncoders, data, blkptr, nz, Scan.ORDER_H, maxCoeff);
          after = this.testStream.written();

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
          if ((maxCoeff <= 1) && (score <= this.scoreThreshold))
             return this.stream.writeBits(0, this.logThresholdNonZeros);

          before = this.testStream.written();
          this.encodeDirectional(this.testEncoders, data, blkptr, nz, Scan.ORDER_V, maxCoeff);
          after = this.testStream.written();

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
          if ((maxCoeff <= 1) && (score <= this.scoreThreshold))
             return this.stream.writeBits(0, this.logThresholdNonZeros);

          before = this.testStream.written();
          this.encodeDirectional(this.testEncoders, data, blkptr, nz, Scan.ORDER_Z, maxCoeff);
          after = this.testStream.written();

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
          if ((maxCoeff <= 1) && (score <= this.scoreThreshold))
             return this.stream.writeBits(0, this.logThresholdNonZeros);

          before = this.testStream.written();
          this.encodeDirectional(this.testEncoders, data, blkptr, nz, Scan.ORDER_HV, maxCoeff);
          after = this.testStream.written();

          if ((after - before) < min)
          {
             min = (int) (after - before);
             scan_order = Scan.ORDER_HV;
             max = maxCoeff;
             nonZeros = nz;
          }
       }

       return this.encodeDirectional(this.encoders, data, blkptr, nonZeros, scan_order, max);
    }


    // Extract statistics of the coefficients in the residue block
    private int getStatistics(byte[] data, int blkptr, int[] scanTable)
    {
       int end = this.blockSize - 1;
       int max = 0;
       int idx = 1; // exclude DC coefficient
       int dc = (data[blkptr] << 8) | (data[blkptr+1] & 0xFF);
       int score = (dc == 0) ? 0 : 5; // DC coefficient
       int nonZeros = (dc == 0) ? 0 : 1; // DC coefficient

       // Find last non zero coefficient
       while ((end > 0) && (data[blkptr+(scanTable[end]<<1)] == 0) && (data[blkptr+(scanTable[end]<<1)+1] == 0))
          end--;

       while (idx <= end)
       {
          int offs = blkptr + (scanTable[idx++]<<1);
          int val = (data[offs] << 8) | (data[offs+1] & 0xFF);

          if (val == 0)
          {
             // Detect runs of 0
             while ((idx < end) && (data[blkptr+(scanTable[idx]<<1)] == 0) && (data[blkptr+(scanTable[idx]<<1)+1] == 0))
                idx++;
          }
          else
          {
             val = (val + (val >> 31)) ^ (val >> 31); //abs
             score += val;
             nonZeros++;

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


    private int encodeDirectional(EntropyEncoder[] encoders, byte[] data, int blkptr,
            int nonZeros, byte scan_order, int max)
    {
       final int thresholdNonZeros = (1 << this.logThresholdNonZeros) - 1;
       int threshold = thresholdNonZeros;
       int log = this.logThresholdNonZeros;
       int nz = nonZeros;
       final EntropyEncoder runEncoder = encoders[RUN_IDX];
       final EntropyEncoder coeffEncoder = encoders[COEFF_IDX];
       final OutputBitStream obs = coeffEncoder.getBitStream();

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
       int val = (data[blkptr] << 8) | (data[blkptr+1] & 0xFF);

       if (val == 0)
       {
          coeffEncoder.encodeByte((byte) 0);
          coeffEncoder.encodeByte((byte) 0);
       }
       else
       {
          final int sign = val >>> 31;
          val = (val + (val >> 31)) ^ (val >> 31); //abs
          coeffEncoder.encodeByte((byte) (val>>8));
          coeffEncoder.encodeByte((byte) (val&0xFF));
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
          int offs = blkptr + (scanTable[idx]<<1);
          val = (data[offs] << 8) | (data[offs+1] & 0xFF);
          idx++;

          if (val == 0)
          {
             run++;
             continue;
          }

          final int sign = val >>> 31;

          if (mode == 0)
          {
             res &= runEncoder.encodeByte((byte) run);
          }
          else
          {
             // Write run length
             if (run > 2)
             {
                res &= (obs.writeBits(0, 2) == 2);
                res &= runEncoder.encodeByte((byte) (run-2));
             }
             else // run = 0, 1 or 2
             {
                if (run > 0)
                   res &= (obs.writeBits(0, run) == run); // short run (len <= 2)

                // Signal end of run (same as ee.encodeByte(0))
                obs.writeBit(1);
             }

             val = (val + (val >> 31)) ^ (val >> 31); //abs
             val--;

             if (mode == 3)
             {
                // Exp golomb : encoded as single bit '1' if x=1 (=> val=0)
                res &= coeffEncoder.encodeByte((byte) (val>>8));
                res &= coeffEncoder.encodeByte((byte) (val&0xFF));
             }
             else // encoded as n bits
                res &= (obs.writeBits(val, mode) == mode);
          }

          // Write sign
          res &= obs.writeBit(sign);
          run = 0;
          nonZeros--;
       }

       return this.blockSize << 1;
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
    private static class NullOutputBitStream implements OutputBitStream
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
