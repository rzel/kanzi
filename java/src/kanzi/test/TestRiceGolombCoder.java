/*
Copyright 2011-2013 Frederic Langlet
Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
you may obtain a copy of the License at

                http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either Riceress or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/

package kanzi.test;

import kanzi.bitstream.DebugInputBitStream;
import kanzi.entropy.RiceGolombDecoder;
import kanzi.entropy.RiceGolombEncoder;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Random;
import kanzi.InputBitStream;
import kanzi.OutputBitStream;
import kanzi.bitstream.DebugOutputBitStream;
import kanzi.bitstream.DefaultInputBitStream;
import kanzi.bitstream.DefaultOutputBitStream;


public class TestRiceGolombCoder
{

    public static void main(String[] args)
    {
        System.out.println("TestRiceGolombCoder");

        // Test behavior
        for (int nn=0; nn<20; nn++)
        {
            try
            {
                byte[] values;
                Random rnd = new Random();
                System.out.println("\nIteration "+nn);

                if (nn == 0)
                   values = new byte[] { -13, -3, -15, -11, 12, -14, -11, 15, 7, 9, 5, -7, 4, 3, 15, -12  }; // -3, 4, 2, 1, 0, -1, 7, -9, 123, 0, 12, -63, -64, 75, -55, 100, 123 };
                else
                {
                   values = new byte[32];

                   for (int i=0; i<values.length; i++)
                      values[i] = (byte) (rnd.nextInt(32) - 16);
                }

                ByteArrayOutputStream os = new ByteArrayOutputStream(16384);
                //OutputStream bos = new BufferedOutputStream(os);
                OutputBitStream bs = new DefaultOutputBitStream(os, 16384);
                DebugOutputBitStream dbgbs = new DebugOutputBitStream(bs, System.out, -1);
                //dbgbs.setMark(true);
                RiceGolombEncoder gc = new RiceGolombEncoder(dbgbs, true, 1+nn%6);

                for (int i=0; i<values.length; i++)
                {
                    System.out.print(values[i]+" ");
                }

                System.out.println();

                for (int i=0; i<values.length; i++)
                {
                    if (gc.encodeByte(values[i]) == false)
                        break;
                }

                gc.dispose();
                bs.close();
                byte[] array = os.toByteArray();
                BufferedInputStream is = new BufferedInputStream(new ByteArrayInputStream(array));
                InputBitStream bs2 = new DefaultInputBitStream(is, 16384);
                DebugInputBitStream dbgbs2 = new DebugInputBitStream(bs2, System.out, -1);
                dbgbs2.setMark(true);
                RiceGolombDecoder gd = new RiceGolombDecoder(dbgbs2, true, 1+nn%6);
                byte[] values2 = new byte[values.length];
                System.out.println("\nDecoded:");

                for (int i=0; i<values2.length; i++)
                {
                    try
                    {
                        values2[i] = gd.decodeByte();
                    }
                    catch (Exception e)
                    {
                        e.printStackTrace();
                        break;
                    }
                }

                System.out.println();
                gc.dispose();
                boolean ok = true;

                for (int i=0; i<values.length; i++)
                {
                    System.out.print(values2[i]+" ");

                    if (values2[i] != values[i])
                    {
                       ok = false;
                       break;
                    }
                }

                System.out.println();
                System.out.println((ok) ? "Identical" : "Different");
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }
        }

        // Test speed
        System.out.println("\n\nSpeed Test");
        int[] repeats = { 3, 1, 4, 1, 5, 9, 2, 6, 5, 3, 5, 8, 9, 7, 9, 3 };
        final int iter = 4000;
        final int size = 50000;

        for (int jj=0; jj<3; jj++)
        {
            System.out.println("\nTest "+(jj+1));
            byte[] values1 = new byte[size];
            byte[] values2 = new byte[size];
            long delta1 = 0, delta2 = 0;

            for (int ii=0; ii<iter; ii++)
            {
                int idx = 0;

                for (int i=0; i<values1.length; i++)
                {
                    int i0 = i;
                    int len = repeats[idx];
                    idx = (idx + 1) & 0x0F;

                    if (i0+len >= values1.length)
                        len = 1;

                    for (int j=i0; j<i0+len; j++)
                    {
                       values1[j] = (byte) (i0 & 255);
                       i++;
                    }
                }

                // Encode
                ByteArrayOutputStream os = new ByteArrayOutputStream(size*2);
                OutputBitStream bs = new DefaultOutputBitStream(os, size);
                RiceGolombEncoder gc = new RiceGolombEncoder(bs, true, 3);
                long before1 = System.nanoTime();
                
                if (gc.encode(values1, 0, values1.length) < 0)
                {
                   System.out.println("Encoding error");
                   System.exit(1);
                }
                   
                long after1 = System.nanoTime();
                delta1 += (after1 - before1);
                gc.dispose();
                bs.close();

                // Decode
                byte[] buf = os.toByteArray();
                InputBitStream bs2 = new DefaultInputBitStream(new ByteArrayInputStream(buf), size);
                RiceGolombDecoder gd = new RiceGolombDecoder(bs2, true, 3);
                long before2 = System.nanoTime();
                
                if (gd.decode(values2, 0, values2.length) < 0)
                {
                  System.out.println("Decoding error");
                  System.exit(1);
                }

                long after2 = System.nanoTime();
                delta2 += (after2 - before2);
                gd.dispose();

                // Sanity check
                for (int i=0; i<values1.length; i++)
                {
                   if (values1[i] != values2[i])
                   {
                      System.out.println("Error at index "+i+" ("+values1[i]
                              +"<->"+values2[i]+")");
                      break;
                   }
                }
            }

            System.out.println("Encode [ms]       : " +delta1/1000000);
            System.out.println("Throughput [KB/s] : " +((long) (iter*size)) * 1000000L / delta1 * 1000L / 1024L);
            System.out.println("Decode [ms]       : " +delta2/1000000);
            System.out.println("Throughput [KB/s] : " +((long) (iter*size)) * 1000000L / delta2 * 1000L / 1024L);
        }
    }
}
