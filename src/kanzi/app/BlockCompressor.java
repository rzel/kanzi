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

package kanzi.app;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import kanzi.EntropyEncoder;
import kanzi.IndexedByteArray;
import kanzi.OutputBitStream;
import kanzi.bitstream.DefaultOutputBitStream;
import kanzi.entropy.HuffmanEncoder;
import kanzi.entropy.NullEntropyEncoder;
import kanzi.entropy.PAQEntropyEncoder;
import kanzi.entropy.RangeEncoder;
import kanzi.function.BlockCodec;


public class BlockCompressor implements Runnable, Callable<Long>
{
   private static final int BITSTREAM_TYPE = 0x4B4E5A; // "KNZ"
   private static final int BITSTREAM_FORMAT_VERSION = 0;

   private boolean debug;
   private boolean overwrite;
   private char entropyType;
   private String fileName;
   private final BlockCodec blockCodec;
   private int blockSize;


   public BlockCompressor(String[] args)
   {
      Map<String, Object> map = new HashMap<String, Object>();
      processCommandLine(args, map);
      this.debug = (Boolean) map.get("debug");
      this.overwrite = (Boolean) map.get("overwrite");
      this.fileName = (String) map.get("fileName");
      this.blockSize = (Integer) map.get("blockSize");
      char entropy = (Character) map.get("entropyType");
      this.entropyType = (entropy == 0) ? 'H' : entropy;
      this.blockCodec = new BlockCodec();
   }


   public static void main(String[] args)
   {
      new BlockCompressor(args).run();
   }


   @Override
   public void run()
   {
      this.call();
   }


   @Override
   public Long call()
   {
      try
      {
         String outputName = this.fileName + ".knz";
         File output = new File(outputName);

         if (output.exists())
         {
            if (output.isDirectory())
            {
               System.out.println("The output file is a directory");
               return -1L;
            }

            if (this.overwrite == false)
            {
               System.out.println("The output file exists and the 'overwrite' command "
                       + "line option has not been provided");
               return -1L;
            }
         }

         if (this.debug == true)
         {
            System.out.println("Input file name set to '" + this.fileName + "'");
            System.out.println("Output file name set to '" + outputName + "'");
            System.out.println("Block size set to "+this.blockSize);
            System.out.println("Debug set to "+this.debug);
            System.out.println("Ouput file overwrite set to "+this.overwrite);

            if (this.entropyType == 'H')
              System.out.println("Using Huffman entropy codec");
            else if(this.entropyType == 'R')
              System.out.println("Using Range entropy codec");
            else if(this.entropyType == 'P')
              System.out.println("Using PAQ entropy codec");
            else
              System.out.println("Using no entropy codec");
         }

         FileOutputStream fos = new FileOutputStream(output);
         OutputBitStream obs = new DefaultOutputBitStream(fos, 32768);

         // Encode
         EntropyEncoder entropyCoder;
         File input = new File(this.fileName);
         FileInputStream fis = new FileInputStream(input);
         long delta = 0L;
         int len;
         int read = 0;
         int step = 0;
         System.out.println("Encoding ...");

         // Write header
         obs.writeBits(BITSTREAM_TYPE, 24);
         obs.writeBits(BITSTREAM_FORMAT_VERSION, 8);
         obs.writeBits(this.entropyType, 8);
         obs.writeBits(this.blockSize, 24);
         long written = obs.written();

         // If the compression ratio is greater than one for this block,
         // the compression will fail (unless up to 7 bytes are reserved
         // in the block for header data)
         byte[] buffer = new byte[this.blockSize+7];
         IndexedByteArray iba = new IndexedByteArray(buffer, 0);

         while ((len = fis.read(iba.array, 0, this.blockSize)) > 0)
         {
            if (this.entropyType == 'H')
               entropyCoder = new HuffmanEncoder(obs);
            else if (this.entropyType == 'R')
               entropyCoder = new RangeEncoder(obs);
            else if (this.entropyType == 'P')
               entropyCoder = new PAQEntropyEncoder(obs);
            else
               entropyCoder = new NullEntropyEncoder(obs);

            read += len;
            long before = System.nanoTime();
            iba.index = 0;
            this.blockCodec.setSize(len);
            int encoded = this.blockCodec.encode(iba, entropyCoder);
            long after = System.nanoTime();
            delta += (after - before);

            if (encoded < 0)
            {
               System.err.println("Error in block codec forward()");
               System.exit(1);
            }

            if (this.debug == true)
            {
               // Display the block size before and after block transform + entropy coding
               System.out.println("Block "+step+": "+len+"=>"+
                      ((obs.written()-written)>>3)+" ("+
                      ((obs.written()-written)*100/(len<<3))+"%)");
            }

            written = obs.written();
            entropyCoder.dispose();
            step++;
          }

          // End block of size 0
          // The 'real' value is BlockCodec.COPY_BLOCK_MASK | (0 & BlockCodec.COPY_LENGTH_MASK)
          obs.writeBits(0x80, 8);
          obs.close();

          delta /= 1000000; // convert to ms
          System.out.println();
          System.out.println("File size:        "+read);
          System.out.println("Encoding took "+delta+" ms");
          System.out.println("Ratio:            "+(obs.written() >> 3) / (float) read);
          System.out.println("Encoded:          "+(obs.written() >> 3));
          System.out.println("Troughput (KB/s): "+(((read * 1000L) >> 10) / delta));
          System.out.println();
          return obs.written();
       }
       catch (Exception e)
       {
          e.printStackTrace();
          return -1L;
       }
    }


