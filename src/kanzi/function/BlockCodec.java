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
import kanzi.EntropyDecoder;
import kanzi.EntropyEncoder;
import kanzi.IndexedByteArray;
import kanzi.transform.BWT;
import kanzi.transform.MTFT;


// Utility class to compress/decompress a data block
// Fast reversible block coder/decoder based on a pipeline of transformations:
// Forward: [Run Length ->] Burrows-Wheeler -> Move to Front -> Zero Length
// Inverse: Zero Length -> Move to Front -> Burrows-Wheeler [-> Run Length]
// The block size determine the balance between speed and compression ratio
// The max block size is around 250 KB and provides the best compression ratio.
// The default block size provides a good balance.

// Stream format: Header (5 bytes) Data (n bytes)
// Header: mode (4 bits) + block size (18 bits) + BWT primary index (18 bits)
//      or mode (1 bit) + block size (7 bits)
// * If mode & 0x80 != 0 then the block is no compressed, just copied.
//   and the block length is cotained in the 7 lower digits
//   Hence a 0 byte block (use to mark end of stream) is 0x80
// * Else, the first 4 Most Significant Bits are used to encode extra information.
//   The next 18 bits encode the block size
//   The next 18 bits encode the BWT primay digits
//
// EG: Mode=0x85 block copy, length = 5 bytes followed by block data
//     Mode=0x0? regular transform followed by compressed length, BWT index, block data
//     Mode & 0x20 != 0 no RLC
//     Mode & 0x40 != 0 no ZLC

public class BlockCodec implements ByteFunction
{
   public static final int COPY_LENGTH_MASK = 0x7F;
   public static final int COPY_BLOCK_MASK  = 0x80;
   public static final int NO_RLT_MASK      = 0x20;
   public static final int NO_ZLT_MASK      = 0x40;

   public static final int DEFAULT_BLOCK_SIZE = 65530;
   public static final int MAX_BLOCK_SIZE = 0x03FFFF; // 256 KB
   private static final int BLOCK_HEADER_SIZE = 5;

   private final IndexedByteArray buffer;
   private final MTFT mtft;
   private final BWT bwt;
   private int size;


   public BlockCodec()
   {
      this(DEFAULT_BLOCK_SIZE);
   }


   public BlockCodec(int blockSize)
   {
       if (blockSize < 0)
           throw new IllegalArgumentException("The block size must be at least 0");

       if (blockSize > MAX_BLOCK_SIZE)
           throw new IllegalArgumentException("The block size must be at most "+MAX_BLOCK_SIZE);

       this.bwt = new BWT();
       this.mtft = new MTFT();
       this.size = blockSize;
       this.buffer = new IndexedByteArray(new byte[0], 0);
   }


   public BlockCodec(byte[] buffer)
   {
       if (buffer == null)
           throw new NullPointerException("The buffer cannot be null");

       this.bwt = new BWT();
       this.mtft = new MTFT();
       this.size = buffer.length;
       this.buffer = new IndexedByteArray(buffer, 0);
   }


   public int size()
   {
       return this.size;
   }


   public boolean setSize(int size)
   {
       if ((size < 0) || (size > MAX_BLOCK_SIZE))
          return false;

       this.size = size;
       return true;
   }


