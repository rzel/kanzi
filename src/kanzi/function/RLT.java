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

package kanzi.function;

import kanzi.ByteFunction;
import kanzi.IndexedByteArray;

// Simple implementation of a Run Length Encoding
// Length is transmitted as 1 or 2 bytes (minus 1 bit for the mask that indicates
// whether a second byte is used). The run threshold is 2.
// EG input:  0x10 0x11 0x11 0x17 0x13 0x13 0x13 0x13 0x13 0x13 0x12 (160 times) 0x14
//   output: 0x10 0x11 0x11 0x00 0x17 0x13 0x13 0x05 0x12 0x12 0x80 0xA0 0x14

public class RLT implements ByteFunction
{
   private static final int TWO_BYTE_LENGTH_MASK = 0x80;
   private static final int MAX_RUN_VALUE = 0x8000;

   private final int size;


   public RLT()
   {
      this(0);
   }


   public RLT(int size)
   {
      if (size < 0)
         throw new IllegalArgumentException("Invalid size parameter (must be at least 0)");

      this.size = size;
   }


   public int size()
   {
      return this.size;
   }


   @Override
   public boolean forward(IndexedByteArray source, IndexedByteArray destination)
   {
      int srcIdx = source.index;
      int dstIdx = destination.index;
      byte[] src = source.array;
      byte[] dst = destination.array;
      int end = (this.size == 0) ? src.length : srcIdx + this.size;
      byte prev = 0;
      int run = 0;
      int threshold = dst.length - 4;

      // Initialize with a value different from the first data
      if (srcIdx < end)
         prev = (byte) (~src[srcIdx]);

      while ((srcIdx < end) && (dstIdx < dst.length))
      {
         byte val = src[srcIdx];

         // Encode up to 0x7F7F repetitions in the 'length' information
         if ((prev == val) && (run < MAX_RUN_VALUE))
         {
            run++;
            srcIdx++;
            continue;
         }

         if (run > 0)
         {
            run--;

            if (dstIdx < threshold)
            {
               dst[dstIdx++] = prev;

               if (run > 0x7F)
                  dst[dstIdx++] = (byte) (((run >> 8) & 0x7F) | TWO_BYTE_LENGTH_MASK);

               dst[dstIdx++] = (byte) (run & 0xFF);
               run = 0;
            }
            else
            {
               dst[dstIdx++] = prev;

               if (dstIdx == dst.length)
                  break;

               // Add MSB to indicate a 2 byte encoding of the length
               if (run > 0x7F)
               {
                  dst[dstIdx++] = (byte) (((run >> 8) & 0x7F) | TWO_BYTE_LENGTH_MASK);

                  if (dstIdx == dst.length)
                     break;
               }

               dst[dstIdx++] = (byte) (run & 0xFF);
               run = 0;

               if (dstIdx == dst.length)
                  break;
            }
         }

         srcIdx++;
         dst[dstIdx++] = val;
         prev = val;
      }

      // Fill up the destination array
      if ((run > 0) && (dstIdx < dst.length))
      {
         run--;
         dst[dstIdx++] = prev;

         // Add MSB to indicate a 2 byte encoding of the length
         if (run > 0x7F)
         {
            if (dstIdx < dst.length)
            {
               dst[dstIdx++] = (byte) (((run >> 8) & 0xFF) | TWO_BYTE_LENGTH_MASK);
               run &= 0xFF;
            }
         }

         if (dstIdx < dst.length)
         {
            dst[dstIdx++] = (byte) (run & 0xFF);
            run = 0;
         }
      }

      source.index = srcIdx;
      destination.index = dstIdx;
      return true;
   }


   @Override
   public boolean inverse(IndexedByteArray source, IndexedByteArray destination)
   {
      int srcIdx = source.index;
      int dstIdx = destination.index;
      byte[] src = source.array;
      byte[] dst = destination.array;
      int end = (this.size == 0) ? src.length : srcIdx + this.size;
      byte prev = 0;
      int run = 0;

      // Initialize with a value different from the first data
      if (srcIdx < end)
         prev = (byte) (~src[srcIdx]);

      while ((srcIdx < end) && (dstIdx < dst.length))
      {
         if (run > 0)
         {
            int iter = run < (dst.length - dstIdx) ? run : dst.length - dstIdx;

            for (int i=0; i<iter; i++)
               dst[dstIdx++] = prev;

            run -= iter;
            continue;
         }

         byte val = src[srcIdx++];

         if (prev == val)
         {
            if (srcIdx == end)
            {
               dst[dstIdx++] = val;
               return false;
            }

            int length = src[srcIdx++];

            // If the length is encoded in 2 bytes, process next byte
            if ((length & TWO_BYTE_LENGTH_MASK) != 0)
            {
               if (srcIdx == end)
                  return false;

               length = (length & 0x7F) << 8;
               length |= (src[srcIdx++] & 0xFF);
               run = (short) length;
            }
            else
            {
               run = (length & 0xFF);
            }
         }

         dst[dstIdx++] = val;
         prev = val;
      }

      if (run > 0)
      {
         int iter = run < (dst.length - dstIdx) ? run : dst.length - dstIdx;

         for (int i=0; i<iter; i++)
            dst[dstIdx++] = prev;

         run -= iter;
      }

      // Not enough space in destination array
      if (run != 0)
         return false;

      source.index = srcIdx;
      destination.index = dstIdx;
      return true;
   }
}