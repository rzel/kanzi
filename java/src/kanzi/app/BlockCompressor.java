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
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;
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


public class BlockCompressor implements Runnable, Callable<Long>
{
   private static final int BITSTREAM_TYPE = 0x4B4E5A; // "KNZ"
   private static final int BITSTREAM_FORMAT_VERSION = 0;
   private static final int MAX_BLOCK_HEADER_SIZE = BlockCodec.MAX_HEADER_SIZE;
   
   public static final int ERR_MISSING_FILENAME  = -1;
   public static final int ERR_BLOCK_SIZE        = -2;
   public static final int ERR_INVALID_CODEC     = -3;
   public static final int ERR_CREATE_COMPRESSOR = -4;
   public static final int ERR_OUTPUT_IS_DIR     = -5;
   public static final int ERR_OVERWRITE_FILE    = -6;
   public static final int ERR_CREATE_FILE       = -7;
   public static final int ERR_CREATE_BITSTREAM  = -8;
   public static final int ERR_OPEN_FILE         = -9;
   public static final int ERR_READ_FILE         = -10;
   public static final int ERR_WRITE_FILE        = -11;
   public static final int ERR_PROCESS_BLOCK     = -12;
   public static final int ERR_CREATE_CODEC      = -13;
   public static final int ERR_UNKNOWN           = -127;
   public static final int WARN_EMPTY_INPUT      = -128;
   
   private boolean debug;
   private boolean silent;
   private boolean overwrite;
   private char entropyType;
   private String inputName;
   private String outputName;
   private final BlockCodec blockCodec;
   private int blockSize;
   private FileInputStream fis;
   private FileOutputStream fos;


   public BlockCompressor(String[] args)
   {
      Map<String, Object> map = new HashMap<String, Object>();
      processCommandLine(args, map);
      this.debug = (Boolean) map.get("debug");
      this.silent = (Boolean) map.get("silent");
      this.overwrite = (Boolean) map.get("overwrite");
      this.inputName = (String) map.get("inputName");
      this.outputName = (String) map.get("outputName");
      this.blockSize = (Integer) map.get("blockSize");
      char entropy = (Character) map.get("entropyType");
      this.entropyType = (entropy == 0) ? 'H' : entropy;      
      this.blockCodec = new BlockCodec();
   }


   public static void main(String[] args)
   {
      BlockCompressor bc = null;
      
      try
      {
         bc = new BlockCompressor(args);
      }
      catch (Exception e)
      {
         System.err.println("Could not create the block codec: "+e.getMessage());
         System.exit(ERR_CREATE_COMPRESSOR);
      }

      final long code = bc.call();
      System.exit((code < 0) ? (int) code : 0);
   }


   public void dispose()
   {
      try 
      { 
         if (this.fis != null) 
            this.fis.close(); 
      }
      catch (IOException ioe) 
      { 
         /* ignore */ 
      }

      try 
      { 
         if (this.fos != null) 
            this.fos.close(); 
      }
      catch (IOException ioe) 
      { 
         /* ignore */
      }  
   }

   
   @Override
   public void run()
   {
      this.call();
   }


