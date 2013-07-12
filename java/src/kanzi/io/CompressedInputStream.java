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
import kanzi.util.XXHash;


// Implementation of a java.io.InputStream that can dedode a stream
// compressed with CompressedOutputStream
public class CompressedInputStream extends InputStream
{
   private static final int BITSTREAM_TYPE           = 0x4B414E5A; // "KANZ"
   private static final int BITSTREAM_FORMAT_VERSION = 3;
   private static final int DEFAULT_BUFFER_SIZE      = 1024*1024;
   private static final int COPY_LENGTH_MASK         = 0x0F;
   private static final int SMALL_BLOCK_MASK         = 0x80;
   private static final int SKIP_FUNCTION_MASK       = 0x40;
   private static final int MAX_BLOCK_SIZE           = (16*1024*1024) - 4;
   private static final byte[] EMPTY_BYTE_ARRAY      = new byte[0];

   private int blockSize;
   private XXHash hasher;
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
      this.iba1 = new IndexedByteArray(EMPTY_BYTE_ARRAY, 0);
      this.iba2 = new IndexedByteArray(EMPTY_BYTE_ARRAY, 0);
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
         if (version != BITSTREAM_FORMAT_VERSION)
            throw new kanzi.io.IOException("Invalid bitstream, cannot read this version of the stream: " + version,
                    Error.ERR_STREAM_VERSION);

         // Read block checksum
         if (this.ibs.readBit() == 1)
            this.hasher = new XXHash(BITSTREAM_TYPE);

         // Read entropy codec
         this.entropyType = (char) this.ibs.readBits(7);

         // Read transform
         this.transformType = (char) this.ibs.readBits(7);

         // Read block size
         this.blockSize = (int) this.ibs.readBits(26);

         if ((this.blockSize < 0) || (this.blockSize > MAX_BLOCK_SIZE))
            throw new kanzi.io.IOException("Invalid bitstream, incorrect block size: " + this.blockSize,
                    Error.ERR_BLOCK_SIZE);

