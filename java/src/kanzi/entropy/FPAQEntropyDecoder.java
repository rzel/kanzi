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
package kanzi.entropy;

import kanzi.BitStreamException;
import kanzi.EntropyDecoder;
import kanzi.InputBitStream;


// Based on fpaq1 by Matt Mahoney - Stationary order 0 entropy decoder
// Note the decoding workflow:
// dec = new FPAQEntropyDecoder(...)
// dec.initialize()
// while (dec.decodeBit() == 0)
//   dec.decodeByte()
// dec.dispose()
// OR
// dec = new FPAQEntropyDecoder(...)
// while (dec.decode(array, 0, len) == len) { ... }
// dec.dispose()
public class FPAQEntropyDecoder extends BinaryEntropyDecoder implements EntropyDecoder
{
   public FPAQEntropyDecoder(InputBitStream ibs, Predictor p)
   {
      super(ibs, p);
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
        // Stop if decodeBit() != 0
        while ((this.decodeBit() == 0) && (i < end))
           array[i++] = this.decodeByte();
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

      int res = 1;

      // Custom logic to decode a byte
      while (res < 256)
      {
        res <<= 1;
        res += this.decodeBit();
      }

      return (byte) (res-256);
   }

}
