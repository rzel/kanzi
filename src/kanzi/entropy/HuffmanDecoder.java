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
    private final int[] buffer;
    private boolean canonical;
    private HuffmanTree tree;


    public HuffmanDecoder(BitStream bitstream) throws BitStreamException
    {
       this(bitstream, true);
    }
    
 
    public HuffmanDecoder(BitStream bitstream, boolean canonical) throws BitStreamException
    {
        if (bitstream == null)
            throw new NullPointerException("Invalid null bitstream parameter");
      
        this.bitstream = bitstream;
        this.buffer = new int[256];
        this.canonical = canonical;
        int maxSize = 8;

        // Default frequencies
        for (int i=0; i<256; i++)
           this.buffer[i] = 1;
         
        this.tree = (this.canonical == true) ? new HuffmanTree(this.buffer, maxSize) : 
                new HuffmanTree(this.buffer, false);
    }
       
    
    public boolean readFrequencies() throws BitStreamException
    {
        final int[] buf = this.buffer;

        if (this.canonical == false)
        {
            final int dataSize = (int) this.bitstream.readBits(5);
            
            // Read frequencies
            for (int i=0; i<buf.length; i++)
                buf[i] = (int) this.bitstream.readBits(dataSize);
            
            // Create Huffman tree        
            this.tree = new HuffmanTree(buf, false);
        }
        else
        {
           int maxSize = 0;
           buf[0] = (int) this.bitstream.readBits(5);
           ExpGolombDecoder egdec = new ExpGolombDecoder(this.bitstream, true);
           
           // Read lengths
           for (int i=1; i<buf.length; i++)
           {
               buf[i] = buf[i-1] + egdec.decodeByte();
               
               if (maxSize < buf[i])
                  maxSize = buf[i];
           }
           
           // Create Huffman tree        
           this.tree = new HuffmanTree(buf, maxSize);
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
