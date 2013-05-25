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

package kanzi.app;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import kanzi.IndexedByteArray;
import kanzi.io.CompressedOutputStream;
import kanzi.io.Error;


public class BlockCompressor implements Runnable, Callable<Integer>
{
   private static final int DEFAULT_BUFFER_SIZE = 32768;
   public static final int WARN_EMPTY_INPUT = -128;

   private boolean debug;
   private boolean silent;
   private boolean overwrite;
   private boolean checksum;
   private String inputName;
   private String outputName;
   private String codec;
   private String transform;
   private int blockSize;
   private InputStream is;
   private CompressedOutputStream cos;


   public BlockCompressor(String[] args)
   {
      Map<String, Object> map = new HashMap<String, Object>();
      processCommandLine(args, map);
      this.debug = (Boolean) map.get("debug");
      this.silent = (Boolean) map.get("silent");
      this.overwrite = (Boolean) map.get("overwrite");
      this.inputName = (String) map.get("inputName");
      this.outputName = (String) map.get("outputName");
      this.codec = (String) map.get("codec");
      this.blockSize = (Integer) map.get("blockSize");
      this.transform = (String) map.get("transform");
      this.checksum = (Boolean) map.get("checksum");
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
         System.exit(Error.ERR_CREATE_COMPRESSOR);
      }

      final int code = bc.call();

      if (code != 0)
         bc.closeAll();

