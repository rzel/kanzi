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

import kanzi.EntropyDecoder;
import kanzi.bitstream.BitStream;
import kanzi.bitstream.BitStreamException;


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
    private static final int LENGTH8 = (NB_SYMBOLS + 1) & 0xFFFFFFF8;
    private static final int LAST = NB_SYMBOLS - 1;
    private static final int HALF = LAST >> 1;
    private static final int QUARTER = HALF >> 1;

    private long code;
    private long low;
    private long range;
    private final int[] frequencies;
    private final BitStream bitstream;
    private boolean initialized;


    public RangeDecoder(BitStream bitstream)
    {
        this.range = (TOP << 8) - 1;
        this.bitstream = bitstream;
        this.frequencies = new int[NB_SYMBOLS+1];

        for (int i=0; i<this.frequencies.length; i++)
            this.frequencies[i] = i;
    }
    

    @Override
    public byte decodeByte()
    {
        if (this.initialized == false)
        {
            this.initialized = true;
            this.code = this.bitstream.readBits(56) & 0xFFFFFFFF;
        }

        int[] freq = this.frequencies;
        this.range /= freq[NB_SYMBOLS];
        int count = (int) ((this.code - this.low) / this.range);

        // Find first frequency less than 'count'
        int value = (freq[HALF] > count) ? HALF : LAST;
        int interval = QUARTER;

        // Try 1/4 or 3/4 index
        if (freq[value-interval] > count)
            value -= interval;

        interval >>= 1;

        // Try 1/8, 3/8, 5/8 or 7/8
        if (freq[value-interval] > count)
           value -= interval;

        // Finish with a (short) loop
        while (freq[value] > count)
            value--;

        if (value == LAST)
        {
            if (this.bitstream.hasMoreToRead() == false)
                throw new BitStreamException("End of bitstream", BitStreamException.END_OF_STREAM);

            throw new BitStreamException("Unknown symbol: "+value, BitStreamException.INVALID_STREAM);
        }

        int symbolLow = freq[value];
        int symbolHigh = freq[value+1];

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

        byte res = (byte) (value & 0xFF);
        value++;

        // Update frequencies
        int part1 = (value + 7) & 0xFFFFFFF8;

        // Unrolling the loop provides a significant boost on my system
        for (int j=value; j<part1; j++)
            freq[j]++;

        for (int j=part1; j<LENGTH8; j+=8)
        {
            freq[j]++;
            freq[j+1]++;
            freq[j+2]++;
            freq[j+3]++;
            freq[j+4]++;
            freq[j+5]++;
            freq[j+6]++;
            freq[j+7]++;
        }

        for (int j=LENGTH8; j<freq.length; j++)
            freq[j]++;

        return res;
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
