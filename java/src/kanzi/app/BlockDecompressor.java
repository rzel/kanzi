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
import kanzi.io.Error;
import kanzi.IndexedByteArray;
import kanzi.io.CompressedInputStream;



public class BlockDecompressor implements Runnable, Callable<Integer>
{
   private static final int DEFAULT_BUFFER_SIZE = 32768;

   private final boolean debug;
   private final boolean silent;
   private final boolean overwrite;
   private final String inputName;
   private final String outputName;
   private CompressedInputStream cis;
   private OutputStream fos;


   public BlockDecompressor(String[] args)
   {
      Map<String, Object> map = new HashMap<String, Object>();
      processCommandLine(args, map);
      this.debug = (Boolean) map.get("debug");
      this.silent = (Boolean) map.get("silent");
      this.overwrite = (Boolean) map.get("overwrite");
      this.inputName = (String) map.get("inputName");
      this.outputName = (String) map.get("outputName");
   }


   private void closeAll()
   {
      try
      {
         if (this.cis != null)
            this.cis.close();
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
         System.exit(Error.ERR_CREATE_COMPRESSOR);
      }

      final int code = bd.call();

      if (code != 0)
         bd.closeAll();

      System.exit(code);
   }


   @Override
   public void run()
   {
      this.call();
   }


   @Override
   public Integer call()
   {
      printOut("Input file name set to '" + this.inputName + "'", this.debug);
      printOut("Output file name set to '" + this.outputName + "'", this.debug);
      printOut("Debug set to "+this.debug, this.debug);
      printOut("Overwrite set to "+this.overwrite, this.debug);

      long delta = 0;
      long read = 0;
      printOut("Decoding ...", !this.silent);
      File output;

      try
      {
         output = new File(this.outputName);

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
      }
      catch (Exception e)
      {
         System.err.println("Cannot open output file '"+ this.outputName+"' for writing: " + e.getMessage());
         return Error.ERR_CREATE_FILE;
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
         return Error.ERR_CREATE_FILE;
      }

      try
      {
         File input = new File(this.inputName);
         
         try
         {
            this.cis = new CompressedInputStream(new FileInputStream(input),
                 (this.debug == true) ? System.out : null);
         }
         catch (Exception e)
         {
            System.err.println("Cannot create compressed stream: "+e.getMessage());
            return Error.ERR_CREATE_DECOMPRESSOR;    
         }
      }
      catch (Exception e)
      {
         System.err.println("Cannot open input file '"+ this.inputName+"': " + e.getMessage());
         return Error.ERR_OPEN_FILE;
      }

      try
      {
         IndexedByteArray iba = new IndexedByteArray(new byte[DEFAULT_BUFFER_SIZE], 0);
         int decoded = 0;

         // Decode next block
         do
         {
            long before = System.nanoTime();
            decoded = this.cis.read(iba.array, 0, iba.array.length);
            long after = System.nanoTime();
            delta += (after - before);

            if (decoded < 0)
            {
               System.err.println("Reached end of stream");
               return Error.ERR_READ_FILE;
            }

            try
            {
               this.fos.write(iba.array, 0, decoded);
            }
            catch (Exception e)
            {
               System.err.println("Failed to read next block: " + e.getMessage());
               return Error.ERR_READ_FILE;
            }

            read += decoded;
         }
         while (decoded == iba.array.length);
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

      // Close streams to ensure all data are flushed
      this.closeAll();

      delta /= 1000000L; // convert to ms
      printOut("", !this.silent);
      printOut("Decoding:         "+delta+" ms", !this.silent);
      printOut("Input size:       "+this.cis.getRead(), !this.silent);
      printOut("Output size:      "+read, !this.silent);
      
      if (delta > 0)
         printOut("Troughput (KB/s): "+(((read * 1000L) >> 10) / delta), !this.silent);
      
      printOut("", !this.silent);
      return 0;
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
           System.exit(Error.ERR_MISSING_FILENAME);
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
