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
import java.io.OutputStream;
import java.io.PrintStream;
import kanzi.BitStreamException;
import kanzi.ByteFunction;
import kanzi.EntropyEncoder;
import kanzi.IndexedByteArray;
import kanzi.OutputBitStream;
import kanzi.bitstream.DefaultOutputBitStream;
import kanzi.entropy.EntropyCodecFactory;
import kanzi.function.FunctionFactory;
import kanzi.util.XXHash;



// Implementation of a java.io.OutputStream that encodes a stream
// using a 2 step process:
// - step 1: a ByteFunction is used to reduce the size of the input data (bytes input & output)
// - step 2: an EntropyEncoder is used to entropy code the results of step 1 (bytes input, bits output)
public class CompressedOutputStream extends OutputStream
{
   private static final int DEFAULT_BLOCK_SIZE       = 1024 * 1024; // Default block size
   private static final int BITSTREAM_TYPE           = 0x4B414E5A; // "KANZ"
   private static final int BITSTREAM_FORMAT_VERSION = 3;
   private static final int COPY_LENGTH_MASK         = 0x0F;
   private static final int SMALL_BLOCK_MASK         = 0x80;
   private static final int SKIP_FUNCTION_MASK       = 0x40;
   private static final int MIN_BLOCK_SIZE           = 1024;
   private static final int MAX_BLOCK_SIZE           = (16*1024*1024) - 4;
   private static final int SMALL_BLOCK_SIZE         = 15;
   private static final byte[] EMPTY_BYTE_ARRAY      = new byte[0];

   private final int blockSize;
   private final XXHash hasher;
   private final IndexedByteArray iba1;
   private final IndexedByteArray iba2;
   private final char entropyType;
   private final char transformType;
   private final OutputBitStream  obs;
   private final PrintStream ds;
   private boolean initialized;
   private boolean closed;
   private int blockId;


   public CompressedOutputStream(String entropyCodec, String functionType, OutputStream os)
   {
      this(entropyCodec, functionType, os, DEFAULT_BLOCK_SIZE, false, null);
   }


   // debug print stream is optional (may be null)
   public CompressedOutputStream(String entropyCodec, String functionType,
               OutputStream os, int blockSize, boolean checksum, PrintStream debug)
   {
      if (entropyCodec == null)
         throw new NullPointerException("Invalid null entropy encoder type parameter");

      if (functionType == null)
         throw new NullPointerException("Invalid null transform type parameter");

      if (os == null)
         throw new NullPointerException("Invalid null output stream parameter");

      if (blockSize > MAX_BLOCK_SIZE)
           throw new IllegalArgumentException("The block size must be at most "+MAX_BLOCK_SIZE);

      if (blockSize < MIN_BLOCK_SIZE)
         throw new IllegalArgumentException("The block size must be at least "+MIN_BLOCK_SIZE);

      this.obs = new DefaultOutputBitStream(os, blockSize);

      // Check entropy type validity (throws if not valid)
      char type = entropyCodec.toUpperCase().charAt(0);
      String checkedEntropyType = new EntropyCodecFactory().getName((byte) type);

      if (entropyCodec.equalsIgnoreCase(checkedEntropyType) == false)
         throw new IllegalArgumentException("Unsupported entropy type: " + entropyCodec);

      this.entropyType = type;

      // Check transform type validity (throws if not valid)
      type = functionType.toUpperCase().charAt(0);
      String checkedFunctionType = new FunctionFactory().getName((byte) type);

      if (functionType.equalsIgnoreCase(checkedFunctionType) == false)
         throw new IllegalArgumentException("Unsupported function type: " + functionType);

      this.transformType = type;
      this.blockSize = blockSize;
      this.hasher = (checksum == true) ? new XXHash(BITSTREAM_TYPE) : null;
      this.iba1 = new IndexedByteArray(new byte[blockSize], 0);
      this.iba2 = new IndexedByteArray(EMPTY_BYTE_ARRAY, 0);
      this.ds = debug;
   }


