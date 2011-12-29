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

package kanzi.bitstream;


import kanzi.BitStreamException;
import kanzi.BitStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;


public final class DefaultBitStream implements BitStream
{
    private final OutputBitStream obs;
    private final InputBitStream ibs;
    
    
    // Open bitstream for reading
    public DefaultBitStream(InputStream is, int bufferSize)
    {
        if (is == null)
            throw new NullPointerException("Invalid null input stream parameter");
        
        if (bufferSize < 64)
            throw new IllegalArgumentException("The buffer size must be at least 64");
        
        bufferSize &= 0xFFFFFFF0;
        this.ibs = new InputBitStream(is, bufferSize);
        this.obs = null;
    }
    
    
    // Open bitstream for writing
    public DefaultBitStream(OutputStream os, int bufferSize)
    {
        if (os == null)
            throw new NullPointerException("Invalid null output stream parameter");
        
        if (bufferSize < 64)
            throw new IllegalArgumentException("The buffer size must be at least 64");
        
        bufferSize &= 0xFFFFFFF0;
        this.ibs = null;
        this.obs = new OutputBitStream(os, bufferSize);
    }
    
    
    // Process the least significant bit of the input integer
    @Override
    public boolean writeBit(int bit)
    {
        return this.obs.writeBit(bit);
    }
    
    
    // 'length' must be <= 64
    @Override
    public int writeBits(long bits, int length)
    {
       if (length > 64)
            throw new IllegalArgumentException("Invalid length: "+length+" (must be in [1..64])");

       if (length == 0)
           return 0;
       
       return this.obs.writeBits(bits, length);
    }
    
    
    // Returns 1 or 0 
    @Override
    public int readBit()
    {
        return this.ibs.readBit();
    }
    
    
    // 'length' must be <= 64 
    @Override
    public long readBits(int length)
    {
        if ((length == 0) || (length > 64))
           throw new IllegalArgumentException("Invalid length: "+length+" (must be in [1..64])");

        return this.ibs.readBits(length);
    }
    
    
    @Override
    public synchronized void flush() throws BitStreamException
    {
        if (this.ibs != null)
            this.ibs.flush();

        if (this.obs != null)
            this.obs.flush();
    }
    
    
    @Override
    public synchronized void close() throws BitStreamException
    {
        try
        {
            if (this.ibs != null)
                this.ibs.close();
            
            if (this.obs != null)
                this.obs.close();
        }
        catch (IOException e)
        {
            throw new BitStreamException(e.getMessage(), BitStreamException.INPUT_OUTPUT);
        }
    }
    
    
    // Number of bits written
    @Override
    public long written()
    {
        if (this.obs == null)
            return 0;
        
        return this.obs.written();
    }
    
   
    // Number of bits read
    @Override
    public long read()
    {
        if (this.ibs == null)
            return 0;
        
        return this.ibs.read();
    }
    

    // Return false when the bitstream is closed or the End-Of-Stream has been reached
    @Override
    public boolean hasMoreToRead()
    {
         if (this.ibs == null)
            return false;
       
         return this.ibs.hasMoreToRead();
    }

}
