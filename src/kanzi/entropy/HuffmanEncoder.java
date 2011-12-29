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



public class HuffmanEncoder extends AbstractEncoder
{
    private final BitStream bitstream;
    private final boolean canonical;
    private HuffmanTree tree;
    private int[] buffer;


    public HuffmanEncoder(BitStream bitstream, boolean canonical) throws BitStreamException
    {
        if (bitstream == null)
            throw new NullPointerException("Invalid null bitstream parameter");

        this.bitstream = bitstream;
        this.canonical = canonical;
        this.buffer = new int[256];

        // Write encoder type and max length (32 bits max)
        final int bit = (this.canonical == true) ? 1 : 0;
        this.bitstream.writeBit(bit);
    }


    public boolean updateFrequencies(int[] frequencies) throws BitStreamException
    {
        if (frequencies == null)
           return false;
        
        this.tree = new HuffmanTree(frequencies, this.canonical);
 
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

            // Transmit code lengths only, frequencies and code do not matter 
            for (int i=0; i<frequencies.length; i++)
                this.bitstream.writeBits(this.tree.getSize(i), maxSize);
        }
        
        return true;
    }

    
    // Do a dynamic computation of the frequencies of the input data
    @Override
    public int encode(byte[] array, int blkptr, int len)
    {
       for (int i=0; i<256; i++)
          this.buffer[i] = 0;

       final int end = blkptr + len;

       for (int i=blkptr; i<end; i++)
          this.buffer[array[i] & 0xFF]++;

       this.updateFrequencies(this.buffer);    
       return super.encode(array, blkptr, len);
    }

    
    // Frequencies of the data block must have been previously set
    @Override
    public boolean encodeByte(byte val)
    {
        if (this.tree == null)
           return false;
        
        final long bits = this.tree.getCode(val & 0xFF);
        final int size = this.tree.getSize(val & 0xFF);
        return (this.bitstream.writeBits(bits, size) == size);
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