    private static void processCommandLine(String args[], Map<String, Object> map)
    {
        // Set default values
        int blockSize = 100000;
        char entropyType = 0;
        boolean debug = false;
        boolean overwrite = false;
        String fileName = null;

        for (String arg : args)
        {
           arg = arg.trim();

           if (arg.equals("-help"))
           {
               System.out.println("-help             : display this message");
               System.out.println("-debug            : display the size of the encoded block pre-entropy coding");
               System.out.println("-overwrite        : overwrite the output file if it already exists");
               System.out.println("-file=<filename>  : name of the input file to encode or decode");
               System.out.println("-block=<size>     : size of the block (max 16 MB / default 100 KB)");
               System.out.println("-entropy=         : Entropy codec to use [None|Huffman|Range|PAQ]");
               System.exit(0);
           }
           else if (arg.equals("-debug"))
           {
               debug = true;
           }
           else if (arg.equals("-overwrite"))
           {
               overwrite = true;
           }
           else if (arg.startsWith("-file="))
           {
              fileName = arg.substring(6).trim();
           }
           else if (arg.startsWith("-entropy="))
           {
              String strVal = arg.substring(9).trim().toUpperCase();

              if ("NONE".equals(strVal))
                 entropyType = 'N';
              else if ("HUFFMAN".equals(strVal))
                 entropyType = 'H';
              else if ("RANGE".equals(strVal))
                 entropyType = 'R';
              else if ("PAQ".equals(strVal))
                 entropyType = 'P';

              if (entropyType == 0)
                 System.err.println("Invalid entropy codec provided: "+arg.substring(9).trim());
           }
           else if (arg.startsWith("-block="))
           {
              arg = arg.substring(7).trim();

              try
              {
                 final int blksz = Integer.parseInt(arg);

                 if (blksz < 256)
                 {
                     System.err.println("The minimum block size is 256, the provided value is "+arg);
                     System.exit(1);
                 }
                 else if (blksz > 16 * 1024 * 1024 - 7)
                 {
                     System.err.println("The maximum block size is 16777209, the provided value is  "+arg);
                     System.exit(1);
                 }
                 else
                     blockSize = blksz;
              }
              catch (NumberFormatException e)
              {
                 System.err.println("Invalid block size provided on command line: "+arg);
              }
           }
           else
           {
               System.out.println("Warning: ignoring unknown option ["+ arg + "]");
           }
        }

        if (fileName == null)
        {
           System.err.println("Missing input file name, exiting ...");
           System.exit(1);
        }

        map.put("blockSize", blockSize);
        map.put("entropyType", entropyType);
        map.put("debug", debug);
        map.put("overwrite", overwrite);
        map.put("fileName", fileName);
    }
}
