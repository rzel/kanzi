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

package kanzi.entropy;


import kanzi.BitStreamException;
import kanzi.InputBitStream;


// This class is a generic implementation of a boolean entropy decoder
public class BinaryEntropyDecoder extends AbstractDecoder
{
   private final Predictor predictor;
   private long low;
   private long high;
   private long current;
   private final InputBitStream bitstream;

   
   public BinaryEntropyDecoder(InputBitStream bitstream, Predictor predictor)
   {
      if (bitstream == null)
         throw new NullPointerException("Invalid null bistream parameter");

      if (predictor == null)
         throw new NullPointerException("Invalid null predictor parameter");

      // Defer stream reading. We are creating the object, we should not do any I/O
      this.low = 0L;
      this.high = 0xFFFFFFFFL;
      this.bitstream = bitstream;
      this.predictor = predictor;
      this.current = -1;
   }


   @Override
   public int decode(byte[] array, int blkptr, int len)
   {
     if ((array == null) || (blkptr + len > array.length) || (blkptr < 0) || (len < 0))
        return -1;

     final int end = blkptr + len;
     int i = blkptr;

     if (this.isInitialized() == false)
        this.initialize();

     try
     {
        while (i < end)
           array[i++] = this.decodeByte_();
     }
     catch (BitStreamException e)
     {
        // Fallback
     }

     return i - blkptr;
   }
   
   
   @Override
   public byte decodeByte()
   {
      // Deferred initialization: the bistream may not be ready at build time
      // Initialize 'current' with bytes read from the bitstream
      if (this.isInitialized() == false)
         this.initialize();
   
      return this.decodeByte_();
   }
   
   
   protected byte decodeByte_()
   {
      int res = 0;

      for (int i=7; i>=0; i--)
         res |= (this.decodeBit() << i);

      return (byte) res;
   }

   
   public boolean isInitialized()
   {
      return (this.current != -1);
   }

   
   public void initialize()
   {
      if (this.current == -1)
         this.current = this.bitstream.readBits(32);
   }
   

   public int decodeBit()
   {
      // Compute prediction
      final int prediction = this.predictor.get();
      
      // Calculate interval split
      final long xmid = this.low + ((this.high - this.low) >> 12) * prediction;
      int bit;

      if (this.current <= xmid)
      {
         bit = 1;
         this.high = xmid;
      }
      else
      {
         bit = 0;
         this.low = xmid + 1;
      }

       // Update predictor
      this.predictor.update(bit);

      // Read from bitstream
      while (((this.low ^ this.high) & 0xFF000000L) == 0)
         this.read();
     
      return bit;
   }

   
   protected void read()
   {
      this.low = (this.low << 8) & 0xFFFFFFFFL;
      this.high = ((this.high << 8) | 255) & 0xFFFFFFFFL;      
      this.current = ((this.current << 8) | this.bitstream.readBits(8)) & 0xFFFFFFFFL;
   }
   
   
   @Override
   public InputBitStream getBitStream()
   {
      return this.bitstream;
   }
}
