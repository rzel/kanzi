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
import java.io.OutputStream;


/*package*/ final class OutputBitStream
{
    private final OutputStream os;
    private final byte[] buffer;
    private int position;
    private int bitIndex;
    private byte current;
    private boolean closed;
    private long written;


    OutputBitStream(OutputStream os, int bufferSize)
    {
        this.os = os;
        this.buffer = new byte[bufferSize];
        this.bitIndex = 7;
    }


    // Processes the least significant bit of the input integer
    public synchronized boolean writeBit(int bit)
    {
        if (this.closed == true)
            throw new BitStreamException("Stream closed", BitStreamException.STREAM_CLOSED);

        // --- START writeBit 'Macro'
        this.current |= ((bit & 1) << this.bitIndex);

        if (this.bitIndex == 0)
        {
            this.buffer[this.position] = this.current;
            this.position++;
            this.current = 0;
        }

        this.bitIndex = (this.bitIndex + 7) & 7;
        this.written++;
        // --- END writeBit 'Macro'

        return true;
    }


    // 'length' must be max 64
    public synchronized int writeBits(long bits, int length)
    {
        if (this.closed == true)
            throw new BitStreamException("Stream closed", BitStreamException.STREAM_CLOSED);

        if (this.position >= this.buffer.length-8)
        {
            try
            {
                this.flush();
            }
            catch (IOException e)
            {
                throw new BitStreamException(e.getClass().getName(), BitStreamException.INPUT_OUTPUT);
            }
        }

        int remaining = length;

        // Process the first bits one by one to pad this.current
        while ((remaining > 0) && (this.bitIndex != 7))
        {
            remaining--;

            // --- START writeBit 'Macro'
            int bit = (int) ((bits >> remaining) & 1);
            this.current |= ((bit & 1) << this.bitIndex);

            if (this.bitIndex == 0)
            {
                this.buffer[this.position] = this.current;
                this.position++;
                this.current = 0;
            }

            this.bitIndex = (this.bitIndex + 7) & 7;
            this.written++;
            // --- END writeBit 'Macro'
        }

        // If this.current has been completely filled, use a fast path
        if (this.bitIndex == 7)
        {
            // Progress byte by byte
            while (remaining >= 8)
            {
                remaining -= 8;
                this.buffer[this.position] = (byte) ((bits >> remaining) & 0xFF);
                this.position++;
                this.written += 8;
            }

            // Process the last bits one by one to update this.current
            while (remaining > 0)
            {
                remaining--;

                // --- START writeBit 'Macro'
                int bit = (int) ((bits >> remaining) & 1);
                this.current |= ((bit & 1) << this.bitIndex);

                if (this.bitIndex == 0)
                {
                    this.buffer[this.position] = this.current;
                    this.position++;
                    this.current = 0;
                }

                this.bitIndex = (this.bitIndex + 7) & 7;
                this.written++;
                // --- END writeBit 'Macro'
            }
        }

        return length;
    }


    public synchronized void flush() throws IOException
    {
        if (this.position > 0)
           this.os.write(this.buffer, 0, this.position);

        this.os.flush();
        this.position = 0;
    }


    public synchronized void close() throws IOException
    {
        if (this.isClosed() == true)
            return;

        if ((this.written > 0) && (this.bitIndex != 7))
        {
            this.buffer[this.position] = this.current;
            this.position++;
            this.written -= (7 - this.bitIndex);
            this.written += 8;
            this.bitIndex = 7;
        }

        // Adjust stream size to multiple of 8 bytes (to allow decoding of long)
        while ((this.written & 63) != 0)
        {
            if (this.position >= this.buffer.length)
                this.flush();

            // Pad with 0xFF (do not choose 0x00)
            this.buffer[this.position] = (byte) 0xFF;
            this.position++;
            this.written += 8;
        }

        this.flush();
        this.current = 0;
        this.closed = true;
        this.os.close();
    }


    public synchronized long written()
    {
        return this.written;
    }


    protected synchronized boolean isClosed()
    {
        return this.closed;
    }
}

