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
import java.util.Arrays;
import kanzi.IndexedByteArray;
import kanzi.bitstream.BitStream;
import kanzi.bitstream.DefaultBitStream;
import kanzi.EntropyDecoder;
import kanzi.EntropyEncoder;
import kanzi.entropy.RangeDecoder;
import kanzi.entropy.RangeEncoder;
import kanzi.function.BlockCodec;
//import kanzi.function.RLT;


public class TestBlockCoder
{
    public static void main(String[] args)
    {
        try
        {
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

            byte[] buffer = new byte[32768];
            BlockCodec blockCodec = new BlockCodec(buffer.length);
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
            int sum0 = 0;
            int sum1 = 0;
            int step = 0;

            while ((len = fis.read(iba.array, 0, iba.array.length)) != -1)
            {
               read += len;
               long before = System.nanoTime();
                iba.index = 0;
                block.index = 0;

                // For debugging only ...
                Arrays.fill(block.array, (byte) 0xAA);

                blockCodec.setSize(len);

                if (blockCodec.forward(iba, block) == false)
                {
                   System.out.println("Error in block codec forward");
                   System.exit(1);
                }

                // Double compression
//                System.out.print(block.index+" ");
//                System.arraycopy(block.array, 0, iba.array, 0, block.index);
//                blockCodec.setSize(block.index);
//                iba.index = 0;
//                block.index = 0;
//                if (blockCodec.forward(iba, block) == false)
//                {
//                   System.out.println("Error in block codec forward");
//                   System.exit(1);
//                }
//                System.out.println(block.index);

                for (int i=0; i<block.index; i++)
                    entropyCoder.encodeByte(block.array[i]);

               long after = System.nanoTime();
               delta += (after - before);
               sum0 += len;
               sum1 += block.index;

               System.out.println(step+": "+len+" --> "+block.index);
               step++;
            }

            // End block of size 0
            entropyCoder.encodeByte((byte) 0);
            entropyCoder.encodeByte((byte) 0);

            System.out.println("Buffer size: "+buffer.length);
            System.out.println("Encoding took "+(delta/1000000)+" ms");
            entropyCoder.dispose();
            dbs.close();
            System.out.println();
            System.out.println("Read:             "+read);
            System.out.println("Encoded:          "+(dbs.written() >> 3));
            System.out.println("Ratio:            "+(dbs.written() >> 3) / (float) read);
            System.out.println("Troughput (KB/s): "+(dbs.written() / 8192) / (float) (delta/1000000000));
            System.out.println();

            // Decode
            // !!! The decoder must know the block size of the encoder !!!
            fis = new FileInputStream(output);
            FileInputStream is = new FileInputStream(output);
            //BitStream ibs = new DefaultBitStream(is, iba.array.length);
            //DebugBitStream dbs2 = new DebugBitStream(ibs, System.out);
            //dbs2.showByte(true);
            BitStream dbs2 = new DefaultBitStream(is, iba.array.length);

            EntropyDecoder entropyDecoder = new RangeDecoder(dbs2);
            delta = 0L;
            step = 0;

// FIXME check mode value to find length
            int mode = (int) entropyDecoder.decodeByte();
            int val1 = (int) entropyDecoder.decodeByte();
            int val2 = (int) entropyDecoder.decodeByte();
            int compressedLength = ((val1 & 0xFF) << 8) | (val2 & 0xFF);
            iba.array = new byte[compressedLength];

            // Decode next block
            while (compressedLength > 0)
            {
                long before = System.nanoTime();

                if (iba.array.length < compressedLength + 5)
                    iba.array = new byte[compressedLength + 5];

                // For debugging only ...
                Arrays.fill(iba.array, (byte) 0xAA);

                iba.array[0] = (byte) mode;
                iba.array[1] = (byte) val1;
                iba.array[2] = (byte) val2;

                for (int i = 0; i < compressedLength+2; i++)
                    iba.array[3+i] = entropyDecoder.decodeByte();

                iba.index = 0;
                block.index = 0;

                if (blockCodec.inverse(iba, block) == false)
                {
                   System.out.println("Error in block codec inverse");
                   System.exit(1);
                }

                long after = System.nanoTime();

                System.out.println(step+": "+(compressedLength+5)+" --> "+block.index);
                step++;
                mode = (int) entropyDecoder.decodeByte();
// FIXME check mode value to find length
                val1 = (int) entropyDecoder.decodeByte();
                val2 = (int) entropyDecoder.decodeByte();
                compressedLength = ((val1 & 0xFF) << 8) | (val2 & 0xFF);
                delta += (after - before);
            }

            System.out.println();
            System.out.println("Decoding took "+(delta/1000000)+" ms");
            System.out.println("Troughput (KB/s): "+(dbs.written() / 8192) / (float) (delta/1000000000));
            System.out.println();

            is.close();
            entropyDecoder.dispose();
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }
}