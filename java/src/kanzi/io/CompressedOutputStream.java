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
import java.io.OutputStream;
import java.io.PrintStream;
import kanzi.EntropyEncoder;
import kanzi.IndexedByteArray;
import kanzi.OutputBitStream;
import kanzi.bitstream.DefaultOutputBitStream;
import kanzi.entropy.BinaryEntropyEncoder;
import kanzi.entropy.FPAQEntropyEncoder;
import kanzi.entropy.FPAQPredictor;
import kanzi.entropy.HuffmanEncoder;
import kanzi.entropy.NullEntropyEncoder;
import kanzi.entropy.PAQPredictor;
import kanzi.entropy.RangeEncoder;
import kanzi.function.BlockCodec;



public class CompressedOutputStream extends OutputStream
{
   private static final int DEFAULT_BLOCK_SIZE = 1024 * 1024; // Default block size
   private static final int BITSTREAM_TYPE = 0x4B4E5A; // "KNZ"
   private static final int BITSTREAM_FORMAT_VERSION = 0;
   private static final int DEFAULT_BUFFER_SIZE = 32768;
   private static final String[] CODECS = { "NONE", "HUFFMAN", "RANGE", "FPAQ", "PAQ" };

   private final int blockSize;
   private final BlockCodec bc;
   private final IndexedByteArray iba;
   private final char entropyType;
   private final OutputBitStream  obs;
   private final PrintStream ds;
   private boolean initialized;
   private boolean closed;
   private int blockId;


   public CompressedOutputStream(String entropyCodec, OutputStream os)
   {
      this(entropyCodec, os, DEFAULT_BLOCK_SIZE, null);
   }


   // debug print stream is optional (may be null)
   public CompressedOutputStream(String entropyCodec, OutputStream os, int blockSize, PrintStream debug)
   {
      if (entropyCodec == null)
         throw new NullPointerException("Invalid null entropy encoder type parameter");

      if (os == null)
         throw new NullPointerException("Invalid null output stream parameter");

      if (blockSize < 256)
         throw new IllegalArgumentException("Invalid buffer size parameter (must be at least 256)");

      if (blockSize > BlockCodec.MAX_BLOCK_SIZE)
         throw new IllegalArgumentException("Invalid buffer size parameter (must be at most " + BlockCodec.MAX_BLOCK_SIZE + ")");

      String strVal = entropyCodec.toUpperCase();
      char type = 0;
      this.obs = new DefaultOutputBitStream(os, DEFAULT_BUFFER_SIZE);

      for (String str : CODECS)
      {
         if (str.equals(strVal) == false)
            continue;

         type = str.charAt(0);
         break;
      }

      if (type == 0)
         throw new IllegalArgumentException("Invalid entropy encoder type: '" + entropyCodec + "'");

      this.entropyType = type;
      this.blockSize = blockSize;
      this.bc = new BlockCodec(blockSize);
      this.iba = new IndexedByteArray(new byte[blockSize], 0);
      this.ds = debug;
   }


   public void writeHeader() throws IOException
   {
      if (this.initialized == true)
         return;

      if (this.obs.writeBits(BITSTREAM_TYPE, 24) != 24)
         throw new kanzi.io.IOException("Cannot write header", Error.ERR_WRITE_FILE);

      if (this.obs.writeBits(BITSTREAM_FORMAT_VERSION, 8) != 8)
         throw new kanzi.io.IOException("Cannot write header", Error.ERR_WRITE_FILE);

      if (this.obs.writeBits(this.entropyType, 8) != 8)
         throw new kanzi.io.IOException("Cannot write header", Error.ERR_WRITE_FILE);

      if (this.obs.writeBits(this.blockSize, 24) != 24)
         throw new kanzi.io.IOException("Cannot write header", Error.ERR_WRITE_FILE);
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
      this.iba.array[this.iba.index++] = (byte) (b & 0xFF);

      // If the buffer is full, time to encode
      if (this.iba.index >= this.iba.array.length)
         this.encode();
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

      if (this.iba.index > 0)
         this.encode();

      // End block of size 0
      // The 'real' value is BlockCodec.COPY_BLOCK_MASK | (0 & BlockCodec.COPY_LENGTH_MASK)
      this.obs.writeBits(0x80, 8);
      this.obs.close();
      this.iba.array = new byte[0];
      super.close();
   }


   private synchronized void encode() throws IOException
   {
      if (this.iba.index == 0)
         return;

      EntropyEncoder ee;
      long written = this.obs.written();

      try
      {
         // Each block is encoded separately
         // Rebuild the entropy encoder to reset block statistics
         switch (this.entropyType)
         {
            case 'H':
               ee = new HuffmanEncoder(this.obs);
               break;
            case 'R':
               ee = new RangeEncoder(this.obs);
               break;
            case 'P':
               ee = new BinaryEntropyEncoder(this.obs, new PAQPredictor());
               break;
            case 'F':
               ee = new FPAQEntropyEncoder(this.obs, new FPAQPredictor());
               break;
            case 'N':
               ee = new NullEntropyEncoder(this.obs);
               break;
            default :
               throw new kanzi.io.IOException("Invalid entropy encoder: " + this.entropyType,
                       Error.ERR_INVALID_CODEC);
         }
      }
      catch (Exception e)
      {
         throw new kanzi.io.IOException("Failed to create entropy encoder", Error.ERR_CREATE_CODEC);
      }

      if (this.initialized == false)
      {
         this.writeHeader();
         this.initialized = true;
      }

      try
      {
         if (this.iba.array.length < this.blockSize)
            this.iba.array = new byte[this.blockSize];

         this.bc.setSize(this.iba.index);
         this.iba.index = 0;

         if (this.bc.encode(this.iba, ee) < 0)
            throw new kanzi.io.IOException("Error in block codec forward()", Error.ERR_PROCESS_BLOCK);

         if (this.ds != null)
         {
            this.ds.println("Block: "+this.blockId+": "+
                  ((this.obs.written()-written)/8)+" bytes ("+
                  ((this.obs.written()-written)*100/(this.bc.size()*8))+"%)");
         }

         this.iba.index = 0;
         ee.dispose();
         this.blockId++;
      }
      catch (Exception e)
      {
         throw new kanzi.io.IOException(e.getMessage(), Error.ERR_UNKNOWN);
      }
   }


   public long getWritten()
   {
      return (this.obs.written() + 7) >> 3;
   }
}
