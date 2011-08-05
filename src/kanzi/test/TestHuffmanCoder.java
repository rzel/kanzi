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

import kanzi.bitstream.BitStream;
import kanzi.bitstream.BitStreamException;
import kanzi.bitstream.DebugBitStream;
import kanzi.bitstream.DefaultBitStream;
import kanzi.entropy.HuffmanDecoder;
import kanzi.entropy.HuffmanEncoder;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;


public class TestHuffmanCoder
{
    public static void main(String[] args)
    {
        System.out.println("TestHuffmanCoder");

        // Test behavior
        for (int ii=1; ii<20; ii++)
        {
            System.out.println("\n\nTest "+ii);
            try
            {
                int[] values;
                java.util.Random random = new java.util.Random();

                if (ii == 3)
                     values = new int[] { 0, 0, 32, 15, -4, 16, 0, 16, 0, 7, -1, -4, -32, 0, 31, -1 };
                else if (ii == 2)
                     values = new int[] { 0x3d, 0x4d, 0x54, 0x47, 0x5a, 0x36, 0x39, 0x26, 0x72, 0x6f, 0x6c, 0x65, 0x3d, 0x70, 0x72, 0x65 };
                else if (ii == 1)
                     values = new int[] { 65, 71, 74, 66, 76, 65, 69, 77, 74, 79, 68, 75, 73, 72, 77, 68, 78, 65, 79, 79, 78, 66, 77, 71, 64, 70, 74, 77, 64, 67, 71, 64 };
                else
                {
                     values = new int[32];

                     for (int i=0; i<values.length; i++)
                          values[i] = 64 + (random.nextInt() & 15);
                }

                System.out.println("Original:");

                for (int i=0; i<values.length; i++)
                    System.out.print(values[i]+" ");

                System.out.println("\nEncoded:");
                ByteArrayOutputStream os = new ByteArrayOutputStream(16384);
                BitStream bs = new DefaultBitStream(os, 16384);
                DebugBitStream dbgbs = new DebugBitStream(bs, System.out);
                dbgbs.showByte(true);
                dbgbs.setMark(true);
                int[] freq = new int[256];

                for (int i=0; i<values.length; i++)
                    freq[values[i] & 255]++;

                HuffmanEncoder rc = new HuffmanEncoder(dbgbs, false, freq);

                for (int i=0; i<values.length; i++)
                {
                    if (rc.encodeByte((byte) (values[i] & 255)) == false)
                        break;
                }

                rc.dispose();
                System.out.println();
                byte[] buf = os.toByteArray();
                bs = new DefaultBitStream(new ByteArrayInputStream(buf), 16384);
                dbgbs = new DebugBitStream(bs, System.out);
                dbgbs.setMark(true);
                HuffmanDecoder rd = new HuffmanDecoder(dbgbs);
                System.out.println("\nDecoded:");
                int len = values.length; // buf.length >> 3;
                boolean ok = true;
                int[] values2 = new int[values.length];

                for (int i=0, j=0; i<len; i++)
                {
                    try
                    {
                        int n = rd.decodeByte();
                        values2[j] = n;

                        if (values[j++] != n)
                           ok = false;
                    }
                    catch (BitStreamException e)
                    {
                        e.printStackTrace();
                        break;
                    }
                }

                System.out.println();

                for (int i=0; i<values2.length; i++)
                    System.out.print(values2[i]+" ");

                System.out.println("\n"+((ok == true) ? "Identical" : "! *** Different *** !"));
                rc.dispose();
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }
        }


        // Test speed
        {
            System.out.println("\n\nSpeed Test");
            int[] values = new int[10000];
            int[] freq = new int[256];
            long delta = 0;

            for (int i=0; i<values.length; i++)
            {
                values[i] = (i & 255);
                freq[values[i]]++;
           }


            for (int ii=0; ii<5000; ii++)
            {
                ByteArrayOutputStream os = new ByteArrayOutputStream(16384);
                BitStream bs = new DefaultBitStream(os, 16384);
                HuffmanEncoder rc = new HuffmanEncoder(bs, true, freq);
                long before = System.nanoTime();

                for (int i=0; i<values.length; i++)
                {
                    rc.encodeByte((byte) (values[i] & 255));
                }

                rc.dispose();
                byte[] buf = os.toByteArray();
                bs = new DefaultBitStream(new ByteArrayInputStream(buf), 256);
                HuffmanDecoder rd = new HuffmanDecoder(bs);

                for (int i=0; i<values.length; i++)
                   rd.decodeByte();

                long after = System.nanoTime();
                delta += (after - before);
            }

            System.out.println("Elapsed [ms]: "+delta/1000000);
        }
    }
}
