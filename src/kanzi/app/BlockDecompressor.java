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

package kanzi.app;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import kanzi.BitStreamException;
import kanzi.EntropyDecoder;
import kanzi.IndexedByteArray;
import kanzi.InputBitStream;
import kanzi.bitstream.DefaultInputBitStream;
import kanzi.entropy.HuffmanDecoder;
import kanzi.entropy.NullEntropyDecoder;
import kanzi.entropy.PAQEntropyDecoder;
import kanzi.entropy.RangeDecoder;
import kanzi.function.BlockCodec;


public class BlockDecompressor implements Runnable, Callable<Long>
{
   private static final int BITSTREAM_TYPE = 0x4B4E5A; // "KNZ"
   private static final int BITSTREAM_FORMAT_VERSION = 0;

   private final boolean debug;
   private final boolean silent;
   private final String fileName;
   private final BlockCodec blockCodec;


   public BlockDecompressor(String[] args)
   {
      Map<String, Object> map = new HashMap<String, Object>();
      processCommandLine(args, map);
      this.debug = (Boolean) map.get("debug");
      this.silent = (Boolean) map.get("silent");
      this.fileName = (String) map.get("fileName");
      this.blockCodec = new BlockCodec();
   }


   public static void main(String[] args)
   {
      new BlockDecompressor(args).run();
   }


   @Override
   public void run()
   {
      this.call();
   }


   @Override
   public Long call()
   {      
      String outputName = this.fileName;

      if (this.fileName.endsWith(".knz") == false)
         printOut("Warning: the input file name does not end with the .KNZ extension", true);
      else
         outputName = this.fileName.substring(0, this.fileName.length()-4);

      outputName += ".tmp";

      printOut("Input file name set to '" + this.fileName + "'", this.debug);
      printOut("Output file name set to '" + outputName + "'", this.debug);
      printOut("Debug set to "+this.debug, this.debug);

      long delta = 0L;
      int decoded;
      long sum = 0;
      int step = 0;
      printOut("Decoding ...", !this.silent);

      OutputStream fos = null;
      EntropyDecoder entropyDecoder = null;
      InputBitStream ibs = null;

      try
      {
         // Read header
         File input = new File(this.fileName);
         File output = new File(outputName);
         fos = new FileOutputStream(output);
         ibs = new DefaultInputBitStream(new FileInputStream(input), 32768);
         final int type = (int) ibs.readBits(24);

         // Sanity check
         if (type != BITSTREAM_TYPE)
         {
            System.err.println("Invalid stream type: expected "+
                    Integer.toHexString(BITSTREAM_TYPE)+", got "+
                    Integer.toHexString(type));
            return -1L;
         }

         final int version = (int) ibs.readBits(8);

         // Sanity check
         if (version < BITSTREAM_FORMAT_VERSION)
         {
            System.err.println("Cannot read this version of the stream: "+version);
            return -1L;
         }

         final char entropyType = (char) ibs.readBits(8);
         final int blockSize = (int) ibs.readBits(24);
         long read = ibs.read();

         if ((blockSize < 0) || (blockSize > 16 * 1024 * 1024 - 7))
         {
            System.err.println("Invalid block size read from file: "+blockSize);
            return -1L;
         }

         byte[] buffer = new byte[blockSize+7];
         IndexedByteArray iba = new IndexedByteArray(buffer, 0);

         if (this.debug == true)
         {
            String strEntropy = "None";

            if (entropyType == 'H')
               strEntropy = "Huffman";
            else if (entropyType == 'R')
               strEntropy = "Range";
            else if (entropyType == 'P')
               strEntropy = "PAQ";

           printOut("Entropy codec: " + strEntropy, !this.silent);
           printOut("Block size: " + blockSize, !this.silent);
         }

         // Decode next block
         do
         {
            if (entropyType == 'H')
               entropyDecoder = new HuffmanDecoder(ibs);
            else if (entropyType == 'R')
               entropyDecoder = new RangeDecoder(ibs);
            else if (entropyType == 'P')
               entropyDecoder = new PAQEntropyDecoder(ibs);
            else
               entropyDecoder = new NullEntropyDecoder(ibs);

            iba.index = 0;
            long before = System.nanoTime();
            decoded = this.blockCodec.decode(iba, entropyDecoder);
            long after = System.nanoTime();
            delta += (after - before);

            if (decoded < 0)
            {
               System.err.println("Error in block codec inverse()");
               System.exit(1);
            }

            // Display block size before and after entropy decoding + block transform
            printOut("Block "+step+": "+((ibs.read()-read)>>3)+"=>"+decoded, this.debug);

            read = ibs.read();
            fos.write(iba.array, 0, decoded);
            sum += decoded;
            step++;
         }
         while (decoded != 0);
         
         delta /= 1000000; // convert to ms
         printOut("", !this.silent);
         printOut("Decoding took "+delta+" ms", !this.silent);
         printOut("Decoded:          "+sum, !this.silent);
         printOut("Troughput (KB/s): "+(((sum * 1000L) >> 10) / delta), !this.silent);
         printOut("", !this.silent);
         return ibs.read();
      }
      catch (Exception e)
      {
         System.err.println("An unexpected condition happened. Exiting ,,,");
         e.printStackTrace();
         return -1L;
      }
      finally
      {
         try
         {
           if (fos != null)
             fos.close();
         }
         catch (IOException e)
         {
            // ignore
         }
         
         try
         {
           if (ibs != null)
             ibs.close();
         }
         catch (BitStreamException e)
         {
            // ignore
         }
         
         if (entropyDecoder != null)
            entropyDecoder.dispose();
      }
   }


    private static void processCommandLine(String args[], Map<String, Object> map)
    {
        boolean debug = false;
        boolean silent = false;
        String fileName = null;
        boolean fileProvided = false;

        for (String arg : args)
        {
           arg = arg.trim();

           if (arg.equals("-help"))
           {
              printOut("-help             : display this message", true);
              printOut("-debug            : display the size of the completely decoded block", true);
              printOut("-silent           : silent mode: no output", true);
              printOut("-file=<filename>  : name of the input file to encode or decode", true);
              System.exit(0);
           }
           else if (arg.equals("-debug"))
           {
              debug = true;
           }
           else if (arg.equals("-silent"))
           {
              silent = true;
           }
           else if (arg.startsWith("-file="))
           {
              fileName = arg.substring(6).trim();
              fileProvided = true;
           }
           else
           {
              printOut("Warning: ignoring unknown option ["+ arg + "]", true);
           }
        }

        if (fileName == null)
        {
           System.err.println("Missing input file name, exiting ...");
           System.exit(1);
        }

        if ((silent == true) && (debug == true))
        {
           printOut("Warning: both 'silent' and 'debug' options were selected, ignoring 'debug'", true);
           debug = false;
        }

        map.put("debug", debug);
        map.put("silent", silent);
        map.put("fileName", fileName);
        map.put("fileProvided", fileProvided);
    }


    private static void printOut(String msg, boolean print)
    {
       if ((print == true) && (msg != null))
          System.out.println(msg);
    }}
