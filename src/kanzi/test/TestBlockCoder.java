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

package kanzi.test;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import kanzi.IndexedByteArray;
import kanzi.BitStream;
import kanzi.bitstream.DefaultBitStream;
import kanzi.EntropyDecoder;
import kanzi.EntropyEncoder;
import kanzi.entropy.RangeDecoder;
import kanzi.entropy.RangeEncoder;
import kanzi.function.BlockCodec;


public class TestBlockCoder
{
    public static void main(String[] args)
    {
        try
        {
            int blockSize = 100000;
            boolean debug = false;
            String fileName = "c:\\temp\\rt.jar";
            boolean fileProvided = false;

            for (String arg : args)
            {
               arg = arg.trim();
               
               if (arg.equals("-help"))
               {
                   System.out.println("-help             : display this message");
                   System.out.println("-debug            : display the size of the encoded block pre-entropy coding");
                   System.out.println("                    and the size of the completely decoded block");
                   System.out.println("-file=<filename>  : name of the input file to encode or decode");
                   System.out.println("-block=<size>     : size of the block (max 16 MB)");
                   System.exit(0);
               }
               else if (arg.equals("-debug"))
               {
                   debug = true;
                   System.out.println("Debug set to true");
               }
               else if (arg.startsWith("-file="))
               {
                  fileName = arg.substring(6);
                  fileProvided = true;
               }
               else if (arg.startsWith("-block="))
               {
                  arg = arg.substring(7);
                  
                  try
                  {
                     int blksz = Integer.parseInt(arg);
                     
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
                   System.out.println("Warning: unknown option: ["+ arg + "]");
               }
            }
            

            if (fileProvided == false)
                System.out.println("No input file name provided on command line, using default value");
            
            System.out.println("Input file name set to '" + fileName + "'");
            System.out.println("Block size set to "+blockSize);
            String outputName = fileName;

            if (outputName.lastIndexOf('.') == outputName.length()-4)
                outputName = outputName.substring(0, outputName.length()-4);

            outputName += ".knz";
            File output = new File(outputName);
            FileOutputStream fos = new FileOutputStream(output);
            //BitStream obs = new DefaultBitStream(fos, 8192);
            //DebugBitStream dbs = new DebugBitStream(obs, System.out);
            //dbs.showByte(true);
            BitStream dbs = new DefaultBitStream(fos, 16384);
            byte[] buffer = new byte[blockSize+7];
            BlockCodec blockCodec = new BlockCodec();
            IndexedByteArray iba = new IndexedByteArray(buffer, 0);

            // Encode
            EntropyEncoder entropyCoder = new RangeEncoder(dbs);
//            ByteArrayInputStream bais = new ByteArrayInputStream(buffer1);
//            InputStream is = new BufferedInputStream(bais);

            File input;
            input = new File(fileName);
            FileInputStream fis = new FileInputStream(input);
            long delta = 0L;
            int len;
            int read = 0;
            int step = 0;
            System.out.println("Encoding ...");

            // If the compression ratio is greater than one for this block, 
            // the compression will fail (unless up to 7 bytes are reserved
            // in the block for headr data)
            while ((len = fis.read(iba.array, 0, blockSize)) != -1)
            {
               read += len;
               long before = System.nanoTime();
               iba.index = 0;
               blockCodec.setSize(len);
               int written = blockCodec.encode(iba, entropyCoder);
               long after = System.nanoTime();
               delta += (after - before);

               if (written < 0)
               {
                  System.err.println("Error in block codec forward()");
                  System.exit(1);
               }
                
              if (debug)
                 System.out.println(step+": "+written);

               step++;
            }

            // End block of size 0
            // The 'real' value is BlockCodec.COPY_BLOCK_MASK | (0 & BlockCodec.COPY_LENGTH_MASK)
            entropyCoder.encodeByte((byte) 0x80);

            System.out.println();
            System.out.println("Buffer size:      "+buffer.length);
            System.out.println("File size:        "+read);
            entropyCoder.dispose();
            dbs.close();
            System.out.println();
            System.out.println("Encoding took "+(delta/1000000)+" ms");
            System.out.println("Ratio:            "+(dbs.written() >> 3) / (float) read);
            System.out.println("Encoded:          "+(dbs.written() >> 3));
            System.out.println("Troughput (KB/s): "+((int) (read * 8 * 1000000000.0 / 8192 / delta)));
            System.out.println();

            // Decode
            // !!! The decoder must know the block size of the encoder !!!
            fis = new FileInputStream(output);
            //BitStream ibs = new DefaultBitStream(is, iba.array.length);
            //DebugBitStream dbs2 = new DebugBitStream(ibs, System.out);
            //dbs2.showByte(true);
            BitStream dbs2 = new DefaultBitStream(fis, iba.array.length);

            EntropyDecoder entropyDecoder = new RangeDecoder(dbs2);
            delta = 0L;
            int decoded;
            int sum = 0;
            step = 0;
            System.out.println("Decoding ...");

            // Decode next block
            do
            {               
               iba.index = 0; 
               long before = System.nanoTime();
               decoded = blockCodec.decode(iba, entropyDecoder);
               long after = System.nanoTime();
               delta += (after - before);
               
               if (decoded < 0)
               {
                  System.err.println("Error in block codec inverse()");
                  System.exit(1);
               }
           
               if (debug)
                 System.out.println(step+": "+decoded);
               
               sum += decoded;
               step++;
            }
            while (decoded != 0);
            
            System.out.println();
            System.out.println("Decoding took "+(delta/1000000)+" ms");
            System.out.println("Decoded:           "+sum);
            System.out.println("Troughput (KB/s):  "+((int) (dbs.written() * 1000000000.0 / 8192 / delta)));
            System.out.println();

            fis.close();
            entropyDecoder.dispose();
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }
    
}