   @Override
   public Long call()
   {
      printOut("Input file name set to '" + this.inputName + "'", this.debug);
      printOut("Output file name set to '" + this.outputName + "'", this.debug);
      printOut("Block size set to "+this.blockSize, this.debug);
      printOut("Debug set to "+this.debug, this.debug);
      printOut("Ouput file overwrite set to "+this.overwrite, this.debug);

      if (this.entropyType == 'H')
        printOut("Using Huffman entropy codec", this.debug);
      else if (this.entropyType == 'R')
        printOut("Using Range entropy codec", this.debug);
      else if (this.entropyType == 'P')
        printOut("Using PAQ entropy codec", this.debug);
      else if (this.entropyType == 'F')
        printOut("Using FPAQ entropy codec", this.debug);
      else
        printOut("Using no entropy codec", this.debug);

      File output;
      long delta = 0L;
      int read = 0;
      OutputBitStream obs;
      
      try
      {
         output = new File(this.outputName);
      }
      catch (Exception e)
      {
         System.err.println("Cannot open output file '"+ this.outputName+"' for writing: " + e.getMessage());
         return (long) ERR_CREATE_FILE;
      }
      
      if (output.exists())
      {
         if (output.isDirectory())
         {
            System.err.println("The output file is a directory");         
            return (long) ERR_OUTPUT_IS_DIR;
         }

         if (this.overwrite == false)
         {
            System.err.println("The output file exists and the 'overwrite' command "
                    + "line option has not been provided");
            return (long) ERR_OVERWRITE_FILE;
         }
      }

      try 
      {
         this.fos = new FileOutputStream(output);
         obs = new DefaultOutputBitStream(this.fos, 32768);
      }
      catch (Exception e)
      {
         System.err.println("Cannot create output bit stream: "+e.getMessage());
         return (long) ERR_CREATE_BITSTREAM;
      }
      
      // Encode
      EntropyEncoder entropyCoder = null;
      File input;

      try
      {
         input = new File(this.inputName);
         this.fis = new FileInputStream(input);
      }
      catch (Exception e)
      {
         System.err.println("Cannot open input file '"+ this.inputName+"': " + e.getMessage());
         return (long) ERR_OPEN_FILE;
      }

      int len;
      int step = 0;
      printOut("Encoding ...", !this.silent);

      try 
      {
         // Write header
         obs.writeBits(BITSTREAM_TYPE, 24);
         obs.writeBits(BITSTREAM_FORMAT_VERSION, 8);
         obs.writeBits(this.entropyType, 8);
         obs.writeBits(this.blockSize, 24);
      }
      catch (Exception e)
      {
         System.err.println("Cannot write header to output file '"+ this.outputName+"': " + e.getMessage());
         return (long) ERR_WRITE_FILE;
      }
      
      try
      {
         long written = obs.written();

         // If the compression ratio is greater than one for this block,
         // the compression will fail (unless up to MAX_BLOCK_HEADER_SIZE bytes are reserved
         // in the block for header data)
         byte[] buffer = new byte[this.blockSize+MAX_BLOCK_HEADER_SIZE];
         IndexedByteArray iba = new IndexedByteArray(buffer, 0);

         while ((len = this.fis.read(iba.array, 0, this.blockSize)) > 0)
         {
            try
            {   
               // Each block is encoded separately
               // Rebuild the entropy encoder to reset block statistics
               switch (this.entropyType)
               {
                  case 'H' :  
                     entropyCoder = new HuffmanEncoder(obs);
                     break;

                  case 'R' :  
                     entropyCoder = new RangeEncoder(obs);
                     break;

                  case 'P' :  
                     entropyCoder = new BinaryEntropyEncoder(obs, new PAQPredictor());
                     break;

                  case 'F' :  
                     entropyCoder = new FPAQEntropyEncoder(obs, new FPAQPredictor());
                     break;

                  case 'N' :
                     if (entropyCoder == null)
                        entropyCoder = new NullEntropyEncoder(obs);
                     break;

                  default :
                     System.err.println("Invalid entropy encoder: " + this.entropyType);
                     return (long) ERR_INVALID_CODEC;
               }
            }
            catch (Exception e)
            {
               System.err.println("Failed to create entropy encoder");
	       return (long) ERR_CREATE_CODEC;               
            }

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
               return (long) ERR_PROCESS_BLOCK;
            }

            // Display the block size before and after block transform + entropy coding
            printOut("Block "+step+": "+
                   ((obs.written()-written)>>3)+" bytes ("+
                   ((obs.written()-written)*100/(len<<3))+"%)", this.debug);

            written = obs.written();
            entropyCoder.dispose();
            step++;
          }

          // End block of size 0
          // The 'real' value is BlockCodec.COPY_BLOCK_MASK | (0 & BlockCodec.COPY_LENGTH_MASK)
          obs.writeBits(0x80, 8);
          obs.close();
       }
       catch (Exception e)
       {
          System.err.println("An unexpected condition happened. Exiting ...");
          e.printStackTrace();
          return (long) ERR_UNKNOWN;
       }

