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
import kanzi.ByteFunction;
import kanzi.EntropyDecoder;
import kanzi.IndexedByteArray;
import kanzi.InputBitStream;
import kanzi.bitstream.DefaultInputBitStream;
import kanzi.entropy.EntropyCodecFactory;
import kanzi.function.FunctionFactory;
import kanzi.util.MurMurHash3;


// Implementation of a java.io.InputStream that can dedode a stream
// compressed with CompressedOutputStream
public class CompressedInputStream extends InputStream
{
   private static final int BITSTREAM_TYPE           = 0x4B414E5A; // "KANZ"
   private static final int BITSTREAM_FORMAT_VERSION = 1;
   private static final int DEFAULT_BUFFER_SIZE      = 32768;
   private static final int COPY_LENGTH_MASK         = 0x0F;
   private static final int SMALL_BLOCK_MASK         = 0x80;
   private static final int SKIP_FUNCTION_MASK       = 0x40;
   private static final int MAX_BLOCK_SIZE           = (16*1024*1024) - 4;

   private int blockSize;
   private MurMurHash3 hasher;
   private final IndexedByteArray iba1;
   private final IndexedByteArray iba2;
   private char entropyType;
   private char transformType;
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
      this.iba1 = new IndexedByteArray(new byte[0], 0);
      this.iba2 = new IndexedByteArray(new byte[0], 0);
      this.ds = debug;
   }


   protected void readHeader() throws IOException
   {
      if (this.initialized == true)
         return;

      try
      {
         // Read stream type
         final int type = (int) this.ibs.readBits(32);

         // Sanity check
         if (type != BITSTREAM_TYPE)
            throw new kanzi.io.IOException("Invalid stream type: expected "
                    + Integer.toHexString(BITSTREAM_TYPE) + ", got "
                    + Integer.toHexString(type), Error.ERR_INVALID_FILE);

         // Read stream version
         final int version = (int) this.ibs.readBits(7);

         // Sanity check
         if (version < BITSTREAM_FORMAT_VERSION)
            throw new kanzi.io.IOException("Cannot read this version of the stream: " + version,
                    Error.ERR_STREAM_VERSION);

         // Read block checksum
         if (this.ibs.readBit() == 1)
            this.hasher = new MurMurHash3(BITSTREAM_TYPE);

         // Read entropy codec
         this.entropyType = (char) this.ibs.readBits(7);

         // Read transform
         this.transformType = (char) this.ibs.readBits(7);

         // Read block size
         this.blockSize = (int) this.ibs.readBits(26);

         if ((this.blockSize < 0) || (this.blockSize > MAX_BLOCK_SIZE))
            throw new kanzi.io.IOException("Invalid block size read from file: " + this.blockSize,
                    Error.ERR_BLOCK_SIZE);

         if (this.ds != null)
         {
            this.ds.println("Checksum set to "+(this.hasher != null));
            this.ds.println("Block size set to "+this.blockSize);
            String w1 = new FunctionFactory().getName((byte) this.transformType);

            if ("NONE".equals(w1))
               w1 = "no";

            this.ds.println("Using " + w1 + " transform (stage 1)");
            String w2 = new EntropyCodecFactory().getName((byte) this.entropyType);

            if ("NONE".equals(w2))
               w2 = "no";

            this.ds.println("Using " + w2 + " entropy codec (stage 2)");
         }
      }
      catch (IOException e)
      {
         throw e;
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
   @Override
   public int read() throws IOException
   {
      try
      {
         if (this.iba1.index >= this.maxIdx)
         {
            this.maxIdx = this.processBlock();

            if (this.maxIdx == 0) // Reached end of stream
               return -1;
         }

         return this.iba1.array[this.iba1.index++] & 0xFF;
      }
      catch (BitStreamException e)
      {
         if (e.getErrorCode() == BitStreamException.END_OF_STREAM)
            return -1;

         throw new kanzi.io.IOException(e.getMessage(), Error.ERR_READ_FILE);
      }
      catch (kanzi.io.IOException e)
      {
         throw e;
      }
      catch (Exception e)
      {
         throw new kanzi.io.IOException(e.getMessage(), Error.ERR_UNKNOWN);
      }
   }


   // Need to override this method because the default implementation
   // in Java gobbles the IOException (uh?)
   @Override
   public int read(byte[] array, int off, int len) throws IOException
   {
      if (len == 0)
         return 0;

      int c = read();

      if (c == -1)
         return -1;

      array[off] = (byte) c;
      int i = 1;

      for (; i<len ; i++)
      {
         c = read();

         if (c == -1)
            break;

         array[off+i] = (byte) c;
      }

      return i;
   }


   private int processBlock() throws IOException
   {
      if (this.initialized == false)
      {
         this.readHeader();
         this.initialized = true;
      }

      try
      {
         if (this.iba1.array.length < this.blockSize)
            this.iba1.array = new byte[this.blockSize];

         this.iba1.index = 0;
         final int decoded = this.decode(this.iba1);

         if (decoded < 0)
            throw new kanzi.io.IOException("Error in transform inverse()", Error.ERR_PROCESS_BLOCK);

         this.iba1.index = 0;
         this.blockId++;
         return decoded;
      }
      catch (kanzi.io.IOException e)
      {
         throw e;
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
   @Override
   public synchronized void close() throws IOException
   {
      if (this.closed == true)
         return;

      this.closed = true;
      this.ibs.close();

      // Release resources
      this.iba1.array = new byte[0];
      this.iba2.array = new byte[0];
      this.maxIdx = 0;
      super.close();
   }


   public long getRead()
   {
      return (this.ibs.read() + 7) >> 3;
   }


   // Return -1 if error, otherwise the number of bytes read from the encoder
   private int decode(IndexedByteArray data)
   {
      // Each block is decoded separately
      // Rebuild the entropy decoder to reset block statistics
      EntropyDecoder ed = new EntropyCodecFactory().newDecoder(this.ibs,
              (byte) this.entropyType);

      try
      {
         // Extract header directly from bitstream
         InputBitStream bs = ed.getBitStream();
         final long read = bs.read();
         byte mode = (byte) (bs.readBits(8) & 0xFF);
         int compressedLength;
         int checksum1 = 0;

         if ((mode & SMALL_BLOCK_MASK) != 0)
         {
            compressedLength = mode & COPY_LENGTH_MASK;
         }
         else
         {
            final int dataSize = mode & 0x03;
            final int length = dataSize << 3;
            final int mask = (1 << length) - 1;
            compressedLength = (int) (bs.readBits(length) & mask);
         }

         if (compressedLength == 0)
            return 0;

         if ((compressedLength < 0) || (compressedLength > MAX_BLOCK_SIZE))
            return -1;

         // Extract checksum from bit stream (if any)
         if ((this.hasher != null) && ((mode & SMALL_BLOCK_MASK) == 0))
            checksum1 = (int) bs.readBits(32);

         if (this.iba2.array.length < this.blockSize)
             this.iba2.array = new byte[this.blockSize];

         final int savedIdx = data.index;

         // Block entropy decode
         if (ed.decode(this.iba2.array, 0, compressedLength) != compressedLength)
            return -1;

         if (((mode & SMALL_BLOCK_MASK) != 0) || ((mode & SKIP_FUNCTION_MASK) != 0))
         {
            System.arraycopy(this.iba2.array, 0, data.array, savedIdx, compressedLength);
            this.iba2.index = compressedLength;
            data.index = savedIdx + compressedLength;
         }
         else
         {
            // Each block is decoded separately
            // Rebuild the entropy decoder to reset block statistics
            ByteFunction transform = new FunctionFactory().newFunction(compressedLength,
                    (byte) this.transformType);

            this.iba2.index = 0;

            // Inverse transform
            if (transform.inverse(this.iba2, data) == false)
               return -1;
         }

         final int decoded = data.index - savedIdx;

         if (this.ds != null)
         {
            this.ds.print("Block "+this.blockId+": "+
                   ((bs.read()-read)/8) + " => " +
                    compressedLength + " => " + decoded + " byte(s)");

            if ((this.hasher != null) && ((mode & SMALL_BLOCK_MASK) == 0))
               this.ds.print("  [" + Integer.toHexString(checksum1) + "]");

            this.ds.println();
         }

         // Verify checksum (unless small block)
         if ((this.hasher != null) && ((mode & SMALL_BLOCK_MASK) == 0))
         {
            final int checksum2 = this.hasher.hash(data.array, savedIdx, decoded);

            if (checksum2 != checksum1)
               throw new IllegalStateException("Invalid checksum: expected " +
                       Integer.toHexString(checksum1) + ", found " + Integer.toHexString(checksum2));
         }

         return decoded;
      }
      finally
      {
         if (ed != null)
            ed.dispose();
      }
   }

}