   @Override
   public boolean forward(IndexedByteArray input, IndexedByteArray output)
   {
       if ((input == null) || (output == null) || (input == output))
           return false;

       int length = (this.size == 0) ? input.array.length - input.index : this.size;

       if ((length < 0) || (length > MAX_BLOCK_SIZE))
          return false;

       if (length + input.index > input.array.length)
           return false;

       if (length == 0)
       {
           if (output.array.length < output.index + 1)
              return false;

           output.array[output.index++] = (byte) COPY_BLOCK_MASK;
           return true;
       }

       final int headerStartIdx = output.index;

       if (length < 16)
       {
          // Since processing the block data will hardly overcome the data added
          // due to the header, use a short header and simply copy the block
          if (output.array.length < output.index + length + 1)
              return false;

          // Add 'mode' byte
          output.array[headerStartIdx] = (byte) (COPY_BLOCK_MASK | length);
          output.index++;

          // Copy block
          for (int i=0; i<length; i++)
              output.array[output.index++] = input.array[input.index++];

          return true;
       }

       byte mode = 0;
       int savedIdx = input.index;
       this.buffer.index = 0;

       if (this.buffer.array.length < length)
          this.buffer.array = new byte[length];
         
//       RLT rlt = new RLT(length);
//
//       // Apply Run Length Encoding
//       if (rlt.forward(input, this.buffer) == false)
//          return false;
//
//       // If the RLE did not compress (it can expand in some pathological cases)
//       // then do not perform it, revert
//       if ((input.index < savedIdx + length) || (this.buffer.index > length))
       {
          System.arraycopy(input.array, savedIdx, this.buffer.array, 0, length);
          this.buffer.index = length;
          mode |= NO_RLT_MASK;
       }

       final int blockSize = this.buffer.index;
       this.buffer.index = 0;

       // Apply Burrows-Wheeler Transform
       this.bwt.setSize(blockSize);
       this.bwt.forward(this.buffer.array, 0);
       int primaryIndex = this.bwt.getPrimaryIndex();

       // Apply Move-To-Front Transform
       this.mtft.setSize(blockSize);
       this.mtft.forward(this.buffer.array, 0);

       output.index += BLOCK_HEADER_SIZE;
       ZLT zlt = new ZLT(blockSize);

       // Apply Zero Length Encoding (changes the index of input & output)
       if (zlt.forward(this.buffer, output) == false)
          return false;

       if (output.index + BLOCK_HEADER_SIZE >= output.array.length)
          return false;

       // If the ZLE did not compress (it can expand in some pathological cases)
       // then do not perform it, revert
       if ((this.buffer.index < blockSize) || (output.index > blockSize))
       {
          System.arraycopy(this.buffer.array, 0, output.array, headerStartIdx + BLOCK_HEADER_SIZE, blockSize);
          output.index = headerStartIdx + BLOCK_HEADER_SIZE + blockSize;
          mode |= NO_ZLT_MASK;
       }

       final int compressedLength = output.index - BLOCK_HEADER_SIZE - headerStartIdx;
       
       // Write block header
       // Pack: mode (4 bits) + compressed length (18 bits) + primary index (18 bits)
       output.array[headerStartIdx]    = (byte) (mode | ((compressedLength >> 14) & 0x0F));
       output.array[headerStartIdx+1]  = (byte) ((compressedLength >> 6) & 0xFF);
       output.array[headerStartIdx+2]  = (byte) (((compressedLength << 2) & 0xFC) 
                                                 | ((primaryIndex >> 16) & 0x03));
       output.array[headerStartIdx+3]  = (byte) ((primaryIndex >> 8) & 0xFF);
       output.array[headerStartIdx+4]  = (byte) (primaryIndex & 0xFF);
       return true;
    }