       if (read == 0)
       {
          System.out.println("Empty input file ... nothing to do");
          return (long) WARN_EMPTY_INPUT;
       }

       delta /= 1000000; // convert to ms
       printOut("", !this.silent);
       printOut("File size:        "+read, !this.silent);
       printOut("Encoding took "+delta+" ms", !this.silent);
       printOut("Ratio:            "+(obs.written() >> 3) / (float) read, !this.silent);
       printOut("Encoded:          "+(obs.written() >> 3), !this.silent);
       printOut("Troughput (KB/s): "+(((read * 1000L) >> 10) / delta), !this.silent);
       printOut("", !this.silent);
       return obs.written();
    }


    private static void processCommandLine(String args[], Map<String, Object> map)
    {
        // Set default values
        int blockSize = 100000;
        char entropyType = 0;
        boolean debug = false;
        boolean silent = false;
        boolean overwrite = false;
        String inputName = null;
        String outputName = null;

        for (String arg : args)
        {
           arg = arg.trim();

           if (arg.equals("-help"))
           {
               printOut("-help                : display this message", true);
               printOut("-debug               : display the size of the encoded block pre-entropy coding", true);
               printOut("-silent              : silent mode: no output (except warnings and errors)", true);
               printOut("-overwrite           : overwrite the output file if it already exists", true);
               printOut("-input=<inputName>   : mandatory name of the input file to encode", true);
               printOut("-output=<inputName>  : optional name of the output file (defaults to <input.knz>)", true);
               printOut("-block=<size>        : size of the block (max 16 MB / default 100 KB)", true);
               printOut("-entropy=            : Entropy codec to use [None|Huffman|Range|PAQ|FPAQ]", true);
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
           else if (arg.equals("-overwrite"))
           {
               overwrite = true;
           }
           else if (arg.startsWith("-input="))
           {
              inputName = arg.substring(7).trim();
           }
           else if (arg.startsWith("-output="))
           {
              outputName = arg.substring(8).trim();
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
              else if ("FPAQ".equals(strVal))
                 entropyType = 'F';

              if (entropyType == 0) 
              {
                 System.err.println("Invalid entropy codec provided: "+arg.substring(9).trim());
                 System.exit(ERR_INVALID_CODEC);
              }
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
                     System.exit(ERR_BLOCK_SIZE);
                 }
                 else if (blksz > 16 * 1024 * 1024 - MAX_BLOCK_HEADER_SIZE)
                 {
                     final int max = 16 * 1024 * 1024 - MAX_BLOCK_HEADER_SIZE;
                     System.err.println("The maximum block size is "+ max +", the provided value is  "+arg);
                     System.exit(ERR_BLOCK_SIZE);
                 }
                 else
                     blockSize = blksz;
              }
              catch (NumberFormatException e)
              {
                 System.err.println("Invalid block size provided on command line: "+arg);
                 System.exit(ERR_BLOCK_SIZE);
              }
           }
           else
           {
              printOut("Warning: ignoring unknown option ["+ arg + "]", true);
           }
        }

        if (inputName == null)
        {
           System.err.println("Missing input file name, exiting ...");
           System.exit(ERR_MISSING_FILENAME);
        }

        if (outputName == null)
           outputName = inputName + ".knz";

        if ((silent == true) && (debug == true))
        {
           printOut("Warning: both 'silent' and 'debug' options were selected, ignoring 'debug'", true);
           debug = false;
        }

        map.put("blockSize", blockSize);
        map.put("entropyType", entropyType);
        map.put("debug", debug);
        map.put("silent", silent);
        map.put("overwrite", overwrite);
        map.put("inputName", inputName);
        map.put("outputName", outputName);
    }


    private static void printOut(String msg, boolean print)
    {
       if ((print == true) && (msg != null))
          System.out.println(msg);
    }
}
