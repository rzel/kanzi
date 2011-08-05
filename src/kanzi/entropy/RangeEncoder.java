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

package kanzi.entropy;

import kanzi.bitstream.BitStream;


// Based on Order 0 range coder by Dmitry Subbotin itself derived from the algorithm
// described by G.N.N Martin in his seminal article in 1979.
// [G.N.N. Martin on the Data Recording Conference, Southampton, 1979]
// Optimized for speed.

// Not thread safe
public final class RangeEncoder extends AbstractEncoder
{
    protected static final long TOP       = 1L << 48;
    protected static final long BOTTOM    = (1L << 40) - 1;
    protected static final long MAX_RANGE = BOTTOM + 1;
    protected static final long MASK      = 0x00FFFFFFFFFFFFFFL;

    private static final int NB_SYMBOLS = 257; //256 + EOF

    private long low;
    private long range;
    private boolean flushed;
    private final int[] frequencies;
    private final BitStream bitstream;
    private boolean written;
    private int dirtyLength;       // For speed optimization purpose
    private int[] dirtyThresholds; // For speed optimization purpose


    public RangeEncoder(BitStream bitstream)
    {
        this.range = (TOP << 8) - 1;
        this.bitstream = bitstream;
        this.frequencies = new int[NB_SYMBOLS+1];

        for (int i=0; i<this.frequencies.length; i++)
            this.frequencies[i] = i;

        // Keep track of lower bounds of frequency segments that need an update.
        // The array size is empirical
        this.dirtyThresholds = new int[8];
    }


    // This method is on the speed critical path (called for each byte)
    // The speed optimization is focused on reducing the frequency table update
    // This is achieved by keeping the previous value on each 'tick' and updating
    // on each 'tack' => frequency table update reduction by half
    @Override
    public boolean encodeByte(byte b)
    {
        int val = b & 0xFF;
        int[] freq = this.frequencies;
        int symbolLow = freq[val];
        int symbolHigh = freq[val+1];
        this.range /= (freq[NB_SYMBOLS] + this.dirtyLength);

        if (this.dirtyLength > 0)
        {
            for (int i=this.dirtyLength-1; i>=0; i--)
            {
                int threshold = this.dirtyThresholds[i];

                // Refresh high and low symbols
                if (val >= threshold)
                {
                    symbolLow++;
                    symbolHigh++;
                }
                else if (val + 1 >= threshold)
                    symbolHigh++;
            }
        }

        // Encode symbol
        this.low += (symbolLow * this.range);
        this.range *= (symbolHigh - symbolLow);

        long checkRange = ((this.low ^ (this.low + this.range)) & MASK);

        // If the left-most digits are the same throughout the range, write bits to bitstream
        while ((checkRange < TOP) || (this.range < MAX_RANGE))
        {
            // Normalize
            if (checkRange >= TOP)
                this.range = (-this.low & MASK) & BOTTOM;

            this.bitstream.writeBits(((this.low >> 48) & 0xFF), 8);
            this.range <<= 8;
            this.low <<= 8;
            checkRange = ((this.low ^ (this.low + this.range)) & MASK);
        }

        // Update frequencies: computational bottleneck !!!
        val++;

        // Find location of 'val' in sorted list of dirty thresholds
        if (this.dirtyLength == 0)
        {
            this.dirtyThresholds[0] = val;
        }
        else
        {
            int idx = 0;

            for ( ; idx<this.dirtyLength; idx++)
            {
                if (val <= this.dirtyThresholds[idx])
                    break;
            }
            
            for (int j=this.dirtyLength; j>idx; j--)
              this.dirtyThresholds[j] = this.dirtyThresholds[j-1];

            this.dirtyThresholds[idx] = val;
        }

        if (++this.dirtyLength == this.dirtyThresholds.length)
        {
            // Time to actually update the frequency table
            int prev = this.dirtyThresholds[0];
            int inc = 1;

            for (int i=1; i<this.dirtyLength; i++)
            {
                int threshold = this.dirtyThresholds[i];

                // Update each frequency segment
                for (int j=prev; j<threshold; j++)
                    freq[j] += inc;

                prev = threshold;
                inc++;
            }

            for (int j=prev; j<=NB_SYMBOLS; j++)
                freq[j] += inc;

            this.dirtyLength = 0;
        }

        this.written = true;
        return true;
    }


    @Override
    public void dispose()
    {
        if ((this.written == true) && (this.flushed == false))
        {
            // After this call the frequency tables may not be up to date. Do not care
            this.flushed = true;
            this.dirtyLength = 0;

            for (int i=0; i<7; i++)
            {
                this.bitstream.writeBits(((this.low >> 48) & 0xFF), 8);
                this.low <<= 8;
            }

            this.bitstream.flush();
        }
    }


    @Override
    public BitStream getBitStream()
    {
       return this.bitstream;
    }
}