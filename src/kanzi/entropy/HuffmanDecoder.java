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



public class HuffmanDecoder extends AbstractDecoder
{
    private final BitStream bitstream;
    private boolean canonical;
    private HuffmanTree tree;


    public HuffmanDecoder(BitStream bitstream) throws BitStreamException
    {
        if (bitstream == null)
            throw new NullPointerException("Invalid null bitstream parameter");

        this.bitstream = bitstream;
        this.canonical = (this.bitstream.readBit() == 1) ? true : false;
    }
    
    
    public boolean readFrequencies() throws BitStreamException
    {
        int[] data = new int[256];
        int dataSize = (int) this.bitstream.readBits(5);

        if (this.canonical == false)
        {
            // Read frequencies
            for (int i=0; i<data.length; i++)
                data[i] = (int) this.bitstream.readBits(dataSize);
            
            // Create Huffman tree        
            this.tree = new HuffmanTree(data, this.canonical);
        }
        else
        {
           int maxSize = 0;
           
           // Read lengths
           for (int i=0; i<data.length; i++)
           {
               data[i] = (int) this.bitstream.readBits(dataSize);
               
               if (maxSize < data[i])
                  maxSize = data[i];
           }
           
           // Create Huffman tree        
           this.tree = new HuffmanTree(data, maxSize);
        }
        
        return true;
    }


    // Rebuild the Huffman tree for each block of data before decoding
    @Override
    public int decode(byte[] array, int blkptr, int len)
    {
       this.readFrequencies();
       return super.decode(array, blkptr, len);
    }
       
    
    // The data block header must have been read before
    @Override
    public final byte decodeByte()
    {
        return this.tree.getSymbol(this.bitstream);
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
