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

import kanzi.BitStream;
import kanzi.BitStreamException;
import kanzi.bitstream.DebugBitStream;
import kanzi.bitstream.DefaultBitStream;
import kanzi.entropy.RangeDecoder;
import kanzi.entropy.RangeEncoder;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;


public class TestRangeCoder
{
    public static void main(String[] args)
    {
        System.out.println("TestRangeCoder");

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
                RangeEncoder rc = new RangeEncoder(dbgbs);

                for (int i=0; i<values.length; i++)
                {
                    if (rc.encodeByte((byte) (values[i] & 255)) == false)
                        break;
                }

                //dbgbs.flush();
                rc.dispose();
                byte[] buf = os.toByteArray();
                bs = new DefaultBitStream(new ByteArrayInputStream(buf), 64);
                RangeDecoder rd = new RangeDecoder(bs);
                System.out.println("\nDecoded:");
                int len = values.length; // buf.length >> 3;
                boolean ok = true;

                for (int i=0, j=0; i<len; i++)
                {
                    try
                    {
                        byte n = rd.decodeByte();

                        if (values[j++] != n)
                           ok = false;

                        System.out.print(n+" ");
                    }
                    catch (BitStreamException e)
                    {
                        e.printStackTrace();
                        break;
                    }
                }

                System.out.println("\n"+((ok == true) ? "Identical" : "Different"));
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
            long delta1 = 0;
            long delta2 = 0;

            for (int i=0; i<values.length; i++)
                values[i] = (i & 255);
            
            int iter = 20000;


            for (int ii=0; ii<iter; ii++)
            {
                ByteArrayOutputStream os = new ByteArrayOutputStream(16384);
                BitStream bs = new DefaultBitStream(os, 16384);
                RangeEncoder rc = new RangeEncoder(bs);
                long before, after;
                before = System.nanoTime();

                for (int i=0; i<values.length; i++)
                   rc.encodeByte((byte) (values[i] & 255));

                after = System.nanoTime();
                delta1 += (after - before);
                bs.flush();
                rc.dispose();

                byte[] buf = os.toByteArray();
                bs = new DefaultBitStream(new ByteArrayInputStream(buf), 64);
                RangeDecoder rd = new RangeDecoder(bs);
                int len = values.length; // buf.length >> 3;
                before = System.nanoTime();

                for (int i=0; i<len; i++)
                   rd.decodeByte();

                after = System.nanoTime();
                delta2 += (after - before);
            }

            System.out.println("Iterations: "+iter);
            System.out.println("Encoding [ms]: "+delta1/1000000);
            System.out.println("Decoding [ms]: "+delta2/1000000);
        }
    }
}
