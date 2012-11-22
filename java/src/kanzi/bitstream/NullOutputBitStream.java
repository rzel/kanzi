/*
Copyright 2011, 2012 Frederic Langlet
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
import kanzi.OutputBitStream;


 // Does nothing on write but increment the number of bits written
 // Useful to test (EG. used with DebugOutputStream)
 public class NullOutputBitStream implements OutputBitStream
 {
   private boolean closed;
   private long size;

   @Override
   public boolean writeBit(int bit) throws BitStreamException
   {
      if (this.closed == true)
         throw new BitStreamException("Stream closed", BitStreamException.STREAM_CLOSED);

      this.size++;
      return true;
   }

   @Override
   public int writeBits(long bits, int length) throws BitStreamException
   {
      if (this.closed == true)
         throw new BitStreamException("Stream closed", BitStreamException.STREAM_CLOSED);

      this.size += length;
      return length;
   }

   @Override
   public void flush() throws BitStreamException
   {
      if (this.closed == true)
         throw new BitStreamException("Stream closed", BitStreamException.STREAM_CLOSED);
   }

   @Override
   public void close() throws BitStreamException
   {
      this.closed = true;
   }

   @Override
   public long written()
   {
      return this.size;
   }
 }
