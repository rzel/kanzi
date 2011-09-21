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

import kanzi.BitStream;
import kanzi.BitStreamException;


// Based on Order 0 range coder by Dmitry Subbotin itself derived from the algorithm
// described by G.N.N Martin in his seminal article in 1979.
// [G.N.N. Martin on the Data Recording Conference, Southampton, 1979]
// Optimized for speed.

// Not thread safe
public final class RangeDecoder extends AbstractDecoder
{
    protected static final long TOP       = 1L << 48;
    protected static final long BOTTOM    = 1L << 40;
    protected static final long MASK      = 0x00FFFFFFFFFFFFFFL;

    private static final int NB_SYMBOLS = 257; //256 + EOF
    private static final int LAST = NB_SYMBOLS - 1;

    private long code;
    private long low;
    private long range;
    private final int[] baseFreq;
    private final int[] deltaFreq;
    private final BitStream bitstream;
    private boolean initialized;


    public RangeDecoder(BitStream bitstream)
    {
        this.range = (TOP << 8) - 1;
        this.bitstream = bitstream;
        
        // Since the frequency update after each byte decoded is the bottleneck,
        // split the frequency table into an array of absolute frequencies (with
        // indexes multiple of 16) and delta frequencies (relative to the previous
        // abosulte frequency) with indexes in the [0..15] range
        // This way, the update of frequencies is much faster
        this.deltaFreq = new int[NB_SYMBOLS+1];
        this.baseFreq = new int[(NB_SYMBOLS>>4)+1];

        for (int i=0; i<this.deltaFreq.length; i++)
            this.deltaFreq[i] = i & 15; // DELTA

        for (int i=0; i<this.baseFreq.length; i++)
            this.baseFreq[i] = i << 4; // DELTA
    }



    // This method is on the speed critical path (called for each byte)
    // The speed optimization is focused on reducing the frequency table update
    @Override
    public byte decodeByte()
    {
        if (this.initialized == false)
        {
            this.initialized = true;
            this.code = this.bitstream.readBits(56) & 0xFFFFFFFF;
        }

        this.range /= (this.baseFreq[NB_SYMBOLS>>4] + this.deltaFreq[NB_SYMBOLS]);
        final int count = (int) ((this.code - this.low) / this.range);

        // Find first frequency less than 'count'
        int value = this.baseFreq.length - 1;

        while ((value > 0) && (count < this.baseFreq[value]))
            value--;

        int base = this.baseFreq[value];
        value <<= 4;

        if (count != base)
        {
           final int count2 = count - base;
           final int end = value;
           value = ((value + 15) > NB_SYMBOLS) ? NB_SYMBOLS : value+15;

           while ((value >= end) && (count2 < this.deltaFreq[value]))
              value--;
        }

        if (value == LAST)
        {
            if (this.bitstream.hasMoreToRead() == false)
                throw new BitStreamException("End of bitstream", BitStreamException.END_OF_STREAM);

            throw new BitStreamException("Unknown symbol: "+value, BitStreamException.INVALID_STREAM);
        }

        int symbolLow = this.baseFreq[value>>4] + this.deltaFreq[value];
        int symbolHigh = this.baseFreq[(value+1)>>4] + this.deltaFreq[value+1];

        // Decode symbol
        this.low += (symbolLow * this.range);
        this.range *= (symbolHigh - symbolLow);

        long checkRange = (this.low ^ (this.low + this.range)) & MASK;

        while ((checkRange < TOP) || (this.range < BOTTOM))
        {
            // Normalize
            if (checkRange >= TOP)
                this.range = (-this.low & MASK) & (BOTTOM-1);

            this.code <<= 8;
            this.code |= (this.bitstream.readBits(8) & 0xFF);
            this.range <<= 8;
            this.low <<= 8;
            checkRange = (this.low ^ (this.low + this.range)) & MASK;
        }

        // Update frequencies: computational bottleneck !!!
        this.updateFrequencies(value+1);
        return (byte) (value & 0xFF);
    }


    private void updateFrequencies(int value)
    {
        int[] freq = this.baseFreq;
        final int start = (value + 15) >> 4;
        final int len = freq.length;

        // Update absolute frequencies
        for (int j=start; j<len; j++)
            freq[j]++;

        freq = this.deltaFreq;

        // Update relative frequencies (in the 'right' segment only)
        for (int j=(start<<4)-1; j>=value; j--)
            freq[j]++;
    }


    @Override
    public void dispose()
    {
    }


    @Override
    public BitStream getBitStream()
    {
       return this.bitstream;
   }
}
