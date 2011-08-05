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
import kanzi.bitstream.BitStreamException;



public class HuffmanEncoder extends AbstractEncoder
{
    private final BitStream bitstream;
    private final HuffmanTree tree;
    private final boolean canonical;


    public HuffmanEncoder(BitStream bitstream, boolean canonical, int[] frequencies) throws BitStreamException
    {
        if (bitstream == null)
            throw new NullPointerException("Invalid null bitstream parameter");

        if (frequencies == null)
            throw new NullPointerException("Invalid null frequencies parameter");

        this.bitstream = bitstream;
        this.canonical = canonical;
        this.tree = new HuffmanTree(frequencies, canonical);
        this.init(frequencies);
    }


    private void init(int[] frequencies) throws BitStreamException
    {
        // Write encoder type and max length (32 bits max)
        int bit = (this.canonical == true) ? 1 : 0;
        this.bitstream.writeBit(bit);
        int maxSize = 0;

        if (this.canonical == false)
        {
            int maxFreq = 0;

            for (int i=0; i<frequencies.length; i++)
            {
                if (maxFreq < frequencies[i])
                    maxFreq = frequencies[i];
            }

            maxSize = 32 - Integer.numberOfLeadingZeros(maxFreq);
            this.bitstream.writeBits(maxSize, 5);

            // Transmit frequencies
            for (int i=0; i<frequencies.length; i++)
                this.bitstream.writeBits(frequencies[i], maxSize);
        }
        else
        {
            for (int i=0; i<frequencies.length; i++)
            {
                int sz = this.tree.getSize(i);

                if (maxSize < sz)
                    maxSize = sz;
            }

            maxSize = 32 - Integer.numberOfLeadingZeros(maxSize);
            this.bitstream.writeBits(maxSize, 5);

            // Transmit code lengths only, frequencies and code do not matter !
            for (int i=0; i<frequencies.length; i++)
                this.bitstream.writeBits(this.tree.getSize(i), maxSize);
        }
    }


    @Override
    public boolean encodeByte(byte val)
    {
        this.bitstream.writeBits(this.tree.getCode(val & 0xFF), this.tree.getSize(val & 0xFF));
        return true;
    }


    @Override
    public void dispose()
    {
       this.bitstream.close();
    }


    @Override
    public BitStream getBitStream()
    {
       return this.bitstream;
    }
}
