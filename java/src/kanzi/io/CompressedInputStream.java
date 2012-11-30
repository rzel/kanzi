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

package kanzi.io;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import kanzi.BitStreamException;
import kanzi.EntropyDecoder;
import kanzi.IndexedByteArray;
import kanzi.InputBitStream;
import kanzi.bitstream.DefaultInputBitStream;
import kanzi.entropy.BinaryEntropyDecoder;
import kanzi.entropy.FPAQEntropyDecoder;
import kanzi.entropy.FPAQPredictor;
import kanzi.entropy.HuffmanDecoder;
import kanzi.entropy.NullEntropyDecoder;
import kanzi.entropy.PAQPredictor;
import kanzi.entropy.RangeDecoder;
import kanzi.function.BlockCodec;


public class CompressedInputStream extends InputStream
{
   private static final int BITSTREAM_TYPE = 0x4B4E5A; // "KNZ"
   private static final int BITSTREAM_FORMAT_VERSION = 0;
   private static final int DEFAULT_BUFFER_SIZE = 32768;

   private int blockSize;
   private final BlockCodec bc;
   private final IndexedByteArray iba;
   private char entropyType;
   private final InputBitStream  ibs;
   private final PrintStream ds;
   private boolean initialized;
   private boolean closed;
   private int blockId;
   private int maxIdx;


   public CompressedInputStream(InputStream is)
   {
      this(is, null);
   }


   // debug print stream is optional (may be null)
   public CompressedInputStream(InputStream is, PrintStream debug)
   {
      if (is == null)
         throw new NullPointerException("Invalid null input stream parameter");

      this.ibs = new DefaultInputBitStream(is, DEFAULT_BUFFER_SIZE);
      this.bc = new BlockCodec(0);
      this.iba = new IndexedByteArray(new byte[0], 0);
      this.ds = debug;
   }


   public void readHeader() throws IOException
   {
      if (this.initialized == true)
         return;

      try
      {
         // Read stream type
         final int type = (int) this.ibs.readBits(24);

         // Sanity check
         if (type != BITSTREAM_TYPE)
            throw new kanzi.io.IOException("Invalid stream type: expected "
                    + Integer.toHexString(BITSTREAM_TYPE) + ", got "
                    + Integer.toHexString(type), Error.ERR_INVALID_FILE);

         // Read stream version
         final int version = (int) this.ibs.readBits(8);

         // Sanity check
         if (version < BITSTREAM_FORMAT_VERSION)
            throw new kanzi.io.IOException("Cannot read this version of the stream: " + version,
                    Error.ERR_STREAM_VERSION);

         // Read entropy codec
         this.entropyType = (char) this.ibs.readBits(8);

         // Read block size
         this.blockSize = (int) this.ibs.readBits(24);

         if ((this.blockSize < 0) || (this.blockSize > BlockCodec.MAX_BLOCK_SIZE))
            throw new kanzi.io.IOException("Invalid block size read from file: " + this.blockSize,
                    Error.ERR_BLOCK_SIZE);

         if (this.ds != null)
         {
            this.ds.println("Block size set to "+this.blockSize);

            if (this.entropyType == 'H')
              this.ds.println("Using HUFFMAN entropy codec");
            else if (this.entropyType == 'R')
              this.ds.println("Using RANGE entropy codec");
            else if (this.entropyType == 'P')
              this.ds.println("Using PAQ entropy codec");
            else if (this.entropyType == 'F')
              this.ds.println("Using FPAQ entropy codec");
            else if (this.entropyType == 'N')
              this.ds.println("Using no entropy codec");
         }
      }
      catch (Exception e)
      {
         throw new kanzi.io.IOException("Cannot read header", Error.ERR_READ_FILE);
      }
   }


   /**
    * Reads the next byte of data from the input stream. The value byte is
    * returned as an <code>int</code> in the range <code>0</code> to
    * <code>255</code>. If no byte is available because the end of the stream
    * has been reached, the value <code>-1</code> is returned. This method
    * blocks until input data is available, the end of the stream is detected,
    * or an exception is thrown.
    *
    * @return     the next byte of data, or <code>-1</code> if the end of the
    *             stream is reached.
    * @exception  IOException  if an I/O error occurs.
    */
   public int read() throws IOException
   {
      try
      {
         if (this.iba.index >= this.maxIdx)
         {
            this.maxIdx = this.decode();

            if (this.maxIdx == 0) // Reached end of stream
               return -1;
         }

         return this.iba.array[this.iba.index++] & 0xFF;
      }
      catch (BitStreamException e)
      {
         if (e.getErrorCode() == BitStreamException.END_OF_STREAM)
            return -1;

         throw new kanzi.io.IOException(e.getMessage(), Error.ERR_READ_FILE);
      }
      catch (Exception e)
      {
         throw new kanzi.io.IOException(e.getMessage(), Error.ERR_UNKNOWN);
      }
   }


   private synchronized int decode() throws IOException
   {
      if (this.initialized == false)
      {
         this.readHeader();
         this.initialized = true;
      }

      EntropyDecoder ed;

      try
      {
         switch (this.entropyType)
         {
            // Each block is decoded separately
            // Rebuild the entropy decoder to reset block statistics
            case 'H':
               ed = new HuffmanDecoder(this.ibs);
               break;
            case 'R':
               ed = new RangeDecoder(this.ibs);
               break;
            case 'P':
               ed = new BinaryEntropyDecoder(this.ibs, new PAQPredictor());
               break;
            case 'F':
               ed = new FPAQEntropyDecoder(this.ibs, new FPAQPredictor());
               break;
            case 'N':
               ed = new NullEntropyDecoder(this.ibs);
               break;
            default:
               throw new kanzi.io.IOException("Unsupported entropy codec type: " + this.entropyType, Error.ERR_INVALID_CODEC);
         }
      }
      catch (Exception e)
      {
         throw new kanzi.io.IOException("Failed to create entropy decoder", Error.ERR_CREATE_CODEC);
      }

      try
      {
         if (this.iba.array.length < this.blockSize)
            this.iba.array = new byte[this.blockSize];

         this.iba.index = 0;
         final int decoded = this.bc.decode(this.iba, ed);

         if (decoded < 0)
            throw new kanzi.io.IOException("Error in block codec inverse()", Error.ERR_PROCESS_BLOCK);

         if (this.ds != null)
         {
            // Display block size after entropy decoding + block transform
            this.ds.println("Block " + this.blockId + ": " + decoded + " byte(s)");
         }

         this.iba.index = 0;
         ed.dispose();
         this.blockId++;
         return decoded;
      }
      catch (Exception e)
      {
         throw new kanzi.io.IOException(e.getMessage(), Error.ERR_UNKNOWN);
      }
   }


   /**
    * Closes this input stream and releases any system resources associated
    * with the stream.
    *
    * @exception  IOException  if an I/O error occurs.
    */
   public synchronized void close() throws IOException
   {
      if (this.closed == true)
         return;

      this.closed = true;
      this.ibs.close();
      this.iba.array = new byte[0];
      this.maxIdx = 0;
      super.close();
   }


   public long getRead()
   {
      return (this.ibs.read() + 7) >> 3;
   }
}