   @Override
   public boolean inverse(IndexedByteArray input, IndexedByteArray output)
   {
      // Read 'mode' byte (8 bits if copy or 4 bits if compression)
      int mode = input.array[input.index++] & 0xFF;

      if ((mode & COPY_BLOCK_MASK) != 0)
      {
         // Extract block length
         int length = mode & COPY_LENGTH_MASK;

         if (output.array.length < output.index + length)
            return false;

         // Just copy block
         for (int i=0; i<length; i++)
            output.array[output.index++] = input.array[input.index++];

         return true;
      }        

      // Extract compressed length (18 bits)
      int val0 = mode & 0x0F;
      int val1 = input.array[input.index++] & 0xFF;
      int val2 = input.array[input.index]   & 0xFC;
      int compressedLength = (val0 << 14) | (val1 << 6) | (val2 >> 2);

      if (compressedLength == 0)
         return true;

      // Extract BWT primary index (18 bits)
      val0 = input.array[input.index++] & 0x03;
      val1 = input.array[input.index++] & 0xFF;
      val2 = input.array[input.index++] & 0xFF;
      int primaryIndex = (val0 << 16) | (val1 << 8) | val2;

      this.buffer.index = 0;

      if ((mode & NO_ZLT_MASK) == 0)
      {
         // Apply Zero Length Decoding (changes the index of input & output)
         ZLT zlt = new ZLT(compressedLength);

         // The size after decompression is not known, let us assume that the output
         // is big enough, otherwise return false after decompression
         // To be safe the size of the output should be set to 0xFFFF (max size allowed)
         if (this.buffer.array.length < output.array.length)
            this.buffer.array = new byte[output.array.length];

         if (zlt.inverse(input, this.buffer) == false)
            return false;

         // If buffer is too small, return error (max buffer size is 0xFFFF)
         if (input.index < compressedLength + BLOCK_HEADER_SIZE)
            return false;
      }
      else
      {
         if (this.buffer.array.length < compressedLength)
            this.buffer.array = new byte[compressedLength];

         System.arraycopy(input.array, input.index, this.buffer.array, 0, compressedLength);
         this.buffer.index = compressedLength;
      }

      int blockSize = this.buffer.index;
      this.buffer.index = 0;

      // Apply Move-To-Front Inverse Transform
      this.mtft.setSize(blockSize);
      this.mtft.inverse(this.buffer.array, 0);

      // Apply Burrows-Wheeler Inverse Transform
      this.bwt.setPrimaryIndex(primaryIndex);
      this.bwt.setSize(blockSize);
      this.bwt.inverse(this.buffer.array, 0);

      if ((mode & NO_RLT_MASK) == 0)
      {
         // Apply Run Length Decoding (changes the index of input & output)
         RLT rlt = new RLT(blockSize);

         if (rlt.inverse(this.buffer, output) == false)
            return false;

         // If output is too small, return error (max buffer size is 0xFFFF)
         if (this.buffer.index < blockSize)
            return false;
      }
      else
      {
         if (output.array.length < output.index + blockSize)
            return false;

         System.arraycopy(this.buffer.array, 0, output.array, output.index, blockSize);
         output.index += blockSize;
      }

      return true;
   }


   // Return -1 if error, otherwise the number of bytes written in the encoder
   public int encode(IndexedByteArray data, EntropyEncoder ee)
   {
      if (ee == null)
         return -1;

      IndexedByteArray output = new IndexedByteArray(data.array, 0);

      if (this.forward(data, output) == false)
         return -1;

      return ee.encode(data.array, 0, output.index);
   }


   // Return -1 if error, otherwise the number of bytes read from the encoder
   public int decode(IndexedByteArray data, EntropyDecoder ed)
   {
      if (ed == null)
         return -1;

      int mode = ed.decodeByte() & 0xFF;
      int savedIdx = data.index;
      data.array[data.index++] = (byte) mode;
      int length = 0;

      // Extract length
      if ((mode & COPY_BLOCK_MASK) != 0)
      {
         length = mode & COPY_LENGTH_MASK;
      }
      else
      {
         int val0 = ed.decodeByte();
         int val1 = ed.decodeByte();
         length = ((val0 & 0xFF) << 8) | (val1 & 0xFF);
         data.array[data.index++] = (byte) val0;
         data.array[data.index++] = (byte) val1;
      }
      
      if (length == 0)
         return 0;

      int toDecode = length + BLOCK_HEADER_SIZE - (data.index - savedIdx);

      int decoded = ed.decode(data.array, data.index, toDecode);

      if (decoded != toDecode)
         return -1;

      data.index = savedIdx;
      this.setSize(length);

      if (this.inverse(new IndexedByteArray(data.array, data.index), data) == false)
         return -1;

      return data.index - savedIdx;
   }

}