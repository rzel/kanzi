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


public class BlockDecompressor implements Runnable, Callable<Long>
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
   public static final int ERR_INVALID_FILE      = -14;
   public static final int ERR_STREAM_VERSION    = -15;
   public static final int ERR_UNKNOWN           = -127;


   private final boolean debug;
   private final boolean silent;
   private final boolean overwrite;
   private final String inputName;
   private final String outputName;
   private final BlockCodec blockCodec;
   private FileInputStream fis;
   private FileOutputStream fos;


   public BlockDecompressor(String[] args)
   {
      Map<String, Object> map = new HashMap<String, Object>();
      processCommandLine(args, map);
      this.debug = (Boolean) map.get("debug");
      this.silent = (Boolean) map.get("silent");
      this.overwrite = (Boolean) map.get("overwrite");
      this.inputName = (String) map.get("inputName");
      this.outputName = (String) map.get("outputName");
      this.blockCodec = new BlockCodec();
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
   
   
   public static void main(String[] args)
   {
      BlockDecompressor bd = null;
      
      try
      {
         bd = new BlockDecompressor(args);
      }
      catch (Exception e)
      {
         System.err.println("Could not create the block codec: "+e.getMessage());
         System.exit(ERR_CREATE_COMPRESSOR);
      }

      final long code = bd.call();
      bd.dispose();
      System.exit((code < 0) ? (int) code : 0);
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
      printOut("Debug set to "+this.debug, this.debug);
      printOut("Overwrite set to "+this.overwrite, this.debug);    

      long delta = 0L;
      int decoded;
      long sum = 0;
      int step = 0;
      printOut("Decoding ...", !this.silent);

      EntropyDecoder entropyDecoder = null;
      InputBitStream ibs = null;
      int blockSize = 0;
      char entropyType = 0;
      File input;
      File output;   
     
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
         // Create output steam (note: it creates the file yielding file.exists()
         // to return true so it must be called after the check).
         this.fos = new FileOutputStream(output);
      }
      catch (IOException e)
      {
         System.err.println("Cannot open output file '"+ this.outputName+"' for writing: " + e.getMessage());
         return (long) ERR_CREATE_FILE;
      }
      
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
        
      try
      {
         ibs = new DefaultInputBitStream(this.fis, 32768);
         
         try
         {
            // Read header
            final int type = (int) ibs.readBits(24);

            // Sanity check
            if (type != BITSTREAM_TYPE)
            {
               System.err.println("Invalid stream type: expected "+
                       Integer.toHexString(BITSTREAM_TYPE)+", got "+
                       Integer.toHexString(type));
               return (long) ERR_INVALID_FILE;
            }         

            final int version = (int) ibs.readBits(8);

            // Sanity check
            if (version < BITSTREAM_FORMAT_VERSION)
            {
               System.err.println("Cannot read this version of the stream: "+version);
               return (long) ERR_STREAM_VERSION;
            }

            entropyType = (char) ibs.readBits(8);
            blockSize = (int) ibs.readBits(24);

            if ((blockSize < 0) || (blockSize > 16 * 1024 * 1024 - MAX_BLOCK_HEADER_SIZE))
            {
               System.err.println("Invalid block size read from file: "+blockSize);
               return (long) ERR_BLOCK_SIZE;
            }
         }
         catch (BitStreamException e)
         {
            System.err.println("Error reading header from input file: "+e.getMessage());
            return (long) ERR_READ_FILE;
         }

         byte[] buffer = new byte[blockSize+MAX_BLOCK_HEADER_SIZE];
         IndexedByteArray iba = new IndexedByteArray(buffer, 0);

         printOut("Block size set to "+blockSize, this.debug);

         if (entropyType == 'H')
           printOut("Using Huffman entropy codec", this.debug);
         else if (entropyType == 'R')
           printOut("Using Range entropy codec", this.debug);
         else if (entropyType == 'P')
           printOut("Using PAQ entropy codec", this.debug);
         else if (entropyType == 'F')
           printOut("Using FPAQ entropy codec", this.debug);
         else
           printOut("Using no entropy codec", this.debug);

         // Decode next block
         do
         {
            try
            {
               switch (entropyType) 
               {
                  // Each block is decoded separately
                  // Rebuild the entropy decoder to reset block statistics
                  case 'H' :
                     entropyDecoder = new HuffmanDecoder(ibs); 
                     break;

                  case 'R' :
                     entropyDecoder = new RangeDecoder(ibs);
                     break;

                  case 'P' :
                     entropyDecoder = new BinaryEntropyDecoder(ibs, new PAQPredictor());
                     break;

                  case 'F' :
                     entropyDecoder = new FPAQEntropyDecoder(ibs, new FPAQPredictor());
                     break;

                  case 'N' :
                     if (entropyDecoder == null)
                        entropyDecoder = new NullEntropyDecoder(ibs);
                     break;

                  default :
                     System.err.println("Invalid entropy codec type: " + entropyType);
                     return (long) ERR_INVALID_CODEC;
               }
            } 
            catch (Exception e)
            {
               System.err.println("Failed to create entropy decoder");
               return (long) ERR_CREATE_CODEC;
            }

            iba.index = 0;
            long before = System.nanoTime();
            decoded = this.blockCodec.decode(iba, entropyDecoder);
            long after = System.nanoTime();
            delta += (after - before);

            if (decoded < 0)
            {
               System.err.println("Error in block codec inverse()");
               return (long) ERR_PROCESS_BLOCK;
            }

            // Display block size after entropy decoding + block transform
            printOut("Block "+step+": "+decoded+" byte(s)", this.debug);

            try
            {   
               this.fos.write(iba.array, 0, decoded);
            }
            catch (Exception e)
            {
               System.err.println("Failed to write next block: " + e.getMessage());
               return (long) ERR_WRITE_FILE;
            }
            
            sum += decoded;
            step++;
            entropyDecoder.dispose();
         }
         while (decoded != 0);
      }
      catch (Exception e)
      {
         System.err.println("An unexpected condition happened. Exiting ...");
         e.printStackTrace();
         return (long) ERR_UNKNOWN;
      }        
      
      delta /= 1000000; // convert to ms
      printOut("", !this.silent);
      printOut("Decoding took "+delta+" ms", !this.silent);
      printOut("Decoded:          "+sum, !this.silent);
      printOut("Troughput (KB/s): "+(((sum * 1000L) >> 10) / delta), !this.silent);
      printOut("", !this.silent);
      return ibs.read();
   }


    private static void processCommandLine(String args[], Map<String, Object> map)
    {
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
              printOut("-debug               : display the size of the completely decoded block", true);
              printOut("-overwrite           : overwrite the output file if it already exists", true);
              printOut("-silent              : silent mode: no output (except warnings and errors)", true);
              printOut("-input=<inputName>   : mandatory name of the input file to decode", true);
              printOut("-output=<outputName> : optional name of the output file", true);
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

        if (inputName.endsWith(".knz") == false)
           printOut("Warning: the input file name does not end with the .KNZ extension", true);

        if (outputName == null) 
        {
           outputName = (inputName.endsWith(".knz")) ? inputName.substring(0, inputName.length()-4)
                   : inputName + ".tmp";
        }
        
        if ((silent == true) && (debug == true))
        {
           printOut("Warning: both 'silent' and 'debug' options were selected, ignoring 'debug'", true);
           debug = false;
        }

        map.put("debug", debug);
        map.put("overwrite", overwrite);
        map.put("silent", silent);
        map.put("outputName", outputName);
        map.put("inputName", inputName);
    }


    private static void printOut(String msg, boolean print)
    {
       if ((print == true) && (msg != null))
          System.out.println(msg);
    } 
}