   protected void writeHeader() throws IOException
   {
      if (this.initialized == true)
         return;

      if (this.obs.writeBits(BITSTREAM_TYPE, 32) != 32)
         throw new kanzi.io.IOException("Cannot write bitstream type in header", Error.ERR_WRITE_FILE);

      if (this.obs.writeBits(BITSTREAM_FORMAT_VERSION, 7) != 7)
         throw new kanzi.io.IOException("Cannot write bitstream version in header", Error.ERR_WRITE_FILE);

      if (this.obs.writeBit((this.hasher != null) ? 1 : 0) == false)
         throw new kanzi.io.IOException("Cannot write checksum in header", Error.ERR_WRITE_FILE);

      if (this.obs.writeBits(this.entropyType & 0x7F, 7) != 7)
         throw new kanzi.io.IOException("Cannot write entropy type in header", Error.ERR_WRITE_FILE);

      if (this.obs.writeBits(this.transformType & 0x7F, 7) != 7)
         throw new kanzi.io.IOException("Cannot write transform type in header", Error.ERR_WRITE_FILE);

      if (this.obs.writeBits(this.blockSize, 26) != 26)
         throw new kanzi.io.IOException("Cannot write block size in header", Error.ERR_WRITE_FILE);
   }


    /**
     * Writes <code>len</code> bytes from the specified byte array
     * starting at offset <code>off</code> to this output stream.
     * The general contract for <code>write(b, off, len)</code> is that
     * some of the bytes in the array <code>b</code> are written to the
     * output stream in order; element <code>b[off]</code> is the first
     * byte written and <code>b[off+len-1]</code> is the last byte written
     * by this operation.
     * <p>
     * The <code>write</code> method of <code>OutputStream</code> calls
     * the write method of one argument on each of the bytes to be
     * written out. Subclasses are encouraged to override this method and
     * provide a more efficient implementation.
     * <p>
     * If <code>b</code> is <code>null</code>, a
     * <code>NullPointerException</code> is thrown.
     * <p>
     * If <code>off</code> is negative, or <code>len</code> is negative, or
     * <code>off+len</code> is greater than the length of the array
     * <code>b</code>, then an <tt>IndexOutOfBoundsException</tt> is thrown.
     *
     * @param      b     the data.
     * @param      off   the start offset in the data.
     * @param      len   the number of bytes to write.
     * @exception  IOException  if an I/O error occurs. In particular,
     *             an <code>IOException</code> is thrown if the output
     *             stream is closed.
     */
    @Override
    public void write(byte[] array, int off, int len) throws IOException 
    {
      int remaining = len;

      while (remaining > 0)
      {
         // Limit to number of available bytes in buffer
         final int lenChunk = (this.iba1.index + remaining < this.iba1.array.length) ? remaining : 
                 this.iba1.array.length - this.iba1.index;

         if (lenChunk > 0)
         {
            // Process a chunk of in-buffer data. No access to bitstream required
            System.arraycopy(array, off, this.iba1.array, this.iba1.index, lenChunk);
            this.iba1.index += lenChunk;
            off += lenChunk;
            remaining -= lenChunk;

            if (remaining == 0)
               break;
         }
         
         // Buffer full, time to encode
         this.write(array[off]);
         off++;
         remaining--;
      }
   }


    
   /**
    * Writes the specified byte to this output stream. The general
    * contract for <code>write</code> is that one byte is written
    * to the output stream. The byte to be written is the eight
    * low-order bits of the argument <code>b</code>. The 24
    * high-order bits of <code>b</code> are ignored.
    * <p>
    * Subclasses of <code>OutputStream</code> must provide an
    * implementation for this method.
    *
    * @param      b   the <code>byte</code>..
    */
   @Override
   public void write(int b) throws IOException
   {
      // If the buffer is full, time to encode
      if (this.iba1.index >= this.iba1.array.length)
         this.processBlock();

      this.iba1.array[this.iba1.index++] = (byte) b;
   }


   /**
    * Flushes this output stream and forces any buffered output bytes
    * to be written out. The general contract of <code>flush</code> is
    * that calling it is an indication that, if any bytes previously
    * written have been buffered by the implementation of the output
    * stream, such bytes should immediately be written to their
    * intended destination.
    * <p>
    * If the intended destination of this stream is an abstraction provided by
    * the underlying operating system, for example a file, then flushing the
    * stream guarantees only that bytes previously written to the stream are
    * passed to the operating system for writing; it does not guarantee that
    * they are actually written to a physical device such as a disk drive.
    * <p>
    * The <code>flush</code> method of <code>OutputStream</code> does nothing.
    *
    */
   @Override
   public void flush()
   {
      // Let the bitstream of the entropy encoder flush itself when needed
   }


   /**
    * Closes this output stream and releases any system resources
    * associated with this stream. The general contract of <code>close</code>
    * is that it closes the output stream. A closed stream cannot perform
    * output operations and cannot be reopened.
    * <p>
    *
    * @exception  IOException  if an I/O error occurs.
    */
   @Override
   public synchronized void close() throws IOException
   {
      if (this.closed == true)
         return;

      this.closed = true;

      if (this.iba1.index > 0)
         this.processBlock();

      // End block of size 0
      this.obs.writeBits(SMALL_BLOCK_MASK, 8);
      this.obs.close();
      
      // Release resources
      this.iba1.array = EMPTY_BYTE_ARRAY;
      this.iba2.array = EMPTY_BYTE_ARRAY;
      super.close();
   }


