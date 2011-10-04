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
            final int blockSize = 100000;
            String fileName = (args.length > 0) ? args[0] : "c:\\temp\\rt.jar";
            String outputName = fileName;

            if (outputName.lastIndexOf('.') == outputName.length()-4)
                outputName = outputName.substring(0, outputName.length()-4);

            outputName += ".bin";
            File output = new File(outputName);
            FileOutputStream fos = new FileOutputStream(output);
            //BitStream obs = new DefaultBitStream(fos, 8192);
            //DebugBitStream dbs = new DebugBitStream(obs, System.out);
            //dbs.showByte(true);
            BitStream dbs = new DefaultBitStream(fos, 16384);
            byte[] buffer = new byte[blockSize];
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
            int len = -1;
            int read = 0;
            IndexedByteArray block = new IndexedByteArray (new byte[buffer.length*6/5], 0);
            int step = 0;

            // Leave some space for block header (if the buffer size is small, there may
            // be no compression and the block header must still be saved)
            while ((len = fis.read(iba.array, 0, iba.array.length-7)) != -1)
            {
               read += len;
               long before = System.nanoTime();
               iba.index = 0;
               block.index = 0;

               // For debugging only ...
               //Arrays.fill(block.array, (byte) 0xAA);

               blockCodec.setSize(len);

               if (blockCodec.encode(iba, entropyCoder) < 0)
               {
                  System.err.println("Error in block codec forward");
                  System.exit(1);
               }

               long after = System.nanoTime();
               delta += (after - before);
               step++;
            }

            // End block of size 0
            entropyCoder.encodeByte((byte) 80);

            System.out.println();
            System.out.println("Buffer size:    "+buffer.length);
            System.out.println("File size:       "+read);
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
            step = 0;
            int decoded;
            long before = System.nanoTime();
            int sum = 0;

            // Decode next block
            do
            {
               iba.index = 0; 
               decoded = blockCodec.decode(iba, entropyDecoder);
               
               if (decoded < 0)
               {
                  System.err.println("Error in block codec inverse (block "+step+")");
                  System.exit(1);
               }
           
               step++;
               sum += decoded;
            }
            while (decoded != 0);
            
            long after = System.nanoTime();
            delta += (after - before);

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