      System.exit(code);
   }


   private void closeAll()
   {
      try
      {
         if (this.is != null)
            this.is.close();
      }
      catch (IOException ioe)
      {
         /* ignore */
      }

      try
      {
         if (this.cos != null)
            this.cos.close();
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


   // Return status (success = 0, error < 0)
   @Override
   public Integer call()
   {
      printOut("Input file name set to '" + this.inputName + "'", this.debug);
      printOut("Output file name set to '" + this.outputName + "'", this.debug);
      printOut("Block size set to "+this.blockSize, this.debug);
      printOut("Debug set to "+this.debug, this.debug);
      printOut("Overwrite set to "+this.overwrite, this.debug);
      printOut("Checksum set to "+this.checksum, this.debug);
      String etransform = ("NONE".equals(this.transform)) ? "no" : this.transform;
      printOut("Using " + etransform + " transform (stage 1)", this.debug);
      String ecodec = ("NONE".equals(this.codec)) ? "no" : this.codec;
      printOut("Using " + ecodec + " entropy codec (stage 2)", this.debug);

      try
      {
         File output = new File(this.outputName);

         if (output.exists())
         {
            if (output.isDirectory())
            {
               System.err.println("The output file is a directory");
               return Error.ERR_OUTPUT_IS_DIR;
            }

            if (this.overwrite == false)
            {
               System.err.println("The output file exists and the 'overwrite' command "
                       + "line option has not been provided");
               return Error.ERR_OVERWRITE_FILE;
            }
         }

         try
         {
            this.cos = new CompressedOutputStream(this.codec, this.transform,
                 new FileOutputStream(output),
                 this.blockSize,
                 this.checksum,
                 (this.debug == true) ? System.out : null);
         }
         catch (Exception e)
         {
            System.err.println("Cannot create compressed stream: "+e.getMessage());
            return Error.ERR_CREATE_COMPRESSOR;
         }
     }
      catch (Exception e)
      {
         System.err.println("Cannot open output file '"+ this.outputName+"' for writing: " + e.getMessage());
         return Error.ERR_CREATE_FILE;
      }

      try
      {
         File input = new File(this.inputName);
         this.is = new FileInputStream(input);
      }
      catch (Exception e)
      {
         System.err.println("Cannot open input file '"+ this.inputName+"': " + e.getMessage());
         return Error.ERR_OPEN_FILE;
      }

      // Encode
      printOut("Encoding ...", !this.silent);
      int read = 0;

      // If the compression ratio is greater than one for this block,
      // the compression will fail (unless up to MAX_BLOCK_HEADER_SIZE bytes are reserved
      // in the block for header data)
      IndexedByteArray iba = new IndexedByteArray(new byte[DEFAULT_BUFFER_SIZE], 0);
      int len;
      long before = System.nanoTime();

      try
      {
         while ((len = this.is.read(iba.array, 0, iba.array.length)) > 0)
         {
            try
            {
               // Just write block to the compressed output stream !
               read += len;
               this.cos.write(iba.array, 0, len);
            }
            catch (kanzi.io.IOException e)
            {
               System.err.println(e.getMessage());
               return e.getErrorCode();
            }
            catch (IOException e)
            {
               System.err.println(e.getMessage());
               return Error.ERR_UNKNOWN;
            }
          }
       }
       catch (kanzi.io.IOException e)
       {
          System.err.println(e.getMessage());
          return e.getErrorCode();
       }
       catch (Exception e)
       {
          System.err.println("An unexpected condition happened. Exiting ...");
          e.printStackTrace();
          return Error.ERR_UNKNOWN;
       }

       if (read == 0)
       {
          System.out.println("Empty input file ... nothing to do");
          return WARN_EMPTY_INPUT;
       }

       // Close streams to ensure all data are flushed
       this.closeAll();

       long after = System.nanoTime();
       long delta = (after - before) / 1000000L; // convert to ms
       printOut("", !this.silent);
       printOut("Encoding:          "+delta+" ms", !this.silent);
       printOut("Input size:        "+read, !this.silent);
       printOut("Output size:       "+this.cos.getWritten(), !this.silent);
       printOut("Ratio:             "+this.cos.getWritten() / (float) read, !this.silent);

       if (delta > 0)
          printOut("Throughput (KB/s): "+(((read * 1000L) >> 10) / delta), !this.silent);

       printOut("", !this.silent);
       return 0;
    }


    private static void processCommandLine(String args[], Map<String, Object> map)
    {
        // Set default values
        int blockSize = 100000;
        boolean debug = false;
        boolean silent = false;
        boolean overwrite = false;
        boolean checksum = false;
        String inputName = null;
        String outputName = null;
        String codec = "HUFFMAN"; // default
        String transform = "BLOCK"; // default

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
               printOut("-output=<outputName> : optional name of the output file (defaults to <input.knz>)", true);
               printOut("-block=<size>        : size of the blocks (max 16 MB / min 1KB / default 100 KB)", true);
               printOut("-entropy=            : entropy codec to use [None|Huffman*|Range|PAQ|FPAQ]", true);
               printOut("-transform=          : transform to use [None|Block*|Snappy|LZ4|RLT]", true);
               printOut("-checksum =          : enable block checksum", true);
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
           else if (arg.equals("-checksum"))
           {
               checksum = true;
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
              codec = arg.substring(9).trim().toUpperCase();
           }
           else if (arg.startsWith("-transform="))
           {
              transform = arg.substring(11).trim().toUpperCase();
           }
           else if (arg.startsWith("-block="))
           {
              arg = arg.substring(7).trim();

              try
              {
                 blockSize = Integer.parseInt(arg);
              }
              catch (NumberFormatException e)
              {
                 System.err.println("Invalid block size provided on command line: "+arg);
                 System.exit(Error.ERR_BLOCK_SIZE);
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
           System.exit(Error.ERR_MISSING_FILENAME);
        }

        if (outputName == null)
           outputName = inputName + ".knz";

        if ((silent == true) && (debug == true))
        {
           printOut("Warning: both 'silent' and 'debug' options were selected, ignoring 'debug'", true);
           debug = false;
        }

        map.put("blockSize", blockSize);
        map.put("debug", debug);
        map.put("silent", silent);
        map.put("overwrite", overwrite);
        map.put("inputName", inputName);
        map.put("outputName", outputName);
        map.put("codec", codec);
        map.put("transform", transform);
        map.put("checksum", checksum);
    }


    private static void printOut(String msg, boolean print)
    {
       if ((print == true) && (msg != null))
          System.out.println(msg);
    }
}
