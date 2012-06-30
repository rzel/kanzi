/*
Copyright 2011, 2012 Frederic Langlet
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
        {
            System.out.println("\nTest speed");
            ByteArrayOutputStream os = new ByteArrayOutputStream(16384);
            long before, after, sum = 0;

            for (int i=0; i<16384; i++)
                os.write(i);

            for (int ii=0; ii<50000; ii++)
            {
                OutputBitStream bs = new DefaultOutputBitStream(os, 16384);
                RiceGolombEncoder gc = new RiceGolombEncoder(bs, true, 3);
                before = System.nanoTime();

                for (int i=0; i<2000; i++)
                    gc.encodeByte((byte) (i & 255));

                after = System.nanoTime();
                sum += (after - before);
                bs.close();
          }

            System.out.println("Elapsed [ms]: "+(sum/1000000));
        }
    }
}