   private void processBlock() throws IOException
   {
      if (this.iba1.index == 0)
         return;

      if (this.initialized == false)
      {
         this.writeHeader();
         this.initialized = true;
      }

      try
      {
         final int sz = this.iba1.index;
         this.iba1.index = 0;
         final int encoded = this.encode(this.iba1, sz);

         if (encoded < 0)
            throw new kanzi.io.IOException("Error in transform forward()", Error.ERR_PROCESS_BLOCK);

         this.iba1.index = 0;
         this.blockId++;
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


   // Return the number of bytes written so far
   public long getWritten()
   {
      return (this.obs.written() + 7) >> 3;
   }


   // Return -1 if error, otherwise the number of encoded bytes
   private int encode(IndexedByteArray data, int blockLength)
   {
      EntropyEncoder ee = null;

      try
      {
         if (this.transformType == 'N')
            this.iba2.array = data.array; // share buffers if no transform
         else if (this.iba2.array.length < blockLength*5/4) // ad-hoc size
             this.iba2.array = new byte[blockLength*5/4];

         ByteFunction transform = new FunctionFactory().newFunction(blockLength,
                 (byte) this.transformType);

         this.iba2.index = 0;
         byte mode = 0;
         int dataSize = 0;
         int compressedLength = blockLength;
         int checksum = 0;

         if (blockLength <= SMALL_BLOCK_SIZE)
         {
            // Just copy
            if (data.array != this.iba2.array)
               System.arraycopy(data.array, data.index, this.iba2.array, 0, blockLength);

            data.index += blockLength;
            this.iba2.index = blockLength;
            mode = (byte) (SMALL_BLOCK_MASK | (blockLength & COPY_LENGTH_MASK));
         }
         else
         {
            // Compute block checksum
            if (this.hasher != null)
               checksum = this.hasher.hash(data.array, data.index, blockLength);

            final int savedIdx = data.index;

            // Forward transform
            if ((transform.forward(data, this.iba2) == false) || (this.iba2.index >= blockLength))
            {
               data.index = savedIdx;

               // Transform failed or did not compress, skip and copy block
               if (data.array != this.iba2.array)
                  System.arraycopy(data.array, data.index, this.iba2.array, 0, blockLength);

               data.index += blockLength;
               this.iba2.index = blockLength;
               mode |= SKIP_FUNCTION_MASK;
            }

            compressedLength = this.iba2.index;
            dataSize++;

            for (int i=0xFF; i<compressedLength; i<<=8)
               dataSize++;

            // Record size of 'block size' in bytes
            mode |= (dataSize & 0x03);
         }

         // Each block is encoded separately
         // Rebuild the entropy encoder to reset block statistics
         ee = new EntropyCodecFactory().newEncoder(this.obs, (byte) this.entropyType);

         // Write block 'header' (mode + compressed length)
         final OutputBitStream bs = ee.getBitStream();
         final long written = bs.written();
         bs.writeBits(mode, 8);

         if (dataSize > 0)
            bs.writeBits(compressedLength, 8*dataSize);

         // Write checksum (unless small block)
         if ((this.hasher != null) && ((mode & SMALL_BLOCK_MASK) == 0))
            bs.writeBits(checksum, 32);

         // Entropy encode block
         final int encoded = ee.encode(this.iba2.array, 0, compressedLength);

         // Dispose before displaying statistics. Dispose may write to the bitstream
         ee.dispose();
         
         // Force ee to null to avoid double dispose (in the finally section)
         ee = null; 
         
         // Print info if debug stream is not null
         if (this.ds != null)
         {
            this.ds.print("Block "+this.blockId+": "+
                   blockLength + " => " + encoded + " => " +
                  ((bs.written()-written)/8L)+" ("+
                  ((bs.written()-written)*100L/(long)(blockLength*8))+"%)");

            if ((this.hasher != null) && ((mode & SMALL_BLOCK_MASK) == 0))
               this.ds.print("  [" + Integer.toHexString(checksum) + "]");

            this.ds.println();
         }

         return encoded;
      }
      catch (Exception e)
      {
         return -1;
      }
      finally
      {
         // Reset buffer in case another block uses a different transform
         if (this.transformType == 'N')
            this.iba2.array = EMPTY_BYTE_ARRAY;

         if (ee != null)
           ee.dispose();
      }
   }


}