         if (this.ds != null)
         {
            this.ds.println("Checksum set to "+(this.hasher != null));
            this.ds.println("Block size set to "+this.blockSize+" bytes");

            try
            {
               String w1 = new FunctionFactory().getName((byte) this.transformType);

               if ("NONE".equals(w1))
                  w1 = "no";

               this.ds.println("Using " + w1 + " transform (stage 1)");
            }
            catch (IllegalArgumentException e)
            {
               throw new kanzi.io.IOException("Invalid bitstream, unknown transform type: "+
                       this.transformType, Error.ERR_INVALID_CODEC);
            }
            
           try
            {
               String w2 = new EntropyCodecFactory().getName((byte) this.entropyType);

               if ("NONE".equals(w2))
                  w2 = "no";

               this.ds.println("Using " + w2 + " entropy codec (stage 2)");
            }
            catch (IllegalArgumentException e)
            {
               throw new kanzi.io.IOException("Invalid bitstream, unknown entropy codec type: "+
                       this.entropyType , Error.ERR_INVALID_CODEC);
            }
         }
      }
      catch (IOException e)
      {
         throw e;
      }
      catch (Exception e)
      {
         throw new kanzi.io.IOException("Invalid bitstream, cannot read header", Error.ERR_READ_FILE);
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


    /**
     * Reads some number of bytes from the input stream and stores them into
     * the buffer array <code>b</code>. The number of bytes actually read is
     * returned as an integer.  This method blocks until input data is
     * available, end of file is detected, or an exception is thrown.
     *
     * <p> If the length of <code>b</code> is zero, then no bytes are read and
     * <code>0</code> is returned; otherwise, there is an attempt to read at
     * least one byte. If no byte is available because the stream is at the
     * end of the file, the value <code>-1</code> is returned; otherwise, at
     * least one byte is read and stored into <code>b</code>.
     *
     * <p> The first byte read is stored into element <code>b[0]</code>, the
     * next one into <code>b[1]</code>, and so on. The number of bytes read is,
     * at most, equal to the length of <code>b</code>. Let <i>k</i> be the
     * number of bytes actually read; these bytes will be stored in elements
     * <code>b[0]</code> through <code>b[</code><i>k</i><code>-1]</code>,
     * leaving elements <code>b[</code><i>k</i><code>]</code> through
     * <code>b[b.length-1]</code> unaffected.
     *
     * <p> The <code>read(b)</code> method for class <code>InputStream</code>
     * has the same effect as: <pre><code> read(b, 0, b.length) </code></pre>
     *
     * @param      b   the buffer into which the data is read.
     * @return     the total number of bytes read into the buffer, or
     *             <code>-1</code> if there is no more data because the end of
     *             the stream has been reached.
     * @exception  IOException  If the first byte cannot be read for any reason
     * other than the end of the file, if the input stream has been closed, or
     * if some other I/O error occurs.
     * @exception  NullPointerException  if <code>b</code> is <code>null</code>.
     * @see        java.io.InputStream#read(byte[], int, int)
     */
   @Override
   public int read(byte[] array, int off, int len) throws IOException
   {
      int remaining = len;

      while (remaining > 0)
      {
         // Limit to number of available bytes in buffer
         final int lenChunk = (this.iba1.index + remaining < this.maxIdx) ? remaining : 
                 this.maxIdx - this.iba1.index;

         if (lenChunk > 0)
         {
            // Process a chunk of in-buffer data. No access to bitstream required
            System.arraycopy(this.iba1.array, this.iba1.index, array, off, lenChunk);
            this.iba1.index += lenChunk;
            off += lenChunk;
            remaining -= lenChunk;

            if (remaining == 0)
               break;
         }
         
         // Buffer empty, time to decode
         int c2 = this.read();

         if (c2 == -1)
            break;

         array[off++] = (byte) c2;
         remaining--;
      }

      return len - remaining;
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
         int errorCode = (e instanceof BitStreamException) ? ((BitStreamException) e).getErrorCode() :
                 Error.ERR_UNKNOWN;
         throw new kanzi.io.IOException(e.getMessage(), errorCode);
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
      this.iba1.array = EMPTY_BYTE_ARRAY;
      this.iba2.array = EMPTY_BYTE_ARRAY;
      this.maxIdx = 0;
      super.close();
   }


   // Return the number of bytes read so far
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

         if (this.transformType == 'N')
            this.iba2.array = data.array; // share buffers if no transform
         else if (this.iba2.array.length < this.blockSize)
             this.iba2.array = new byte[this.blockSize];

         final int savedIdx = data.index;

         // Block entropy decode
         if (ed.decode(this.iba2.array, 0, compressedLength) != compressedLength)
            return -1;

         if (((mode & SMALL_BLOCK_MASK) != 0) || ((mode & SKIP_FUNCTION_MASK) != 0))
         {
            if (this.iba2.array != data.array)
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

         // Print info if debug stream is not null
         if (this.ds != null)
         {
            this.ds.print("Block "+this.blockId+": "+
                   ((bs.read()-read)/8) + " => " +
                    compressedLength + " => " + decoded);

            if ((this.hasher != null) && ((mode & SMALL_BLOCK_MASK) == 0))
               this.ds.print("  [" + Integer.toHexString(checksum1) + "]");

            this.ds.println();
         }

         // Verify checksum (unless small block)
         if ((this.hasher != null) && ((mode & SMALL_BLOCK_MASK) == 0))
         {
            final int checksum2 = this.hasher.hash(data.array, savedIdx, decoded);

            if (checksum2 != checksum1)
               throw new IllegalStateException("Corrupted bitstream: expected checksum " +
                       Integer.toHexString(checksum1) + ", found " + Integer.toHexString(checksum2));
         }

         return decoded;
      }
      finally
      {
         // Reset buffer in case another block uses a different transform
         if (this.transformType == 'N')
            this.iba2.array = EMPTY_BYTE_ARRAY; 

         if (ed != null)
            ed.dispose();
      }
   }

}
