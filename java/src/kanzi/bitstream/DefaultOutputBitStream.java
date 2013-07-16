/*
Copyright 2011-2013 Frederic Langlet
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
import kanzi.OutputBitStream;


public final class DefaultOutputBitStream implements OutputBitStream
{
   private final OutputStream os;
   private final byte[] buffer;
   private boolean closed;
   private int position;  // index of current byte in buffer
   private int bitIndex;  // index of current bit to write
   private long written;


   public DefaultOutputBitStream(OutputStream os, int bufferSize)
   {
      this(os, new byte[bufferSize]);
   }

   
   public DefaultOutputBitStream(OutputStream os, byte[] buffer)
   {
      if (os == null)
         throw new NullPointerException("Invalid null output stream parameter");
   
      if (buffer == null)
         throw new NullPointerException("Invalid null buffer parameter");

      if (buffer.length < 64)
         throw new IllegalArgumentException("Invalid buffer size (must be at least 64)");
      
      this.os = os;
      this.buffer = buffer;
      this.bitIndex = 7;
   }


    
   // Processes the least significant bit of the input integer
   @Override
   public synchronized boolean writeBit(int bit)
   {
      try
      {
         this.buffer[this.position] |= ((bit & 1) << this.bitIndex);

         if (this.bitIndex == 0)
         {
            this.bitIndex = 7;

            if (++this.position >= this.buffer.length)
               this.flush();
         }
         else
            this.bitIndex--;
      }
      catch (ArrayIndexOutOfBoundsException e)
      {
         throw new BitStreamException("Stream closed", BitStreamException.STREAM_CLOSED);
      }

      return true;
   }


   // 'length' must be max 64
   @Override
   public synchronized int writeBits(long value, int length)
   {
      if (length == 0)
         return 0;

      if (length > 64)
         throw new IllegalArgumentException("Invalid length: "+length+" (must be in [1..64])");

      try
      {
         int remaining = length;

         // Pad the current position in buffer
         if (this.bitIndex != 7)
         {
            int idx = this.bitIndex + 1; 
            final int sz = (remaining <= idx) ? remaining : idx;
            remaining -= sz;
            idx -= sz;
            final long bits = (value >>> remaining) & ((1 << sz) - 1);
            this.buffer[this.position] |= (bits << idx);
            this.bitIndex = (idx + 7) & 7;
            
            if (this.bitIndex == 7)
            {
               if (++this.position >= this.buffer.length) 
                  this.flush();
            }
         } 

         while (remaining >= 8)
         {
            // Fast track, progress byte by byte
            remaining -= 8;
            this.buffer[this.position] = (byte) (value >>> remaining);

            if (++this.position >= this.buffer.length)
               this.flush();
         }

         // Process remaining bits
         if (remaining > 0)
         {
            this.bitIndex -= remaining;
            this.buffer[this.position] = (byte) (value << (8 - remaining));
         }

         return length;
      }
      catch (ArrayIndexOutOfBoundsException e)
      {
         throw new BitStreamException("Stream closed", BitStreamException.STREAM_CLOSED);
      }
   }


   @Override
   public synchronized void flush() throws BitStreamException
   {
      if (this.isClosed() == true)
         throw new BitStreamException("Stream closed", BitStreamException.STREAM_CLOSED);

      try
      {
         if (this.position > 0)
         {            
            // The buffer contains an incomplete byte at 'position'
            this.os.write(this.buffer, 0, this.position);
            this.written += (this.position << 3);
            this.buffer[0] = (this.bitIndex != 7) ? this.buffer[this.position] : 0;
            final int end = (this.position < this.buffer.length) ? this.position 
                    : this.buffer.length-1;
            
            for (int i=1; i<=end; i++) // do not reset buffer[0]
               this.buffer[i] = 0;

            this.position = 0;
         }

         this.os.flush();
      }
      catch (IOException e)
      {
         throw new BitStreamException(e.getMessage(), BitStreamException.INPUT_OUTPUT);
      }
   }


   @Override
   public synchronized void close()
   {
      if (this.isClosed() == true)
         return;

      final int savedBitIndex = this.bitIndex;
      final int savedPosition = this.position;
      
      if (this.bitIndex != 7)
      {
         // Ready to write the incomplete last byte
         this.position++;
         this.bitIndex = 7;
      }

      try
      {
         this.flush();
      }
      catch (BitStreamException e)
      {
	 // Revert fields to allow subsequent attempts in case of transient failure
         this.position = savedPosition;
         this.bitIndex = savedBitIndex;
         throw e;
      }

      try
      {
         this.os.close();
      }
      catch (IOException e)
      {
         throw new BitStreamException(e, BitStreamException.INPUT_OUTPUT);
      }

      this.closed = true;

      // Force an exception on any subsequent write attempt
      this.position = this.buffer.length;
      this.bitIndex = 7;
   }


   @Override
   public synchronized long written()
   {
      // Number of bits flushed + bytes written in memory + bits written in memory
      return (this.isClosed() == true) ? this.written :
              this.written + (this.position << 3) + (7 - this.bitIndex);
   }


   public synchronized boolean isClosed()
   {
      return this.closed;
   }
}