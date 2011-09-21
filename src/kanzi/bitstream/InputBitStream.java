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
import java.io.IOException;
import java.io.InputStream;


/*package*/ final class InputBitStream
{
    private final InputStream is;
    private final byte[] buffer;
    private int position;
    private int bitIndex;
    private long read;
    private byte current;
    private boolean closed;
    private int maxPosition;


    InputBitStream(InputStream is, int bufferSize)
    {
        this.is = is;
        this.buffer = new byte[bufferSize];
        this.maxPosition = -1;
    }


    // Returns 1 or 0
    /*package*/ synchronized int readBit() throws BitStreamException
    {
        if (this.closed == true)
            throw new BitStreamException("Stream closed", BitStreamException.STREAM_CLOSED);

        if ((this.position > this.maxPosition) ||
            ((this.bitIndex == 0) && (this.position == this.maxPosition)))
            this.readFromInputStream(0, this.buffer.length);

        // --- START readBit 'Macro'
        if (this.bitIndex == 0)
        {
            // Read next byte from backing byte buffer
            this.current = this.buffer[this.position++];
        }

        // Extract the bit from this.current
        this.bitIndex = (this.bitIndex + 7) & 7;
        this.read++;
        return (this.current >> this.bitIndex) & 1;
        // --- END readBit 'Macro'
    }


    private synchronized int readFromInputStream(int offset, int length) throws BitStreamException
    {
        try
        {
            this.position = 0;
            int size = this.is.read(this.buffer, offset, length);

            if (size < 0)
            {
               throw new BitStreamException("Nore more data to read in the bitstream",
                       BitStreamException.END_OF_STREAM);
            }

            this.maxPosition = offset + size;
            return size;
        }
        catch (IOException e)
        {
            throw new BitStreamException(e.getMessage(), BitStreamException.INPUT_OUTPUT);
        }
    }


    // Limited to 64 bits at a time even if 'length' is greater than 64
    /*package*/ synchronized long readBits(int length) throws BitStreamException
    {
        if (this.closed == true)
            throw new BitStreamException("Stream closed", BitStreamException.STREAM_CLOSED);

        int remaining = length;
        long res = 0;
        int inBufferBytes = this.maxPosition - this.position;
        inBufferBytes &= ~(inBufferBytes >> 31); // positive or null
        int inBufferBits = (inBufferBytes << 3) + this.bitIndex;

        if (inBufferBits < length)
        {           
           for (int i=0; i<inBufferBytes; i++)
              this.buffer[i] = this.buffer[this.position+i];

           this.readFromInputStream(inBufferBytes, this.buffer.length-inBufferBytes);
        }

        try
        {
            // Process the first bits one by one to pad 'current'
            while ((remaining > 0) && (this.bitIndex != 0))
            {
                // --- START readBit 'Macro'
                if (this.bitIndex == 0)
                {
                    // Read next byte from backing byte buffer
                    this.current = this.buffer[this.position++];
                }

                // Extract the bit from this.current
                this.bitIndex = (this.bitIndex + 7) & 7;
                this.read++;
                long bit = (this.current >> this.bitIndex) & 1;
                // --- END readBit 'Macro'

                remaining--;
                res |= (bit << remaining);
            }

            while (remaining >= 8)
            {
                if (this.position >= this.maxPosition)
                    throw new BitStreamException("No more data to read", BitStreamException.END_OF_STREAM);

                long value = (this.buffer[this.position] & 0xFF);
                this.position++;
                remaining -= 8;
                this.read += 8;
                res |= (value << remaining);
            }

            // Process the last bits one by one to update 'current'
            while (remaining > 0)
            {
                // --- START readBit 'Macro'
                if (this.bitIndex == 0)
                {
                    // Read next byte from backing byte buffer
                    this.current = this.buffer[this.position++];
                }

                // Extract the bit from this.current
                this.bitIndex = (this.bitIndex + 7) & 7;
                this.read++;
                long bit = (this.current >> this.bitIndex) & 1;
                // --- END readBit 'Macro'

                remaining--;
                res |= (bit << remaining);
            }

        }
        catch (ArrayIndexOutOfBoundsException e)
        {
           throw new BitStreamException("No more data to read", BitStreamException.END_OF_STREAM);
        }

        return res;
    }


    public void flush()
    {
    }


    public synchronized void close() throws IOException
    {
        if (this.closed == true)
            return;

        this.flush();
        this.closed = true;
        this.is.close();
    }


    // Return number of bits read so far
    public synchronized long read()
    {
        return this.read;
    }


    public synchronized boolean hasMoreToRead()
    {
        if (this.closed == true)
            return false;

        if (this.position > this.maxPosition)
        {
            try
            {
                this.readFromInputStream(0, this.buffer.length);
            }
            catch (BitStreamException e)
            {
                return false;
            }

            return ((this.position < this.maxPosition) ||
                    ((this.position == this.maxPosition) && (this.bitIndex > 0)));
        }

        return true;
    }

}